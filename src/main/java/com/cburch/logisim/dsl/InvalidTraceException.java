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

/** Thrown when a trace asks for a non-positive step or a sample count outside the supported range. */
public final class InvalidTraceException extends DslException {
  public InvalidTraceException(int samples, int halfCycles, int maxSamples) {
    super(
        "a trace needs 1.." + maxSamples + " samples and at least 1 half-period per sample, got "
            + samples + " and " + halfCycles,
        Map.of("samples", samples, "halfCyclesPerSample", halfCycles, "maxSamples", maxSamples),
        "lower the sample count, or trace in several calls");
  }
}
