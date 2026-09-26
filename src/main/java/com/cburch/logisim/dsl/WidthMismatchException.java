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

/** Thrown by {@link Space#connect(Port, Port)} when the two ports carry different bit widths. */
public final class WidthMismatchException extends DslException {
  public WidthMismatchException(Port a, Port b) {
    super(
        a + " is " + a.width() + " bit(s) wide but " + b + " is " + b.width() + " bit(s) wide",
        Map.of("portA", a, "widthA", a.width(), "portB", b, "widthB", b.width()),
        null);
  }
}
