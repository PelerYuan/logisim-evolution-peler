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

/** Thrown for a memory read/write outside the component's address range, a value too wide for its
 * data bits, or an image that does not parse. */
public final class InvalidMemoryAccessException extends DslException {
  public InvalidMemoryAccessException(String message, Map<String, Object> details, String suggestion) {
    super(message, details, suggestion);
  }
}
