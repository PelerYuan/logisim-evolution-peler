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

/** Thrown by {@link Simulation} when the background {@code Simulator} thread does not report the
 * requested operation complete within the bounded wait -- defense in depth: nothing observed in
 * {@code SimThread}'s own loop should ever block on the calling thread, so this should never fire
 * in practice, but a script that would otherwise hang forever with no diagnostic is worse than one
 * that fails loudly. */
public final class SimulationTimedOutException extends DslException {
  public SimulationTimedOutException(String operation) {
    super(
        "simulation did not report \"" + operation + "\" complete within the timeout",
        Map.of("operation", operation),
        null);
  }
}
