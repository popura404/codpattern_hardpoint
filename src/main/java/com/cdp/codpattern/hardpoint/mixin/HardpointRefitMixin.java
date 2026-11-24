package com.cdp.codpattern.hardpoint.mixin;

import com.cdp.codpattern.app.refit.service.AttachmentPresetRequestService;
import com.cdp.codpattern.hardpoint.room.HardpointMap;
import com.phasetranscrystal.fpsmatch.core.FPSMCore;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.UUID;

/** CDP's attachment preview uses the live inventory. Keep it in the lobby for this mode. */
@Mixin(value = AttachmentPresetRequestService.class, remap = false)
abstract class HardpointRefitMixin {
    @Inject(method = "prepare(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/UUID;ILjava/lang/String;)Ljava/util/Optional;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void hardpoint$checkRefit(ServerPlayer player, UUID session, int bag, String slot,
                                             CallbackInfoReturnable<Optional<AttachmentPresetRequestService.Result>> result) {
        if (player != null && FPSMCore.initialized()
                && FPSMCore.getInstance().getMapByPlayerWithSpec(player).orElse(null) instanceof HardpointMap room
                && !room.isDeploymentEditable()) {
            player.sendSystemMessage(Component.translatable("message.codpattern_hardpoint.refit_locked"));
            result.setReturnValue(Optional.empty());
        }
    }
}
