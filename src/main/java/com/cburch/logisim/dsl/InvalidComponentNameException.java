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

/** Thrown by {@link Pcomp#saveAsComponent(String, String, String)} for a component name that is
 * empty, or that {@link com.cburch.logisim.pcomp.PcompMetadata#circuitNameFor(String, int)} cannot
 * turn into a valid circuit name -- checked up front, exactly what {@code PcompSaveDialog}'s own
 * save button greys itself out for, since there is no dialog here to leave disabled instead. */
public final class InvalidComponentNameException extends DslException {
  public InvalidComponentNameException(String name, String reason) {
    super(
        "invalid component name \"" + name + "\": " + reason,
        Map.of("name", name == null ? "" : name, "reason", reason),
        null);
  }
}
