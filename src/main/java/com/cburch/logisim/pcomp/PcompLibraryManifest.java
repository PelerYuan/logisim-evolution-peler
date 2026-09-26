/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import java.util.UUID;

/**
 * Peler Edition. What a component library directory says about itself.
 *
 * <p>{@code id} is generated once, when the library is created, and never again -- it is the
 * library's stable identity regardless of where the directory is moved to or what the user renames
 * it to. {@code name} is purely a display label and, unlike a {@link PcompMetadata#name}, never
 * appears in any project's persisted reference to this library: a project records a loaded library
 * by the directory's path (see {@code pcomplib#} descriptors in {@code LibraryManager}), not by this
 * name. Renaming a library is therefore always safe and never orphans a reference the way renaming a
 * published component would.
 *
 * @param id stable across renames and directory moves; a UUID string
 * @param name what the user calls the library; shown as the toolbox category title
 */
public record PcompLibraryManifest(String id, String name) {

  public PcompLibraryManifest {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("a library needs an id");
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("a library needs a name");
    }
    id = id.trim();
    name = name.trim();
  }

  /** A brand new library identity, for "New Library...". */
  public static PcompLibraryManifest create(String name) {
    return new PcompLibraryManifest(UUID.randomUUID().toString(), name);
  }
}
