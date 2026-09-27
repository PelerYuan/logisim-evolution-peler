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

import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.pcomp.PcompLibraryFile;
import com.cburch.logisim.proj.Project;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Covers the "库管理" (library management) expansion: {@link Libraries} adds load/unload of
 * external {@code .circ}/JAR/{@code pcomp} libraries on top of the existing single-circuit {@link
 * Space}, calling straight into {@link com.cburch.logisim.file.LibraryManager} with a throwaway,
 * dialog-free loader (see {@link Libraries}'s class javadoc) rather than the GUI's own
 * dialog-popping {@code Loader} convenience methods. */
class LibrariesAcceptanceTest {

  @TempDir File tempDir;

  private static final String WIDGET_CIRC =
      """
      <?xml version="1.0" encoding="UTF-8" standalone="no"?>
      <project source="4.1.0" version="1.0">
        <main name="Widget"/>
        <circuit name="Widget">
        </circuit>
      </project>
      """;

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  private File writeWidgetCirc(String fileName) throws Exception {
    final var file = new File(tempDir, fileName);
    Files.writeString(file.toPath(), WIDGET_CIRC, StandardCharsets.UTF_8);
    return file;
  }

  @Test
  void loadCircuitAddsALibraryUsableAsAKindSource() throws Exception {
    final var project = blankProject();
    final var space = Space.of(project);
    final var libraries = Libraries.of(space);
    final var circFile = writeWidgetCirc("Widget.circ");

    final var name = libraries.loadCircuit(circFile.getAbsolutePath());

    assertEquals("Widget", name);
    assertTrue(libraries.list().contains("Widget"));

    // The loaded library's own circuit is placeable exactly like any other subcircuit, via the
    // mechanical "circuit/<name>" key -- see KindRegistryExpansionAcceptanceTest.
    final var widget = Kind.of(space, "circuit/Widget");
    space.place(widget).anchorAt(0, 0).place();
    space.commit("place a subcircuit from the loaded library");
    assertEquals(1, space.componentsOf(widget).size());
  }

  @Test
  void loadCircuitRejectsAMissingFile() {
    final var libraries = Libraries.of(Space.of(blankProject()));
    final var missing = new File(tempDir, "does-not-exist.circ").getAbsolutePath();

    final var thrown =
        assertThrows(LibraryLoadFailedException.class, () -> libraries.loadCircuit(missing));
    assertEquals(missing, thrown.details().get("path"));
  }

  @Test
  void loadCircuitRejectsADuplicateName() throws Exception {
    final var libraries = Libraries.of(Space.of(blankProject()));
    final var circFile = writeWidgetCirc("Widget.circ");
    libraries.loadCircuit(circFile.getAbsolutePath());

    assertThrows(DuplicateLibraryNameException.class,
        () -> libraries.loadCircuit(circFile.getAbsolutePath()));
  }

  @Test
  void loadJarRejectsAMissingFile() {
    final var libraries = Libraries.of(Space.of(blankProject()));
    final var missing = new File(tempDir, "does-not-exist.jar").getAbsolutePath();

    final var thrown = assertThrows(LibraryLoadFailedException.class,
        () -> libraries.loadJar(missing, "com.example.NoSuchLibrary"));
    assertEquals(missing, thrown.details().get("path"));
  }

  @Test
  void loadPcompAddsALibraryAndUnloadRemovesIt() throws Exception {
    final var libraries = Libraries.of(Space.of(blankProject()));
    final var dir = new File(tempDir, "mylib");
    PcompLibraryFile.create(dir, "My Gates");

    final var name = libraries.loadPcomp(dir.getAbsolutePath());

    assertTrue(libraries.list().contains(name));
    libraries.unload(name);
    assertFalse(libraries.list().contains(name));
  }

  @Test
  void unloadOfUnknownNameSuggestsTheNearestRealOne() throws Exception {
    final var libraries = Libraries.of(Space.of(blankProject()));
    final var circFile = writeWidgetCirc("Widget.circ");
    libraries.loadCircuit(circFile.getAbsolutePath());

    final var thrown =
        assertThrows(UnknownLibraryException.class, () -> libraries.unload("Widgt"));
    @SuppressWarnings("unchecked")
    final var nearNames = (java.util.List<String>) thrown.details().get("nearNames");
    assertTrue(nearNames.contains("Widget"));
  }

  @Test
  void unloadRefusesALibraryStillPlacedAsASubcircuit() throws Exception {
    final var project = blankProject();
    final var space = Space.of(project);
    final var libraries = Libraries.of(space);
    final var circFile = writeWidgetCirc("Widget.circ");
    final var name = libraries.loadCircuit(circFile.getAbsolutePath());

    final var widget = Kind.of(space, "circuit/Widget");
    space.place(widget).anchorAt(0, 0).place();
    space.commit("place a subcircuit from the loaded library");

    assertThrows(LibraryInUseException.class, () -> libraries.unload(name));
  }
}
