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

/** Thrown by {@link Libraries#unload(String)}: {@link
 * com.cburch.logisim.file.LogisimFile#getUnloadLibraryMessage(com.cburch.logisim.tools.Library)}
 * already knows every reason a library cannot be safely dropped (a component from it is placed
 * somewhere in the project, or bound to the toolbar or a mouse mapping) -- this just carries that
 * same reason as a structured field instead of a dialog. */
public final class LibraryInUseException extends DslException {
  public LibraryInUseException(String name, String reason) {
    super(
        "cannot unload library \"" + name + "\": " + reason,
        Map.of("name", name, "reason", reason),
        null);
  }
}
