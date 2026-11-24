package com.cdp.codpattern.hardpoint.deploy;

import com.cdp.codpattern.client.gui.GuiTextHelper;
import com.cdp.codpattern.hardpoint.deploy.HardpointEditor.Action;
import com.cdp.codpattern.hardpoint.deploy.HardpointEditor.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import java.util.List;
import static com.cdp.codpattern.hardpoint.deploy.HardpointEditor.text;

final class HardpointDeployScreen extends Screen {
    private static final int INK = 0xFFEEEAF5, LAVENDER = 0xFFD5B9FF, MUTED = 0xFFD2C9DB;
    private HardpointView view;
    private int x, y, inner, heightUsed;
    private EditBox input;
    private boolean waiting, confirm, closing;
    private HardpointDeployScreen(HardpointView view) { super(text("title")); this.view = view; }
    static void receive(HardpointView next, boolean open) {
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof HardpointDeployScreen panel) {
            if (next == null || panel.closing) { mc.setScreen(null); return; }
            panel.waiting = panel.confirm = false;
            panel.view = next;
            panel.input = null;
            panel.rebuildWidgets();
        } else if (next != null && open) mc.setScreen(new HardpointDeployScreen(next));
    }
    private boolean bound() { return !view.mapName().isEmpty(); }
    private boolean zone() { return view.field() == Field.ZONE; }
    private boolean storedZone() { return zone() && view.index() >= 0 && view.index() < view.zones().size(); }

    @Override protected void init() {
        inner = Math.min(318, width - 24);
        heightUsed = bound() ? 210 : 150;
        x = (width - inner) / 2;
        y = Math.max(3, (height - heightUsed) / 2);
        int right = inner - 45;
        button(right, 3, 45, text("close"), this::onClose, true);
        int mapArrows = inner - 42;
        button(mapArrows, 27, 20, Component.literal("‹"), () -> send(Action.PREV_MAP, ""), !view.pending())
                .setTooltip(Tooltip.create(text("previous_map")));
        button(mapArrows + 22, 27, 20, Component.literal("›"), () -> send(Action.NEXT_MAP, ""), !view.pending())
                .setTooltip(Tooltip.create(text("next_map")));
        boolean edit = view.editable();
        if (bound()) {
            button(mapArrows - 67, 27, 64, text("new_map"), () -> send(Action.NEW_MAP, ""), !view.pending());
            var fields = List.of(Field.RED, Field.BLUE, Field.ZONE, Field.EXIT);
            int slot = (inner + 2) / fields.size();
            for (int i = 0; i < fields.size(); i++) {
                Field f = fields.get(i);
                int at = slot * i;
                var label = f.label().copy();
                if (f == Field.RED || f == Field.BLUE) label.append(" " + (f == Field.RED ? view.red() : view.blue()));
                if (f == Field.ZONE) label.append(" " + view.zones().size());
                button(at, 50, i == 3 ? inner - at : slot - 2, label, () -> send(Action.FIELD, f.name()),
                        edit && !view.pending() && f != view.field());
            }
        }
        if (zone()) {
            button(0, 75, 22, Component.literal("‹"), () -> send(Action.PREV_ZONE, ""), edit && !view.pending());
            button(24, 75, 22, Component.literal("›"), () -> send(Action.NEXT_ZONE, ""), edit && !view.pending());
            int narrow = Math.min(50, inner / 6);
            button(inner - narrow * 2 - 73, 75, 69, text("append_zone"), () -> send(Action.ADD_ZONE, ""),
                    edit && storedZone() && !view.pending());
            button(inner - narrow * 2 - 2, 75, narrow, text("earlier"), () -> send(Action.UP, ""),
                    edit && storedZone() && view.index() > 0 && !view.pending());
            button(inner - narrow, 75, narrow, text("later"), () -> send(Action.DOWN, ""),
                    edit && storedZone() && view.index() + 1 < view.zones().size() && !view.pending());
        }
        if (!bound() || zone()) {
            String preserved = input == null ? view.input() : input.getValue();
            input = new EditBox(font, x + (bound() ? 0 : 1), y + (bound() ? 99 : 27),
                    bound() ? inner : mapArrows - 4, 17, text(bound() ? "zone_name" : "map_name"));
            input.setMaxLength(bound() ? 32 : 100);
            input.setHint(text(bound() ? "zone_name" : "map_name"));
            input.setValue(preserved);
            addRenderableWidget(input);
        }
        int row = bound() ? 153 : 98;
        int slot = (inner + 2) / (bound() ? zone() ? 4 : 3 : 2);
        boolean complete = view.first().isPresent() && (!view.field().paired() || view.second().isPresent());
        if (!bound()) {
            button(0, row, slot - 2, text("create"), () -> send(Action.CREATE, value()), edit && complete);
            button(slot, row, inner - slot, text("discard"), () -> send(Action.DISCARD, ""), view.pending());
        } else {
            if (zone()) button(0, row, slot - 2, text("save"), () -> send(Action.SAVE, value()),
                    edit && (complete || storedZone() && !view.pending()));
            boolean stored = switch (view.field()) {
                case ZONE -> storedZone();
                case RED -> view.red() > 0;
                case BLUE -> view.blue() > 0;
                case EXIT -> view.exit();
                default -> false;
            };
            int at = zone() ? slot : 0;
            button(at, row, slot - 2, text(confirm ? "confirm" : view.field() == Field.RED || view.field() == Field.BLUE
                    ? "remove_last" : "remove"), () -> {
                if (confirm) send(Action.DELETE, "");
                else { confirm = true; rebuildWidgets(); }
            }, edit && !view.pending() && stored);
            button(at + slot, row, slot - 2, text(view.pending() ? "discard" : "refresh"),
                    () -> send(Action.DISCARD, ""), true);
            button(at + slot * 2, row, inner - at - slot * 2, text("validate"), () -> send(Action.VALIDATE, ""), edit);
        }
    }
    private Button button(int dx, int dy, int w, Component label, Runnable run, boolean enabled) {
        var b = addRenderableWidget(Button.builder(label, ignored -> run.run()).bounds(x + dx, y + dy, w, 18).build());
        b.active = enabled && !waiting;
        return b;
    }
    private String value() { return input == null ? "" : input.getValue(); }
    private void send(Action action, String value) {
        if (waiting) return;
        waiting = true;
        for (var w : children()) if (w instanceof Button b) b.active = false;
        HardpointDeployNetwork.send(view.session(), action, value);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() {
        if (waiting) { closing = true; return; }
        if (input != null && !value().equals(view.input())) HardpointDeployNetwork.send(view.session(), Action.DRAFT_NAME, value());
        super.onClose();
    }
    @Override public void render(GuiGraphics g, int mx, int my, float tick) {
        g.fill(0, 0, width, height, 0x440D0B13);
        g.fill(x - 7, y - 4, x + inner + 7, y + heightUsed, 0xF326202E);
        g.fill(x - 7, y - 4, x + inner + 7, y - 2, LAVENDER);
        line(g, 0, 8, inner - 48, title, LAVENDER);
        if (bound()) {
            line(g, 0, 32, inner - 111, Component.literal(view.mapName()), INK);
            if (zone()) {
                Component position = storedZone() ? text("position", view.index() + 1, view.zones().size())
                        : Component.literal("P" + (view.zones().size() + 1));
                line(g, 49, 80, inner - 124 - 2 * Math.min(50, inner / 6), position, INK);
            }
        }
        int labels = bound() ? 123 : 55;
        if (view.field().paired()) line(g, 0, labels, inner, text("picks", coord(view.first()), coord(view.second())), MUTED);
        else {
            int count = switch (view.field()) {
                case EXIT -> view.exit() ? 1 : 0;
                case RED -> view.red();
                default -> view.blue();
            };
            line(g, 0, labels, inner, view.field().label().copy().append("  " + count), MUTED);
        }
        line(g, 0, labels + 14, inner, text(view.field().paired() ? "draw_pair" : "draw_point"), LAVENDER);
        var msg = waiting ? text("working") : view.feedback();
        var lines = font.split(msg, inner);
        int feedbackY = bound() ? 179 : 122;
        for (int i = 0; i < Math.min(2, lines.size()); i++)
            g.drawString(font, lines.get(i), x, y + feedbackY + 10 * i, INK, false);
        super.render(g, mx, my, tick);
    }
    private void line(GuiGraphics g, int dx, int dy, int w, Component label, int color) {
        g.drawString(font, GuiTextHelper.ellipsize(font, label, w), x + dx, y + dy, color, false);
    }
    private static String coord(java.util.Optional<BlockPos> p) { return p.map(BlockPos::toShortString).orElse("—"); }
}
