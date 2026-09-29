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

/** Thrown when text given as a PLA program is not valid: a malformed row, or rows of differing
 * widths. The GUI's own parser answers a bad row with a modal dialog, which a script cannot
 * dismiss, so the DSL validates first. */
public final class InvalidPlaTableException extends DslException {
  public InvalidPlaTableException(String message, Map<String, Object> details, String suggestion) {
    super(message, details, suggestion);
  }
}
