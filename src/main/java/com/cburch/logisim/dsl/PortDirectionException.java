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

/** Thrown by {@link Space#connect(Port, Port)} when both ports are outputs -- nothing can drive
 * the resulting net without two components fighting over it. */
public final class PortDirectionException extends DslException {
  public PortDirectionException(Port a, Port b) {
    super(
        a + " and " + b + " are both outputs; a net needs at most one driver",
        Map.of("portA", a, "portB", b, "dirA", a.dir(), "dirB", b.dir()),
        null);
  }
}
