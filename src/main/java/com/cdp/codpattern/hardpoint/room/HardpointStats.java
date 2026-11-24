package com.cdp.codpattern.hardpoint.room;

import com.cdp.codpattern.app.match.model.ModePlayerValue;
import com.cdp.codpattern.fpsmatch.room.PlayerInfo;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/** All players who spawned in this match remain eligible for its final standings. */
final class HardpointStats {
    private static final class Player {
        final String name;
        final String team;
        int kills, deaths, holdTicks;
        Player(String name, String team) {
            this.name = name;
            this.team = team;
        }
    }
    private final Map<UUID, Player> players = new LinkedHashMap<>();

    void enter(ServerPlayer player, String team) {
        players.put(player.getUUID(), new Player(player.getGameProfile().getName(), team));
    }
    void hold(UUID id) { players.get(id).holdTicks++; }
    void death(UUID victim, UUID killer) {
        Player target = players.get(victim);
        if (target == null) return;
        target.deaths++;
        Player attacker = players.get(killer);
        if (attacker != null && !attacker.team.equals(target.team)) attacker.kills++;
    }
    void addHoldTimes(Map<String, ModePlayerValue> values) {
        players.forEach((id, p) -> values.put("hold." + id, ModePlayerValue.ofInt(p.holdTicks / 20)));
    }
    Map<String, List<PlayerInfo>> roster(Map<String, List<PlayerInfo>> live, boolean includeDeparted) {
        if (players.isEmpty()) return live;
        Map<UUID, PlayerInfo> online = new HashMap<>();
        live.values().forEach(rows -> rows.forEach(row -> online.put(row.uuid(), row)));
        Map<String, List<PlayerInfo>> teams = Map.of("red", new ArrayList<>(), "blue", new ArrayList<>());
        players.forEach((id, p) -> {
            PlayerInfo row = online.get(id);
            if (row == null && !includeDeparted) return;
            teams.get(p.team).add(new PlayerInfo(id, p.name, row != null && row.isReady(), p.kills, p.deaths,
                    0, row != null && row.isAlive(), false, row == null ? -1 : row.pingMs()));
        });
        var order = Comparator.<PlayerInfo>comparingInt(p -> players.get(p.uuid()).holdTicks / 20).reversed()
                .thenComparing(Comparator.comparingInt(PlayerInfo::kills).reversed())
                .thenComparingInt(PlayerInfo::deaths).thenComparing(PlayerInfo::name).thenComparing(PlayerInfo::uuid);
        teams.values().forEach(rows -> rows.sort(order));
        return Map.of("red", List.copyOf(teams.get("red")), "blue", List.copyOf(teams.get("blue")));
    }
    void clear() { players.clear(); }
}
