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

/** Thrown by {@link VhdlEntities#create(String)}/{@link VhdlEntities#importFile(String)}/{@link
 * VhdlEntities#rename(String, String)} for a name that fails the same checks {@link
 * com.cburch.logisim.vhdl.base.VhdlContent#labelVHDLInvalidNotify} applies -- empty, not a valid
 * VHDL identifier, or a reserved keyword -- checked up front so the mutation never reaches that
 * method, which is written for a GUI dialog and pops one up on failure rather than throwing. */
public final class InvalidVhdlNameException extends DslException {
  public InvalidVhdlNameException(String name, String reason) {
    super(
        "invalid VHDL entity name \"" + name + "\": " + reason,
        Map.of("name", name == null ? "" : name, "reason", reason),
        null);
  }
}
