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

/** Thrown for an appearance edit that cannot be made: a bad option, a malformed polygon, or an
 * attempt to delete a port or the anchor (which follow the circuit's pins, not the drawing). */
public final class InvalidAppearanceEditException extends DslException {
  public InvalidAppearanceEditException(
      String message, Map<String, Object> details, String suggestion) {
    super(message, details, suggestion);
  }
}
