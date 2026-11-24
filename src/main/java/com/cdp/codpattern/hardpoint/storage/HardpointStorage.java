package com.cdp.codpattern.hardpoint.storage;

import com.cdp.codpattern.app.match.management.MapNameValidation;
import com.cdp.codpattern.app.match.persistence.CommonModeMapData;
import com.cdp.codpattern.app.match.persistence.MapDefinitionCodec;
import com.cdp.codpattern.app.match.persistence.ModeMapMutationProvider;
import com.cdp.codpattern.compat.fpsmatch.map.FpsMatchMapRegistry;
import com.cdp.codpattern.config.storage.MapStorageRegistration;
import com.cdp.codpattern.config.storage.ServerMapStorage;
import com.cdp.codpattern.hardpoint.HardpointAddon;
import com.cdp.codpattern.hardpoint.room.HardpointMap;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import com.phasetranscrystal.fpsmatch.core.data.AreaData;
import com.phasetranscrystal.fpsmatch.core.data.SpawnPointData;
import com.phasetranscrystal.fpsmatch.core.data.save.FPSMDataManager;
import com.phasetranscrystal.fpsmatch.core.data.save.SaveHolder;
import com.phasetranscrystal.fpsmatch.core.event.RegisterFPSMSaveDataEvent;
import com.phasetranscrystal.fpsmatch.core.map.BaseMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@Mod.EventBusSubscriber(modid = HardpointAddon.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HardpointStorage implements ModeMapMutationProvider {
    public static final HardpointStorage INSTANCE = new HardpointStorage();
    public static final String DIRECTORY = HardpointAddon.GAME_TYPE;
    private static final int SCHEMA_VERSION = 1;

    private HardpointStorage() { }

    public record MapData(int schemaVersion, String gameType, String mapName, ResourceLocation levelName,
                          AreaData areaData, Optional<SpawnPointData> matchEndTeleportPoint,
                          List<SpawnPointData> redSpawns, List<SpawnPointData> blueSpawns, List<HardpointZone> zones) {
        public static final Codec<MapData> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("schemaVersion").forGetter(MapData::schemaVersion),
                Codec.STRING.fieldOf("gameType").forGetter(MapData::gameType),
                Codec.STRING.fieldOf("mapName").forGetter(MapData::mapName),
                ResourceLocation.CODEC.fieldOf("levelName").forGetter(MapData::levelName),
                HardpointZone.AREA_CODEC.fieldOf("areaData").forGetter(MapData::areaData),
                optional("matchEndTeleportPoint", SpawnPointData.CODEC).forGetter(MapData::matchEndTeleportPoint),
                SpawnPointData.CODEC.listOf().fieldOf("redSpawns").forGetter(MapData::redSpawns),
                SpawnPointData.CODEC.listOf().fieldOf("blueSpawns").forGetter(MapData::blueSpawns),
                HardpointZone.CODEC.listOf().fieldOf("zones").forGetter(MapData::zones)
        ).apply(i, MapData::new));

        public MapData {
            if (!HardpointAddon.GAME_TYPE.equals(gameType) || schemaVersion != SCHEMA_VERSION)
                throw new IllegalArgumentException("Unsupported Hardpoint map type or schema version");
            var name = MapNameValidation.validate(mapName);
            if (!name.valid() || !name.normalized().equals(mapName))
                throw new IllegalArgumentException("Invalid map name");
            var deployment = new HardpointDeployment(redSpawns, blueSpawns, zones, matchEndTeleportPoint);
            deployment.validateBounds(levelName, areaData);
            redSpawns = deployment.redSpawns();
            blueSpawns = deployment.blueSpawns();
            zones = deployment.zones();
            matchEndTeleportPoint = deployment.endPoint();
        }

        private HardpointDeployment deployment() {
            return new HardpointDeployment(redSpawns, blueSpawns, zones, matchEndTeleportPoint);
        }

        private HardpointMap instantiate(ServerLevel level) {
            if (!levelName.equals(level.dimension().location())) {
                throw new IllegalArgumentException("Map " + mapName + " belongs to " + levelName);
            }
            HardpointMap map = new HardpointMap(level, mapName, areaData);
            map.replaceDeployment(deployment());
            return map;
        }
    }

    @SubscribeEvent
    public static void registerSaveData(RegisterFPSMSaveDataEvent event) {
        var holder = new SaveHolder.Builder<>(MapData.CODEC)
                .withReadHandler(HardpointStorage::load)
                .withWriteHandler(manager -> FpsMatchMapRegistry.listMaps(HardpointAddon.GAME_TYPE)
                        .forEach(map -> INSTANCE.save(map, manager)))
                .build();
        event.registerMapData(MapData.class, new MapStorageRegistration(HardpointAddon.GAME_TYPE, HardpointAddon.MOD_ID,
                DIRECTORY, List.of(), holder::decodeFromJson, null), holder);
    }

    private static void load(MapData data) {
        if (FpsMatchMapRegistry.findByName(HardpointAddon.GAME_TYPE, data.mapName()).isPresent()) {
            return;
        }
        var server = FPSMCore.getInstance().getServer();
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, data.levelName()));
        if (level == null) {
            throw new IllegalStateException("Missing dimension " + data.levelName() + " for map " + data.mapName());
        }
        HardpointMap map = data.instantiate(level);
        try {
            FpsMatchMapRegistry.register(HardpointAddon.GAME_TYPE, map);
        } catch (RuntimeException error) {
            INSTANCE.retire(map);
            throw error;
        }
    }

    @Override
    public String gameType() {
        return HardpointAddon.GAME_TYPE;
    }

    @Override
    public MapData capturePayload(BaseMap map) {
        HardpointMap room = (HardpointMap) map;
        return data(room, room.deployment());
    }

    @Override
    public void applyPayload(BaseMap map, Object payload) {
        ((HardpointMap) map).replaceDeployment(((MapData) payload).deployment());
    }

    @Override
    public HardpointMap createMap(ServerLevel level, CommonModeMapData common, Object payload) {
        var config = payload == null ? HardpointDeployment.EMPTY.withEndPoint(common.fallbackExitPoint())
                : ((MapData) payload).deployment();
        return new MapData(SCHEMA_VERSION, common.gameType(), common.mapName(),
                ResourceLocation.parse(common.levelName()), common.areaData(), config.endPoint(),
                config.redSpawns(), config.blueSpawns(), config.zones())
                .instantiate(level);
    }

    @Override
    public void save(BaseMap map, FPSMDataManager manager) {
        manager.saveData(capturePayload(map), map.getMapName(), true);
    }

    @Override
    public FPSMDataManager.DeleteStatus delete(String mapName, FPSMDataManager manager) {
        return manager.deleteData(MapData.class, mapName);
    }

    public static boolean editable(HardpointMap map) {
        return map.isDeploymentEditable()
                && !ServerMapStorage.get(map.getServerLevel().getServer()).blocked(HardpointAddon.GAME_TYPE)
                && FpsMatchMapRegistry.findByName(HardpointAddon.GAME_TYPE, map.getMapName()).orElse(null) == map;
    }

    /** Commit on the server thread. Publish the new in-memory layout only after its atomic file write. */
    public static void update(ServerPlayer admin, HardpointMap map, UUID expectedVersion, HardpointDeployment replacement) {
        if (admin == null || !admin.hasPermissions(2) || !admin.server.isSameThread())
            throw new IllegalStateException(HardpointDeployment.PREFIX + "permission");
        // Exit points may be marked in another dimension; all arena geometry stays map-local.
        if (admin.serverLevel() != map.getServerLevel()
                && !replacement.withEndPoint(map.deployment().endPoint()).equals(map.deployment()))
            throw new IllegalArgumentException(HardpointDeployment.PREFIX + "dimension");
        if (!editable(map)) throw new IllegalStateException(HardpointDeployment.PREFIX + "locked");
        if (!map.deploymentVersion().equals(expectedVersion)) throw new IllegalStateException(HardpointDeployment.PREFIX + "stale");
        replacement.validateChange(map, map.deployment());
        if (replacement.equals(map.deployment())) return;

        try (var ignored = ServerMapStorage.get(admin.server).beginManagement(HardpointAddon.GAME_TYPE)) {
            FPSMCore.getInstance().getFPSMDataManager().saveData(data(map, replacement), map.getMapName(), true);
            map.replaceDeployment(replacement);
        } catch (RuntimeException failure) {
            throw new IllegalStateException(HardpointDeployment.PREFIX + "save_failed", failure);
        }
        map.syncToClient();
    }

    private static MapData data(HardpointMap map, HardpointDeployment config) {
        return new MapData(SCHEMA_VERSION, HardpointAddon.GAME_TYPE, map.getMapName(),
                map.getServerLevel().dimension().location(), map.getMapArea(), config.endPoint(),
                config.redSpawns(), config.blueSpawns(), config.zones());
    }

    @Override
    public JsonObject captureDefinition(BaseMap map) {
        return MapDefinitionCodec.encode(MapData.CODEC, capturePayload(map));
    }

    @Override
    public HardpointMap createRenamed(ServerLevel level, JsonObject definition, String newName) {
        return MapDefinitionCodec.decode(MapData.CODEC, MapDefinitionCodec.withName(definition, newName))
                .instantiate(level);
    }

    @Override
    public void retire(BaseMap map) {
        ((HardpointMap) map).getMapTeams().retireCreatedScoreboardTeams();
    }

    // DFU's optionalFieldOf discards decode errors. A present but malformed field must fail the load.
    private static <T> MapCodec<Optional<T>> optional(String name, Codec<T> codec) {
        return new MapCodec<>() {
            @Override public <V> Stream<V> keys(DynamicOps<V> ops) { return Stream.of(ops.createString(name)); }
            @Override public <V> DataResult<Optional<T>> decode(DynamicOps<V> ops, MapLike<V> input) {
                V value = input.get(name);
                return value == null ? DataResult.success(Optional.empty()) : codec.parse(ops, value).map(Optional::of);
            }
            @Override public <V> RecordBuilder<V> encode(Optional<T> value, DynamicOps<V> ops, RecordBuilder<V> out) {
                return value.isPresent() ? out.add(name, codec.encodeStart(ops, value.get())) : out;
            }
        };
    }
}

