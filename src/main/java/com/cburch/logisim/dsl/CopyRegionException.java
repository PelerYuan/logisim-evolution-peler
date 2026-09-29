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

/** Thrown by {@link Space#copyRegion} when the copy cannot be made as asked. {@code reason} is one
 * of {@code empty} (nothing lies fully inside the region), {@code off-canvas} (a copy would land at
 * a negative coordinate), {@code conflict} (a copied component would sit on a pin or exactly on
 * another component in the target) or {@code circular} (a copied subcircuit would place a circuit
 * inside itself). Nothing has been changed when this is thrown. */
public final class CopyRegionException extends DslException {
  public CopyRegionException(String reason, String message, String suggestion) {
    super("cannot copy region: " + message, Map.of("reason", reason), suggestion);
  }
}
