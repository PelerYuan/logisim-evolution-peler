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

/** Thrown by {@link Pcomp#saveAsComponent(String, String, String)} and {@link
 * Pcomp#importFile(String, String)} when the destination library already has a different
 * component answering to the same underlying circuit name -- the same collision {@code
 * PcompSaveDialog}'s destination check and {@code PcompComponentTable}'s import both refuse, since
 * a library is one flat toolbox category and two components cannot share the name a project file
 * would use to tell them apart. */
public final class DuplicatePcompNameException extends DslException {
  public DuplicatePcompNameException(String name, String libraryName) {
    super(
        "a different component named \"" + name + "\" already exists in library \"" + libraryName + "\"",
        Map.of("name", name, "libraryName", libraryName),
        "publish under a different name, or delete the existing one first");
  }
}
