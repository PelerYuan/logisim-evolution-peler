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

/** Thrown by {@link Pcomp#importFile(String, String)} when {@code path} cannot be read as a
 * component file, or cannot be copied into the destination library's directory -- wraps whatever
 * {@link java.io.IOException} {@code PcompComponentTable}'s own "Import..." button would otherwise
 * report through a dialog. */
public final class PcompImportFailedException extends DslException {
  public PcompImportFailedException(String path, String cause) {
    super(
        "could not import \"" + path + "\": " + cause,
        Map.of("path", path, "cause", cause),
        null);
  }
}
