/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import java.util.List;
import java.util.Map;

/** Thrown by {@link Libraries#unload(String)} when no top-level library in this project has the
 * given name -- mirrors {@link UnknownCircuitException}'s "did you mean" shape, since a typo'd
 * library name is exactly as self-correcting as a typo'd circuit name. */
public final class UnknownLibraryException extends DslException {
  public UnknownLibraryException(String name, List<String> nearNames) {
    super(
        "no library named \"" + name + "\" is loaded in this project",
        Map.of("name", name, "nearNames", nearNames),
        nearNames.isEmpty() ? null : "did you mean \"" + nearNames.get(0) + "\"?");
  }
}
