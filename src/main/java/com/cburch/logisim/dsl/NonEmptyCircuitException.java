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

/** Thrown by {@link Space#synthesize(Synthesis)}: {@code CircuitBuilder.build} starts with a full
 * clear of the destination circuit (design doc, section 十一), so it can only ever generate a brand
 * new circuit's content -- never add to or edit one that already has something in it, or that
 * content would be silently destroyed (design doc, invariant 4: no silent substitution). */
public final class NonEmptyCircuitException extends DslException {
  public NonEmptyCircuitException(String circuitName, int existingComponentCount) {
    super(
        "circuit \"" + circuitName + "\" already has " + existingComponentCount
            + " component(s); synthesize() only generates into an empty circuit",
        Map.of("circuitName", circuitName, "existingComponentCount", existingComponentCount),
        "use a freshly created, empty circuit for synthesize(), or build onto this one with "
            + "place()/connect() instead");
  }
}
