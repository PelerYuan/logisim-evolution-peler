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

/** Thrown when a memory-contents call names a component that is not a ROM, RAM or dual-port RAM,
 * or a RAM that is not in the circuit yet (a RAM's contents live in simulation state). */
public final class NotAMemoryException extends DslException {
  public NotAMemoryException(String message, String kind, String suggestion) {
    super(message, Map.of("kind", kind), suggestion);
  }
}
