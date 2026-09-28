/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import com.cburch.logisim.file.LibraryManager;
import com.cburch.logisim.file.LoadedLibrary;
import com.cburch.logisim.file.LogisimFileActions;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.pcomp.PcompCatalog;
import com.cburch.logisim.pcomp.PcompCatalogLibrary;
import com.cburch.logisim.pcomp.PcompComponentLibrary;
import com.cburch.logisim.pcomp.PcompLibraryExport;
import com.cburch.logisim.pcomp.PcompLibraryFile;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.tools.Library;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Project-level library management -- load an external {@code .circ} file, a JAR, or a component
 * library directory (Peler Edition's own {@code pcomp} libraries, see {@code
 * docs/peler-edition/design/pcomp-libraries.md}) as a top-level library of this project, list what
 * is currently loaded, and unload one -- the counterpart to {@link Circuits} for libraries rather
 * than circuits, and like it, independent of whichever circuit {@link Space} is currently open on.
 * {@link #createPcomp(String, String)} and {@link #exportPcomp(String, String)} additionally create
 * a brand-new component-library directory and export one's contents as a shareable zip; everything
 * about a library's own installed components -- publishing a circuit into one, importing, deleting,
 * replacing a version -- lives on {@link Pcomp} instead, the same way {@link Circuits} handles
 * circuits themselves while this class only handles which libraries are loaded.
 *
 * <p>Deliberately does not go through {@link Loader#loadLogisimLibrary(File)}, {@link
 * Loader#loadJarLibrary(File, String)}, or {@link Loader#loadPcompLibrary(File)} -- the same
 * methods the GUI's "Load Library" menu items use -- because on any failure they call {@code
 * Loader.showError}, which pops a real, modal {@code JOptionPane} whenever {@code Main.hasGui()} is
 * true (the ordinary case: this MCP server runs embedded in the same JVM as a normal GUI session).
 * That dialog is shown from whatever thread ran this call, not the event dispatch thread, and nothing
 * is waiting to click it -- exactly the class of EDT hang the fork's own MCP code has been burned by
 * before (see {@code CLAUDE.md}'s "hopping to the event dispatch thread" section). {@code
 * Loader.loadLogisimLibrary(File)} additionally always shows an informational dialog for any
 * embedded load message, with no way to suppress it (unlike {@link Loader#openLogisimFile(File,
 * boolean)}'s {@code showFileMessages} flag).
 *
 * <p>The fix mirrors the one {@code mcp.McpProjectLifecycleTools} already uses to open an arbitrary
 * project file safely: a private {@link QuietLoader} overrides {@code showError} to throw instead
 * of dialoging and {@code showOptions} to auto-decline, and every load here calls straight into
 * {@link LibraryManager}'s public loading methods (which do not themselves show the informational
 * dialog) with a fresh, throwaway {@code QuietLoader} rather than this project's own interactive
 * one. A library that itself references further, missing libraries by a relative path can still
 * reach {@code Loader.getFileFor}/{@code getDirectoryFor}'s own unconditional {@code JFileChooser}
 * prompt -- an existing gap {@code QuietLoader}'s original use in {@code openProject} does not close
 * either, not one this class introduces.
 */
public final class Libraries {
  private final Project proj;

  private Libraries(Project proj) {
    this.proj = proj;
  }

  public static Libraries of(Space space) {
    return new Libraries(space.project());
  }

  /** Names of every library loaded at the top level of this project, in file order -- not
   * recursive, matching what {@code LogisimFileActions.loadLibraryQuiet}'s own duplicate-name
   * check (and therefore {@link #unload(String)}) treats as the addressable set. */
  public List<String> list() {
    final var names = new ArrayList<String>();
    for (final var lib : proj.getLogisimFile().getLibraries()) names.add(lib.getName());
    return List.copyOf(names);
  }

  /** Loads an external {@code .circ}/{@code .pcirc} file as a library and adds it to this project
   * in one undo-logged action. Returns the name it was loaded under (what {@link #unload(String)}
   * later takes). */
  public String loadCircuit(String path) {
    final var file = new File(path);
    final var quiet = new QuietLoader();
    final Library lib;
    try {
      lib = LibraryManager.instance.loadLogisimLibrary(quiet, file);
    } catch (QuietLoadException e) {
      throw new LibraryLoadFailedException(path, quietMessage(e));
    }
    return register(path, lib);
  }

  /** Loads a {@code ComponentFactory}/{@code Library} class out of an external JAR and adds it to
   * this project in one undo-logged action. {@code className} is the fully-qualified class the JAR
   * exposes (what a human is prompted for when the JAR's manifest has no {@code Library-Class}
   * attribute). Returns the name it was loaded under. */
  public String loadJar(String path, String className) {
    final var file = new File(path);
    final var quiet = new QuietLoader();
    final Library lib;
    try {
      lib = LibraryManager.instance.loadJarLibrary(quiet, file, className);
    } catch (QuietLoadException e) {
      throw new LibraryLoadFailedException(path, quietMessage(e));
    }
    return register(path, lib);
  }

  /** Loads a Peler Edition component-library directory (created via the GUI's "New Library..." or
   * by another project already pointed at it) and adds it to this project in one undo-logged
   * action. Returns the name it was loaded under. */
  public String loadPcomp(String path) {
    final var dir = new File(path);
    final var quiet = new QuietLoader();
    final Library lib;
    try {
      lib = LibraryManager.instance.loadPcompLibrary(quiet, dir);
    } catch (QuietLoadException e) {
      throw new LibraryLoadFailedException(path, quietMessage(e));
    }
    return register(path, lib);
  }

  /** Creates a brand-new, empty Peler Edition component-library directory at {@code path} (writing
   * its manifest with the given display {@code name}) and immediately loads it into this project in
   * one undo-logged action, mirroring {@code ProjectLibraryActions.doNewPcompLibrary}. Returns the
   * name it was loaded under (see {@link com.cburch.logisim.pcomp.PcompComponentLibrary#getName()}
   * -- a stable internal id, not {@code name}). */
  public String createPcomp(String path, String name) {
    final var dir = new File(path);
    try {
      PcompLibraryFile.create(dir, name);
    } catch (IOException e) {
      throw new PcompLibraryCreateFailedException(path, causeOf(e));
    }
    final var quiet = new QuietLoader();
    final Library lib;
    try {
      lib = LibraryManager.instance.loadPcompLibrary(quiet, dir);
    } catch (QuietLoadException e) {
      throw new PcompLibraryCreateFailedException(path, quietMessage(e));
    }
    return register(path, lib);
  }

  /** Exports a loaded component library's whole directory as a {@code .zip} someone else can hand
   * back to {@link #loadPcomp(String)}, mirroring the Component Libraries window's "Export..."
   * button. {@code name} is the default catalog's name ({@link
   * com.cburch.logisim.pcomp.PcompCatalogLibrary#_ID}, "My Components") or another library's own
   * {@code getName()} (from {@link #list()}), not its display name. */
  public void exportPcomp(String name, String destinationZip) {
    final var directory = pcompDirectoryOf(name);
    try {
      PcompLibraryExport.export(directory, new File(destinationZip));
    } catch (IOException e) {
      throw new PcompLibraryExportFailedException(name, causeOf(e));
    }
  }

  private File pcompDirectoryOf(String name) {
    for (final var lib : proj.getLogisimFile().getLibraries()) {
      if (!lib.getName().equals(name)) continue;
      final var base = lib instanceof LoadedLibrary loaded ? loaded.getBase() : lib;
      if (base instanceof PcompCatalogLibrary) return PcompCatalog.directoryFile();
      if (base instanceof PcompComponentLibrary library) return library.getDirectory();
      throw new NotAPcompLibraryException(name);
    }
    throw new UnknownLibraryException(name, nearest(name));
  }

  private static String causeOf(IOException e) {
    return e.getMessage() == null ? e.toString() : e.getMessage();
  }

  /** Removes a top-level library by name, refusing (with a structured reason) if anything in the
   * project still uses it. */
  public void unload(String name) {
    final var file = proj.getLogisimFile();
    final var lib = find(file, name);
    final var message = file.getUnloadLibraryMessage(lib);
    if (message != null) {
      throw new LibraryInUseException(name, message);
    }
    proj.doAction(LogisimFileActions.unloadLibrary(lib));
  }

  private String register(String path, Library lib) {
    if (lib == null) {
      throw new LibraryLoadFailedException(path, "load failed for an unknown reason");
    }
    final com.cburch.logisim.proj.Action action;
    try {
      action = LogisimFileActions.loadLibraryQuiet(lib, proj.getLogisimFile());
    } catch (IllegalArgumentException e) {
      throw new DuplicateLibraryNameException(lib.getName());
    }
    proj.doAction(action);
    return lib.getName();
  }

  private Library find(com.cburch.logisim.file.LogisimFile file, String name) {
    for (final var lib : file.getLibraries()) {
      if (lib.getName().equals(name)) return lib;
    }
    throw new UnknownLibraryException(name, nearest(name));
  }

  private static String quietMessage(QuietLoadException e) {
    final var message = e.getMessage();
    return message == null || message.isBlank() ? "load failed for an unknown reason" : message;
  }

  private List<String> nearest(String name) {
    final var candidates = new ArrayList<>(list());
    candidates.sort((a, b) -> distance(name, a) - distance(name, b));
    final var top = new ArrayList<String>();
    for (final var candidate : candidates) {
      if (distance(name, candidate) <= Math.max(3, name.length() / 2)) top.add(candidate);
      if (top.size() == 3) break;
    }
    return top;
  }

  /** Plain Levenshtein distance -- same purpose and shape as {@code Circuits}'/{@code
   * KindRegistry}'s own copies, kept separate rather than shared since all three are small,
   * private, and belong to unrelated key spaces. */
  private static int distance(String a, String b) {
    final var dp = new int[a.length() + 1][b.length() + 1];
    for (var i = 0; i <= a.length(); i++) dp[i][0] = i;
    for (var j = 0; j <= b.length(); j++) dp[0][j] = j;
    for (var i = 1; i <= a.length(); i++) {
      for (var j = 1; j <= b.length(); j++) {
        final var cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
      }
    }
    return dp[a.length()][b.length()];
  }

  /** See the class javadoc: converts {@code Loader}'s dialog-on-failure behavior into a plain
   * exception a headless caller can catch, exactly like {@code
   * mcp.McpProjectLifecycleTools}'s own private copy for opening a whole project file safely. Kept
   * as its own small copy here rather than shared, since {@code dsl} has no other reason to depend
   * on {@code mcp} (or vice versa) and the override is three lines. */
  private static final class QuietLoader extends Loader {
    QuietLoader() {
      super(null);
    }

    @Override
    public void showError(String description) {
      throw new QuietLoadException(description);
    }

    @Override
    public int showOptions(String message, String title, String[] options, int initialSelection) {
      // JOptionPane.CLOSED_OPTION (from the Swing package this module must never import -- see
      // DslPackageBoundaryTest) spelled as its literal value, -1; never actually reaches a live
      // dialog here.
      return -1;
    }
  }

  private static final class QuietLoadException extends RuntimeException {
    QuietLoadException(String message) {
      super(message);
    }
  }
}
