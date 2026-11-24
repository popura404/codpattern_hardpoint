package com.cdp.codpattern.hardpoint;

import com.cdp.codpattern.app.match.editor.ModeEndTeleportSupport;
import com.cdp.codpattern.app.match.extension.ModeHeldToolPreviewContributor;
import com.cdp.codpattern.app.match.extension.ModeModule;
import com.cdp.codpattern.app.match.model.*;
import com.cdp.codpattern.hardpoint.deploy.HardpointPreview;
import com.cdp.codpattern.hardpoint.room.HardpointRuntimeProvider;
import com.cdp.codpattern.hardpoint.storage.HardpointStorage;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;
import java.util.Set;

enum HardpointModeModule implements ModeModule {
    INSTANCE;

    @Override
    public ResourceLocation id() {
        return new ResourceLocation(HardpointAddon.MOD_ID, "mode");
    }

    @Override
    public List<ModeHeldToolPreviewContributor> heldToolPreviewContributors() {
        return List.of(HardpointPreview.INSTANCE);
    }

    @Override
    public List<GameModeDefinition> definitions() {
        return List.of(new GameModeDefinition(
                HardpointAddon.GAME_TYPE, List.of("hp"),
                "mode.codpattern_hardpoint.name", "mode.codpattern_hardpoint.room", "mode.codpattern_hardpoint.create_hint",
                List.of(new TeamDescriptor("red", "team.codpattern_hardpoint.red", "team.codpattern_hardpoint.red.short", 0xFFEF7775),
                        new TeamDescriptor("blue", "team.codpattern_hardpoint.blue", "team.codpattern_hardpoint.blue.short", 0xFF80B9F4)),
                ModeFamily.PVP_TEAM, TeamPolicy.FIXED_TEAMS, JoinPolicy.WAITING_ONLY,
                LifecycleKind.OBJECTIVE_LOOP, ScoreboardKind.TEAM_SCORE,
                Set.of(ModeCapability.TEAM_SELECTION, ModeCapability.READY_STATE, ModeCapability.START_VOTE,
                        ModeCapability.MATCH_END_TELEPORT, ModeCapability.ROUND_START_SPAWNS, ModeCapability.MODE_SPECIFIC_MAP_FEATURES),
                Optional.of(HardpointRuntimeProvider.INSTANCE), Optional.of(HardpointStorage.INSTANCE),
                Optional.of(ModeEndTeleportSupport.schema()),
                Optional.of(new ClientModePresentation("textures/gui/modes/shared_preview.png", 3840, 2160,
                        0xFFD2B6FF, "mode.codpattern_hardpoint.description", ""))));
    }
}
