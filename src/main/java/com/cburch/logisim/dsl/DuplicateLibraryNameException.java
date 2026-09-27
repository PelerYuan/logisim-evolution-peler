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
 * {@link Libraries#loadPcomp(String)} when the file/directory parses fine but this project already
 * has a top-level library of the same name (case-insensitively) -- {@code
 * LogisimFileActions.loadLibraryQuiet} rejects the load outright rather than shadowing the
 * existing one, matching what the GUI's own "Load Library" menu item would refuse interactively. */
public final class DuplicateLibraryNameException extends DslException {
  public DuplicateLibraryNameException(String name) {
    super(
        "a library named \"" + name + "\" is already loaded in this project",
        Map.of("name", name),
        "unload the existing library first, or use a copy under a different name");
  }
}
