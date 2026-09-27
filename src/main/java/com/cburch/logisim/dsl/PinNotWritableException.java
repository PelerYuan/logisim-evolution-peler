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

/** Thrown by {@link Simulation#writePin(String, long)} when {@code label} names an output pin:
 * an output pin's value is driven by the circuit itself, so nothing outside it may set one --
 * mirrors why the GUI's poke tool refuses the same click. */
public final class PinNotWritableException extends DslException {
  public PinNotWritableException(String label) {
    super(
        "pin \"" + label + "\" is an output pin; its value is driven by the circuit, not by "
            + "writePin()",
        Map.of("label", label),
        "writePin() only works on input pins; read this pin's value with readPin() instead");
  }
}
