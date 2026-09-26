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

/** The result of {@link Space#check()} -- a query, not a gate. It is legal to {@link
 * Space#commit(String)} a circuit this report calls incomplete; only a routing failure blocks a
 * commit outright. */
public record CheckReport(
    List<Port> unconnected,
    List<Net> undriven,
    List<Net> multiplyDriven,
    List<WidthConflict> conflicts) {

  public boolean ok() {
    return unconnected.isEmpty() && undriven.isEmpty() && multiplyDriven.isEmpty() && conflicts.isEmpty();
  }

  public record WidthConflict(Port a, Port b) {}
}
