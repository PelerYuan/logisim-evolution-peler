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

/** Thrown when an operation that edits what the circuit already holds is asked to act on a
 * component that is only staged in this session -- {@link Space#move} is the standing example. */
public final class ComponentNotCommittedException extends DslException {
  public ComponentNotCommittedException(Comp comp) {
    super(
        comp.id() + " has not been committed yet, so it has no position in the circuit to move from",
        Map.of("component", comp.id()),
        "call space:commit(...) first, or place it again at the new position");
  }
}
