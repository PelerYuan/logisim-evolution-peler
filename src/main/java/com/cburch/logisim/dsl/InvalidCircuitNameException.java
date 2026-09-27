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

/** Thrown by {@link Circuits#create(String)}/{@link Circuits#rename(String, String)} for a name
 * that fails the same checks the GUI's "New Circuit"/rename dialogs apply -- empty, a VHDL/Verilog
 * keyword, or rejected by {@link com.cburch.logisim.util.SyntaxChecker} -- checked up front so the
 * mutation never reaches {@link com.cburch.logisim.circuit.CircuitAttributes}'s own validation,
 * which is written for a GUI dialog and pops one up on failure rather than throwing. */
public final class InvalidCircuitNameException extends DslException {
  public InvalidCircuitNameException(String name, String reason) {
    super(
        "invalid circuit name \"" + name + "\": " + reason,
        Map.of("name", name, "reason", reason),
        null);
  }
}
