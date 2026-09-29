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

/** Thrown when a script tries to edit the appearance of a custom component's circuit, which the
 * GUI's own appearance editor refuses to open for the same reason (see {@code PcompLock}). */
public final class AppearanceLockedException extends DslException {
  public AppearanceLockedException(String circuit) {
    super(
        "cannot edit the appearance of \"" + circuit + "\": it belongs to a custom component",
        Map.of("circuit", circuit),
        "edit the component's own source project instead, then save a new version of it");
  }
}
