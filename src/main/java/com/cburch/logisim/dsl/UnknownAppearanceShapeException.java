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

/** Thrown when an appearance shape index does not name one of the circuit's custom shapes. */
public final class UnknownAppearanceShapeException extends DslException {
  public UnknownAppearanceShapeException(int index, int count) {
    super(
        "no appearance shape at index " + index + " (this circuit has " + count + ")",
        Map.of("index", index, "count", count),
        "call appearance:list() for the current indices; they shift when a shape is removed");
  }
}
