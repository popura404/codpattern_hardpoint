package com.cdp.codpattern.hardpoint.room;

import com.cdp.codpattern.app.match.GameModeRegistry;
import com.cdp.codpattern.app.match.model.JoinRoomRequest;
import com.cdp.codpattern.app.match.model.JoinRoomResult;
import com.cdp.codpattern.app.match.model.LeaveRoomResult;
import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.match.model.TeamDescriptor;
import com.cdp.codpattern.app.match.port.ModeRoomLifecyclePort;
import com.cdp.codpattern.app.match.port.ModeRoomReadPort;
import com.cdp.codpattern.app.match.port.TeamRoomPort;
import com.cdp.codpattern.app.match.runtime.termination.ModeForceEndHandler;
import com.cdp.codpattern.app.tdm.service.TeamBalanceService;
import com.cdp.codpattern.fpsmatch.room.CodTdmRoomManager;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import com.phasetranscrystal.fpsmatch.core.data.AreaData;
import com.phasetranscrystal.fpsmatch.core.data.SpawnPointData;
import com.phasetranscrystal.fpsmatch.core.map.BaseTeam;
import com.phasetranscrystal.fpsmatch.core.map.SpawnSafetyValidator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The public menu uses the read port to include both teams in its room summary. */
final class HardpointRoom implements ModeRoomReadPort, ModeRoomLifecyclePort, TeamRoomPort {
    private final HardpointMap map;
    final HardpointRoster roster;

    HardpointRoom(HardpointMap map) {
        this.map = map;
        roster = new HardpointRoster(map);
    }

    @Override
    public JoinRoomResult join(ServerPlayer player, JoinRoomRequest request) {
        if (player == null) return JoinRoomResult.failure(roomId(), "PLAYER_MISSING", "");
        if (!map.termination().canJoin(roomId(), player.getUUID())) {
            return JoinRoomResult.failure(roomId(), "PHASE_LOCKED", "");
        }
        if (containsJoinedPlayer(player.getUUID())) return JoinRoomResult.success(roomId(), "ALREADY_JOINED");
        if (!map.match.canAdmit()) return JoinRoomResult.failure(roomId(), "PHASE_LOCKED", "");
        if (FPSMCore.getInstance().getMapByPlayerWithSpec(player).isPresent()) {
            return JoinRoomResult.failure(roomId(), "ALREADY_IN_OTHER_ROOM",
                    Component.translatable("message.codpattern_hardpoint.leave_first").getString());
        }
        JoinRoomRequest desired = request == null ? JoinRoomRequest.autoTeam() : request;
        if (desired.spectator()) {
            return JoinRoomResult.failure(roomId(), "SPECTATOR_UNSUPPORTED",
                    Component.translatable("message.codpattern_hardpoint.no_spectator").getString());
        }
        Optional<String> team = desired.preferredTeamName().map(HardpointRoom::teamId).or(() -> chooseAutoJoinTeam(-1));
        if (team.isEmpty()) return JoinRoomResult.failure(roomId(), "TEAM_FULL", "");
        if (!hasTeam(team.get())) return JoinRoomResult.failure(roomId(), "TEAM_NOT_FOUND", "");
        if (isTeamFull(team.get())) return JoinRoomResult.failure(roomId(), "TEAM_FULL", "");
        if (!canReturnHere(player)) {
            var reason = Component.translatableWithFallback("message.codpattern_hardpoint.unsafe_return_position",
                    "Stand on safe, dry ground before joining the room.");
            player.sendSystemMessage(reason);
            return JoinRoomResult.failure(roomId(), "UNSAFE_RETURN_POSITION", reason.getString());
        }

        map.admit(team.get(), player);
        if (!containsJoinedPlayer(player.getUUID())) return JoinRoomResult.failure(roomId(), "PHASE_LOCKED", "");
        map.match.onAdmitted(player);
        membershipChanged();
        return JoinRoomResult.success(roomId(), "OK");
    }

    @Override
    public LeaveRoomResult leave(ServerPlayer player) {
        if (player == null || (!containsJoinedPlayer(player.getUUID()) && !containsSpectator(player))) {
            return LeaveRoomResult.failure(roomId(), "NOT_IN_ROOM", "");
        }
        try {
            map.leave(player);
        } catch (IllegalStateException failure) {
            map.logLeaveFailure(player, failure);
            var reason = Component.translatableWithFallback("message.codpattern_hardpoint.leave_recovery_pending",
                    "Recovery could not finish. Make your entry location or the world spawn safe and try leaving again.");
            player.sendSystemMessage(reason);
            return LeaveRoomResult.failure(roomId(), "RECOVERY_PENDING", reason.getString());
        }
        return LeaveRoomResult.success(roomId(), "OK");
    }

    /** Check the exact entry position that core captures for its later return teleport. */
    private static boolean canReturnHere(ServerPlayer player) {
        var level = player.serverLevel();
        BlockPos feet = player.blockPosition();
        if (!level.getWorldBorder().isWithinBounds(feet) || feet.getY() < level.getMinBuildHeight()
                || feet.getY() + 2 >= level.getMaxBuildHeight()) return false;
        var landing = new SpawnPointData(level.dimension(), feet, player.getYRot(), player.getXRot());
        if (!SpawnSafetyValidator.isSafe(level, player, landing) || !level.noCollision(player, player.getBoundingBox())
                || !level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty()) return false;
        if (level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()
                || level.getBlockState(feet.below()).is(Blocks.MAGMA_BLOCK)
                || !level.getBlockCollisions(player, player.getBoundingBox().move(0, -0.02, 0)).iterator().hasNext()) {
            return false;
        }
        for (BlockPos pos : List.of(feet.below(), feet, feet.above())) {
            var state = level.getBlockState(pos);
            if (state.is(BlockTags.FIRE) || state.is(BlockTags.CAMPFIRES) || state.is(Blocks.CACTUS)
                    || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.WITHER_ROSE)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void switchTeam(ServerPlayer player, String team) {
        team = teamId(team);
        if (player == null || !map.match.waiting() || !map.termination().canJoin(roomId(), player.getUUID()) || !hasTeam(team)) return;
        Optional<String> current = findTeamNameByPlayer(player);
        if (current.isEmpty() || current.get().equals(team)) return;
        if (isTeamFull(team)) {
            player.sendSystemMessage(Component.translatable("screen.codpattern.room.error.team_full"));
            return;
        }
        // Keep the original recovery record and admission location when changing teams.
        map.getMapTeams().leaveTeam(player);
        map.getMapTeams().joinTeam(team, player);
        map.match.initializeReadyState(player);
        membershipChanged();
    }

    void membershipChanged() {
        CodTdmRoomManager.getInstance().markRoomListDirty();
        roster.sync();
    }

    @Override public RoomId roomId() { return map.roomId(); }
    @Override public String mapName() { return map.getMapName(); }
    @Override public String gameType() { return map.getGameType(); }
    @Override public String modeDisplayNameKey() { return GameModeRegistry.getOrDefault(gameType()).displayNameKey(); }
    @Override public List<TeamDescriptor> teamDescriptors() { return GameModeRegistry.getOrDefault(gameType()).teams(); }
    @Override public AreaData mapArea() { return map.getMapArea(); }
    @Override public String dimensionId() { return map.getServerLevel().dimension().location().toString(); }
    @Override public String phaseName() { return map.match.lobbyPhase(); }
    @Override public boolean isWaitingPhase() { return map.match.waiting(); }
    @Override public boolean isPlayingPhase() { return map.match.playing(); }
    @Override public boolean canDealDamage() { return map.match.playing(); }
    @Override public boolean isPlayerInvincible(UUID player) { return !map.match.canFight(player); }
    @Override public boolean isStarted() { return !map.match.waiting(); }
    @Override public int getRemainingTimeTicks() { return map.match.remainingTicks(); }
    @Override public boolean isJoinable() { return map.match.canAdmit() && !map.recoveryBlocked() && playerCount() < maxPlayers(); }
    @Override public boolean containsJoinedPlayer(UUID id) { return map.checkGameHasPlayer(id); }
    @Override public boolean containsSpectator(ServerPlayer player) { return map.checkSpecHasPlayer(player); }
    @Override public Optional<SpawnPointData> matchEndTeleportPoint() { return map.matchEndTeleportPoint(); }
    @Override public boolean hasMatchEndTeleportPoint() { return matchEndTeleportPoint().isPresent(); }
    @Override public void syncToClient() { map.syncToClient(); }
    @Override public ModeForceEndHandler forceEndHandler() { return map.forceEndHandler(); }
    // Core room screens can send the built-in faction names from the two team buttons.
    private static String teamId(String name) {
        if ("kortac".equals(name)) return "red";
        if ("specgru".equals(name)) return "blue";
        return name;
    }

    @Override public boolean hasTeam(String name) { return map.getMapTeams().checkTeam(teamId(name)); }
    @Override public boolean isTeamFull(String name) { return map.getMapTeams().testTeamIsFull(teamId(name)); }
    @Override public int getMaxPlayerCapacity() { return map.getMapTeams().getTeams().stream().mapToInt(BaseTeam::getPlayerLimit).sum(); }
    @Override public Optional<String> findTeamNameByPlayer(ServerPlayer player) {
        return map.getMapTeams().getTeamByPlayer(player).map(team -> team.name);
    }

    @Override public Map<String, Integer> teamPlayerCountsSnapshot() { return getTeamPlayerCountsSnapshot(); }

    @Override
    public Map<String, Integer> getTeamPlayerCountsSnapshot() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        map.getMapTeams().getTeams().forEach(team -> counts.put(team.name, team.getPlayerCount()));
        return counts;
    }

    @Override
    public Map<String, Integer> getTeamScoresSnapshot() {
        return map.match.scores();
    }

    private List<TeamBalanceService.TeamSnapshot> teamSizes() {
        return map.getMapTeams().getTeams().stream()
                .map(team -> new TeamBalanceService.TeamSnapshot(team.name, team.getPlayerCount())).toList();
    }

    @Override public Optional<String> chooseAutoJoinTeam(int maxTeamDiff) {
        return TeamBalanceService.chooseAutoJoinTeam(teamSizes(), this::isTeamFull, maxTeamDiff);
    }

    @Override public boolean canJoinWithBalance(String team, int maxTeamDiff) {
        String id = teamId(team);
        return hasTeam(id) && !isTeamFull(id) && TeamBalanceService.canJoinWithBalance(teamSizes(), id, maxTeamDiff);
    }
}

