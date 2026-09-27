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

/** Thrown by {@link History#redo()} when the project's redo log is empty -- mirrors the GUI's own
 * "Redo" menu item, which is simply disabled in this state rather than doing nothing silently. */
public final class NothingToRedoException extends DslException {
  public NothingToRedoException() {
    super("nothing to redo: the redo log is empty", Map.of(), null);
  }
}
