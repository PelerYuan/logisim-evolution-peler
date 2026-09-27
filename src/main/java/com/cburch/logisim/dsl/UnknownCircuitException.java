/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import java.util.List;
import java.util.Map;

/** Thrown by {@link Circuits#remove(String)}, {@link Circuits#rename(String, String)} and
 * {@link Circuits#setMain(String)} for a circuit name this project does not have -- carries the
 * nearest existing names, the same "did you mean" treatment {@link UnknownKindException} gives a
 * bad kind key. */
public final class UnknownCircuitException extends DslException {
  public UnknownCircuitException(String name, List<String> nearNames) {
    super(
        "no circuit named \"" + name + "\" in this project"
            + (nearNames.isEmpty() ? "" : "; did you mean " + nearNames + "?"),
        Map.of("name", name, "nearNames", nearNames),
        nearNames.isEmpty() ? null : "try \"" + nearNames.get(0) + "\"");
  }
}
