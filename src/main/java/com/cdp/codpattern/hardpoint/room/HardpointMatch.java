package com.cdp.codpattern.hardpoint.room;

import com.cdp.codpattern.adapter.forge.network.ModNetworkChannel;
import com.cdp.codpattern.app.backpack.service.BackpackDistributor;
import com.cdp.codpattern.app.match.model.*;
import com.cdp.codpattern.app.match.port.*;
import com.cdp.codpattern.app.match.runtime.ready.DefaultReadyStateService;
import com.cdp.codpattern.app.match.runtime.vote.RoomVoteEngine.FailureReason;
import com.cdp.codpattern.app.tdm.service.CombatRegenService;
import com.cdp.codpattern.app.tdm.service.PhaseStateMachine;
import com.cdp.codpattern.app.tdm.service.TdmSettlementTiming;
import com.cdp.codpattern.app.tdm.service.WarmupMovementLockService;
import com.cdp.codpattern.config.backpack.BackpackConfigRepository;
import com.cdp.codpattern.config.path.ConfigPath;
import com.cdp.codpattern.config.tdm.CodTdmConfig;
import com.cdp.codpattern.config.weaponfilter.WeaponFilterConfigRepository;
import com.cdp.codpattern.core.refit.AttachmentEditSessionManager;
import com.cdp.codpattern.core.throwable.ThrowableInventoryService;
import com.cdp.codpattern.fpsmatch.room.CodTdmRoomManager;
import com.cdp.codpattern.hardpoint.storage.HardpointZone;
import com.cdp.codpattern.network.match.CountdownPacket;
import com.cdp.codpattern.network.match.GamePhasePacket;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.GameType;

import java.util.*;

/** Server authority for one room. Occupancy is sampled from this map's admitted, living players. */
final class HardpointMatch implements ReadyStatePort, ModeRoomTickPort, ModeCombatEventPort,
        ModeRespawnPolicyPort, ModeKitDistributionPort, ModeRuntimeStatePort {
    private enum Phase { WAITING, COUNTDOWN, WARMUP, PLAYING, ENDED }
    private static final int RESULT_TICKS = TdmSettlementTiming.TOTAL_TICKS;
    private static final int RESPAWN_TICKS = 60;

    private final HardpointMap map;
    private final Map<UUID, Boolean> readyFlags = new HashMap<>();
    private final DefaultReadyStateService ready;
    final HardpointStartVote vote;
    final HardpointStats stats = new HardpointStats();
    private final Set<UUID> alive = new HashSet<>();
    private final Map<UUID, Integer> respawns = new HashMap<>();
    private final Map<UUID, Integer> regen = new HashMap<>();
    private UUID generation;
    private Phase phase = Phase.WAITING;
    private int phaseTicks;
    private int openingTicks;
    private HardpointRules rules;

    HardpointMatch(HardpointMap map) {
        this.map = map;
        ready = new DefaultReadyStateService(readyFlags, id -> waiting() && map.checkGameHasPlayer(id) && !map.recoveryBlocked());
        vote = new HardpointStartVote(map, this);
    }
    @Override public RoomId roomId() { return map.roomId(); }
    @Override public String gameType() { return map.getGameType(); }
    @Override public String modeDisplayNameKey() { return "mode.codpattern_hardpoint.name"; }
    @Override public String mapName() { return map.getMapName(); }
    boolean waiting() { return phase == Phase.WAITING; }
    boolean warmingUp() { return phase == Phase.WARMUP; }
    boolean playing() { return phase == Phase.PLAYING; }
    boolean ended() { return phase == Phase.ENDED; }
    boolean canAdmit() { return waiting(); }
    boolean canFight(UUID id) { return playing() && alive.contains(id) && current(); }
    String lobbyPhase() { return phase.name(); }
    int remainingTicks() { return playing() ? rules.matchTicks() : phaseTicks; }
    Map<String, Integer> scores() {
        return Map.of("red", rules == null ? 0 : rules.redScore(), "blue", rules == null ? 0 : rules.blueScore());
    }
    Map<UUID, Boolean> readySnapshot() { return Map.copyOf(readyFlags); }
    private boolean current() {
        return generation != null && !map.recoveryBlocked() && map.termination().valid(roomId(), generation);
    }
    private String team(ServerPlayer p) { return map.getMapTeams().getTeamByPlayer(p).map(t -> t.name).orElse(""); }
    private List<ServerPlayer> onlineRoster() {
        var online = map.getServerLevel().getServer().getPlayerList();
        return map.getMapTeams().getJoinedUUID().stream().map(online::getPlayer).filter(Objects::nonNull).toList();
    }
    List<ServerPlayer> members() {
        return onlineRoster().stream().filter(p -> !map.termination().playerPending(p.getUUID())).toList();
    }
    void broadcast(String key, Object... args) {
        var message = Component.translatable(key, args);
        members().forEach(p -> p.sendSystemMessage(message));
    }
    private void publish() {
        CodTdmRoomManager.getInstance().markRoomListDirty();
        map.syncToClient();
        members().forEach(map::syncRuntimeState);
    }
    void onAdmitted(ServerPlayer p) { initializeReadyState(p); }
    @Override public void initializeReadyState(ServerPlayer p) {
        ready.initialize(p.getUUID());
        vote.cancel();
    }
    @Override public boolean setPlayerReady(ServerPlayer p, boolean value) {
        if (p == null || !ready.setReady(p.getUUID(), value).accepted()) return false;
        if (!value) vote.cancel();
        publish();
        return true;
    }
    void clearReady() { ready.clear(); vote.cancel(); }
    void removeMember(UUID id) {
        vote.memberLeft(id);
        ready.remove(id);
        alive.remove(id);
        respawns.remove(id);
        regen.remove(id);
    }
    FailureReason startProblem(List<ServerPlayer> members) {
        if (!waiting() || map.recoveryBlocked()) return FailureReason.NOT_WAITING;
        if (members.stream().map(this::team).distinct().count() < 2) return FailureReason.MIN_PLAYERS;
        if (members.size() != map.getMapTeams().getJoinedUUID().size() || !ready.areAllReady(HardpointStartVote.ids(members))) {
            return FailureReason.PLAYERS_NOT_READY;
        }
        return null;
    }
    boolean validStartDeployment() {
        var issues = map.deployment().problems(map);
        if (issues.isEmpty()) return true;
        broadcast("message.codpattern_hardpoint.invalid_map", Component.translatable(issues.get(0)));
        return false;
    }
    void begin(Set<UUID> voters) {
        List<ServerPlayer> players = members();
        if (!voters.equals(HardpointStartVote.ids(players)) || startProblem(players) != null || !validStartDeployment()) {
            broadcast("message.codpattern.game.vote_failed");
            return;
        }
        try {
            // Validate all loadouts first: a missing player file must not start half the room.
            Objects.requireNonNull(WeaponFilterConfigRepository.loadOrCreate(ConfigPath.SERVER_FILTER.getPath(map.getServerLevel().getServer())));
            for (ServerPlayer player : players) validateBackpack(player);
            generation = map.termination().begin(roomId(), voters, map.matchEndTeleportPoint().orElse(null));
            rules = new HardpointRules(map.deployment().zones().size());
            map.isStart = true;
            for (ServerPlayer player : players) {
                stats.enter(player, team(player));
                closeSessions(player);
            }
            broadcast("message.codpattern.game.vote_passed");
            enterPhase(Phase.COUNTDOWN, PhaseStateMachine.PRE_GAME_COUNTDOWN_TICKS, players);
            countdown(players, phaseTicks, false);
        } catch (RuntimeException e) { abort(e); }
    }
    private static void validateBackpack(ServerPlayer player) {
        var data = BackpackConfigRepository.loadOrCreatePlayer(player.getUUID().toString(), ConfigPath.SERVERBACKPACK.getPath(player.server));
        if (BackpackDistributor.resolveBackpack(data) == null)
            throw new IllegalStateException("No backpack available for " + player.getScoreboardName());
    }
    private void enterPhase(Phase next, int ticks, List<ServerPlayer> players) {
        phase = next;
        phaseTicks = ticks;
        var phasePacket = new GamePhasePacket(next.name(), remainingTicks());
        players.forEach(p -> ModNetworkChannel.sendToPlayer(phasePacket, p));
        publish();
    }
    private static void countdown(List<ServerPlayer> players, int ticks, boolean blackout) {
        var packet = new CountdownPacket(ticks, blackout);
        players.forEach(p -> ModNetworkChannel.sendToPlayer(packet, p));
    }
    private void tickCountdown(List<ServerPlayer> players) {
        --phaseTicks;
        if (phaseTicks == 0) {
            map.getMapTeams().getTeams().forEach(team -> team.randomSpawnPoints());
            for (var player : players) {
                spawn(player);
                WarmupMovementLockService.lock(player);
            }
            enterPhase(Phase.WARMUP, PhaseStateMachine.PREPARE_DURATION_TICKS, players);
        } else if (phaseTicks == PhaseStateMachine.PREPARE_BLACKOUT_FADE_IN_TICKS) {
            countdown(players, PhaseStateMachine.PREPARE_BLACKOUT_TOTAL_TICKS, true);
        } else if (phaseTicks > PhaseStateMachine.PREPARE_BLACKOUT_FADE_IN_TICKS && phaseTicks % 20 == 0) {
            countdown(players, phaseTicks, false);
        }
    }
    private static void showIntro(List<ServerPlayer> players) {
        var timing = new ClientboundSetTitlesAnimationPacket(4, 42, 16);
        var title = new ClientboundSetTitleTextPacket(Component.translatable("mode.codpattern_hardpoint.name"));
        var objective = Component.translatable("hud.codpattern_hardpoint.intro", HardpointRules.SCORE_LIMIT);
        var subtitle = new ClientboundSetSubtitleTextPacket(objective);
        for (var player : players) {
            player.connection.send(timing);
            player.connection.send(title);
            player.connection.send(subtitle);
            player.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.get(), SoundSource.PLAYERS, 0.9f, 1.0f);
        }
    }
    void closeSessions(ServerPlayer p) {
        if (AttachmentEditSessionManager.hasSession(p.getUUID())) AttachmentEditSessionManager.abortSession(p, "hardpoint");
        if (generation != null) {
            p.stopUsingItem();
            ThrowableInventoryService.abortSession(p, true);
        }
    }
    private void spawn(ServerPlayer player) {
        closeSessions(player);
        player.stopRiding();
        player.setCamera(player);
        player.setGameMode(GameType.ADVENTURE);
        if (player.isSpectator() || !map.teleportPlayerToRoundStartPoint(player))
            throw new IllegalStateException("No safe spawn for " + player.getScoreboardName());
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.clearFire();
        player.fallDistance = 0;
        player.setInvulnerable(false);
        if (player.getAbilities().invulnerable) {
            player.getAbilities().invulnerable = false;
            player.onUpdateAbilities();
        }
        map.termination().registerRoundInventory(player);
        BackpackDistributor.distributeBackpackItemsSilently(player);
        alive.add(player.getUUID());
    }
    private void abort(RuntimeException error) {
        LogUtils.getLogger().error("Hardpoint room {} aborted", roomId().encode(), error);
        broadcast("message.codpattern_hardpoint.match_aborted");
        map.termination().finishNormally(map);
    }
    @Override public void tick(ModeRoomTickContext context) {
        if (!roomId().equals(context.roomId())) return;
        if (map.recoveryBlocked()) { vote.cancel(); return; }
        if (waiting()) { vote.tick(); return; }
        if (!current()) return;
        try {
            var players = members();
            if (players.isEmpty()) { map.termination().finishNormally(map); return; }
            if (ended()) {
                if (--phaseTicks <= 0) map.termination().finishNormally(map);
                return;
            }
            boolean redRemains = players.stream().anyMatch(p -> "red".equals(team(p)));
            boolean blueRemains = players.stream().anyMatch(p -> "blue".equals(team(p)));
            if (!redRemains || !blueRemains) {
                rules.forfeit(redRemains);
                end();
                return;
            }
            if (phase == Phase.COUNTDOWN) {
                tickCountdown(players);
                return;
            }
            if (phase == Phase.WARMUP) {
                players.forEach(WarmupMovementLockService::lock);
                if (--phaseTicks <= 0) {
                    players.forEach(WarmupMovementLockService::unlock);
                    openingTicks = HardpointRules.OPENING_TICKS;
                    enterPhase(Phase.PLAYING, 0, players);
                }
                return;
            }
            for (ServerPlayer p : players) {
                Integer remaining = respawns.get(p.getUUID());
                if (remaining == null) continue;
                if (remaining > 1) respawns.put(p.getUUID(), remaining - 1);
                else {
                    validateBackpack(p);
                    spawn(p);
                    respawns.remove(p.getUUID());
                }
            }
            if (openingTicks > 0) {
                rules.tickBeforeOpening();
                openingTicks--;
                if (openingTicks == HardpointRules.OPENING_TICKS - 60) showIntro(players);
                if (openingTicks == 0) players.forEach(map::syncRuntimeState);
            } else {
                tickHill(players);
            }
            if (rules.finished()) end();
            else CombatRegenService.tick(regen, players, respawns.keySet(), true, CodTdmConfig.getConfig());
        } catch (RuntimeException e) { abort(e); }
    }
    private void tickHill(List<ServerPlayer> players) {
        HardpointZone hill = map.deployment().zones().get(rules.index());
        var occupants = players.stream().filter(p -> alive.contains(p.getUUID()) && p.isAlive() && !p.isSpectator()
                && p.serverLevel() == map.getServerLevel() && hill.contains(p.blockPosition())).toList();
        var control = HardpointRules.occupancy(occupants.stream().anyMatch(p -> team(p).equals("red")),
                occupants.stream().anyMatch(p -> team(p).equals("blue")));
        if (control == HardpointRules.Control.RED || control == HardpointRules.Control.BLUE) {
            occupants.forEach(p -> stats.hold(p.getUUID()));
        }
        int previousHill = rules.index();
        int previousPreview = rules.preview();
        var previousControl = rules.control();
        rules.tick(control);
        if (!rules.finished() && (rules.index() != previousHill || rules.preview() != previousPreview
                || rules.control() != previousControl)) {
            players.forEach(map::syncRuntimeState);
        }
    }
    private void end() {
        var players = members();
        if (phase == Phase.COUNTDOWN || phase == Phase.WARMUP) countdown(players, 0, false);
        players.forEach(WarmupMovementLockService::unlock);
        respawns.clear();
        enterPhase(Phase.ENDED, RESULT_TICKS, players);
    }
    void stop() {
        // Force-end marks the roster recovery-pending before invoking the mode's stop hook.
        for (var player : onlineRoster()) {
            closeSessions(player);
            WarmupMovementLockService.unlock(player);
            ModNetworkChannel.sendToPlayer(new GamePhasePacket(Phase.WAITING.name(), 0), player);
        }
        generation = null;
        rules = null;
        phase = Phase.WAITING;
        phaseTicks = 0;
        openingTicks = 0;
        clearReady();
        alive.clear();
        respawns.clear();
        regen.clear();
        stats.clear();
        publish();
    }
    @Override public DamageDecision onPlayerHurt(ServerPlayer victim, DamageContext ctx) {
        if (waiting()) return DamageDecision.passThrough();
        if (!canFight(victim.getUUID()) || ctx.attacker().filter(p -> !canFight(p.getUUID()) || team(p).equals(team(victim))).isPresent()) {
            return DamageDecision.cancel();
        }
        if (ctx.amount() > 0) CombatRegenService.onPlayerDamaged(regen, victim, CodTdmConfig.getConfig());
        return DamageDecision.passThrough();
    }
    @Override public DeathDecision onPlayerDeath(ServerPlayer victim, DeathContext ctx) {
        if (waiting() || !current()) return DeathDecision.passThrough();
        if (playing() && alive.remove(victim.getUUID())) {
            stats.death(victim.getUUID(), ctx.killer().map(ServerPlayer::getUUID).orElse(null));
            beginRespawn(victim);
        }
        return DeathDecision.cancelAndRestoreHealth();
    }
    private void beginRespawn(ServerPlayer player) {
        UUID id = player.getUUID();
        closeSessions(player);
        alive.remove(id);
        regen.remove(id);
        respawns.put(id, RESPAWN_TICKS);
        player.setGameMode(GameType.SPECTATOR);
        player.sendSystemMessage(Component.translatable("message.codpattern_hardpoint.respawning", RESPAWN_TICKS / 20));
    }
    @Override public void onPlayerRespawn(ServerPlayer p, PlayerRespawnContext ctx) {
        if (playing() && current()) beginRespawn(p);
    }
    @Override public void onBackpackSelected(ServerPlayer player, int backpackId) {
        if (!waiting()) player.sendSystemMessage(Component.translatable("message.codpattern_hardpoint.next_backpack"));
    }
    @Override public ModeRuntimeStateSnapshot runtimeStateSnapshot(ServerPlayer viewer) {
        var values = new LinkedHashMap<String, ModePlayerValue>();
        scores().forEach((team, score) -> values.put(team + "Score", ModePlayerValue.ofInt(score)));
        values.put("team", ModePlayerValue.ofString(team(viewer)));
        values.put("scoreLimit", ModePlayerValue.ofInt(HardpointRules.SCORE_LIMIT));
        values.put("mapDimension", ModePlayerValue.ofString(map.getServerLevel().dimension().location().toString()));
        long voteId = vote.activeId().orElse(0);
        values.put("startVoteId", ModePlayerValue.ofLong(voteId));
        values.put("canStartVote", ModePlayerValue.ofBoolean(waiting() && voteId == 0 && startProblem(members()) == null));
        if (rules != null) {
            values.put("status", ModePlayerValue.ofString(rules.control().name()));
            values.put("pointRemainingTicks", ModePlayerValue.ofInt(rules.hillTicks()));
            values.put("hillCount", ModePlayerValue.ofInt(map.deployment().zones().size()));
            if (phase == Phase.COUNTDOWN) values.put("countdown", ModePlayerValue.ofInt(phaseTicks));
            if (phase == Phase.WARMUP) values.put("warmup", ModePlayerValue.ofInt(phaseTicks));
            if (playing() && openingTicks > 0) values.put("opening", ModePlayerValue.ofInt(openingTicks));
            if (playing() && openingTicks == 0) addZone(values, "point", rules.index());
            if (phase == Phase.WARMUP || playing() && openingTicks > 0) addZone(values, "next", 0);
            else if (playing() && rules.preview() >= 0) addZone(values, "next", rules.preview());
            if (ended()) {
                values.put("winner", ModePlayerValue.ofString(rules.winner()));
                values.put("endReason", ModePlayerValue.ofString(rules.reason()));
                values.put("resultTicks", ModePlayerValue.ofInt(RESULT_TICKS));
                stats.addHoldTimes(values);
            }
        }
        long revision = map.getServerLevel().getServer().overworld().getGameTime();
        return new ModeRuntimeStateSnapshot(roomId().encode(), voteId == 0 ? phase.name() : "START_VOTE",
                remainingTicks(), List.of(), values, List.of(), revision);
    }
    private void addZone(Map<String, ModePlayerValue> values, String prefix, int index) {
        var zone = map.deployment().zones().get(index);
        var area = zone.captureArea();
        var first = area.pos1();
        var second = area.pos2();
        values.put(prefix + "Index", ModePlayerValue.ofInt(index + 1));
        values.put(prefix + "Name", ModePlayerValue.ofString(zone.name()));
        values.put(prefix + "MinX", ModePlayerValue.ofInt(Math.min(first.getX(), second.getX())));
        values.put(prefix + "MinY", ModePlayerValue.ofInt(Math.min(first.getY(), second.getY())));
        values.put(prefix + "MinZ", ModePlayerValue.ofInt(Math.min(first.getZ(), second.getZ())));
        values.put(prefix + "MaxX", ModePlayerValue.ofInt(Math.max(first.getX(), second.getX())));
        values.put(prefix + "MaxY", ModePlayerValue.ofInt(Math.max(first.getY(), second.getY())));
        values.put(prefix + "MaxZ", ModePlayerValue.ofInt(Math.max(first.getZ(), second.getZ())));
    }
}
