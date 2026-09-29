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

/** Thrown by every {@link Pcomp} method and {@link Libraries#exportPcomp(String, String)} when
 * {@code libraryName} does name a top-level library loaded in this project, but not a component
 * library ({@link com.cburch.logisim.pcomp.PcompCatalogLibrary} or {@link
 * com.cburch.logisim.pcomp.PcompComponentLibrary}) -- an external {@code .circ}/JAR library has no
 * directory of {@code .pcomp} files for any of these operations to act on. */
public final class NotAPcompLibraryException extends DslException {
  public NotAPcompLibraryException(String name) {
    super(
        "library \"" + name + "\" is loaded, but it is not a component library",
        Map.of("name", name),
        null);
  }
}
