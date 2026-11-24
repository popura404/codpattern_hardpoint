package com.cdp.codpattern.hardpoint.deploy;

import com.cdp.codpattern.app.match.management.MapNameValidation;
import com.cdp.codpattern.compat.fpsmatch.map.FpsMatchMapRegistry;
import com.cdp.codpattern.hardpoint.HardpointAddon;
import com.cdp.codpattern.hardpoint.room.HardpointMap;
import com.cdp.codpattern.hardpoint.storage.HardpointDeployment;
import com.cdp.codpattern.hardpoint.storage.HardpointStorage;
import com.cdp.codpattern.hardpoint.storage.HardpointZone;
import com.mojang.logging.LogUtils;
import com.phasetranscrystal.fpsmatch.common.item.tool.ToolAccessHelper;
import com.phasetranscrystal.fpsmatch.common.item.tool.ToolInteractionAction;
import com.phasetranscrystal.fpsmatch.common.item.tool.ToolInteractionHit;
import com.phasetranscrystal.fpsmatch.common.service.MapCreationService;
import com.phasetranscrystal.fpsmatch.core.data.AreaData;
import com.phasetranscrystal.fpsmatch.core.data.SpawnPointData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

final class HardpointEditor {
    enum Field {
        BOUNDS, RED, BLUE, ZONE, EXIT;
        boolean paired() { return this == BOUNDS || this == ZONE; }
        Component label() { return text("field_" + name().toLowerCase(Locale.ROOT)); }
    }
    enum Action { CREATE, NEXT_MAP, PREV_MAP, NEW_MAP, FIELD, NEXT_ZONE, PREV_ZONE, ADD_ZONE,
        SAVE, DELETE, UP, DOWN, DISCARD, VALIDATE, DRAFT_NAME }
    private static final WeakHashMap<ServerPlayer, Draft> DRAFTS = new WeakHashMap<>();
    private HardpointEditor() {}

    static final class Draft {
        final ItemStack tool;
        final ResourceKey<Level> dimension;
        UUID session = UUID.randomUUID();
        UUID version;
        String mapName = "", input = "";
        int index;
        BlockPos first, second;
        float yaw;
        Field field = Field.BOUNDS;
        Component feedback = Component.empty();
        Draft(ServerPlayer p) { tool = p.getMainHandItem(); dimension = p.serverLevel().dimension(); }
        boolean pending() { return first != null || second != null; }
        void clearPicks() { first = null; second = null; }
        HardpointMap map() {
            if (mapName.isEmpty()) return null;
            return FpsMatchMapRegistry.findByName(HardpointAddon.GAME_TYPE, mapName)
                    .filter(HardpointMap.class::isInstance).map(HardpointMap.class::cast).orElse(null);
        }
        void select(HardpointMap map) {
            mapName = map.getMapName();
            version = map.deploymentVersion();
            clearPicks();
            field = Field.ZONE;
            zoneIndex(0, map.deployment());
        }
        void zoneIndex(int next, HardpointDeployment data) {
            index = Math.max(0, Math.min(next, data.zones().size()));
            input = index < data.zones().size() ? data.zones().get(index).name() : "";
        }
    }
    static Draft current(ServerPlayer player) {
        if (!ToolAccessHelper.hasAdminAccess(player) || !player.getMainHandItem().is(HardpointDeploymentTool.ITEM.get())) return null;
        Draft d = DRAFTS.get(player);
        if (d == null || d.tool != player.getMainHandItem() || !d.dimension.equals(player.level().dimension())) {
            d = new Draft(player);
            DRAFTS.put(player, d);
        }
        return d;
    }
    static HardpointView open(ServerPlayer player) {
        Draft d = current(player);
        if (d == null) return null;
        d.session = UUID.randomUUID();
        return view(d);
    }
    static void capture(ServerPlayer player, ToolInteractionAction action, ToolInteractionHit hit) {
        Draft d = current(player);
        if (d == null) return;
        var map = d.map();
        String error = problem(d, map);
        if (error == null && map != null && map.getServerLevel() != player.serverLevel() && d.field != Field.EXIT)
            error = "dimension";
        var clicked = hit.clickedBlockPos();
        if (!d.field.paired() && action != ToolInteractionAction.LEFT_CLICK_BLOCK) return;
        var pos = d.field.paired() ? clicked : clicked.above();
        if (!HardpointDeployment.inWorld(player.serverLevel(), pos) || !player.serverLevel().hasChunkAt(clicked)
                || player.getEyePosition().distanceToSqr(Vec3.atCenterOf(clicked)) > Math.pow(player.getBlockReach() + 1, 2))
            error = "out_of_reach";
        if (error != null) {
            player.displayClientMessage(text(error), true);
            return;
        }
        if (d.field.paired() && action == ToolInteractionAction.RIGHT_CLICK_BLOCK) d.second = pos.immutable();
        else d.first = pos.immutable();
        d.yaw = player.getYRot();
        if (!d.field.paired()) {
            // Built-in point layers commit a left-click directly and allow placing a sequence without reopening the panel.
            act(player, d.session, Action.SAVE, "");
            d.clearPicks();
            player.displayClientMessage(d.feedback, true);
            return;
        }
        d.session = UUID.randomUUID();
        d.feedback = text("marked", d.field.label(), pos.toShortString());
        player.displayClientMessage(d.feedback, true);
    }
    static HardpointView act(ServerPlayer player, UUID session, Action action, String value) {
        var d = current(player);
        if (d == null) return null;
        if (!d.session.equals(session)) {
            d.feedback = text("stale");
            return view(d);
        }
        d.session = UUID.randomUUID();
        try {
            d.feedback = apply(player, d, action, value);
        } catch (RuntimeException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith(HardpointDeployment.PREFIX)) {
                d.feedback = Component.translatable(ex.getMessage());
            } else {
                LogUtils.getLogger().warn("Hardpoint deployment failed for {}", d.mapName, ex);
                d.feedback = text("save_failed");
            }
        }
        return view(d);
    }

    private static Component apply(ServerPlayer p, Draft d, Action action, String value) {
        var map = d.map();
        if (action == Action.DRAFT_NAME) {
            if (d.field == Field.BOUNDS || d.field == Field.ZONE) d.input = value;
            return d.feedback;
        }
        if (action == Action.DISCARD) {
            d.clearPicks();
            if (map != null) { d.version = map.deploymentVersion(); d.zoneIndex(d.index, map.deployment()); }
            return text("refreshed");
        }
        if (action == Action.NEW_MAP || action == Action.NEXT_MAP || action == Action.PREV_MAP) {
            if (d.pending()) return text("pending");
            if (action == Action.NEW_MAP) {
                d.version = null;
                d.mapName = d.input = "";
                d.field = Field.BOUNDS;
                d.index = 0;
                return Component.empty();
            }
            var maps = FpsMatchMapRegistry.listMaps(HardpointAddon.GAME_TYPE).stream()
                    .filter(HardpointMap.class::isInstance).map(HardpointMap.class::cast)
                    .sorted(java.util.Comparator.comparing(HardpointMap::getMapName)).toList();
            if (maps.isEmpty()) return text("no_maps");
            int current = maps.indexOf(map);
            int index = Math.floorMod((current < 0 && action == Action.PREV_MAP ? 0 : current)
                    + (action == Action.NEXT_MAP ? 1 : -1), maps.size());
            d.select(maps.get(index));
            return Component.empty();
        }
        String problem = problem(d, map);
        if (problem != null) return text(problem);
        if (action == Action.CREATE) {
            d.input = value;
            if (!d.mapName.isEmpty() || d.field != Field.BOUNDS || d.first == null || d.second == null
                    || d.first.equals(d.second)) return text("need_bounds");
            var name = MapNameValidation.validate(value);
            if (!name.valid()) return Component.translatable("screen.codpattern.map_admin.result.invalid_name");
            if (FpsMatchMapRegistry.listMaps(HardpointAddon.GAME_TYPE).stream()
                    .anyMatch(m -> m.getMapName().equalsIgnoreCase(name.normalized()))) return text("map_exists");
            var result = MapCreationService.instance().createMap(p, HardpointAddon.GAME_TYPE, name.normalized(), d.first, d.second);
            if (result.success()) d.select((HardpointMap)result.map());
            return Component.translatable(result.messageKey(), result.arguments().toArray());
        }
        if (map == null) return text("no_maps");
        var data = map.deployment();
        if (action == Action.VALIDATE) {
            var issues = data.problems(map);
            return issues.isEmpty() ? text("ready") : Component.translatable(issues.get(0));
        }
        if (action == Action.FIELD || action == Action.NEXT_ZONE || action == Action.PREV_ZONE || action == Action.ADD_ZONE) {
            if (d.pending()) return text("pending");
            if (action == Action.FIELD) {
                for (var f : Field.values()) if (f != Field.BOUNDS && f.name().equals(value)) d.field = f;
            } else if (d.field == Field.ZONE && action == Action.ADD_ZONE) {
                d.zoneIndex(data.zones().size(), data);
            } else if (d.field == Field.ZONE) {
                d.zoneIndex(Math.floorMod(d.index + (action == Action.NEXT_ZONE ? 1 : -1), data.zones().size() + 1), data);
            }
            return Component.empty();
        }
        if (action != Action.SAVE && d.pending()) return text("pending");
        if (action != Action.SAVE && action != Action.DELETE && action != Action.UP && action != Action.DOWN) return text("stale");
        var red = new ArrayList<>(data.redSpawns());
        var blue = new ArrayList<>(data.blueSpawns());
        var zones = new ArrayList<>(data.zones());
        var end = data.endPoint();
        int index = d.index;
        if (d.field == Field.ZONE) {
            if (action == Action.SAVE) {
                d.input = value;
                if ((d.first == null) != (d.second == null) || (index == zones.size() && d.first == null))
                    return text("need_pair");
                AreaData area = d.first == null ? zones.get(index).area() : new AreaData(d.first, d.second);
                var zone = new HardpointZone(value, area);
                if (index == zones.size()) zones.add(zone); else zones.set(index, zone);
            } else {
                if (index >= zones.size()) return text("select_zone");
                if (action == Action.DELETE) zones.remove(index);
                else {
                    int target = index + (action == Action.UP ? -1 : 1);
                    if (target < 0 || target >= zones.size()) return text("select_zone");
                    Collections.swap(zones, index, target);
                    index = target;
                }
            }
        } else {
            if (action == Action.UP || action == Action.DOWN || d.field == Field.BOUNDS) return text("select_zone");
            if (action == Action.SAVE && d.first == null) return text("need_mark");
            var point = action == Action.SAVE ? Optional.of(new SpawnPointData(p.level().dimension(), d.first, d.yaw, 0))
                    : Optional.<SpawnPointData>empty();
            if (d.field == Field.EXIT) end = point;
            else {
                var list = d.field == Field.RED ? red : blue;
                if (point.isEmpty()) {
                    if (!list.isEmpty()) list.remove(list.size() - 1);
                } else {
                    list.removeIf(existing -> existing.getPosition().equals(d.first));
                    list.add(point.get());
                }
            }
        }
        HardpointStorage.update(p, map, d.version, new HardpointDeployment(red, blue, zones, end));
        d.version = map.deploymentVersion();
        d.zoneIndex(index, map.deployment());
        d.clearPicks();
        return text("saved");
    }
    static String problem(Draft d, HardpointMap map) {
        if (d.mapName.isEmpty()) return null;
        if (map == null || !map.deploymentVersion().equals(d.version)) return "stale";
        return HardpointStorage.editable(map) ? null : "locked";
    }
    private static HardpointView view(Draft d) {
        var map = d.map();
        var data = map == null ? HardpointDeployment.EMPTY : map.deployment();
        String error = problem(d, map);
        return new HardpointView(d.session, d.mapName, d.field, d.index, d.input,
                data.zones().stream().map(HardpointZone::name).toList(), data.redSpawns().size(), data.blueSpawns().size(),
                data.endPoint().isPresent(), Optional.ofNullable(d.first), Optional.ofNullable(d.second),
                error == null, error == null ? d.feedback : text(error));
    }
    static Component text(String key, Object... args) { return Component.translatable(HardpointDeployment.PREFIX + key, args); }
}
