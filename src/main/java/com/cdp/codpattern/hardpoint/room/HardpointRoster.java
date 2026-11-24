package com.cdp.codpattern.hardpoint.room;

import com.cdp.codpattern.adapter.forge.network.ModNetworkChannel;
import com.cdp.codpattern.app.match.port.ModeRosterPort;
import com.cdp.codpattern.app.match.runtime.roster.RoomRosterSyncCoordinator;
import com.cdp.codpattern.app.tdm.service.TeamPlayerSnapshotService;
import com.cdp.codpattern.fpsmatch.room.PlayerInfo;
import com.cdp.codpattern.network.match.RoomPlayerDeltaPacket;
import com.cdp.codpattern.network.match.RoomPreviewRosterPacket;
import com.cdp.codpattern.network.match.RoomRosterDelta;
import com.cdp.codpattern.network.match.TeamPlayerListPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class HardpointRoster implements ModeRosterPort, RoomRosterSyncCoordinator.Source<ServerPlayer>,
        RoomRosterSyncCoordinator.Publisher<ServerPlayer> {
    private final HardpointMap map;
    private final RoomRosterSyncCoordinator<ServerPlayer> sync;
    private Map<String, List<PlayerInfo>> finalRoster;

    HardpointRoster(HardpointMap map) {
        this.map = map;
        sync = new RoomRosterSyncCoordinator<>(this, this,
                RoomRosterSyncCoordinator.Settings.deltaEnabled(500, 2000), System::currentTimeMillis);
    }

    void sync() {
        if (map.match.ended() && finalRoster == null) {
            finalRoster = map.match.stats.roster(liveRoster(), true);
            sync.broadcastFullSnapshot();
        } else if (!map.match.ended() && finalRoster != null) {
            finalRoster = null;
            sync.broadcastFullSnapshot();
        } else {
            sync.synchronize(true);
        }
    }
    @Override public void requestRosterResync(ServerPlayer player) { sync.requestResync(player); }
    @Override public void requestRosterPreview(ServerPlayer player) { sync.requestPreview(player); }
    @Override public String roomKey() { return map.roomId().encode(); }
    @Override public UUID recipientId(ServerPlayer player) { return player.getUUID(); }
    @Override public boolean canRequestResync(ServerPlayer player) { return map.checkGameHasPlayer(player); }

    @Override
    public Map<String, List<PlayerInfo>> rosterSnapshot() {
        return finalRoster == null ? map.match.stats.roster(liveRoster(), false) : finalRoster;
    }

    private Map<String, List<PlayerInfo>> liveRoster() {
        var teams = map.getMapTeams().getTeams().stream()
                .map(team -> new TeamPlayerSnapshotService.TeamRoster(team.name, team.getPlayerList())).toList();
        var players = map.getServerLevel().getServer().getPlayerList();
        return TeamPlayerSnapshotService.buildTeamPlayers(teams, players::getPlayer,
                map.match.readySnapshot(), Map.of(), Map.of(), Map.of(), Set.of(), Set.of());
    }

    @Override
    public Collection<ServerPlayer> liveRecipients() {
        var players = map.getServerLevel().getServer().getPlayerList();
        return map.getMapTeams().getJoinedPlayersWithSpec().stream().map(players::getPlayer)
                .filter(Objects::nonNull).toList();
    }

    @Override
    public void publishFull(String room, int version, Map<String, List<PlayerInfo>> snapshot,
                            Collection<ServerPlayer> recipients) {
        var packet = new TeamPlayerListPacket(room, version, snapshot);
        recipients.forEach(player -> ModNetworkChannel.sendToPlayer(packet, player));
    }

    @Override
    public void publishDelta(String room, int version, List<RoomRosterDelta> updates,
                             Collection<ServerPlayer> recipients) {
        var packet = new RoomPlayerDeltaPacket(room, version, updates);
        recipients.forEach(player -> ModNetworkChannel.sendToPlayer(packet, player));
    }

    @Override
    public void publishPreview(String room, int version, Map<String, List<PlayerInfo>> snapshot, ServerPlayer player) {
        ModNetworkChannel.sendToPlayer(new RoomPreviewRosterPacket(room, version, snapshot), player);
    }
}

