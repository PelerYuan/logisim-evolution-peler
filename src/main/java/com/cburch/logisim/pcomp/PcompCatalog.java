/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.file.Loader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Peler Edition. The components a user has installed, and where they live.
 *
 * <p>The directory is this edition's own, under the user's home rather than beside the program.
 * Upstream's {@code logisim-defaults} is both -- beside the program, which on an installed build is
 * a directory the user cannot write to, and shared with the official edition, which is the mistake
 * the FPGA workspace already had to be moved out of.
 *
 * <p><b>A component that fails to load is skipped, not thrown.</b> This directory is scanned at
 * startup and holds whatever the user has put in it; one bad file must not be able to stop the
 * program from opening, and the caller is handed the reason so it can be shown somewhere useful
 * rather than swallowed.
 */
public final class PcompCatalog {
  private PcompCatalog() {}

  /** Where components are kept unless the user says otherwise. */
  public static String defaultDirectory() {
    return System.getProperty("user.home") + File.separator + ".logisim-peler" + File.separator
        + "components";
  }

  public static File defaultDirectoryFile() {
    return new File(defaultDirectory());
  }

  /**
   * Where {@link #installed} looks.
   *
   * <p>A field rather than a call so a test can point it at a temporary folder. Without that the
   * catalog tests would read whatever the person running them happens to have installed, and would
   * pass or fail depending on the machine -- which for a registry shared by the whole process is
   * exactly the kind of thing that only shows up on somebody else's.
   */
  private static File directory = defaultDirectoryFile();

  /** The folder components are read from and written to. */
  public static synchronized File directoryFile() {
    return directory;
  }

  /** Points the catalog at another folder and forgets what it had. For tests. */
  static synchronized void useDirectory(File value) {
    directory = value == null ? defaultDirectoryFile() : value;
    reload();
  }

  private static List<PcompLibrary> installed;
  private static Map<String, String> lastProblems = Map.of();
  private static boolean scanning;

  /**
   * The installed components, scanned once and then remembered.
   *
   * <p>Shared by every open project rather than scanned per project. That follows what a library
   * file already does -- {@code LibraryManager} hands the same {@code LoadedLibrary} to every
   * project that references it -- and it matters more here, because the toolbox entry is in the
   * new-project template, so a per-project scan would run on every window.
   *
   * <p>The re-entrancy guard is not defensive coding. Reading a component file needs a {@link
   * Loader}, every {@code Loader} builds a {@code Builtin}, and {@code Builtin} now holds {@link
   * PcompCatalogLibrary} -- so a component whose file names this library would re-enter the scan
   * that is loading it. Answering empty while a scan is in progress ends that, at the price of a
   * component built out of other components not seeing them; nothing writes such a file yet.
   */
  public static synchronized List<PcompLibrary> installed() {
    if (installed != null) return installed;
    if (scanning) return List.of();
    scanning = true;
    try {
      final var problems = new LinkedHashMap<String, String>();
      final var found = scan(directory, new Loader(null),
          (file, why) -> problems.put(file.getName(), why));
      installed = Collections.unmodifiableList(found);
      lastProblems = Collections.unmodifiableMap(problems);
    } finally {
      scanning = false;
    }
    return installed;
  }

  /**
   * Adds one component to what is already loaded, without re-reading the rest.
   *
   * <p>Appending rather than rescanning is the point. A rescan would replace every {@link
   * PcompLibrary}, and with it every {@code SubcircuitFactory} -- while an open project still holds
   * components built from the old ones. {@code XmlWriter.findLibrary} asks each library whether it
   * contains a component's factory, so those placed components would belong to no library any
   * more and the project would refuse to save.
   *
   * @return the component that was added
   * @throws IOException if the file is not a component this program can load
   */
  public static synchronized PcompLibrary install(File file, Loader loader) throws IOException {
    final var component = PcompLibrary.load(file, loader);
    final var next = new ArrayList<>(installed());
    next.removeIf(other -> other.getSource().equals(component.getSource()));
    next.add(component);
    next.sort((a, b) -> a.getSource().getName().compareToIgnoreCase(b.getSource().getName()));
    installed = Collections.unmodifiableList(next);
    return component;
  }

  /**
   * Removes one component from the catalog and deletes the file behind it.
   *
   * <p>Deleting the file is the point of the action, so a failure to delete is a failure of the
   * action -- the entry stays in the catalog rather than disappearing from a list while the next
   * scan is still going to find it.
   *
   * @throws IOException if the file is still there afterwards
   */
  public static synchronized void uninstall(PcompLibrary component) throws IOException {
    final var file = component.getSource();
    if (file.exists() && !file.delete()) {
      throw new IOException(file.getName() + " could not be deleted");
    }
    final var next = new ArrayList<>(installed());
    next.remove(component);
    installed = Collections.unmodifiableList(next);
  }

  /**
   * The installed component that {@code circuit} belongs to, or null if it belongs to none.
   *
   * <p>A linear walk rather than an index. The list is short -- it is what one user has installed
   * by hand -- and an index would have to be kept in step with {@link #install} and {@link
   * #uninstall} for no measurable gain on a question only ever asked in answer to a click.
   */
  public static synchronized PcompLibrary componentOf(Circuit circuit) {
    if (circuit == null) return null;
    for (final var component : installed()) {
      if (component.ownsCircuit(circuit)) return component;
    }
    return null;
  }

  /** The installed component that {@code factory} places, or null if it places something else. */
  public static PcompLibrary componentOf(ComponentFactory factory) {
    return factory instanceof SubcircuitFactory sub ? componentOf(sub.getSubcircuit()) : null;
  }

  /** Every installed version of one component id, oldest first. */
  public static synchronized List<PcompLibrary> versionsOf(String id) {
    return installed().stream()
        .filter(component -> component.getMetadata().id().equals(id))
        .sorted((a, b) -> a.getMetadata().version() - b.getMetadata().version())
        .toList();
  }

  /** Forgets the scan, so the next {@link #installed} call reads the directory again. */
  public static synchronized void reload() {
    installed = null;
    lastProblems = Map.of();
  }

  /** File name to reason, for the components the last scan could not load. */
  public static synchronized Map<String, String> problemsFromLastScan() {
    return lastProblems;
  }

  /**
   * Loads every component in a directory.
   *
   * @param onProblem told the file and the reason for each one that could not be loaded
   * @return the ones that loaded, in file-name order so the toolbox does not reshuffle itself
   *     between runs
   */
  public static List<PcompLibrary> scan(File directory, Loader loader, BiConsumer<File, String> onProblem) {
    final var found = new ArrayList<PcompLibrary>();
    if (directory == null || !directory.isDirectory()) return found;
    final var files = directory.listFiles(PcompCatalog::looksLikeAComponent);
    if (files == null) return found;
    Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
    for (final var file : files) {
      try {
        found.add(PcompLibrary.load(file, loader));
      } catch (Exception e) {
        // Deliberately broad. A component file is user-supplied and reaches a whole project reader;
        // anything it throws is a reason to skip that one file, never to fail the scan.
        if (onProblem != null) {
          onProblem.accept(file, e.getMessage() == null ? e.toString() : e.getMessage());
        }
      }
    }
    return found;
  }

  private static boolean looksLikeAComponent(File file) {
    return file.isFile() && PcompFile.isPcompFile(file);
  }
}
