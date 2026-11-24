package com.cdp.codpattern.hardpoint.client;

import com.cdp.codpattern.app.tdm.model.TdmTeamNames;
import com.cdp.codpattern.app.tdm.service.TdmSettlementTiming;
import com.cdp.codpattern.client.ClientMatchState;
import com.cdp.codpattern.client.gui.GuiTextHelper;
import com.cdp.codpattern.client.gui.overlay.TdmSettlementRenderer;
import com.cdp.codpattern.fpsmatch.room.PlayerInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.Mth;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.cdp.codpattern.hardpoint.client.HardpointView.*;

public final class HardpointResult {
    private static final String COLUMN = "hud.codpattern.tdm.result.column.";
    private static final double SCORES_START = (TdmSettlementTiming.BACKDROP_TICKS + TdmSettlementTiming.RESULT_TICKS) / 20d;
    private static final double BOARD_SECONDS = TdmSettlementTiming.SCOREBOARD_TICKS / 20d;
    private static final int BODY = 0xECE7F1, HEAD = 0xC7C1D0;

    // Set only for the duration of our synchronous call into the native renderer on the render thread.
    private static HardpointView rendering;
    private static HardpointView lastView;
    private static int lastRosterVersion = -1;
    private static List<Row> red = List.of(), blue = List.of();

    private record Row(UUID id, String name, int hold, int kills, int deaths, String time, String kd, String compact) {
        Row(PlayerInfo player, int seconds) {
            this(player.uuid(), player.name(), seconds, player.kills(), player.deaths(), clock(seconds),
                    String.format(Locale.ROOT, "%.2f", player.kills() / (double) Math.max(1, player.deaths())),
                    player.kills() + " / " + player.deaths());
        }
    }
    private static final Comparator<Row> RANK = Comparator.comparingInt(Row::hold).reversed()
            .thenComparing(Comparator.comparingInt(Row::kills).reversed()).thenComparingInt(Row::deaths)
            .thenComparing(Row::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Row::id);

    private HardpointResult() {}

    static void render(GuiGraphics gui, Minecraft mc, HardpointView view, int width, int height, float partialTick) {
        int duration = Math.max(1, view.number("resultTicks", TdmSettlementTiming.TOTAL_TICKS));
        double ticks = duration - view.state.remainingTimeTicks() + Math.max(0, mc.level.getGameTime() - view.received) + partialTick;
        double seconds = Mth.clamp(ticks / duration, 0, 1) * TdmSettlementTiming.TOTAL_TICKS / 20d;
        String winner = view.value("winner", "draw"), team = view.value("team", "");
        String nativeTeam = switch (team) {
            case "red" -> TdmTeamNames.KORTAC;
            case "blue" -> TdmTeamNames.SPECGRU;
            default -> "";
        };
        // The core chooses victory/defeat by comparing its two inputs. A forfeit can award victory to
        // the trailing team. Pass the declared result here; the score table reads the real hill scores below.
        if (nativeTeam.isEmpty() && !"draw".equals(winner)) seconds = Math.max(SCORES_START + .25, seconds);
        rendering = view;
        try {
            TdmSettlementRenderer.render(gui, mc.font, width, height, seconds,
                    "red".equals(winner) ? 1 : 0, "blue".equals(winner) ? 1 : 0, nativeTeam,
                    mc.player.getUUID(), Map.of(), I18n.get("mode.codpattern_hardpoint.name"));
        } finally {
            rendering = null;
        }
    }

    /** Called at the native renderer's table stage. Its other modes keep their own scoreboards. */
    public static boolean drawStandings(GuiGraphics g, Font font, int w, int h, double age, UUID localId) {
        var view = rendering;
        if (view == null) return false;
        float t = (float) Mth.clamp(age / .25, 0, 1);
        float alpha = t * t * (3 - 2 * t);
        if (alpha < .016f) return true;
        if (lastView != view || lastRosterVersion != ClientMatchState.rosterVersion()) {
            var roster = ClientMatchState.teamPlayersSnapshot();
            red = ranked(roster.getOrDefault("red", List.of()), view);
            blue = ranked(roster.getOrDefault("blue", List.of()), view);
            lastView = view;
            lastRosterVersion = ClientMatchState.rosterVersion();
        }

        boolean homeBlue = "blue".equals(view.value("team", ""));
        String home = homeBlue ? "blue" : "red", away = homeBlue ? "red" : "blue";
        int homeColor = homeBlue ? BLUE : RED, awayColor = homeBlue ? RED : BLUE;
        boolean affiliated = home.equals(view.value("team", ""));
        String left = Integer.toString(view.number(home + "Score", 0)), right = Integer.toString(view.number(away + "Score", 0));
        int margin = w < 315 ? 6 : Math.round(Mth.clamp(w * .042f, 10, 42));
        int report = Math.min(540, w - 2 * margin), gap = w < 315 ? 3 : Math.round(Mth.clamp(w * .021f, 5, 18));
        int boardW = (report - gap) / 2, boardX = (w - report) / 2;
        boolean shortScreen = h <= 290;
        float scoreH = shortScreen ? 26 : Mth.clamp(Math.min(w * .107f, h * .18f), 28, 83);
        float scale = Math.min(scoreH / 8f, boardW * .61f / Math.max(font.width(left), font.width(right)));
        int numberH = Math.round(scale * 8);
        int scoreGap = shortScreen ? 6 : Math.round(Mth.clamp(h * .048f, 9, 31));
        int headerH = shortScreen ? 12 : Math.round(Mth.clamp(h * .038f, 12, 20));
        int topMargin = Math.max(21, Math.round(h * .085f)), bottomMargin = Math.max(20, h / 24);
        int available = Math.max(1, h - topMargin - bottomMargin - numberH - scoreGap - headerH);
        int rows = Math.max(1, Math.max(red.size(), blue.size()));
        int preferredRow = shortScreen ? 13 : Math.round(Mth.clamp(h * .046f, 14, 25));
        int rowH = Mth.clamp(available / rows, font.lineHeight + 4, preferredRow);
        int capacity = Math.max(1, available / rowH), pages = (rows + capacity - 1) / capacity;
        int page = Math.min(pages - 1, (int) (age * pages / BOARD_SECONDS));
        int visible = Math.min(rows, capacity);
        int block = numberH + scoreGap + headerH + visible * rowH;
        int top = topMargin + (h - topMargin - bottomMargin - block) / 2;

        int mid = w / 2, separation = Math.round(Mth.clamp(report * .025f, 6, 15));
        float leftX = mid - separation - font.width(left) * scale, rightX = mid + separation;
        text(g, font, left, leftX, top, scale, homeColor, alpha);
        text(g, font, right, rightX, top, scale, awayColor, alpha);
        int dot = Math.max(2, numberH / 18);
        for (int i = 1; i <= 2; i++) {
            int y = top + numberH * i / 3;
            g.fill(mid - dot / 2, y, mid - dot / 2 + dot, y + dot, color(MUTED, alpha));
        }
        faction(g, font, home, true, boardX, leftX - gap, top + numberH / 2f, w, homeColor, alpha);
        faction(g, font, away, false, rightX + font.width(right) * scale + gap, boardX + report,
                top + numberH / 2f, w, awayColor, alpha);
        int boardY = top + numberH + scoreGap;
        board(g, font, boardX, boardY, boardW, headerH, rowH, homeBlue ? blue : red, page * capacity, visible,
                affiliated ? "allies" : "players", localId, homeColor, alpha);
        board(g, font, boardX + boardW + gap, boardY, boardW, headerH, rowH, homeBlue ? red : blue, page * capacity,
                visible, affiliated ? "opponents" : "players", localId, awayColor, alpha);
        int footerY = h - Math.max(12, bottomMargin / 2 + 3);
        String reason = endReason(view);
        if (!affiliated && !"draw".equals(view.value("winner", "draw"))) {
            reason = tr("team_wins", teamName(view.value("winner", "red"))) + "   " + reason;
        }
        text(g, font, reason, boardX, footerY, 1, HEAD, alpha);
        if (pages > 1) {
            String pageLabel = (page + 1) + " / " + pages;
            text(g, font, pageLabel, boardX + report - font.width(pageLabel), footerY, 1, HEAD, alpha);
        }
        return true;
    }

    private static String endReason(HardpointView v) {
        return switch (v.value("endReason", "")) {
            case "score" -> tr("result_score", v.number("scoreLimit", 250));
            case "time" -> tr("result_clock");
            case "forfeit" -> tr("result_forfeit");
            case "manual" -> tr("result_manual");
            default -> "";
        };
    }

    private static List<Row> ranked(List<PlayerInfo> players, HardpointView view) {
        return players.stream().map(p -> new Row(p, view.number("hold." + p.uuid(), 0))).sorted(RANK).toList();
    }

    private static void faction(GuiGraphics g, Font font, String team, boolean left, float x0, float x1,
                                float midY, int w, int tint, float alpha) {
        String code = team.toUpperCase(Locale.ROOT), label = teamName(team);
        float available = Math.max(1, x1 - x0);
        float size = Math.min(Mth.clamp(w * .022f, 8, 19) / font.lineHeight, available / font.width(label));
        float small = Math.min(Mth.clamp(w * .0105f, 6, 9) / font.lineHeight, available / font.width(code));
        boolean both = !label.equalsIgnoreCase(code);
        float y = midY - (font.lineHeight * size + (both ? font.lineHeight * small + 2 : 0)) / 2;
        if (both) {
            text(g, font, code, left ? x1 - font.width(code) * small : x0, y, small, tint, alpha);
            y += font.lineHeight * small + 2;
        }
        text(g, font, label, left ? x1 - font.width(label) * size : x0, y, size, INK, alpha);
    }

    private static void board(GuiGraphics g, Font font, int x, int y, int w, int headerH, int rowH,
                              List<Row> rows, int first, int visible, String heading, UUID localId, int tint, float alpha) {
        int count = Math.max(0, Math.min(visible, rows.size() - first));
        int end = y + headerH + Math.max(count, rows.isEmpty() ? 1 : 0) * rowH;
        g.fill(x, y, x + w, end, color(0x191820, .91f * alpha));
        g.fill(x, y, x + w, y + 1, color(tint, .75f * alpha));
        int pad = Math.max(3, w / 32), right = x + w - pad;
        boolean compact = w < 153;
        int nameEnd = x + pad + Math.round((w - 2 * pad) * (compact ? .44f : .39f));
        int holdW = Math.max(29, Math.round((w - 2 * pad) * (compact ? .25f : .19f)));
        int holdEnd = nameEnd + holdW, statW = (right - holdEnd) / (compact ? 1 : 3);
        int hy = y + (headerH - font.lineHeight) / 2;
        text(g, font, GuiTextHelper.ellipsize(font, I18n.get(COLUMN + heading), nameEnd - x - pad),
                x + pad, hy, 1, HEAD, alpha);
        right(g, font, tr(compact ? "hold_short" : "hold"), holdEnd, hy, holdW, LILAC, alpha);
        if (compact) {
            right(g, font, I18n.get(COLUMN + "kills_deaths"), right, hy, statW, HEAD, alpha);
        } else {
            right(g, font, I18n.get(COLUMN + "kills"), right - statW * 2, hy, statW, HEAD, alpha);
            right(g, font, I18n.get(COLUMN + "deaths"), right - statW, hy, statW, HEAD, alpha);
            right(g, font, "K/D", right, hy, statW, HEAD, alpha);
        }
        for (int i = 0; i < count; i++) {
            Row row = rows.get(first + i);
            int top = y + headerH + i * rowH, ty = top + (rowH - font.lineHeight) / 2;
            boolean local = row.id.equals(localId);
            if (local) g.fill(x, top, x + w, top + rowH, color(0x43334F, alpha));
            g.fill(x, top, x + w, top + 1, color(0xB7A4BF, .12f * alpha));
            String you = local ? I18n.get(COLUMN + "you") : "";
            String name = GuiTextHelper.ellipsize(font, row.name, nameEnd - x - pad - (local ? font.width(you) + 3 : 0));
            text(g, font, name, x + pad, ty, 1, BODY, alpha);
            if (local) text(g, font, you, x + pad + font.width(name) + 2, ty, 1, LILAC, alpha);
            right(g, font, row.time, holdEnd, ty, holdW, LILAC, alpha);
            if (compact) {
                right(g, font, row.compact, right, ty, statW, BODY, alpha);
            } else {
                right(g, font, Integer.toString(row.kills), right - statW * 2, ty, statW, BODY, alpha);
                right(g, font, Integer.toString(row.deaths), right - statW, ty, statW, BODY, alpha);
                right(g, font, row.kd, right, ty, statW, BODY, alpha);
            }
        }
        if (rows.isEmpty()) text(g, font, tr("no_players"), x + pad, y + headerH + (rowH - font.lineHeight) / 2, 1, HEAD, alpha);
    }

    private static void right(GuiGraphics g, Font font, String value, int right, int y, int width, int rgb, float alpha) {
        float scale = Math.min(1f, (width - 2f) / Math.max(1, font.width(value)));
        text(g, font, value, right - font.width(value) * scale, y, scale, rgb, alpha);
    }

    private static int color(int rgb, float alpha) { return Math.round(255 * alpha) << 24 | rgb & 0xFFFFFF; }
    private static void text(GuiGraphics g, Font font, String value, float x, float y, float scale, int rgb, float alpha) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, value, 0, 0, color(rgb, alpha), false);
        g.pose().popPose();
    }
}
