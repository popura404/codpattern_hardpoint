package com.cdp.codpattern.hardpoint.mixin;

import com.cdp.codpattern.event.client.ThrowableClientInputHandler;
import com.cdp.codpattern.hardpoint.client.HardpointView;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ThrowableClientInputHandler.class, remap = false)
abstract class HardpointThrowableInputMixin {
    @Inject(method = "canStartThrowableInput", at = @At("HEAD"), cancellable = true, remap = false)
    private static void hardpoint$beforePredictingThrow(LocalPlayer player, CallbackInfoReturnable<Boolean> result) {
        if (HardpointView.preparingLocalPlayer(player)) result.setReturnValue(false);
    }
}
