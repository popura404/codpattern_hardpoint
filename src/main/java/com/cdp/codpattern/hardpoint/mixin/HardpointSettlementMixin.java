package com.cdp.codpattern.hardpoint.mixin;

import com.cdp.codpattern.hardpoint.client.HardpointResult;
import com.cdp.codpattern.fpsmatch.room.PlayerInfo;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Keep CDP's native settlement sequence. Only its score table needs columns specific to Hardpoint. */
@Mixin(targets = "com.cdp.codpattern.client.gui.overlay.TdmSettlementScoreboard", remap = false)
abstract class HardpointSettlementMixin {
    @Inject(method = "draw", at = @At("HEAD"), cancellable = true, remap = false)
    private static void hardpoint$standings(GuiGraphics gui, Font font, int width, int height, double age,
            int homeScore, int awayScore, boolean homeIsKortac, boolean affiliated, UUID localId,
            Map<String, List<PlayerInfo>> nativeRosters, CallbackInfo callback) {
        if (HardpointResult.drawStandings(gui, font, width, height, age, localId)) callback.cancel();
    }
}
