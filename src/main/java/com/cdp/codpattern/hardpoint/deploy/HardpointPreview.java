package com.cdp.codpattern.hardpoint.deploy;

import com.cdp.codpattern.app.match.extension.ModeHeldToolPreviewContributor;
import com.cdp.codpattern.hardpoint.room.HardpointMap;
import com.cdp.codpattern.hardpoint.storage.HardpointZone;
import com.phasetranscrystal.fpsmatch.FPSMatch;
import com.phasetranscrystal.fpsmatch.common.packet.AddAreaDataS2CPacket;
import com.phasetranscrystal.fpsmatch.common.packet.AddPointDataS2CPacket;
import com.phasetranscrystal.fpsmatch.common.packet.RemoveDebugDataByPrefixS2CPacket;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import com.phasetranscrystal.fpsmatch.core.data.AreaData;
import com.phasetranscrystal.fpsmatch.core.data.SpawnPointData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.UUID;
import java.util.WeakHashMap;

/** CDP removes these private overlays when the operator puts the deployment tool away. */
public enum HardpointPreview implements ModeHeldToolPreviewContributor {
    INSTANCE;
    private static final int LAVENDER = 0xFFC4A6ED;
    private record State(HardpointMap map, UUID version, HardpointEditor.Field field, int index,
                         BlockPos first, BlockPos second, float yaw) {}
    private final WeakHashMap<ServerPlayer, State> visible = new WeakHashMap<>();
    @Override public String id() { return "codpattern_hardpoint:deployment"; }
    @Override public boolean matches(ItemStack stack) { return stack != null && stack.is(HardpointDeploymentTool.ITEM.get()); }
    @Override public void clear(ServerPlayer p) {
        if (visible.remove(p) != null) FPSMatch.sendToPlayer(p, new RemoveDebugDataByPrefixS2CPacket(prefix(p)));
    }
    private static String prefix(ServerPlayer p) { return "held_tool_preview:codpattern_hardpoint:" + p.getUUID() + ":"; }

    @Override public void sync(ServerPlayer p, ItemStack stack) {
        if (!matches(stack) || !FPSMCore.initialized()) { clear(p); return; }
        var d = HardpointEditor.current(p);
        if (d == null || FPSMCore.getInstance().getMapByPlayerWithSpec(p).isPresent()) { clear(p); return; }
        var map = d.map();
        if ((map == null && !d.pending()) || HardpointEditor.problem(d, map) != null) { clear(p); return; }
        var state = new State(map, d.version, d.field, d.index, d.first, d.second, d.yaw);
        if (state.equals(visible.get(p))) return;
        clear(p);
        visible.put(p, state);
        String key = prefix(p);
        if (map != null && map.getServerLevel() == p.serverLevel()) {
            var layout = map.deployment();
            box(p, key + "arena", Component.literal(map.getMapName()), 0xFF817888, map.getMapArea());
            for (int i = 0; i < layout.zones().size(); i++) {
                var zone = layout.zones().get(i);
                int color = d.field == HardpointEditor.Field.ZONE && i == d.index ? 0xFFEEE0FF : LAVENDER;
                box(p, key + "zone" + i, Component.literal("P" + (i + 1) + " · " + zone.name()), color, zone.captureArea());
            }
            spawns(p, key, HardpointEditor.Field.RED, layout.redSpawns(), 0xFFF39595);
            spawns(p, key, HardpointEditor.Field.BLUE, layout.blueSpawns(), 0xFF93B8F3);
            layout.endPoint().filter(point -> point.getDimension().equals(p.level().dimension())).ifPresent(point ->
                    point(p, key + "exit", HardpointEditor.Field.EXIT.label(), 0xFF9CD3B4, point.getPosition(), point.getYaw()));
        }
        if (d.first != null) point(p, key + "first", Component.literal("1"), 0xFFFFFFFF, d.first, d.field.paired() ? Float.NaN : d.yaw);
        if (d.second != null) point(p, key + "second", Component.literal("2"), 0xFFFFFFFF, d.second, Float.NaN);
        if (d.first != null && d.second != null) {
            var marked = new AreaData(d.first, d.second);
            var shown = d.field == HardpointEditor.Field.ZONE ? HardpointZone.captureArea(marked) : marked;
            box(p, key + "marked", d.field.label(), 0xFFFFFFFF, shown);
        }
    }
    private static void spawns(ServerPlayer p, String key, HardpointEditor.Field field, List<SpawnPointData> spawns, int color) {
        for (int i = 0; i < spawns.size(); i++) {
            var spawn = spawns.get(i);
            point(p, key + field.name() + i, field.label().copy().append(" " + (i + 1)), color,
                    spawn.getPosition(), spawn.getYaw());
        }
    }
    private static void box(ServerPlayer p, String key, Component label, int color, AreaData area) {
        FPSMatch.sendToPlayer(p, new AddAreaDataS2CPacket(key, label, color, area));
    }
    private static void point(ServerPlayer p, String key, Component label, int color, BlockPos pos, float yaw) {
        FPSMatch.sendToPlayer(p, new AddPointDataS2CPacket(key, label, color, Vec3.atCenterOf(pos), yaw));
    }
}
