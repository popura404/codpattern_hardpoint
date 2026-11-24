package com.cdp.codpattern.hardpoint.room;

import com.cdp.codpattern.app.match.GameModeRuntimeProvider;
import com.cdp.codpattern.app.match.ModeRoomHandle;
import com.cdp.codpattern.compat.fpsmatch.map.FpsMatchMapRegistry;
import com.cdp.codpattern.hardpoint.HardpointAddon;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import com.phasetranscrystal.fpsmatch.core.data.AreaData;
import com.phasetranscrystal.fpsmatch.core.map.BaseMap;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;
import java.util.stream.Stream;

public final class HardpointRuntimeProvider implements GameModeRuntimeProvider {
    public static final HardpointRuntimeProvider INSTANCE = new HardpointRuntimeProvider();

    private HardpointRuntimeProvider() { }

    @Override
    public String gameType() {
        return HardpointAddon.GAME_TYPE;
    }

    @Override
    public HardpointMap createMap(ServerLevel level, String mapName, AreaData area) {
        return new HardpointMap(level, mapName, area);
    }

    @Override
    public Optional<ModeRoomHandle> roomHandle(BaseMap map) {
        return map instanceof HardpointMap hardpoint ? Optional.of(hardpoint.roomHandle()) : Optional.empty();
    }

    @Override
    public Stream<ModeRoomHandle> listRoomHandles() {
        if (!FPSMCore.initialized()) return Stream.empty();
        return FpsMatchMapRegistry.listMaps(gameType()).stream().flatMap(map -> roomHandle(map).stream());
    }
}

