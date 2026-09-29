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

/** Thrown by {@link Circuits#setEverywhere(String, String, String)} when the text cannot be read
 * as a value of the attribute it names, on the first component that carries it. Nothing has been
 * changed when this is thrown. */
public final class InvalidAttributeValueException extends DslException {
  public InvalidAttributeValueException(String attribute, String value, String reason) {
    super(
        "\"" + value + "\" is not a valid value for attribute \"" + attribute + "\": " + reason,
        Map.of("attribute", attribute, "value", value, "reason", reason),
        "Use the same text comp:set accepts for this attribute, for example \"true\" or \"8\".");
  }

}
