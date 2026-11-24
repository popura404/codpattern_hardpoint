package com.cdp.codpattern.hardpoint;

import com.cdp.codpattern.app.match.ModeModules;
import com.cdp.codpattern.hardpoint.deploy.HardpointDeploymentTool;
import com.cdp.codpattern.hardpoint.room.HardpointMap;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(HardpointAddon.MOD_ID)
public final class HardpointAddon {
    public static final String MOD_ID = "codpattern_hardpoint";
    public static final String GAME_TYPE = "hardpoint";

    public HardpointAddon() {
        HardpointMap.registerPlayerRecovery();
        HardpointDeploymentTool.register(FMLJavaModLoadingContext.get().getModEventBus());
        ModeModules.contribute(HardpointModeModule.INSTANCE);
    }
}
