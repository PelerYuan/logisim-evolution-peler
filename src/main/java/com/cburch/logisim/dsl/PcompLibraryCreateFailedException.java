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

/** Thrown by {@link Libraries#createPcomp(String, String)} when {@code
 * com.cburch.logisim.pcomp.PcompLibraryFile#create} cannot write a fresh manifest at {@code path}
 * -- the directory already holds a manifest, or the directory could not be created. */
public final class PcompLibraryCreateFailedException extends DslException {
  public PcompLibraryCreateFailedException(String path, String cause) {
    super(
        "could not create a component library at \"" + path + "\": " + cause,
        Map.of("path", path, "cause", cause),
        null);
  }
}
