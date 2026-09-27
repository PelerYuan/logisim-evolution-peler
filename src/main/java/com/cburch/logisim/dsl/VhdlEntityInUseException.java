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

/** Thrown by {@link VhdlEntities#remove(String)} when another circuit in this project still
 * places the entity as a component and would be left with a dangling reference -- the same guard
 * {@link com.cburch.logisim.proj.Dependencies#canRemove(com.cburch.logisim.vhdl.base.VhdlContent)}
 * already applies before the GUI's "Remove" menu item shows its confirmation dialog. */
public final class VhdlEntityInUseException extends DslException {
  public VhdlEntityInUseException(String name, String reason) {
    super(
        "cannot remove VHDL entity \"" + name + "\": " + reason,
        Map.of("name", name, "reason", reason),
        null);
  }
}
