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

/** Thrown by {@link Circuits#create(String)}/{@link Circuits#rename(String, String)} when the
 * requested name is already used by a circuit, or by any tool in this file's own libraries --
 * matching the case-insensitive check the GUI's "New Circuit" dialog already applies. */
public final class DuplicateCircuitNameException extends DslException {
  public DuplicateCircuitNameException(String name) {
    super(
        "circuit name \"" + name + "\" is already in use",
        Map.of("name", name),
        "choose a different name");
  }
}
