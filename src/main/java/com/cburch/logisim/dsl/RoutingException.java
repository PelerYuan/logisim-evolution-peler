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
 * Thrown when the router cannot connect a net -- either no obstacle-free Manhattan path exists, or
 * an explicit {@code viaColumn}/{@code viaRow} hint is blocked. A blocked hint is never silently
 * rerouted through a different column (design doc, 六: "提示满足不了必须抛"); this exception is the
 * caller's only way to find out and pick another hint.
 */
public final class RoutingException extends DslException {
  public RoutingException(String netId, int blockedAtRawX, int blockedAtRawY, String reason, String suggestion) {
    super(
        "could not route net " + netId + " at (" + blockedAtRawX + "," + blockedAtRawY + "): " + reason,
        Map.of("netId", netId, "blockedAtRawX", blockedAtRawX, "blockedAtRawY", blockedAtRawY, "reason", reason),
        suggestion);
  }
}
