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
  public PlacementException(String reason, Comp collidedWith, com.cburch.logisim.data.Bounds attempted) {
    super(
        reason + " (colliding with " + collidedWith.id() + "): the new component covers "
            + cells(attempted) + ", " + collidedWith.id() + " covers " + cells(collidedWith.bounds()),
        Map.of(
            "reason", reason,
            "collidedWith", collidedWith.id(),
            "attempted", cells(attempted),
            "occupied", cells(collidedWith.bounds())),
        "give it its own place, e.g. :rightOf(space:byId(\"" + collidedWith.id() + "\"), 2) or"
            + " :below(space:byId(\"" + collidedWith.id() + "\"), 2), or pick another anchorAt");
  }

  private static String cells(com.cburch.logisim.data.Bounds b) {
    return "cols " + b.getX() / 10 + ".." + (b.getX() + b.getWidth()) / 10
        + " rows " + b.getY() / 10 + ".." + (b.getY() + b.getHeight()) / 10;
  }
}
