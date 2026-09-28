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

/** Thrown by {@link Pcomp#delete(String, String, int)} when this project still places an instance
 * of the component -- the same {@link com.cburch.logisim.file.PcompReplacement#countUses} check
 * {@code PcompComponentTable}'s own "Delete" button applies before it lets the file disappear out
 * from under a placed instance. */
public final class PcompComponentInUseException extends DslException {
  public PcompComponentInUseException(String id, int version, int uses) {
    super(
        "cannot delete component \"" + id + "\" version " + version + ": placed " + uses
            + " time(s) in this project",
        Map.of("id", id, "version", version, "uses", uses),
        "replace it with another version first, or remove every placed instance");
  }
}
