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

/** Thrown by {@link Space#tidyWires()} when {@link Space#isDirty()} is true: tidying discards and
 * rebuilds every {@link com.cburch.logisim.circuit.Wire} in the circuit from what {@link
 * com.cburch.logisim.circuit.WireTidier} finds already committed there, so anything placed or
 * connected this session but not yet in the circuit ({@link Space#commit(String)} not yet called)
 * would simply be dropped rather than tidied -- silent data loss, not a smaller version of the feature
 * (design doc, invariant 4: no silent substitution). */
public final class UncommittedChangesException extends DslException {
  public UncommittedChangesException(int pendingComponentCount) {
    super(
        "cannot tidy wires: " + pendingComponentCount
            + " component(s) placed this session are not committed yet",
        Map.of("pendingComponentCount", pendingComponentCount),
        "call commit(...) first, then tidyWires()");
  }
}
