package com.cdp.codpattern.hardpoint.client;

import com.cdp.codpattern.client.gui.GuiTextHelper;
import com.cdp.codpattern.client.gui.overlay.TdmHudOverlay;
import com.cdp.codpattern.hardpoint.HardpointAddon;
import com.cdp.codpattern.hardpoint.mixin.HardpointOpeningAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import static com.cdp.codpattern.hardpoint.client.HardpointView.*;

@Mod.EventBusSubscriber(modid = HardpointAddon.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class HardpointHud {
    private HardpointHud() {}

    @SubscribeEvent
    public static void register(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("hardpoint", (overlay, gui, partialTick, width, height) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen != null || mc.getOverlay() != null || mc.options.hideGui) return;
            var view = HardpointView.read();
            if (view == null) return;
            String phase = view.state.phaseKey();
            if ("ENDED".equals(phase)) {
                HardpointResult.render(gui, mc, view, width, height, partialTick);
            } else if ("COUNTDOWN".equals(phase) || "WARMUP".equals(phase)) {
                ((HardpointOpeningAccessor) TdmHudOverlay.INSTANCE).hardpoint$renderOpening(gui, mc.font, width, height);
            } else if ("PLAYING".equals(phase)) {
                float scale = Math.min(GuiTextHelper.referenceScale(), Math.min(width / 290f, height / 178f));
                gui.pose().pushPose();
                try {
                    gui.pose().scale(scale, scale, 1);
                    match(gui, mc, view, Math.round(width / scale));
                } finally {
                    gui.pose().popPose();
                }
            }
        });
    }

    private static void match(GuiGraphics g, Minecraft mc, HardpointView view, int width) {
        Font font = mc.font;
        int x = 8, y = 8, w = Mth.clamp(width / 3, 140, 184);
        boolean opening = view.current == null && view.next != null;
        boolean preview = view.current != null && view.next != null;
        int bottom = y + (preview ? 79 : 63);
        g.fill(x, y, x + w, bottom, 0xDE1A1624);
        g.fill(x, y, x + w, y + 1, LILAC);
        String goal = tr("goal", view.number("scoreLimit", 250));
        g.drawString(font, goal, x + 5, y + 5, MUTED, false);
        String timer = tr("match_clock", clock(seconds(view.state.remainingTimeTicks())));
        right(g, font, timer, x + w - 5, y + 5, INK);
        int half = (w - 14) / 2;
        score(g, font, view, "red", x + 5, y + 19, half, RED);
        score(g, font, view, "blue", x + 9 + half, y + 19, half, BLUE);

        Hill point = opening ? view.next : view.current;
        String place = point == null ? tr("waiting_point") : point.label();
        String pointClock = opening ? tr("starts", seconds(view.number("opening", 0)))
                : clock(seconds(view.number("pointRemainingTicks", 0)));
        g.drawString(font, GuiTextHelper.ellipsize(font, place, w - 16 - font.width(pointClock)),
                x + 5, y + 37, INK, false);
        right(g, font, pointClock, x + w - 5, y + 37, LILAC);
        String bearing = point == null ? "" : bearing(mc.player.position(), point);
        String status = opening ? tr("first_point") : view.statusLabel();
        g.drawString(font, GuiTextHelper.ellipsize(font, status, w - 15 - font.width(bearing)),
                x + 5, y + 50, opening ? LILAC : view.statusColor(), false);
        right(g, font, bearing, x + w - 5, y + 50, MUTED);
        if (preview) {
            g.fill(x + 5, y + 63, x + w - 5, y + 64, 0x66574C68);
            g.drawString(font, GuiTextHelper.ellipsize(font, tr("next", view.next.label()), w - 10),
                    x + 5, y + 67, LILAC, false);
        }
    }

    private static void score(GuiGraphics g, Font font, HardpointView v, String team, int x, int y, int w, int color) {
        int score = v.number(team + "Score", 0);
        String value = Integer.toString(score);
        g.drawString(font, GuiTextHelper.ellipsize(font, teamName(team), w - font.width(value) - 4), x, y, color, false);
        right(g, font, value, x + w, y, INK);
        g.fill(x, y + 10, x + w, y + 12, 0xFF44404F);
        int fill = Math.round(w * Mth.clamp((float) score / Math.max(1, v.number("scoreLimit", 250)), 0, 1));
        if (fill > 0) g.fill(x, y + 10, x + fill, y + 12, color);
    }

    private static String bearing(Vec3 pos, Hill hill) {
        if (hill.bounds().contains(pos)) return tr("inside");
        Vec3 vector = hill.bounds().getCenter().subtract(pos);
        double angle = Math.atan2(vector.x, -vector.z);
        String cardinal = switch (Math.floorMod((int) Math.round(angle / (Math.PI / 2)), 4)) {
            case 1 -> "east";
            case 2 -> "south";
            case 3 -> "west";
            default -> "north";
        };
        return tr("bearing", tr(cardinal), Math.round(vector.length()));
    }

    private static int seconds(int ticks) { return Mth.ceil(Math.max(0, ticks) / 20f); }
    static void right(GuiGraphics g, Font font, String text, int x, int y, int color) {
        g.drawString(font, text, x - font.width(text), y, color, false);
    }
}
