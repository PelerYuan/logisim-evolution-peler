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

/** Thrown by {@link History#undo()} when the project's undo log is empty -- mirrors the GUI's own
 * "Undo" menu item, which is simply disabled in this state rather than doing nothing silently. */
public final class NothingToUndoException extends DslException {
  public NothingToUndoException() {
    super("nothing to undo: the undo log is empty", Map.of(), null);
  }
}
