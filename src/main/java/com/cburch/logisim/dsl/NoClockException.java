/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import java.util.Map;

/** Thrown by {@link Simulation#tick(int)} and {@link Simulation#setAutoTicking(boolean)} (true
 * case) when the circuit has no {@link com.cburch.logisim.std.wiring.Clock} component: driving
 * ticks with nothing to receive them would otherwise fall through to {@code Simulator}'s own
 * {@code ensureClocks()}, which pops a modal "choose a clock" dialog -- fine for a human, fatal for
 * an unattended script running on the event dispatch thread (see {@code CLAUDE.md}, "Before hopping
 * to the event dispatch thread"). This is the headless refusal in its place. */
public final class NoClockException extends DslException {
  public NoClockException(String circuitName) {
    super(
        "circuit \"" + circuitName + "\" has no Clock component; ticking has nothing to drive",
        Map.of("circuitName", circuitName),
        "place a wiring/clock component in this circuit before calling tick() or "
            + "setAutoTicking(true)");
  }
}
