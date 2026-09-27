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

/** Thrown by {@link VhdlEntities#importFile(String)} when the file cannot be read, or cannot be
 * parsed as VHDL by {@link com.cburch.logisim.vhdl.base.VhdlParser} -- wraps whatever message the
 * underlying reader/parser would otherwise have surfaced through a dialog (see {@link
 * VhdlEntities}'s class javadoc for why that dialog is avoided rather than shown). */
public final class VhdlImportFailedException extends DslException {
  public VhdlImportFailedException(String path, String cause) {
    super(
        "could not import a VHDL entity from \"" + path + "\": " + cause,
        Map.of("path", path, "cause", cause),
        null);
  }
}
