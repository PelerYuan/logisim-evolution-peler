/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import java.util.List;
import java.util.Map;

/** Thrown by {@code commit} when the wires it would draw physically join ports of different nets. */
public final class ShortCircuitException extends DslException {
  public ShortCircuitException(List<String> ports) {
    super(
        "the wires would short " + ports.size() + " ports from different nets together: " + ports,
        Map.of("ports", ports),
        "give one of the nets a viaColumn/viaRow hint, or move a component so the wires stay apart");
  }
}
