/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.tools.Tool;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Peler Edition. One loaded component library: a directory of {@code .pcomp} files plus the
 * manifest naming it, presented as one toolbox category.
 *
 * <p>Unlike {@link PcompCatalogLibrary} (the one fixed, always-loaded default library), any number
 * of these can exist, are loaded and unloaded per project through the ordinary {@code
 * LibraryManager}/{@code LogisimFileActions.loadLibrary} machinery, and are shared across projects
 * that reference the same directory the same way a loaded {@code .circ} library already is -- see
 * {@code docs/peler-edition/design/pcomp-libraries.md}.
 *
 * <p>{@code getTools()} flattens this library's own components only, first-name-wins within this
 * library -- the same rule {@link PcompCatalogLibrary} applies, reused via {@link
 * PcompCatalogLibrary#toolsOf}. Two different libraries may offer components that resolve to the
 * same circuit name: {@code XmlWriter.findLibrary} asks each top-level library in turn whether its
 * own tool list contains a given factory, so which specific library an instance belongs to is never
 * ambiguous even when two libraries both happen to have, say, an {@code Adder}.
 */
public final class PcompComponentLibrary extends Library {

  private final File directory;
  private final PcompLibraryManifest manifest;
  private final List<PcompComponent> components;

  private PcompComponentLibrary(
      File directory, PcompLibraryManifest manifest, List<PcompComponent> components) {
    this.directory = directory;
    this.manifest = manifest;
    this.components = components;
  }

  /**
   * Loads a component library from a directory, skipping any {@code .pcomp} file that fails to
   * load the same way {@link PcompCatalog#scan} does.
   *
   * @throws IOException if the directory has no manifest
   */
  public static PcompComponentLibrary load(File directory, Loader loader) throws IOException {
    return load(directory, loader, null);
  }

  /** As {@link #load(File, Loader)}, additionally told the file and reason for each skip. */
  public static PcompComponentLibrary load(
      File directory, Loader loader, BiConsumer<File, String> onProblem) throws IOException {
    final var manifest = PcompLibraryFile.read(directory);
    final var components = PcompCatalog.scan(directory, loader, onProblem);
    return new PcompComponentLibrary(directory, manifest, components);
  }

  public File getDirectory() {
    return directory;
  }

  public PcompLibraryManifest getManifest() {
    return manifest;
  }

  public List<PcompComponent> getComponents() {
    return components;
  }

  /** The component in this library that owns {@code candidate}, or null if none does. */
  public PcompComponent componentOwning(Circuit candidate) {
    for (final var component : components) {
      if (component.ownsCircuit(candidate)) return component;
    }
    return null;
  }

  /**
   * A stable in-memory identifier for this library instance. Never persisted -- a project's
   * reference to a component library goes through its directory path (a {@code pcomplib#}
   * descriptor), never through this name, unlike a builtin library's {@code #Name} reference.
   */
  @Override
  public String getName() {
    return "pcomplib:" + manifest.id();
  }

  @Override
  public String getDisplayName() {
    return manifest.name();
  }

  @Override
  public List<? extends Tool> getTools() {
    return PcompCatalogLibrary.toolsOf(components);
  }
}
