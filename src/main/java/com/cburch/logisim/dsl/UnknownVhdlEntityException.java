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

/** Thrown by {@link VhdlEntities#remove(String)}/{@link VhdlEntities#rename(String, String)} for
 * an entity name this project does not have -- mirrors {@link UnknownCircuitException}'s "did you
 * mean" shape, since a typo'd entity name is exactly as self-correcting as a typo'd circuit
 * name. */
public final class UnknownVhdlEntityException extends DslException {
  public UnknownVhdlEntityException(String name, List<String> nearNames) {
    super(
        "no VHDL entity named \"" + name + "\" in this project"
            + (nearNames.isEmpty() ? "" : "; did you mean " + nearNames + "?"),
        Map.of("name", name, "nearNames", nearNames),
        nearNames.isEmpty() ? null : "try \"" + nearNames.get(0) + "\"");
  }
}
