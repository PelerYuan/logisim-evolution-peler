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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <p><b>Mutable, unlike most of this program's {@code Library} instances.</b> The management window
 * (see {@code gui/pcomp/PcompLibraryManagerFrame.java}) installs, replaces and removes components in
 * an already-loaded library the same way {@link PcompCatalog} does for the default one -- in place,
 * not by reloading the directory -- for the same reason {@link PcompCatalog#install} gives: an open
 * project may already hold components built from the instances here, and {@code
 * XmlWriter.findLibrary} finds a placed component's library by asking this exact object, so it
 * cannot be swapped out from under a project that references it. One instance is shared by every
 * project that has loaded this directory (see {@code LibraryManager}'s cache), so every method here
 * is synchronized the same way {@code PcompCatalog}'s are.
 */
public final class PcompComponentLibrary extends Library {

  private final File directory;
  private final PcompLibraryManifest manifest;
  private final List<PcompComponent> components;
  private final Map<String, String> problems;

  private PcompComponentLibrary(
      File directory,
      PcompLibraryManifest manifest,
      List<PcompComponent> components,
      Map<String, String> problems) {
    this.directory = directory;
    this.manifest = manifest;
    this.components = components;
    this.problems = problems;
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
    final var problems = new LinkedHashMap<String, String>();
    final var components =
        PcompCatalog.scan(
            directory,
            loader,
            (file, why) -> {
              problems.put(file.getName(), why);
              if (onProblem != null) onProblem.accept(file, why);
            });
    return new PcompComponentLibrary(directory, manifest, new ArrayList<>(components), problems);
  }

  public File getDirectory() {
    return directory;
  }

  public PcompLibraryManifest getManifest() {
    return manifest;
  }

  public synchronized List<PcompComponent> getComponents() {
    return List.copyOf(components);
  }

  /** File name to reason, for the components this library's load could not read. */
  public synchronized Map<String, String> problemsFromLastScan() {
    return Map.copyOf(problems);
  }

  /**
   * Adds one component to this library, or replaces the one already loaded from the same file.
   * Mirrors {@link PcompCatalog#install} exactly, in place of a rescan for the reason given in this
   * class's own javadoc.
   *
   * @return the component that was added
   * @throws IOException if the file is not a component this program can load
   */
  public synchronized PcompComponent install(File file, Loader loader) throws IOException {
    final var component = PcompComponent.load(file, loader);
    components.removeIf(other -> other.getSource().equals(component.getSource()));
    components.add(component);
    components.sort((a, b) -> a.getSource().getName().compareToIgnoreCase(b.getSource().getName()));
    return component;
  }

  /**
   * Removes one component from this library and deletes the file behind it. Mirrors {@link
   * PcompCatalog#uninstall} exactly.
   *
   * @throws IOException if the file is still there afterwards
   */
  public synchronized void uninstall(PcompComponent component) throws IOException {
    final var file = component.getSource();
    if (file.exists() && !file.delete()) {
      throw new IOException(file.getName() + " could not be deleted");
    }
    components.remove(component);
  }

  /** Every version of one component id installed in this library, oldest first. */
  public synchronized List<PcompComponent> versionsOf(String id) {
    return components.stream()
        .filter(component -> component.getMetadata().id().equals(id))
        .sorted((a, b) -> a.getMetadata().version() - b.getMetadata().version())
        .toList();
  }

  /** The component in this library that owns {@code candidate}, or null if none does. */
  public synchronized PcompComponent componentOwning(Circuit candidate) {
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
  public synchronized List<? extends Tool> getTools() {
    return PcompCatalogLibrary.toolsOf(components);
  }
}
