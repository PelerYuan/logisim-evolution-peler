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

/** Thrown by {@link Placement#place()} for a bounding-box overlap or a pin landing exactly on a
 * foreign pin (which Logisim would silently wire together -- almost never the intent). */
public final class PlacementException extends DslException {
  public PlacementException(String reason, Comp collidedWith) {
    super(
        reason + " (colliding with " + collidedWith.id() + ")",
        Map.of("reason", reason, "collidedWith", collidedWith.id()),
        null);
  }
}
