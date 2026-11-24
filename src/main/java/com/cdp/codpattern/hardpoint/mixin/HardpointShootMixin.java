package com.cdp.codpattern.hardpoint.mixin;

import com.cdp.codpattern.hardpoint.room.HardpointWarmupWeapons;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.entity.shooter.LivingEntityShoot;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

@Mixin(value = LivingEntityShoot.class, remap = false)
abstract class HardpointShootMixin {
    @Shadow @Final private LivingEntity shooter;

    // The server's GunShootEvent is too late to prevent chambering a round from the magazine.
    @Inject(method = "shoot(Ljava/util/function/Supplier;Ljava/util/function/Supplier;J)Lcom/tacz/guns/api/entity/ShootResult;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void hardpoint$beforeChamber(Supplier<Float> pitch, Supplier<Float> yaw, long timestamp,
                                        CallbackInfoReturnable<ShootResult> result) {
        if (HardpointWarmupWeapons.blocked(shooter)) result.setReturnValue(ShootResult.FORGE_EVENT_CANCEL);
    }
}
