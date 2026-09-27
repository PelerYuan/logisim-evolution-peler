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

/** Thrown by {@link Libraries#loadCircuit(String)}, {@link Libraries#loadJar(String, String)}, and
 * {@link Libraries#loadPcomp(String)} when the file or directory cannot be read as a library --
 * wraps whatever message the underlying loader would otherwise have shown in a dialog (see {@link
 * Libraries}'s class javadoc for why that dialog is intercepted rather than shown). */
public final class LibraryLoadFailedException extends DslException {
  public LibraryLoadFailedException(String path, String cause) {
    super(
        "could not load a library from \"" + path + "\": " + cause,
        Map.of("path", path, "cause", cause),
        null);
  }
}
