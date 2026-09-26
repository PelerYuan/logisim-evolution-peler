/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.pcomp;

import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LoadedLibrary;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.pcomp.PcompCatalog;
import com.cburch.logisim.pcomp.PcompCatalogLibrary;
import com.cburch.logisim.pcomp.PcompComponent;
import com.cburch.logisim.pcomp.PcompComponentLibrary;
import com.cburch.logisim.tools.Library;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Peler Edition. One place a component can be installed, read the same way whether it is the
 * always-present default catalog or a library the project has loaded -- see {@code
 * docs/peler-edition/design/pcomp-libraries.md}. {@link PcompComponentTable} and {@link
 * PcompSaveDialog} both only ever need this operation set, which is what lets one table and one
 * save dialog serve every library instead of one copy per kind.
 */
interface PcompLibraryTarget {

  /** The library as it appears in {@code LogisimFile.getLibraries()}, for unload/lookup. */
  Library asLibrary();

  String displayName();

  /** Where a file this target installs would be written. */
  File directory();

  List<PcompComponent> installed();

  List<PcompComponent> versionsOf(String id);

  /** File name to reason, for the components this target's own load could not read. */
  Map<String, String> problems();

  PcompComponent install(File file, Loader loader) throws IOException;

  void uninstall(PcompComponent component) throws IOException;

  /**
   * Every pcomp library currently reachable from {@code file}: the default catalog first (it is
   * always in {@code getLibraries()} -- see {@code default.templ}), then any loaded ones, in {@code
   * getLibraries()} order.
   */
  static List<PcompLibraryTarget> allIn(LogisimFile file) {
    final var targets = new ArrayList<PcompLibraryTarget>();
    for (final var lib : file.getLibraries()) {
      final var base = lib instanceof LoadedLibrary loaded ? loaded.getBase() : lib;
      if (base instanceof PcompCatalogLibrary catalog) {
        targets.add(new OfCatalog(catalog));
      } else if (base instanceof PcompComponentLibrary library) {
        targets.add(new OfLibrary(lib, library));
      }
    }
    return targets;
  }

  /** The default, always-loaded "My Components" catalog -- a static registry, not an instance. */
  record OfCatalog(PcompCatalogLibrary library) implements PcompLibraryTarget {
    @Override
    public Library asLibrary() {
      return library;
    }

    @Override
    public String displayName() {
      return library.getDisplayName();
    }

    @Override
    public File directory() {
      return PcompCatalog.directoryFile();
    }

    @Override
    public List<PcompComponent> installed() {
      return PcompCatalog.installed();
    }

    @Override
    public List<PcompComponent> versionsOf(String id) {
      return PcompCatalog.versionsOf(id);
    }

    @Override
    public Map<String, String> problems() {
      return PcompCatalog.problemsFromLastScan();
    }

    @Override
    public PcompComponent install(File file, Loader loader) throws IOException {
      return PcompCatalog.install(file, loader);
    }

    @Override
    public void uninstall(PcompComponent component) throws IOException {
      PcompCatalog.uninstall(component);
    }
  }

  /** A library the project loaded from a directory -- an instance, possibly shared with others. */
  record OfLibrary(Library asStored, PcompComponentLibrary library) implements PcompLibraryTarget {
    @Override
    public Library asLibrary() {
      return asStored;
    }

    @Override
    public String displayName() {
      return library.getDisplayName();
    }

    @Override
    public File directory() {
      return library.getDirectory();
    }

    @Override
    public List<PcompComponent> installed() {
      return library.getComponents();
    }

    @Override
    public List<PcompComponent> versionsOf(String id) {
      return library.versionsOf(id);
    }

    @Override
    public Map<String, String> problems() {
      return library.problemsFromLastScan();
    }

    @Override
    public PcompComponent install(File file, Loader loader) throws IOException {
      return library.install(file, loader);
    }

    @Override
    public void uninstall(PcompComponent component) throws IOException {
      library.uninstall(component);
    }
  }
}
