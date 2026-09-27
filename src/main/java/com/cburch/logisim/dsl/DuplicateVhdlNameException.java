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

/** Thrown by {@link VhdlEntities#create(String)}/{@link VhdlEntities#importFile(String)}/{@link
 * VhdlEntities#rename(String, String)} when the requested name is already used by a circuit or
 * another VHDL entity in this project -- matching what {@link
 * com.cburch.logisim.file.LogisimFile#containsFactory(String)} already treats as taken. */
public final class DuplicateVhdlNameException extends DslException {
  public DuplicateVhdlNameException(String name) {
    super(
        "VHDL entity name \"" + name + "\" is already in use",
        Map.of("name", name),
        "choose a different name");
  }
}
