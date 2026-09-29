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

/** Thrown by {@link Pcomp#delete(String, String, int)} and {@link Pcomp#replace(String, String,
 * int, int)} when {@code libraryName} has no installed component with the given id and version.
 * Unlike a misspelled name, a wrong id/version pair is not something "did you mean" can guess at --
 * {@link Pcomp#list(String)} is the way to find the right one, which the suggestion points at. */
public final class UnknownPcompComponentException extends DslException {
  public UnknownPcompComponentException(String libraryName, String id, int version) {
    super(
        "library \"" + libraryName + "\" has no component with id \"" + id + "\" version "
            + version,
        Map.of("libraryName", libraryName, "id", id, "version", version),
        "call pcomp:list(\"" + libraryName + "\") to see what is installed");
  }
}
