/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import java.util.LinkedHashMap;
import java.util.List;

/** Thrown by {@link Simulation#readPin(String)} and {@link Simulation#writePin(String, long)}
 * when {@code label} does not name a {@code wiring/pin} component in this circuit -- either no
 * component carries that label at all ({@code foundKind} is {@code null}, and {@code nearNames}
 * carries the "did you mean" treatment {@link UnknownCircuitException} also gives a bad name), or
 * a component does carry it but is some other kind ({@code foundKind} names it, and {@code
 * nearNames} is empty since the label itself was not in question). */
public final class UnknownPinException extends DslException {
  public UnknownPinException(String label, List<String> nearNames, String foundKind) {
    super(message(label, nearNames, foundKind), details(label, nearNames, foundKind),
        suggestion(nearNames));
  }

  private static String message(String label, List<String> nearNames, String foundKind) {
    if (foundKind != null) {
      return "\"" + label + "\" names a " + foundKind + " component, not a pin";
    }
    return "no pin labeled \"" + label + "\" in this circuit"
        + (nearNames.isEmpty() ? "" : "; did you mean " + nearNames + "?");
  }

  private static LinkedHashMap<String, Object> details(
      String label, List<String> nearNames, String foundKind) {
    final var details = new LinkedHashMap<String, Object>();
    details.put("label", label);
    details.put("nearNames", nearNames);
    if (foundKind != null) details.put("foundKind", foundKind);
    return details;
  }

  private static String suggestion(List<String> nearNames) {
    return nearNames.isEmpty() ? null : "try \"" + nearNames.get(0) + "\"?";
  }
}
