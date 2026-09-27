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

import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.proj.Project;
import org.junit.jupiter.api.Test;

/** Covers the "电路管理" (circuit management) expansion: {@link Circuits} adds create/remove/
 * rename/setMain/list on top of the existing single-circuit {@link Space}, reusing the same
 * validation the GUI's Project menu applies but as structured {@link DslException}s rather than
 * Swing dialogs. */
public class CircuitsAcceptanceTest {

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  @Test
  public void testCreateAddsANewEmptyCircuitThatCanThenBeOpened() {
    final var project = blankProject();
    final var circuits = Circuits.of(Space.of(project));

    circuits.create("Helper");

    assertEquals(java.util.List.of("main", "Helper"), circuits.list());
    final var helperSpace = Space.of(project, "Helper");
    assertTrue(helperSpace.components().isEmpty());
  }

  @Test
  public void testCreateRejectsADuplicateNameCaseInsensitively() {
    final var project = blankProject();
    final var circuits = Circuits.of(Space.of(project));

    assertThrows(DuplicateCircuitNameException.class, () -> circuits.create("MAIN"));
  }

  @Test
  public void testCreateRejectsAnEmptyOrSyntacticallyInvalidName() {
    final var project = blankProject();
    final var circuits = Circuits.of(Space.of(project));

    assertThrows(InvalidCircuitNameException.class, () -> circuits.create(""));
    assertThrows(InvalidCircuitNameException.class, () -> circuits.create("1leadingDigit"));
  }

  @Test
  public void testRemoveDeletesACircuitAndForgetsIt() {
    final var project = blankProject();
    final var circuits = Circuits.of(Space.of(project));
    circuits.create("Helper");

    circuits.remove("Helper");

    assertEquals(java.util.List.of("main"), circuits.list());
  }

  @Test
  public void testRemoveRejectsTheLastCircuit() {
    final var project = blankProject();
    final var circuits = Circuits.of(Space.of(project));

    assertThrows(CircuitInUseException.class, () -> circuits.remove("main"));
  }

  @Test
  public void testRemoveRejectsACircuitStillPlacedAsASubcircuitElsewhere() {
    final var project = blankProject();
    final var circuits = Circuits.of(Space.of(project));
    circuits.create("Helper");

    final var main = Space.of(project, "main");
    final var helper = Kind.of(main, "circuit/Helper");
    main.place(helper).anchorAt(0, 0).place();
    main.commit("place a subcircuit instance");

    assertThrows(CircuitInUseException.class, () -> circuits.remove("Helper"));
  }

  @Test
  public void testRemoveOfUnknownNameSuggestsTheNearestRealOne() {
    final var project = blankProject();
    final var circuits = Circuits.of(Space.of(project));

    final var thrown = assertThrows(UnknownCircuitException.class, () -> circuits.remove("mian"));
    @SuppressWarnings("unchecked")
    final var nearNames = (java.util.List<String>) thrown.details().get("nearNames");
    assertTrue(nearNames.contains("main"));
  }

  @Test
  public void testRenameChangesTheNameAndTheCircuitStaysOpenableUnderIt() {
    final var project = blankProject();
    final var circuits = Circuits.of(Space.of(project));

    circuits.rename("main", "Top");

    assertEquals(java.util.List.of("Top"), circuits.list());
    assertEquals("Top", Space.of(project, "Top").circuitName());
  }

  @Test
  public void testRenameToAnAlreadyUsedNameIsRejected() {
    final var project = blankProject();
    final var circuits = Circuits.of(Space.of(project));
    circuits.create("Helper");

    assertThrows(DuplicateCircuitNameException.class, () -> circuits.rename("Helper", "main"));
  }

  @Test
  public void testSetMainChangesWhichCircuitIsMainAndRejectsAnUnknownName() {
    final var project = blankProject();
    final var circuits = Circuits.of(Space.of(project));
    circuits.create("Helper");

    assertEquals("main", circuits.mainName());
    circuits.setMain("Helper");
    assertEquals("Helper", circuits.mainName());

    assertThrows(UnknownCircuitException.class, () -> circuits.setMain("NoSuchCircuit"));
  }
}
