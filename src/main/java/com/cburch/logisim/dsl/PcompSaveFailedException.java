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

/** Thrown by {@link Pcomp#saveAsComponent(String, String, String)} when writing or installing the
 * new {@code .pcomp} file fails -- wraps whatever {@link java.io.IOException} {@code PcompWriter}
 * or the target library's own {@code install} raised, the same failure {@code PcompSaveDialog}
 * would otherwise report through a dialog. */
public final class PcompSaveFailedException extends DslException {
  public PcompSaveFailedException(String path, String cause) {
    super(
        "could not save component to \"" + path + "\": " + cause,
        Map.of("path", path, "cause", cause),
        null);
  }
}
