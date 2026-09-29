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

/** Thrown when the circuit cannot be analyzed: no inputs or outputs, too many of either, or a
 * structure the analyzer cannot express (feedback, unsupported components). The GUI reports the
 * same conditions in a dialog. */
public final class AnalysisFailedException extends DslException {
  public AnalysisFailedException(String message, Map<String, Object> details, String suggestion) {
    super(message, details, suggestion);
  }
}
