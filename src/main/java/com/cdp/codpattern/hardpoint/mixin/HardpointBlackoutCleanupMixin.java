package com.cdp.codpattern.hardpoint.mixin;

import com.cdp.codpattern.client.state.ClientMatchStateStore;
import com.cdp.codpattern.hardpoint.HardpointAddon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientMatchStateStore.class, remap = false)
abstract class HardpointBlackoutCleanupMixin {
    @Shadow public abstract String roomContextName();
    @Shadow private void startBlackoutSequence(int ticks) { throw new AssertionError(); }

    /** Core's recovery packet clears the countdown flag while a running fade has its own clock. */
    @Inject(method = "updateCountdown", at = @At("RETURN"))
    private void hardpoint$cancelOpeningFade(int count, boolean blackout, CallbackInfo ci) {
        if (count == 0 && !blackout && roomContextName().startsWith(HardpointAddon.GAME_TYPE + "|")) {
            startBlackoutSequence(0);
        }
    }
}
