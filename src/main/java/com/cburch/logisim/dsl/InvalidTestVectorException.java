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

/** Thrown when a test vector cannot be read or does not match the circuit's pins. */
public final class InvalidTestVectorException extends DslException {
  public InvalidTestVectorException(String message, Map<String, Object> details, String suggestion) {
    super(message, details, suggestion);
  }
}
