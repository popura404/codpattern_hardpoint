package com.cdp.codpattern.hardpoint.client;

import com.cdp.codpattern.client.gui.GuiTextHelper;
import com.cdp.codpattern.hardpoint.HardpointAddon;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import static com.cdp.codpattern.hardpoint.client.HardpointView.*;

@Mod.EventBusSubscriber(modid = HardpointAddon.MOD_ID, value = Dist.CLIENT)
public final class HardpointMarkers {
    private static final int HALF_SIZE = 16;
    private static final float STRIPE = .12f;
    private static final RenderType FLOOR = RenderType.create("codpattern_hardpoint_floor",
            DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 256, false, false,
            RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(GameRenderer::getPositionColorShader))
                    .createCompositeState(false));

    // Capture with the live camera matrices, then draw the signs in the GUI pass after terrain.
    // Both stages run on the render thread. Each GUI pass consumes only its matching world frame.
    private record Projection(Hill hill, float x, float y) {}
    private record Frame(HardpointView view, Projection current, Projection next) {}
    private static Frame frame;

    private HardpointMarkers() {}

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        boolean floorStage = event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS;
        if (!floorStage && event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (!floorStage) frame = null;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null || mc.options.hideGui
                || !event.getCamera().isInitialized()) return;
        var view = HardpointView.read();
        if (view == null || !mc.level.dimension().location().toString().equals(view.value("mapDimension", ""))) return;
        // The native preparation countdown owns the centre until both teams are released.
        if (!"PLAYING".equals(view.state.phaseKey())) return;
        Hill active = view.current;
        if (floorStage) {
            if (active != null) floor(event, active.bounds(), view.statusColor());
            return;
        }

        var current = active == null || active.bounds().contains(mc.player.position())
                ? null : project(event, active);
        var next = view.next == null ? null : project(event, view.next);
        if (current != null || next != null) frame = new Frame(view, current, next);
    }

    private static Projection project(RenderLevelStageEvent event, Hill hill) {
        var bounds = hill.bounds();
        Vec3 centre = bounds.getCenter();
        var delta = new Vec3(centre.x, Math.min(bounds.minY + 2.6, bounds.maxY + .45), centre.z)
                .subtract(event.getCamera().getPosition());
        double distance = delta.length();
        if (distance < .7) return null;
        // Preserve the sightline while bringing distant hills inside the projection's far plane.
        Vec3 ray = delta.scale(Math.min(1, 12 / distance));
        var clip = new Vector4f((float)ray.x, (float)ray.y, (float)ray.z, 1)
                .mul(event.getPoseStack().last().pose()).mul(event.getProjectionMatrix());
        if (clip.w < .05f) return null;
        float x = clip.x / clip.w, y = clip.y / clip.w;
        return Math.abs(x) <= 1 && Math.abs(y) <= 1 ? new Projection(hill, x, y) : null;
    }

    @Mod.EventBusSubscriber(modid = HardpointAddon.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Overlay {
        private Overlay() {}

        @SubscribeEvent
        public static void register(RegisterGuiOverlaysEvent event) {
            event.registerBelow(VanillaGuiOverlay.CROSSHAIR.id(), "hill_markers", (overlay, gui, tick, width, height) -> {
                var rendered = frame;
                frame = null;
                var mc = Minecraft.getInstance();
                if (rendered == null || mc.screen != null || mc.getOverlay() != null || mc.options.hideGui
                        || rendered.view() != HardpointView.read()) return;
                var view = rendered.view();
                float side = (float)(Math.max(38, mc.getWindow().getHeight() * .066) / mc.getWindow().getGuiScale());
                var current = rendered.current();
                float currentX = 0, currentY = 0;
                if (current != null) {
                    currentX = pixelX(current, width, side);
                    currentY = pixelY(current, height, side);
                    String key = switch (view.status) {
                        case "RED" -> "marker_red";
                        case "BLUE" -> "marker_blue";
                        case "CONTESTED" -> "marker_contested";
                        default -> "marker_capture";
                    };
                    badge(gui, current, currentX, currentY, side, tr(key), view.statusColor(), false, true);
                }
                var next = rendered.next();
                if (next != null) {
                    float x = pixelX(next, width, side), y = pixelY(next, height, side);
                    boolean overlap = current != null && Math.abs(currentX - x) < side * 1.1f
                            && Math.abs(currentY - y) < side * 1.25f;
                    if (overlap) {
                        float step = side * 1.15f;
                        x = currentX + (currentX + step + side * .5f < width ? step : -step);
                    }
                    String cue = view.current == null ? "marker_opening" : "marker_next";
                    badge(gui, next, x, y, side, tr(cue), LILAC, true, !overlap);
                }
            });
        }
        private static float pixelX(Projection p, int width, float side) {
            return Mth.clamp((1 + p.x()) * width / 2, side / 2 + 1, width - side / 2 - 1);
        }
        private static float pixelY(Projection p, int height, float side) {
            return Mth.clamp((1 - p.y()) * height / 2, side / 2 + 1, height - side / 2 - 1);
        }
    }

    private static void badge(GuiGraphics gui, Projection p, float x, float y, float side, String state,
                              int color, boolean next, boolean showName) {
        var mc = Minecraft.getInstance();
        var pose = gui.pose();
        pose.pushPose();
        try {
            pose.translate(x, y, 0);
            float scale = side / (2 * HALF_SIZE);
            pose.scale(scale, scale, 1);
            gui.fill(-16, -16, 16, 16, next ? 0xCA292134 : 0xCE221D2B);
            if (next) {
                for (int row = -16; row <= 15; row += 31) {
                    gui.fill(-16, row, -8, row + 1, color);
                    gui.fill(8, row, 16, row + 1, color);
                }
                for (int col = -16; col <= 15; col += 31) {
                    gui.fill(col, -15, col + 1, -8, color);
                    gui.fill(col, 8, col + 1, 15, color);
                }
            } else {
                gui.renderOutline(-16, -16, 32, 32, color);
            }
            text(gui, "P" + p.hill().index(), -12, 1.7f, INK, 28);
            text(gui, state, 4, 1f, color, 29);
            String name = p.hill().name();
            if (showName && Math.abs(p.x()) < .38f && Math.abs(p.y()) < .45f && !name.isBlank()
                    && !name.equalsIgnoreCase("P" + p.hill().index())) {
                name = GuiTextHelper.ellipsize(mc.font, name, 74);
                int half = (mc.font.width(name) + 1) / 2 + 3;
                gui.fill(-half, 18, half, 30, 0xD4201B28);
                text(gui, name, 20, 1f, INK, 74);
            }
        } finally { pose.popPose(); }
    }

    private static void text(GuiGraphics gui, String value, int top, float scale, int color, int maxWidth) {
        var font = Minecraft.getInstance().font;
        int width = font.width(value);
        if (width == 0) return;
        var pose = gui.pose();
        pose.pushPose();
        try {
            pose.translate(0, top, 0);
            float fit = Math.min(scale, (float)maxWidth / width);
            pose.scale(fit, fit, 1);
            gui.drawString(font, value, -width / 2, 0, color, false);
        } finally { pose.popPose(); }
    }
    /** An upward-facing footprint uses terrain depth, so walls and floors occlude the strips. */
    private static void floor(RenderLevelStageEvent event, AABB box, int color) {
        var mc = Minecraft.getInstance();
        var floorBlock = BlockPos.containing(box.getCenter().x, box.minY, box.getCenter().z);
        double floorY = box.minY;
        if (mc.level.hasChunkAt(floorBlock)) {
            var shape = mc.level.getBlockState(floorBlock).getCollisionShape(mc.level, floorBlock);
            if (!shape.isEmpty()) floorY += shape.max(Direction.Axis.Y);
        }
        var camera = event.getCamera().getPosition();
        var matrix = new Matrix4f(event.getPoseStack().last().pose())
                .translate((float)(box.minX - camera.x), (float)(floorY + .012 - camera.y), (float)(box.minZ - camera.z));
        var buffers = mc.renderBuffers().bufferSource();
        var out = buffers.getBuffer(FLOOR);
        float x = (float)box.getXsize(), z = (float)box.getZsize();
        strip(out, matrix, 0, 0, x, STRIPE, color);
        strip(out, matrix, 0, z - STRIPE, x, z, color);
        strip(out, matrix, 0, STRIPE, STRIPE, z - STRIPE, color);
        strip(out, matrix, x - STRIPE, STRIPE, x, z - STRIPE, color);
        buffers.endBatch(FLOOR);
    }
    private static void strip(VertexConsumer out, Matrix4f pose, float x0, float z0, float x1, float z1, int color) {
        out.vertex(pose, x0, 0, z0).color(color).endVertex();
        out.vertex(pose, x0, 0, z1).color(color).endVertex();
        out.vertex(pose, x1, 0, z1).color(color).endVertex();
        out.vertex(pose, x1, 0, z0).color(color).endVertex();
    }
}

