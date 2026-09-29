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

/** Thrown when a VHDL co-simulation operation cannot run: QuestaSim is not configured, or the
 * co-simulator has not been enabled. Refused up front because the co-simulator's own fallback is
 * a modal dialog nothing running a script could ever dismiss. */
public final class VhdlSimulatorUnavailableException extends DslException {
  public VhdlSimulatorUnavailableException(String reason, String suggestion) {
    super("VHDL co-simulation is unavailable: " + reason, Map.of("reason", reason), suggestion);
  }
}
