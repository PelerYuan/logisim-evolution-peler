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

/** Thrown by {@link Libraries#exportPcomp(String, String)} when {@code
 * com.cburch.logisim.pcomp.PcompLibraryExport#export} cannot write the destination zip -- the same
 * failure the library manager window's own "Export..." button would otherwise report through a
 * dialog. */
public final class PcompLibraryExportFailedException extends DslException {
  public PcompLibraryExportFailedException(String name, String cause) {
    super(
        "could not export library \"" + name + "\": " + cause,
        Map.of("name", name, "cause", cause),
        null);
  }
}
