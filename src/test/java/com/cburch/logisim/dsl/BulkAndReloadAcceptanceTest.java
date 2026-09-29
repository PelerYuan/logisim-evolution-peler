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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code circuits:setEverywhere} and {@code libraries:reload}. */
class BulkAndReloadAcceptanceTest {

  @TempDir File tempDir;

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  private static String drawing(Comp chip) {
    return String.valueOf(chip.attrs().get("ShowInternalStructure"));
  }

  @Test
  void setEverywhereReachesEveryCircuitAsOneUndoEntry() {
    final var project = blankProject();
    final var circuits = Circuits.of(Space.of(project));
    circuits.create("Second");
    final var chips = new java.util.ArrayList<Comp>();
    for (final var name : new String[] {"main", "Second"}) {
      final var space = Space.of(project, name);
      chips.add(space.place(Kind.of(space, "TTL/7400")).anchorAt(0, 0).place());
      space.commit("chip in " + name);
    }
    circuits.setEverywhere("ShowInternalStructure", "false", null);
    final var history = History.of(Space.of(project));

    assertEquals(2, circuits.setEverywhere("ShowInternalStructure", "true", null));
    for (final var space : new Space[] {Space.of(project, "main"), Space.of(project, "Second")}) {
      final var chip = space.componentsOf(Kind.of(space, "TTL/7400")).get(0);
      assertEquals("true", drawing(chip));
    }

    history.undo();
    for (final var space : new Space[] {Space.of(project, "main"), Space.of(project, "Second")}) {
      final var chip = space.componentsOf(Kind.of(space, "TTL/7400")).get(0);
      assertEquals("false", drawing(chip));
    }
  }

  @Test
  void setEverywhereMakesNoUndoEntryWhenNothingChanges() {
    final var project = blankProject();
    final var circuits = Circuits.of(Space.of(project));
    final var space = Space.of(project);
    space.place(Kind.of(space, "TTL/7400")).anchorAt(0, 0).place();
    space.commit("chip");
    circuits.setEverywhere("ShowInternalStructure", "true", null);
    final var before = History.of(space).nextUndoDescription();

    assertEquals(0, circuits.setEverywhere("ShowInternalStructure", "true", null));
    assertEquals(0, circuits.setEverywhere("NoSuchAttribute", "1", null));
    assertEquals(before, History.of(space).nextUndoDescription());
  }

  @Test
  void setEverywhereKindFilterLeavesOtherKindsAlone() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var and = space.place(Kind.of(space, "gates/and_gate")).anchorAt(0, 0).place();
    final var or = space.place(Kind.of(space, "gates/or_gate")).anchorAt(0, 100).place();
    space.commit("gates");

    final var circuits = Circuits.of(space);
    assertEquals(1, circuits.setEverywhere("inputs", "4", "gates/and_gate"));
    assertEquals("4", String.valueOf(space.componentsOf(Kind.of(space, "gates/and_gate")).get(0)
        .attrs().get("inputs")));
    assertEquals("2", String.valueOf(space.componentsOf(Kind.of(space, "gates/or_gate")).get(0)
        .attrs().get("inputs")));
    assertTrue(and != null && or != null);
  }

  @Test
  void setEverywhereRejectsAnUnreadableValueBeforeChangingAnything() {
    final var project = blankProject();
    final var space = Space.of(project);
    space.place(Kind.of(space, "gates/and_gate")).anchorAt(0, 0).place();
    space.commit("gate");

    assertThrows(
        InvalidAttributeValueException.class,
        () -> Circuits.of(space).setEverywhere("width", "abc", null));
  }

  @Test
  void reloadPicksUpAChangedLibraryFile() throws Exception {
    final var project = blankProject();
    final var space = Space.of(project);
    final var libraries = Libraries.of(space);
    final var file = new File(tempDir, "Lib.circ");
    Files.writeString(
        file.toPath(),
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"
            + "<project source=\"4.1.0\" version=\"1.0\"><main name=\"Widget\"/>"
            + "<circuit name=\"Widget\"></circuit></project>\n",
        StandardCharsets.UTF_8);
    final var loadedName = libraries.loadCircuit(file.getAbsolutePath());
    assertThrows(UnknownKindException.class, () -> Kind.of(space, "circuit/Gadget"));

    Files.writeString(
        file.toPath(),
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"
            + "<project source=\"4.1.0\" version=\"1.0\"><main name=\"Widget\"/>"
            + "<circuit name=\"Widget\"></circuit><circuit name=\"Gadget\"></circuit></project>\n",
        StandardCharsets.UTF_8);
    libraries.reload(loadedName);

    Kind.of(space, "circuit/Gadget");
  }

  @Test
  void reloadRefusesBuiltInLibrariesAndUnknownNames() {
    final var project = blankProject();
    final var file = project.getLogisimFile();
    file.addLibrary(file.getLoader().getBuiltin().getLibrary("Gates"));
    final var libraries = Libraries.of(Space.of(project));

    assertTrue(libraries.list().contains("Gates"));
    assertThrows(LibraryNotReloadableException.class, () -> libraries.reload("Gates"));
    assertThrows(UnknownLibraryException.class, () -> libraries.reload("Nope"));
  }

  @Test
  void copyRegionCopiesComponentsAndWiresIntoAnotherCircuitAsOneUndoEntry() {
    final var project = blankProject();
    final var space = Space.of(project);
    Circuits.of(space).create("Other");
    final var pinKind = Kind.of(space, "wiring/pin");
    final var andKind = Kind.of(space, "gates/and_gate");
    final var a = space.place(pinKind).anchorAt(6, 6).place();
    final var gate = space.place(andKind).anchorAt(16, 6).place();
    space.connect(a.outputs().isEmpty() ? a.port(0) : a.outputs().get(0), gate.inputs().get(0));
    space.commit("source");
    final var outsideKind = Kind.of(space, "gates/or_gate");
    space.place(outsideKind).anchorAt(40, 30).place();
    space.commit("outside the region");

    final var copied = space.copyRegion(0, 0, 20, 10, 0, 0, "Other");

    assertEquals(2, copied.components());
    assertTrue(copied.wires() > 0);
    final var other = Space.of(project, "Other");
    assertEquals(1, other.componentsOf(andKind).size());
    assertEquals(0, other.componentsOf(outsideKind).size());
    History.of(space).undo();
    assertEquals(0, Space.of(project, "Other").components().size());
  }

  @Test
  void copyRegionIntoTheSameCircuitRefusesConflictsAndKeepsTheSessionUsable() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var andKind = Kind.of(space, "gates/and_gate");
    space.place(andKind).anchorAt(10, 10).place();
    space.commit("gate");

    assertEquals(
        "conflict",
        assertThrows(CopyRegionException.class, () -> space.copyRegion(0, 0, 30, 30, 0, 0, null))
            .details()
            .get("reason"));
    assertEquals(
        "off-canvas",
        assertThrows(CopyRegionException.class, () -> space.copyRegion(0, 0, 30, 30, -20, 0, null))
            .details()
            .get("reason"));
    assertEquals(
        "empty",
        assertThrows(CopyRegionException.class, () -> space.copyRegion(50, 50, 5, 5, 1, 1, null))
            .details()
            .get("reason"));
    assertThrows(
        UnknownCircuitException.class, () -> space.copyRegion(0, 0, 30, 30, 0, 40, "Nope"));

    space.copyRegion(0, 0, 30, 30, 0, 40, null);
    assertEquals(2, space.componentsOf(andKind).size());
  }
}
