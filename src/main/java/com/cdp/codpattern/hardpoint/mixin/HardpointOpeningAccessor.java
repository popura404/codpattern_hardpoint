package com.cdp.codpattern.hardpoint.mixin;

import com.cdp.codpattern.client.gui.overlay.TdmHudOverlay;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = TdmHudOverlay.class, remap = false)
public interface HardpointOpeningAccessor {
    @Invoker("renderCountdownOverlay")
    void hardpoint$renderOpening(GuiGraphics graphics, Font font, int width, int height);
}
