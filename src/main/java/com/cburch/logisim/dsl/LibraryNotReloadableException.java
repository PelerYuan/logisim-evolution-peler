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

/** Thrown by {@link Libraries#reload(String)} for a library that has no file to read again: the
 * built-in libraries and the default "My Components" catalog. */
public final class LibraryNotReloadableException extends DslException {
  public LibraryNotReloadableException(String name, String reason) {
    super(
        "cannot reload library \"" + name + "\": " + reason,
        Map.of("name", name, "reason", reason),
        "Only libraries loaded from a file, a JAR or a component-library directory can be reloaded.");
  }
}
