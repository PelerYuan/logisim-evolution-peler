/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.file.LoadedLibrary;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.PcompWriter;
import com.cburch.logisim.pcomp.PcompComponentLibrary;
import com.cburch.logisim.pcomp.PcompFile;
import com.cburch.logisim.proj.Project;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the MCP-feature-parity gap closed by {@link Pcomp}: everything a person can do to a
 * custom component through the GUI's three {@code gui/pcomp} dialogs -- publish a circuit,
 * import/delete an installed one, replace every placed instance of one version with another --
 * driven instead through the same headless DSL {@link Circuits}/{@link Libraries} already use.
 * {@link Libraries#createPcomp(String, String)} is exercised here too since every test needs a
 * fresh library to act on.
 */
class PcompAcceptanceTest {

  @TempDir File tempDir;

  /** Unlike the bare {@code LogisimFile.createNew(loader, null)} fixture every other DSL test
   * uses, this one declares "Wiring" as a real top-level library (the way {@code default.templ}
   * always does in a real project) rather than leaving {@code getLibraries()} empty. {@link
   * PcompWriter#write} -- unlike every other DSL operation, which only ever touches in-memory
   * state -- has to serialize the project to find out what {@code Pcomp.saveAsComponent} is
   * publishing, and {@code XmlWriter.findLibrary} cannot resolve a Pin component's factory to any
   * library at all if "Wiring" was never loaded at the top level; not a defect in {@link Pcomp}
   * itself, just something every other bare DSL fixture happens never to exercise. */
  private static final String BLANK_WITH_WIRING =
      """
      <?xml version="1.0" encoding="UTF-8" standalone="no"?>
      <project source="4.1.0" version="1.0">
        <lib desc="#Wiring" name="0"/>
        <main name="Widget"/>
        <circuit name="Widget">
        </circuit>
      </project>
      """;

  private static Project blankProject() {
    try {
      final var loader = new Loader(null);
      final var file =
          LogisimFile.load(
              new ByteArrayInputStream(BLANK_WITH_WIRING.getBytes(StandardCharsets.UTF_8)), loader);
      final var project = new Project(file);
      for (final var circuit : file.getCircuits()) circuit.setProject(project);
      return project;
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Builds an input pin labeled {@code A} and an output pin labeled {@code Y} in the circuit
   * `space` is open on, so it has a valid, nameable port layout to publish. */
  private static void addAPinAndAYPin(Space space) {
    final var pin = Kind.of(space, "wiring/pin");
    space.place(pin).anchorAt(0, 0).with(Attrs.of("type", "input")).place().label("A");
    space.place(pin).anchorAt(0, 10).with(Attrs.of("type", "output")).place().label("Y");
    space.commit("add pins");
  }

  private String newLibrary(Libraries libraries, String dirName, String displayName) {
    return libraries.createPcomp(new File(tempDir, dirName).getAbsolutePath(), displayName);
  }

  // ---- saveAsComponent ----------------------------------------------------

  @Test
  void saveAsComponentInstallsAPlaceableComponent() {
    final var project = blankProject();
    final var space = Space.of(project);
    addAPinAndAYPin(space);
    final var libraries = Libraries.of(space);
    final var libName = newLibrary(libraries, "lib1", "My Gates");

    final var saved = Pcomp.of(space).saveAsComponent(space.circuitName(), "Half Adder", libName);

    assertEquals(1, saved.version());
    assertEquals("Half Adder", saved.name());
    assertEquals(libName, saved.libraryName());
    assertTrue(new File(saved.path()).isFile());

    final var installed = Pcomp.of(space).list(libName);
    assertEquals(1, installed.size());
    assertEquals(saved.mainCircuit(), installed.get(0).mainCircuit());

    // Installed components are placeable exactly like any subcircuit, by mainCircuit name --
    // see KindRegistry.collect's SubcircuitFactory branch and McpScriptTools.EVAL_DESCRIPTION.
    final var kind = Kind.of(space, "circuit/" + saved.mainCircuit());
    space.place(kind).anchorAt(20, 20).place();
    space.commit("place the newly published component");
    assertEquals(1, space.componentsOf(kind).size());
  }

  @Test
  void saveAsComponentRejectsAnUnknownCircuit() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var libName = newLibrary(Libraries.of(space), "lib1", "My Gates");

    assertThrows(UnknownCircuitException.class,
        () -> Pcomp.of(space).saveAsComponent("NoSuchCircuit", "Foo", libName));
  }

  @Test
  void saveAsComponentRejectsAnEmptyName() {
    final var project = blankProject();
    final var space = Space.of(project);
    addAPinAndAYPin(space);
    final var libName = newLibrary(Libraries.of(space), "lib1", "My Gates");

    assertThrows(InvalidComponentNameException.class,
        () -> Pcomp.of(space).saveAsComponent(space.circuitName(), "  ", libName));
  }

  @Test
  void saveAsComponentRejectsACircuitWithNoPins() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var libName = newLibrary(Libraries.of(space), "lib1", "My Gates");

    assertThrows(InvalidComponentLayoutException.class,
        () -> Pcomp.of(space).saveAsComponent(space.circuitName(), "Foo", libName));
  }

  @Test
  void saveAsComponentRejectsADuplicateName() {
    final var project = blankProject();
    final var space = Space.of(project);
    addAPinAndAYPin(space);
    final var libName = newLibrary(Libraries.of(space), "lib1", "My Gates");
    Pcomp.of(space).saveAsComponent(space.circuitName(), "Foo", libName);

    assertThrows(DuplicatePcompNameException.class,
        () -> Pcomp.of(space).saveAsComponent(space.circuitName(), "Foo", libName));
  }

  @Test
  void saveAsComponentRejectsAnUnknownLibrary() {
    final var project = blankProject();
    final var space = Space.of(project);
    addAPinAndAYPin(space);

    assertThrows(UnknownLibraryException.class,
        () -> Pcomp.of(space).saveAsComponent(space.circuitName(), "Foo", "Nope"));
  }

  // ---- importFile -----------------------------------------------------------

  @Test
  void importFileCopiesAComponentIntoAnotherLibrary() {
    final var project = blankProject();
    final var space = Space.of(project);
    addAPinAndAYPin(space);
    final var libraries = Libraries.of(space);
    final var sourceLib = newLibrary(libraries, "source", "Source");
    final var targetLib = newLibrary(libraries, "target", "Target");
    final var saved = Pcomp.of(space).saveAsComponent(space.circuitName(), "Foo", sourceLib);

    final var imported = Pcomp.of(space).importFile(targetLib, saved.path());

    assertEquals(saved.id(), imported.id());
    assertEquals(saved.mainCircuit(), imported.mainCircuit());
    assertTrue(Pcomp.of(space).list(targetLib).stream().anyMatch(i -> i.id().equals(saved.id())));
  }

  @Test
  void importFileRejectsANameAlreadyPresent() {
    final var project = blankProject();
    final var space = Space.of(project);
    addAPinAndAYPin(space);
    final var libraries = Libraries.of(space);
    final var sourceLib = newLibrary(libraries, "source", "Source");
    final var targetLib = newLibrary(libraries, "target", "Target");
    final var saved = Pcomp.of(space).saveAsComponent(space.circuitName(), "Foo", sourceLib);
    Pcomp.of(space).importFile(targetLib, saved.path());

    assertThrows(PcompImportFailedException.class,
        () -> Pcomp.of(space).importFile(targetLib, saved.path()));
  }

  @Test
  void importFileRejectsANonComponentFile() throws Exception {
    final var project = blankProject();
    final var space = Space.of(project);
    final var libName = newLibrary(Libraries.of(space), "lib1", "My Gates");
    final var notAComponent = new File(tempDir, "not-a-component.pcomp");
    java.nio.file.Files.writeString(notAComponent.toPath(), "not xml");

    assertThrows(PcompImportFailedException.class,
        () -> Pcomp.of(space).importFile(libName, notAComponent.getAbsolutePath()));
  }

  // ---- delete -----------------------------------------------------------

  @Test
  void deleteRemovesAnUnusedComponent() {
    final var project = blankProject();
    final var space = Space.of(project);
    addAPinAndAYPin(space);
    final var libName = newLibrary(Libraries.of(space), "lib1", "My Gates");
    final var saved = Pcomp.of(space).saveAsComponent(space.circuitName(), "Foo", libName);

    Pcomp.of(space).delete(libName, saved.id(), saved.version());

    assertTrue(Pcomp.of(space).list(libName).isEmpty());
    assertFalse(new File(saved.path()).exists());
  }

  @Test
  void deleteRejectsAComponentStillPlaced() {
    final var project = blankProject();
    final var space = Space.of(project);
    addAPinAndAYPin(space);
    final var libName = newLibrary(Libraries.of(space), "lib1", "My Gates");
    final var saved = Pcomp.of(space).saveAsComponent(space.circuitName(), "Foo", libName);
    final var kind = Kind.of(space, "circuit/" + saved.mainCircuit());
    space.place(kind).anchorAt(20, 20).place();
    space.commit("place it");

    assertThrows(PcompComponentInUseException.class,
        () -> Pcomp.of(space).delete(libName, saved.id(), saved.version()));
  }

  @Test
  void deleteRejectsAnUnknownComponent() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var libName = newLibrary(Libraries.of(space), "lib1", "My Gates");

    assertThrows(UnknownPcompComponentException.class,
        () -> Pcomp.of(space).delete(libName, "no-such-id", 1));
  }

  // ---- replace -----------------------------------------------------------

  @Test
  void replaceSwapsEveryPlacedInstanceForAnotherVersion() throws Exception {
    final var project = blankProject();
    final var space = Space.of(project);
    addAPinAndAYPin(space);
    final var libraries = Libraries.of(space);
    final var libName = newLibrary(libraries, "lib1", "My Gates");
    final var v1 = Pcomp.of(space).saveAsComponent(space.circuitName(), "Foo", libName);

    // Pcomp.saveAsComponent only ever publishes a first version (see its own javadoc); building a
    // second version of the same component id is otherwise only offered by the interactive
    // PcompSaveDialog re-save flow, so it is built here directly with the lower-level pcomp/file
    // APIs Pcomp.java itself is built on.
    final var loadedLib = (LoadedLibrary) findLibrary(project, libName);
    final var pcompLibrary = (PcompComponentLibrary) loadedLib.getBase();
    final var v1Metadata = PcompFile.read(new File(v1.path()));
    final var v2Metadata = v1Metadata.nextVersion(v1Metadata.layout());
    final var v2File = new File(pcompLibrary.getDirectory(), v2Metadata.mainCircuit() + PcompFile.EXTENSION);
    PcompWriter.write(
        v2File, project.getLogisimFile(), project.getLogisimFile().getCircuit(space.circuitName()),
        v2Metadata, project.getLogisimFile().getLoader());
    pcompLibrary.install(v2File, new Loader(null));

    final var kind = Kind.of(space, "circuit/" + v1.mainCircuit());
    space.place(kind).anchorAt(20, 20).place();
    space.commit("place v1");

    final var result = Pcomp.of(space).replace(libName, v1.id(), 1, 2);

    assertEquals(1, result.uses());
    assertTrue(result.replaced());

    // Pcomp.replace mutates the project directly (proj.doAction), not through this Space's own
    // place/commit bookkeeping, so the pre-existing space's cached component list is now stale --
    // exactly the kind of "possibly-fresh script session" Space.byId's javadoc already talks
    // about. A fresh Space rediscovers current state the way a later eval call would.
    final var after = Space.of(project);
    final var v2Kind = Kind.of(after, "circuit/" + v2Metadata.mainCircuit());
    assertEquals(1, after.componentsOf(v2Kind).size());
    assertEquals(0, after.componentsOf(kind).size());
  }

  @Test
  void replaceWithNoPlacedInstancesChangesNothing() {
    final var project = blankProject();
    final var space = Space.of(project);
    addAPinAndAYPin(space);
    final var libraries = Libraries.of(space);
    final var libName = newLibrary(libraries, "lib1", "My Gates");
    final var v1 = Pcomp.of(space).saveAsComponent(space.circuitName(), "Foo", libName);

    final var result = Pcomp.of(space).replace(libName, v1.id(), 1, 1);

    assertEquals(0, result.uses());
    assertFalse(result.replaced());
  }

  @Test
  void replaceRejectsAnUnknownVersion() {
    final var project = blankProject();
    final var space = Space.of(project);
    addAPinAndAYPin(space);
    final var libraries = Libraries.of(space);
    final var libName = newLibrary(libraries, "lib1", "My Gates");
    final var v1 = Pcomp.of(space).saveAsComponent(space.circuitName(), "Foo", libName);

    assertThrows(UnknownPcompComponentException.class,
        () -> Pcomp.of(space).replace(libName, v1.id(), 1, 2));
  }

  private static com.cburch.logisim.tools.Library findLibrary(Project project, String name) {
    for (final var lib : project.getLogisimFile().getLibraries()) {
      if (lib.getName().equals(name)) return lib;
    }
    throw new IllegalStateException("library not found: " + name);
  }
}
