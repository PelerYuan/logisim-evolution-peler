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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.proj.Project;
import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Acceptance tests for {@link Space#commit(String)}'s library auto-load, the fix behind the
 * reported "XOR Gate component not found" bug: {@link com.cburch.logisim.dsl.internal.KindRegistry}
 * deliberately resolves a built-in kind through the loader's shared builtin tree without checking
 * whether this project's own file has that component's library loaded (see its own javadoc), so a
 * project whose file never loaded, say, {@code Gates} could still place an AND gate through the DSL
 * -- and then silently lose it the moment the project was saved, since
 * {@link com.cburch.logisim.file.XmlWriter} only attributes a component to one of the project's own
 * loaded libraries. These tests build the worst case directly: a bare {@link
 * LogisimFile#createNew} with zero libraries, exactly like a pre-fix MCP-created blank project.
 */
class SpaceLoadsMissingLibrariesAcceptanceTest {

  @TempDir File tempDir;

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  @Test
  void committingAPlacedGateLoadsItsLibraryIntoTheProjectFile() {
    final var project = blankProject();
    assertTrue(project.getLogisimFile().getLibraries().isEmpty(),
        "a bare LogisimFile.createNew should start with no libraries of its own");

    final var space = Space.of(project);
    final var andGate = Kind.of(space, "gates/and_gate");
    space.place(andGate).anchorAt(0, 0).place();
    space.commit("place an and gate");

    final var libraryNames = project.getLogisimFile().getLibraries().stream()
        .map(lib -> lib.getName())
        .toList();
    assertTrue(libraryNames.contains("Gates"),
        "committing a Gates-family component should load the Gates library, got " + libraryNames);
  }

  @Test
  void placedComponentSurvivesBeingSavedAndReopenedEvenFromALibraryLessProject() throws Exception {
    final var project = blankProject();
    final var loader = project.getLogisimFile().getLoader();
    final var space = Space.of(project);
    final var xorGate = Kind.of(space, "gates/xor_gate");
    space.place(xorGate).anchorAt(0, 0).place();
    space.commit("place an xor gate");

    final var dest = new File(tempDir, "no-libraries-project.circ");
    assertTrue(loader.save(project.getLogisimFile(), dest),
        "save should succeed without a \"component not found\" file error");

    final var reopened = new Loader(null).openLogisimFile(dest);
    final var hasXorGate = reopened.getMainCircuit().getNonWires().stream()
        .anyMatch(c -> c.getFactory().getName().equals("XOR Gate"));
    assertTrue(hasXorGate, "the XOR gate should have survived the save/reload round trip");
  }

  @Test
  void secondCommitFromTheSameLibraryDoesNotTryToLoadItTwice() {
    final var project = blankProject();
    final var space = Space.of(project);

    final var and1 = Kind.of(space, "gates/and_gate");
    space.place(and1).anchorAt(0, 0).place();
    space.commit("place first and gate");

    final var and2 = Kind.of(space, "gates/and_gate");
    space.place(and2).anchorAt(0, 8).place();
    space.commit("place second and gate");

    final var gatesLibraryCount = project.getLogisimFile().getLibraries().stream()
        .filter(lib -> lib.getName().equals("Gates"))
        .count();
    assertEquals(1, gatesLibraryCount, "the Gates library should be loaded exactly once");
  }

  @Test
  void undoingTheCommitAlsoUnloadsTheLibraryItAutoLoaded() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var andGate = Kind.of(space, "gates/and_gate");
    space.place(andGate).anchorAt(0, 0).place();
    space.commit("place an and gate");
    assertTrue(project.getLogisimFile().getLibraries().stream().anyMatch(lib -> lib.getName().equals("Gates")));

    project.undoAction();

    assertTrue(project.getLogisimFile().getLibraries().isEmpty(),
        "undo should roll back both the placed component and the library loaded for it");
    assertTrue(project.getCurrentCircuit().getNonWires().isEmpty());
  }

  @Test
  void committingAComponentFromAnAlreadyLoadedLibraryLoadsNothingNew() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    file.addLibrary(loader.getBuiltin().getLibrary("Gates"));
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);

    final var space = Space.of(project);
    final var andGate = Kind.of(space, "gates/and_gate");
    space.place(andGate).anchorAt(0, 0).place();
    final var result = space.commit("place an and gate");

    assertFalse(result.action().getClass().getSimpleName().equals("JoinedAction"),
        "nothing needed loading, so commit should submit the plain circuit mutation as-is");
    assertEquals(1, project.getLogisimFile().getLibraries().size());
  }
}
