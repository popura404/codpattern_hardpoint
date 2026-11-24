package com.cdp.codpattern.hardpoint.client;

import com.cdp.codpattern.app.match.model.ModeRuntimeStateSnapshot;
import com.cdp.codpattern.client.ClientMatchState;
import com.cdp.codpattern.client.ClientModeRuntimeState;
import com.cdp.codpattern.hardpoint.HardpointAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.phys.AABB;

import java.util.Locale;

/** Read-only presentation of the local room's most recent server snapshot. */
public final class HardpointView {
    static final int LILAC = 0xFFD2B6FF, INK = 0xFFF5F0FC, MUTED = 0xFFC5BDD2;
    static final int RED = 0xFFFFB9AE, BLUE = 0xFFAEDCFF, AMBER = 0xFFFFD797;
    private static HardpointView latest;

    final ModeRuntimeStateSnapshot state;
    final Hill current, next;
    final String status;
    final long received;

    private HardpointView(ModeRuntimeStateSnapshot state, long received) {
        this.state = state;
        this.received = received;
        status = value("status", "NEUTRAL").toUpperCase(Locale.ROOT);
        current = hill("point");
        next = hill("next");
    }

    static HardpointView read() {
        var mc = Minecraft.getInstance();
        String room = ClientMatchState.roomContextName();
        if (mc.level == null || mc.player == null || room == null || !room.startsWith(HardpointAddon.GAME_TYPE + "|")) {
            latest = null;
            return null;
        }
        var snapshot = ClientModeRuntimeState.snapshot(room).orElse(null);
        if (snapshot == null || !snapshot.roomKey().equals(room)) return null;
        if (latest == null || latest.state != snapshot) latest = new HardpointView(snapshot, mc.level.getGameTime());
        return latest;
    }

    public static boolean preparingLocalPlayer(net.minecraft.world.entity.Entity actor) {
        if (actor != Minecraft.getInstance().player) return false;
        var view = read();
        return view != null && "WARMUP".equals(view.state.phaseKey());
    }

    String value(String key, String fallback) {
        var field = state.playerValues().get(key);
        return field == null ? fallback : field.value();
    }

    int number(String key, int fallback) {
        var field = state.playerValues().get(key);
        if (field == null) return fallback;
        try { return Integer.parseInt(field.value()); }
        catch (NumberFormatException invalid) { return fallback; }
    }

    private Hill hill(String prefix) {
        int index = number(prefix + "Index", 0);
        if (index < 1) return null;
        int[] block = new int[6];
        String[] edges = {"MinX", "MinY", "MinZ", "MaxX", "MaxY", "MaxZ"};
        for (int i = 0; i < block.length; i++) {
            block[i] = number(prefix + edges[i], Integer.MIN_VALUE);
            if (block[i] == Integer.MIN_VALUE) return null;
        }
        AABB bounds = new AABB(block[0], block[1], block[2], block[3] + 1d, block[4] + 1d, block[5] + 1d);
        return new Hill(index, value(prefix + "Name", ""), bounds);
    }

    record Hill(int index, String name, AABB bounds) {
        String label() {
            String id = "P" + index;
            return name.isBlank() || name.equalsIgnoreCase(id) ? id : id + "  " + name;
        }
    }

    int statusColor() {
        return switch (status) {
            case "RED" -> RED;
            case "BLUE" -> BLUE;
            case "CONTESTED" -> AMBER;
            default -> LILAC;
        };
    }

    String statusLabel() {
        return switch (status) {
            case "RED", "BLUE" -> tr("holding", teamName(status.toLowerCase(Locale.ROOT)));
            case "CONTESTED" -> tr("contested");
            default -> tr("neutral");
        };
    }

    static String teamName(String team) { return I18n.get("team.codpattern_hardpoint." + team); }
    static String tr(String key, Object... args) { return I18n.get("hud.codpattern_hardpoint." + key, args); }
    static String clock(int seconds) {
        return String.format(Locale.ROOT, "%d:%02d", Math.max(0, seconds) / 60, Math.max(0, seconds) % 60);
    }
}
