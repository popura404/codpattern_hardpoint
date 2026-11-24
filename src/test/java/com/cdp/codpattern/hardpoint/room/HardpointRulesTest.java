package com.cdp.codpattern.hardpoint.room;

import static com.cdp.codpattern.hardpoint.room.HardpointRules.Control.*;

/** Runs without Forge so the full clocks and rotation cycle can be exercised deterministically. */
public final class HardpointRulesTest {
    public static void main(String[] args) {
        scoringAndContest();
        rotateInConfiguredOrder();
        scoreAndTimeoutWinConditions();
        System.out.println("Hardpoint rules passed");
    }

    private static void scoringAndContest() {
        var game = new HardpointRules(3);
        equal(HardpointRules.occupancy(true, true), CONTESTED);
        equal(HardpointRules.occupancy(true, false), RED);
        equal(HardpointRules.occupancy(false, true), BLUE);
        equal(HardpointRules.occupancy(false, false), NEUTRAL);
        tick(game, 19, RED);
        equal(game.redScore(), 0);
        tick(game, 120, CONTESTED);
        equal(game.redScore(), 0);
        equal(game.blueScore(), 0);
        equal(game.matchTicks(), HardpointRules.MATCH_TICKS);
        tick(game, 1, RED);
        equal(game.redScore(), 1);
        tick(game, 39, BLUE);
        equal(game.blueScore(), 1);
        tick(game, 12, NEUTRAL);
        equal(game.matchTicks(), HardpointRules.MATCH_TICKS - 12);
        equal(game.hillTicks(), HardpointRules.HILL_TICKS - 191);
    }

    private static void rotateInConfiguredOrder() {
        var game = new HardpointRules(3);
        for (int hill = 0; hill < 6; hill++) {
            equal(game.index(), hill % 3);
            equal(game.preview(), -1);
            tick(game, 999, CONTESTED);
            equal(game.preview(), -1);
            tick(game, 1, CONTESTED);
            equal(game.preview(), (hill + 1) % 3);
            tick(game, 200, CONTESTED);
            equal(game.hillTicks(), 1200);
            equal(game.control(), NEUTRAL);
        }
        equal(game.matchTicks(), HardpointRules.MATCH_TICKS);
    }

    private static void scoreAndTimeoutWinConditions() {
        var game = new HardpointRules(2);
        tick(game, 4999, RED);
        equal(game.redScore(), 249);
        equal(game.finished(), false);
        game.tick(RED);
        equal(game.redScore(), 250);
        equal(game.winner(), "red");
        equal(game.reason(), "score");
        game.tick(BLUE);
        equal(game.blueScore(), 0);
        var timeout = new HardpointRules(2);
        tick(timeout, 20, BLUE);
        tick(timeout, HardpointRules.MATCH_TICKS, NEUTRAL);
        equal(timeout.winner(), "blue");
        equal(timeout.reason(), "time");
        var tie = new HardpointRules(2);
        tick(tie, HardpointRules.MATCH_TICKS, NEUTRAL);
        equal(tie.winner(), "draw");
        var forfeit = new HardpointRules(2);
        forfeit.forfeit(false);
        equal(forfeit.winner(), "blue");
        equal(forfeit.reason(), "forfeit");
    }

    private static void tick(HardpointRules rules, int ticks, HardpointRules.Control control) {
        for (int i = 0; i < ticks; i++) rules.tick(control);
    }
    private static void equal(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
