/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.PcompReplacement;
import com.cburch.logisim.file.PcompWriter;
import com.cburch.logisim.pcomp.PcompCatalog;
import com.cburch.logisim.pcomp.PcompCatalogLibrary;
import com.cburch.logisim.pcomp.PcompComponent;
import com.cburch.logisim.pcomp.PcompComponentLibrary;
import com.cburch.logisim.pcomp.PcompFile;
import com.cburch.logisim.pcomp.PcompMetadata;
import com.cburch.logisim.pcomp.PortLayoutDraft;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.Projects;
import com.cburch.logisim.util.SyntaxChecker;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Peler Edition. Everything a script can do to a custom component that the GUI otherwise only
 * offers through {@code gui/pcomp}'s three dialogs -- publish a circuit as a new component,
 * install one someone else made, delete one, or swap every placed instance of one version for
 * another -- the counterpart to {@link Libraries} for a component library's own contents rather
 * than for the library as a whole. See {@code docs/peler-edition/design/pcomp-libraries.md}.
 *
 * <p>{@link #saveAsComponent(String, String, String)} always derives the port layout
 * automatically ({@link PortLayoutDraft#of(Circuit)}, the same arithmetic behind the GUI's
 * "Arrange for Me" button) rather than offering the interactive drag-to-position step {@code
 * PcompSaveDialog} exists for: a script has no reason to want to drag a port by hand, and the
 * automatic layout is already what that button hands back. A port's name comes from whatever
 * label its pin already carries (set the ordinary way, e.g. {@code pin:setLabel("A")}) -- there is
 * no separate rename step here the way the dialog's port table offers one.
 *
 * <p>Every method resolves {@code libraryName} the same way {@link Libraries#unload(String)}
 * does -- one of this project's own top-level {@code getLibraries()} entries, by exact {@code
 * getName()} -- then requires that entry to actually be a component library ({@link
 * PcompCatalogLibrary}, the one always-loaded default, or a loaded {@link PcompComponentLibrary}),
 * throwing {@link NotAPcompLibraryException} for anything else (an external {@code .circ}/JAR
 * library has no {@code .pcomp} directory to act on).
 */
public final class Pcomp {
  private final Project proj;

  private Pcomp(Project proj) {
    this.proj = proj;
  }

  public static Pcomp of(Space space) {
    return new Pcomp(space.project());
  }

  /** One version of one installed component, as {@link #list(String)} reports it. */
  public record Installed(String id, int version, String name, String mainCircuit, boolean locked) {}

  /** What {@link #saveAsComponent(String, String, String)} and {@link #importFile(String,
   * String)} hand back once a component is on disk and installed. */
  public record Saved(
      String id, int version, String name, String mainCircuit, String path, String libraryName) {}

  /** What {@link #replace(String, String, int, int)} did. */
  public record Replaced(int uses, boolean replaced) {}

  /** Every version of every component installed in one library, oldest-version-first within a
   * component id, in whatever order the library itself lists them. */
  public List<Installed> list(String libraryName) {
    final var target = target(libraryName);
    final var out = new ArrayList<Installed>();
    for (final var component : target.installed()) out.add(installedOf(component));
    return out;
  }

  /**
   * Publishes {@code circuitName} as a new component named {@code componentName} in {@code
   * libraryName}, with an automatically-derived layout (see this class's own javadoc). Always a
   * first version -- unlike {@code PcompSaveDialog}, this has no notion of "this script is
   * editing an existing component's own file", so republishing over an existing one is not
   * offered; publish under a different name, or {@link #delete(String, String, int)} the old one
   * first.
   *
   * @throws UnknownCircuitException if this project has no circuit named {@code circuitName}
   * @throws InvalidComponentNameException if {@code componentName} is empty or cannot become a
   *     valid circuit name (see {@link PcompMetadata#circuitNameFor(String, int)})
   * @throws InvalidComponentLayoutException if the circuit has no pins, or two pins share a label
   * @throws DuplicatePcompNameException if the library already has a different component
   *     answering to the same underlying circuit name
   * @throws PcompSaveFailedException if writing or installing the new file fails
   */
  public Saved saveAsComponent(String circuitName, String componentName, String libraryName) {
    final var circuit = requireCircuit(circuitName);
    final var target = target(libraryName);

    final var draft = PortLayoutDraft.of(circuit);
    final var typed = componentName == null ? "" : componentName.trim();
    draft.setCaption(typed);

    if (typed.isEmpty()) {
      throw new InvalidComponentNameException(componentName, "name must not be empty");
    }
    final var syntaxError = SyntaxChecker.getErrorMessage(PcompMetadata.circuitNameFor(typed, 1));
    if (syntaxError != null) {
      throw new InvalidComponentNameException(typed, syntaxError);
    }
    final var problem = draft.problem();
    if (problem != null) {
      throw new InvalidComponentLayoutException(circuitName, layoutProblemMessage(problem));
    }

    final var metadata = PcompMetadata.firstVersion(typed, draft.layout());
    final var directory = target.directory();
    if (!directory.isDirectory() && !directory.mkdirs()) {
      throw new PcompSaveFailedException(
          directory.getAbsolutePath(), "could not create the library directory");
    }
    refuseIfNameTaken(target, metadata.mainCircuit(), metadata.id(), metadata.displayName(), libraryName);

    final var destination = new File(directory, metadata.mainCircuit() + PcompFile.EXTENSION);
    if (destination.exists()) {
      throw new DuplicatePcompNameException(metadata.displayName(), libraryName);
    }

    try {
      PcompWriter.write(
          destination, proj.getLogisimFile(), circuit, metadata, proj.getLogisimFile().getLoader());
      target.install(destination, new Loader(null));
    } catch (IOException e) {
      throw new PcompSaveFailedException(destination.getAbsolutePath(), causeOf(e));
    }
    rebuildEveryToolbox();
    return new Saved(
        metadata.id(), metadata.version(), metadata.name(), metadata.mainCircuit(),
        destination.getAbsolutePath(), libraryName);
  }

  /**
   * Copies an existing {@code .pcomp} file into {@code libraryName} and installs it, mirroring
   * {@code PcompComponentTable}'s "Import..." button.
   *
   * @throws PcompImportFailedException if {@code path} is not a readable component file, if a
   *     file of the same name already sits in the destination directory, or if the copy/install
   *     itself fails
   * @throws DuplicatePcompNameException if the library already has a different component
   *     answering to the same underlying circuit name
   */
  public Saved importFile(String libraryName, String path) {
    final var target = target(libraryName);
    final var source = new File(path);
    final PcompMetadata arriving;
    try {
      arriving = PcompFile.read(source);
    } catch (IOException e) {
      throw new PcompImportFailedException(path, causeOf(e));
    }
    if (arriving == null) throw new PcompImportFailedException(path, "not a custom component file");
    refuseIfNameTaken(target, arriving.mainCircuit(), arriving.id(), arriving.displayName(), libraryName);

    final var directory = target.directory();
    if (!directory.isDirectory() && !directory.mkdirs()) {
      throw new PcompImportFailedException(directory.getAbsolutePath(), "could not create the library directory");
    }
    final var destination = new File(directory, source.getName());
    if (!destination.equals(source) && destination.exists()) {
      throw new PcompImportFailedException(path, destination.getName() + " already exists in this library");
    }
    final PcompComponent installed;
    try {
      if (!destination.equals(source)) {
        Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
      }
      installed = target.install(destination, new Loader(null));
    } catch (IOException e) {
      throw new PcompImportFailedException(path, causeOf(e));
    }
    rebuildEveryToolbox();
    final var metadata = installed.getMetadata();
    return new Saved(
        metadata.id(), metadata.version(), metadata.name(), metadata.mainCircuit(),
        destination.getAbsolutePath(), libraryName);
  }

  /**
   * Removes one installed version from {@code libraryName} and deletes its file, mirroring {@code
   * PcompComponentTable}'s "Delete" button.
   *
   * @throws UnknownPcompComponentException if the library has no component with that id/version
   * @throws PcompComponentInUseException if this project still places an instance of it
   */
  public void delete(String libraryName, String id, int version) {
    final var target = target(libraryName);
    final var component = require(target, libraryName, id, version);
    final var uses = PcompReplacement.countUses(proj.getLogisimFile(), component);
    if (uses > 0) throw new PcompComponentInUseException(id, version, uses);
    try {
      target.uninstall(component);
    } catch (IOException e) {
      throw new PcompDeleteFailedException(component.getSource().getAbsolutePath(), causeOf(e));
    }
    rebuildEveryToolbox();
  }

  /**
   * Replaces every instance of one installed version of a component, throughout this project,
   * with another installed version of the same id -- mirroring {@code PcompComponentTable}'s
   * "Replace..." button. A single undo-logged action, like {@link Libraries}'/{@link
   * VhdlEntities}'s own immediate actions, not staged like {@link Space#commit(String)}. Wires are
   * never touched -- see {@link PcompReplacement}'s own class javadoc for why.
   *
   * @return how many instances were found and whether they were actually replaced ({@code false}
   *     when there were none, in which case nothing is changed and no undo entry is created)
   * @throws UnknownPcompComponentException if either version is not installed in the library
   */
  public Replaced replace(String libraryName, String id, int fromVersion, int toVersion) {
    final var target = target(libraryName);
    final var from = require(target, libraryName, id, fromVersion);
    final var to = require(target, libraryName, id, toVersion);
    final var uses = PcompReplacement.countUses(proj.getLogisimFile(), from);
    if (uses == 0) return new Replaced(0, false);
    final var action = PcompReplacement.replace(
        proj.getLogisimFile(), from, to, com.cburch.logisim.util.StringUtil.constantGetter("replace component"));
    if (action != null) proj.doAction(action);
    return new Replaced(uses, action != null);
  }

  // ---- shared resolution -----------------------------------------------------------------

  /** One place a component can be installed: the always-loaded default catalog, or a loaded
   * named library -- the {@code dsl} package's own copy of what {@code
   * gui.pcomp.PcompLibraryTarget} abstracts for the GUI, kept separate rather than shared so this
   * package never depends on {@code gui} (see {@link DslPackageBoundaryTest}). */
  private interface Target {
    File directory();

    List<PcompComponent> installed();

    PcompComponent install(File file, Loader loader) throws IOException;

    void uninstall(PcompComponent component) throws IOException;
  }

  private record OfCatalog() implements Target {
    @Override
    public File directory() {
      return PcompCatalog.directoryFile();
    }

    @Override
    public List<PcompComponent> installed() {
      return PcompCatalog.installed();
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

  private record OfLibrary(PcompComponentLibrary library) implements Target {
    @Override
    public File directory() {
      return library.getDirectory();
    }

    @Override
    public List<PcompComponent> installed() {
      return library.getComponents();
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

  private Target target(String libraryName) {
    for (final var lib : proj.getLogisimFile().getLibraries()) {
      if (!lib.getName().equals(libraryName)) continue;
      final var base = lib instanceof com.cburch.logisim.file.LoadedLibrary loaded ? loaded.getBase() : lib;
      if (base instanceof PcompCatalogLibrary) return new OfCatalog();
      if (base instanceof PcompComponentLibrary library) return new OfLibrary(library);
      throw new NotAPcompLibraryException(libraryName);
    }
    throw new UnknownLibraryException(libraryName, nearestLibraryNames(libraryName));
  }

  private PcompComponent require(Target target, String libraryName, String id, int version) {
    for (final var component : target.installed()) {
      final var metadata = component.getMetadata();
      if (metadata.id().equals(id) && metadata.version() == version) return component;
    }
    throw new UnknownPcompComponentException(libraryName, id, version);
  }

  private static void refuseIfNameTaken(
      Target target, String mainCircuit, String id, String displayName, String libraryName) {
    for (final var other : target.installed()) {
      final var otherMetadata = other.getMetadata();
      if (otherMetadata.mainCircuit().equals(mainCircuit) && !otherMetadata.id().equals(id)) {
        throw new DuplicatePcompNameException(displayName, libraryName);
      }
    }
  }

  private Circuit requireCircuit(String name) {
    final var circuit = proj.getLogisimFile().getCircuit(name);
    if (circuit == null) throw new UnknownCircuitException(name, nearestCircuitNames(name));
    return circuit;
  }

  private static Installed installedOf(PcompComponent component) {
    final var metadata = component.getMetadata();
    return new Installed(
        metadata.id(), metadata.version(), metadata.name(), metadata.mainCircuit(), component.isLocked());
  }

  /** Every open project shows every library's toolbox category, so every open project has to be
   * told one changed -- the same rebuild {@code PcompSaveDialog}/{@code PcompComponentTable} run
   * after any install/uninstall, reimplemented here rather than called there so {@code dsl} never
   * depends on {@code gui} (see {@link DslPackageBoundaryTest}). */
  private static void rebuildEveryToolbox() {
    for (final var open : Projects.getOpenProjects()) {
      final var frame = open.getFrame();
      if (frame != null) frame.rebuildToolbox();
    }
  }

  private static String causeOf(IOException e) {
    return e.getMessage() == null ? e.toString() : e.getMessage();
  }

  private static String layoutProblemMessage(PortLayoutDraft.Problem problem) {
    return switch (problem) {
      case NO_PORTS -> "it has no pins to become ports";
      case UNNAMED -> "at least one pin has no label -- every port must be named";
      case DUPLICATE_NAME -> "two pins share the same label";
      case OVERLAP -> "two ports would land on the same coordinate";
    };
  }

  private List<String> nearestCircuitNames(String name) {
    final var names = new ArrayList<String>();
    for (final var circuit : proj.getLogisimFile().getCircuits()) names.add(circuit.getName());
    return nearest(name, names);
  }

  private List<String> nearestLibraryNames(String name) {
    final var names = new ArrayList<String>();
    for (final var lib : proj.getLogisimFile().getLibraries()) names.add(lib.getName());
    return nearest(name, names);
  }

  /** Plain Levenshtein distance -- same purpose and shape as {@code Circuits}'/{@code
   * Libraries}'/{@code KindRegistry}'s own copies, kept separate rather than shared. */
  private static List<String> nearest(String name, List<String> candidates) {
    final var sorted = new ArrayList<>(candidates);
    sorted.sort((a, b) -> distance(name, a) - distance(name, b));
    final var top = new ArrayList<String>();
    for (final var candidate : sorted) {
      if (distance(name, candidate) <= Math.max(3, name.length() / 2)) top.add(candidate);
      if (top.size() == 3) break;
    }
    return top;
  }

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
}
