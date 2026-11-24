package com.cdp.codpattern.hardpoint.storage;

import com.cdp.codpattern.hardpoint.room.HardpointMap;
import com.phasetranscrystal.fpsmatch.core.data.AreaData;
import com.phasetranscrystal.fpsmatch.core.data.SpawnPointData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/** Draft maps may omit geometry. The list order on disk is the rotation order in game. */
public record HardpointDeployment(List<SpawnPointData> redSpawns, List<SpawnPointData> blueSpawns,
                                  List<HardpointZone> zones, Optional<SpawnPointData> endPoint) {
    public static final String PREFIX = "deploy.codpattern_hardpoint.";
    public static final int MAX_ZONES = 64, MAX_SPAWNS = 64;
    public static final HardpointDeployment EMPTY = new HardpointDeployment(List.of(), List.of(), List.of(), Optional.empty());

    public HardpointDeployment {
        if (zones.size() > MAX_ZONES || redSpawns.size() > MAX_SPAWNS || blueSpawns.size() > MAX_SPAWNS)
            throw error("limit");
        redSpawns = redSpawns.stream().map(HardpointDeployment::copyPoint).toList();
        blueSpawns = blueSpawns.stream().map(HardpointDeployment::copyPoint).toList();
        zones = List.copyOf(zones);
        endPoint = endPoint.map(HardpointDeployment::copyPoint);
        var names = new HashSet<String>();
        if (zones.stream().anyMatch(zone -> !names.add(zone.name().toLowerCase(Locale.ROOT))))
            throw error("duplicate_name");
    }

    public HardpointDeployment withEndPoint(Optional<SpawnPointData> end) {
        return new HardpointDeployment(redSpawns, blueSpawns, zones, end);
    }

    public List<String> problems(HardpointMap map) {
        var issues = new ArrayList<String>();
        if (redSpawns.isEmpty()) issues.add(PREFIX + "missing_red");
        if (blueSpawns.isEmpty()) issues.add(PREFIX + "missing_blue");
        if (zones.size() < 2) issues.add(PREFIX + "missing_zones");
        try {
            validateBounds(map.getServerLevel().dimension().location(), map.getMapArea());
            if (Stream.concat(redSpawns.stream(), blueSpawns.stream()).anyMatch(p -> !safe(map.getServerLevel(), p)))
                issues.add(PREFIX + "unsafe");
        } catch (IllegalArgumentException e) {
            issues.add(e.getMessage());
        }
        return List.copyOf(issues);
    }

    void validateBounds(ResourceLocation dimension, AreaData bounds) {
        var arena = bounds.getBlockInclusiveAABB();
        for (HardpointZone zone : zones) {
            var capture = zone.captureArea();
            if (!arena.contains(Vec3.atCenterOf(capture.pos1())) || !arena.contains(Vec3.atCenterOf(capture.pos2())))
                throw error("outside");
        }
        var positions = new HashSet<BlockPos>();
        for (SpawnPointData p : Stream.concat(redSpawns.stream(), blueSpawns.stream()).toList()) {
            if (!dimension.equals(p.getDimension().location()) || !arena.contains(Vec3.atCenterOf(p.getPosition())))
                throw error("outside");
            if (!positions.add(p.getPosition())) throw error("duplicate_spawn");
        }
    }

    void validateChange(HardpointMap map, HardpointDeployment previous) {
        var level = map.getServerLevel();
        validateBounds(level.dimension().location(), map.getMapArea());
        for (HardpointZone zone : zones)
            if (!inWorld(level, zone.captureArea().pos1()) || !inWorld(level, zone.captureArea().pos2())) throw error("outside");
        var oldSpawns = new HashSet<>(previous.redSpawns());
        oldSpawns.addAll(previous.blueSpawns());
        if (Stream.concat(redSpawns.stream(), blueSpawns.stream()).anyMatch(p -> !oldSpawns.contains(p) && !safe(level, p)))
            throw error("unsafe");
        endPoint.filter(p -> !previous.endPoint().equals(endPoint)).ifPresent(p -> {
            var destination = level.getServer().getLevel(p.getDimension());
            if (destination == null || !safe(destination, p)) throw error("unsafe");
        });
    }

    public static boolean inWorld(ServerLevel level, BlockPos pos) {
        return level.isInWorldBounds(pos) && level.getWorldBorder().isWithinBounds(pos);
    }

    private static boolean safe(ServerLevel level, SpawnPointData p) {
        var pos = p.getPosition();
        if (!level.dimension().equals(p.getDimension()) || !inWorld(level, pos) || !inWorld(level, pos.above())) return false;
        AABB body = new AABB(pos.getX() + .2, pos.getY(), pos.getZ() + .2,
                pos.getX() + .8, pos.getY() + 1.8, pos.getZ() + .8);
        return level.getFluidState(pos).isEmpty() && level.getFluidState(pos.above()).isEmpty()
                && !level.getBlockCollisions(null, body).iterator().hasNext()
                && level.getBlockCollisions(null, body.move(0, -.02, 0)).iterator().hasNext();
    }

    private static SpawnPointData copyPoint(SpawnPointData p) {
        if (!Float.isFinite(p.getYaw()) || !Float.isFinite(p.getPitch())) throw error("unsafe");
        return new SpawnPointData(p.getDimension(), p.getPosition().immutable(), p.getYaw(), p.getPitch(), p.getKind());
    }
    private static IllegalArgumentException error(String key) { return new IllegalArgumentException(PREFIX + key); }
}
