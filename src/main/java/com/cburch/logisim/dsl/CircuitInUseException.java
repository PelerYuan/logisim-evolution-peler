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

/** Thrown by {@link Circuits#remove(String)}: either this is the project's only circuit (a
 * {@link com.cburch.logisim.file.LogisimFile} must always have at least one), or another circuit
 * places it as a subcircuit and would be left with a dangling reference -- the same two guards
 * the GUI's "Remove Circuit" menu item already applies before its confirmation dialog. */
public final class CircuitInUseException extends DslException {
  public CircuitInUseException(String name, String reason) {
    super(
        "cannot remove circuit \"" + name + "\": " + reason,
        Map.of("name", name, "reason", reason),
        null);
  }
}
