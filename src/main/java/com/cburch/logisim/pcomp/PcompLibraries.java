/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.file.LoadedLibrary;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Projects;
import com.cburch.logisim.tools.Library;

/**
 * Peler Edition. "Which component owns this circuit", asked across every component library a
 * component might now come from -- not just the one fixed default catalog {@link PcompCatalog}
 * knows how to answer for.
 *
 * <p>Two forms exist because the two callers have different things in hand. {@link PcompLock}'s
 * call sites are mostly type-level code ({@code SubcircuitFactory}, {@code ContinuousPlacement})
 * with no {@code Project} available, so {@link #componentOf(Circuit)} falls back to searching every
 * open project. {@code PcompLowering.plan(LogisimFile)} already has the exact file whose libraries
 * matter, and searching only those -- {@link #componentOf(LogisimFile, Circuit)} -- does not depend
 * on some other open project happening to reference the same library.
 */
public final class PcompLibraries {
  private PcompLibraries() {}

  /**
   * The component owning {@code circuit}, searching every open project's library tree.
   *
   * <p>Falls back to the default catalog even when no project is open to search: {@link
   * #componentOf(LogisimFile, Circuit)} checks it unconditionally for exactly this reason (it is
   * reachable from every file, open or not), and a caller with no open project in hand -- {@link
   * PcompLock}'s callers mostly are not -- should see the same answer for a default-catalog
   * component as one that does have a project to search.
   */
  public static PcompComponent componentOf(Circuit circuit) {
    if (circuit == null) return null;
    for (final var project : Projects.getOpenProjects()) {
      final var found = componentOf(project.getLogisimFile(), circuit);
      if (found != null) return found;
    }
    return PcompCatalog.componentOf(circuit);
  }

  /** The component owning {@code circuit}, searching only {@code file}'s own libraries. */
  public static PcompComponent componentOf(LogisimFile file, Circuit circuit) {
    if (circuit == null) return null;
    // The default catalog is reachable from every file (it is in the new-project template), so it
    // is checked unconditionally rather than only when it turns up in file.getLibraries().
    final var fromDefault = PcompCatalog.componentOf(circuit);
    if (fromDefault != null) return fromDefault;
    if (file == null) return null;
    for (final var lib : file.getLibraries()) {
      final var component = componentIn(unwrap(lib), circuit);
      if (component != null) return component;
    }
    return null;
  }

  private static PcompComponent componentIn(Library lib, Circuit circuit) {
    return lib instanceof PcompComponentLibrary pcompLib ? pcompLib.componentOwning(circuit) : null;
  }

  private static Library unwrap(Library lib) {
    return lib instanceof LoadedLibrary loaded ? loaded.getBase() : lib;
  }
}
