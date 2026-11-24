package com.cdp.codpattern.hardpoint.mixin;

import com.cdp.codpattern.core.throwable.ThrowableInventoryService;
import com.cdp.codpattern.core.throwable.ThrowableStopReason;
import com.cdp.codpattern.hardpoint.room.HardpointWarmupWeapons;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ThrowableInventoryService.class, remap = false)
abstract class HardpointThrowableMixin {
    @Inject(method = "startThrowableUse", at = @At("HEAD"), cancellable = true, remap = false)
    private static void hardpoint$start(ServerPlayer player, int slot, CallbackInfoReturnable<Boolean> result) {
        if (HardpointWarmupWeapons.blocked(player)) result.setReturnValue(false);
    }

    @Inject(method = "stopThrowableUse", at = @At("HEAD"), cancellable = true, remap = false)
    private static void hardpoint$release(ServerPlayer player, ThrowableStopReason reason, CallbackInfo result) {
        if (!HardpointWarmupWeapons.blocked(player)) return;
        if (ThrowableInventoryService.getActiveSlot(player) >= 0) {
            player.stopUsingItem();
            ThrowableInventoryService.abortSession(player, true);
        }
        result.cancel();
    }
}
