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

/** Thrown by {@link Comp#port(int)} / {@link Comp#port(String)} for an out-of-range index or an
 * unknown subcircuit pin label. Always lists the ports that do exist, in the caller's domain
 * language, rather than "component not found" (design doc, 五). */
public final class UnknownPortException extends DslException {
  public UnknownPortException(Comp comp, Object requested, List<Port> available, String suggestion) {
    super(
        "no port " + requested + " on " + comp.id() + "; available: " + describe(available),
        details(comp, requested, available),
        suggestion);
  }

  private static LinkedHashMap<String, Object> details(Comp comp, Object requested, List<Port> available) {
    final var map = new LinkedHashMap<String, Object>();
    map.put("component", comp.id());
    map.put("requested", requested);
    map.put("available", available);
    return map;
  }

  private static String describe(List<Port> available) {
    final var sb = new StringBuilder();
    for (var i = 0; i < available.size(); i++) {
      if (i > 0) sb.append(", ");
      final var p = available.get(i);
      sb.append(p.name().map(n -> n + "(" + p.index() + ")").orElseGet(() -> String.valueOf(p.index())));
    }
    return sb.toString();
  }
}
