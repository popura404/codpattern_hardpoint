package com.cdp.codpattern.hardpoint.room;

import com.cdp.codpattern.adapter.forge.network.ModNetworkChannel;
import com.cdp.codpattern.app.match.ModeRoomBackedMap;
import com.cdp.codpattern.app.match.ModeRoomHandle;
import com.cdp.codpattern.app.match.editor.ModeEndTeleportSupport;
import com.cdp.codpattern.app.match.model.JoinRoomRequest;
import com.cdp.codpattern.app.match.model.RoomId;
import com.cdp.codpattern.app.match.model.result.ModeOperationResult;
import com.cdp.codpattern.app.match.port.ModeRoomEvictionPort;
import com.cdp.codpattern.app.match.runtime.termination.*;
import com.cdp.codpattern.fpsmatch.room.CodTdmRoomManager;
import com.cdp.codpattern.hardpoint.HardpointAddon;
import com.cdp.codpattern.hardpoint.storage.HardpointDeployment;
import com.cdp.codpattern.network.match.ModeRuntimeStatePacket;
import com.cdp.codpattern.network.match.GamePhasePacket;
import com.mojang.logging.LogUtils;
import com.phasetranscrystal.fpsmatch.core.data.AreaData;
import com.phasetranscrystal.fpsmatch.core.data.SpawnPointData;
import com.phasetranscrystal.fpsmatch.core.map.BaseMap;
import com.phasetranscrystal.fpsmatch.core.map.EndTeleportMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Team;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class HardpointMap extends BaseMap implements ModeRoomBackedMap, EndTeleportMap<HardpointMap>,
        ModeRoomEvictionPort, ModeForceEndHandler {
    private static final String INVULNERABILITY = "codpattern_hardpoint:invulnerable_ability";
    private final RoomId roomId;
    private final HardpointRoom room;
    private final ModeRoomHandle handle;
    private HardpointDeployment deployment = HardpointDeployment.EMPTY;
    private UUID deploymentVersion = UUID.randomUUID();
    final HardpointMatch match;

    public HardpointMap(ServerLevel level, String name, AreaData area) {
        super(level, name, area, HardpointAddon.GAME_TYPE);
        roomId = RoomId.of(getGameType(), name);
        for (String team : new String[]{"red", "blue"}) {
            addTeam(team, 6).getPlayerTeam().setNameTagVisibility(Team.Visibility.HIDE_FOR_OTHER_TEAMS);
        }
        match = new HardpointMatch(this);
        room = new HardpointRoom(this);
        handle = ModeRoomHandle.builder(roomId, room, room)
                .withTeam(room).withRoster(room.roster).withReady(match).withVote(match.vote)
                .withTick(match).withCombatEvents(match).withRespawnPolicy(match)
                .withKitDistribution(match).withRuntimeState(match)
                .withMapEdit(ModeEndTeleportSupport.editor(this::matchEndTeleportPoint, this::setMatchEndTeleportPoint))
                .build();
    }

    @Override public String getGameType() { return HardpointAddon.GAME_TYPE; }
    @Override public HardpointMap getMap() { return this; }
    @Override public ModeRoomHandle roomHandle() { return handle; }
    public RoomId roomId() { return roomId; }
    public java.util.OptionalLong activeStartVoteId() { return match.vote.activeId(); }
    public boolean isWarmingUp() { return match.warmingUp(); }
    public HardpointDeployment deployment() { return deployment; }
    public UUID deploymentVersion() { return deploymentVersion; }
    public boolean isDeploymentEditable() { return match.waiting() && !recoveryBlocked(); }
    public Optional<SpawnPointData> matchEndTeleportPoint() { return deployment.endPoint(); }
    @Override public void setMatchEndTeleportPoint(SpawnPointData point) {
        replaceDeployment(deployment.withEndPoint(Optional.ofNullable(point)));
    }

    public void replaceDeployment(HardpointDeployment value) {
        Objects.requireNonNull(value);
        if (deployment.equals(value)) return;
        if (!match.waiting()) throw new IllegalStateException("The match layout is locked");
        for (var team : getMapTeams().getTeams()) {
            team.setAllSpawnPointData(team.name.equals("red") ? value.redSpawns() : value.blueSpawns());
            team.clearPlayerSpawnPointAssignments();
        }
        deployment = value;
        deploymentVersion = UUID.randomUUID();
        match.clearReady();
    }

    RoomTerminationService termination() { return RoomTerminationService.get(getServerLevel().getServer()); }
    @Override public ModeForceEndHandler forceEndHandler() { return this; }
    @Override public ModeOperationResult<Void> stop(ForceEndContext context) {
        isStart = false;
        match.stop();
        return ModeOperationResult.success(null);
    }
    @Override public ModeOperationResult<Void> cleanup(ForceEndContext context) {
        CodTdmRoomManager.getInstance().markRoomListDirty();
        return ModeOperationResult.success(null);
    }
    @Override public Set<UUID> deletionMembers() { return Set.copyOf(getMapTeams().getJoinedPlayersWithSpec()); }
    @Override public void evictRecoveredMember(UUID player) {
        if (getMapTeams().removePlayer(player)) {
            match.removeMember(player);
            room.membershipChanged();
        }
    }
    @Override public void join(ServerPlayer player) { room.join(player, JoinRoomRequest.autoTeam()); }
    @Override public void join(String team, ServerPlayer player) { room.join(player, JoinRoomRequest.team(team)); }
    @Override public void joinSpec(ServerPlayer player) { room.join(player, JoinRoomRequest.spectatorOnly()); }
    void admit(String team, ServerPlayer player) {
        super.join(team, player);
        if (checkGameHasPlayer(player) && player.getAbilities().invulnerable) {
            termination().registerCustom(player, INVULNERABILITY, "true");
        }
    }
    public static void registerPlayerRecovery() {
        PlayerRecoveryExecutor.register(INVULNERABILITY, (player, value) -> {
            player.getAbilities().invulnerable = Boolean.parseBoolean(value);
            player.onUpdateAbilities();
        });
    }
    @Override public void leave(ServerPlayer player) {
        if (player == null || !checkGameHasPlayer(player)) return;
        match.closeSessions(player);
        super.leave(player);
        if (!match.waiting()) ModNetworkChannel.sendToPlayer(new GamePhasePacket("WAITING", 0), player);
        match.removeMember(player.getUUID());
        room.membershipChanged();
        if (getMapTeams().getJoinedPlayersWithSpec().isEmpty()) termination().finishNormally(this);
    }
    @Override public void onPlayerLoggedOut(ServerPlayer player) {
        try { leave(player); }
        catch (IllegalStateException failure) { logLeaveFailure(player, failure); }
    }
    void logLeaveFailure(ServerPlayer player, IllegalStateException failure) {
        LogUtils.getLogger().warn("Unable to recover {} leaving Hardpoint room {}", player.getUUID(), roomId.encode(), failure);
    }
    void syncRuntimeState(ServerPlayer viewer) {
        ModNetworkChannel.sendToPlayer(new ModeRuntimeStatePacket(match.runtimeStateSnapshot(viewer)), viewer);
    }
    @Override public void syncToClient() { room.roster.sync(); }
    @Override public void startGame() { match.vote.startApprovedMatch(); }
    @Override public void resetGame() { termination().finishNormally(this); }
    @Override public boolean victoryGoal() { return false; }
    @Override public void victory() { }
}
