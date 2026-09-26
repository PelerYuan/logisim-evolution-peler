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

/**
 * Thrown by {@link Dot#col()}/{@link Dot#row()} when the point sits on Logisim's half-grid (see
 * design doc, 3.9) rather than the whole-dot grid the DSL itself always produces. {@link
 * Dot#rawX()}/{@link Dot#rawY()} remain readable; this is a refusal to silently round, not a claim
 * that the point is unreadable.
 */
public final class OffGridException extends DslException {
  public OffGridException(int rawX, int rawY) {
    super(
        "point (" + rawX + "," + rawY + ") is not aligned to the 10-unit dot grid",
        Map.of("rawX", rawX, "rawY", rawY),
        null);
  }
}
