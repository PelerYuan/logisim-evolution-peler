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

/** Thrown when replacement VHDL source for an existing entity is refused: it does not parse, or it
 * declares a different entity name than the one being edited. */
public final class InvalidVhdlSourceException extends DslException {
  public InvalidVhdlSourceException(String entity, String reason) {
    super(
        "cannot use that source for VHDL entity \"" + entity + "\": " + reason,
        Map.of("entity", entity, "reason", reason),
        "fix the source, or use vhdlEntities:rename to change the entity's name");
  }
}
