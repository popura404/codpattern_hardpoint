package com.cdp.codpattern.hardpoint.room;

/** Advances exactly once per server tick. The hill and idle match clocks are independent. */
final class HardpointRules {
    static final int TICKS_PER_SECOND = 20;
    static final int SCORE_LIMIT = 250;
    static final int OPENING_TICKS = 5 * TICKS_PER_SECOND;
    static final int HILL_TICKS = 60 * TICKS_PER_SECOND;
    static final int PREVIEW_TICKS = 10 * TICKS_PER_SECOND;
    static final int MATCH_TICKS = 5 * 60 * TICKS_PER_SECOND;
    enum Control { NEUTRAL, RED, BLUE, CONTESTED }

    private final int count;
    private int index;
    private int hillTicks = HILL_TICKS;
    private int matchTicks = MATCH_TICKS;
    private int redTicks;
    private int blueTicks;
    private Control control = Control.NEUTRAL;
    private String winner = "";
    private String reason = "";

    HardpointRules(int count) {
        if (count < 1) throw new IllegalArgumentException("At least one hill is required");
        this.count = count;
    }
    int index() { return index; }
    int preview() { return hillTicks <= PREVIEW_TICKS ? (index + 1) % count : -1; }
    int hillTicks() { return hillTicks; }
    int matchTicks() { return matchTicks; }
    int redScore() { return redTicks / TICKS_PER_SECOND; }
    int blueScore() { return blueTicks / TICKS_PER_SECOND; }
    Control control() { return control; }
    String winner() { return winner; }
    String reason() { return reason; }
    boolean finished() { return !winner.isEmpty(); }

    static Control occupancy(boolean red, boolean blue) {
        if (red && blue) return Control.CONTESTED;
        if (red) return Control.RED;
        return blue ? Control.BLUE : Control.NEUTRAL;
    }

    /** The match has started but the first hill's own clock has not. */
    void tickBeforeOpening() { matchTicks--; }

    void tick(Control occupants) {
        if (finished()) return;
        control = occupants;
        switch (control) {
            case RED -> redTicks++;
            case BLUE -> blueTicks++;
            case NEUTRAL -> matchTicks--;
            case CONTESTED -> { }
        }
        hillTicks--;
        if (redScore() >= SCORE_LIMIT || blueScore() >= SCORE_LIMIT || matchTicks == 0) {
            winner = redScore() == blueScore() ? "draw" : redScore() > blueScore() ? "red" : "blue";
            reason = matchTicks == 0 ? "time" : "score";
        } else if (hillTicks == 0) {
            index = (index + 1) % count;
            hillTicks = HILL_TICKS;
            control = Control.NEUTRAL;
        }
    }

    void forfeit(boolean redRemains) {
        if (finished()) return;
        winner = redRemains ? "red" : "blue";
        reason = "forfeit";
    }
}
