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

/**
 * Thrown when a {@link Port} obtained before a {@link Comp#set(String, Object)} call is used
 * afterwards. Attributes can change the port count or layout entirely (design doc, 3.8 -- RAM is
 * the standing example), so a stale reference is refused rather than silently resolved against
 * whatever port now happens to sit at that index.
 */
public final class StaleReferenceException extends DslException {
  public StaleReferenceException(Comp comp) {
    super(
        "port was read before " + comp.id() + "'s attributes last changed; re-read it",
        Map.of("component", comp.id()),
        "call ports()/port(...) again after the attribute change");
  }
}
