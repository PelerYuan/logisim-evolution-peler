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

import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import org.junit.jupiter.api.Test;

/** Acceptance tests for {@link Space#move} and the committed-component branch of {@link
 * Space#remove}: the two ways a script edits what a circuit already holds. */
class SpaceMoveRemoveAcceptanceTest {

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  private record Wired(Project project, Space space, Comp in, Comp gate, Comp out) {}

  private static Wired pinGatePin() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var pin = Kind.of(space, "wiring/pin");
    final var not = Kind.of(space, "gates/not_gate");
    final var in = space.place(pin).anchorAt(4, 4).with(Attrs.of("type", "input")).place().label("A");
    final var gate = space.place(not).anchorAt(12, 4).place();
    final var out = space.place(pin).anchorAt(20, 4).with(Attrs.of("type", "output")).place().label("Y");
    space.connect(in.outputs().get(0), gate.inputs().get(0));
    space.connect(gate.outputs().get(0), out.inputs().get(0));
    space.commit("build");
    return new Wired(project, space, in, gate, out);
  }

  @Test
  void movingAGateKeepsItsConnectionsAndIdentity() {
    final var w = pinGatePin();
    final var id = w.gate().id();

    final var moved = w.space().move(w.gate(), 12, 12, true);

    assertEquals(0, moved.unconnectedPorts());
    assertEquals(id, w.gate().id());
    assertEquals(120, w.gate().origin().rawY());
    // A fresh Space rediscovers the circuit from scratch: the gate must still be wired to both pins.
    final var fresh = Space.of(w.project());
    final var found = fresh.componentsOf(Kind.of(fresh, "gates/not_gate")).get(0);
    assertTrue(found.inputs().get(0).isConnected());
    assertTrue(found.outputs().get(0).isConnected());
  }

  @Test
  void movingWithoutKeepingConnectionsLeavesTheGateDisconnected() {
    final var w = pinGatePin();

    w.space().move(w.gate(), 12, 20, false);

    final var fresh = Space.of(w.project());
    final var found = fresh.componentsOf(Kind.of(fresh, "gates/not_gate")).get(0);
    assertFalse(found.inputs().get(0).isConnected());
  }

  @Test
  void aMoveIsOneUndoableAction() {
    final var w = pinGatePin();
    final var history = History.of(w.space());
    final var before = w.gate().origin().rawY();

    w.space().move(w.gate(), 12, 12, true);
    history.undo();

    final var fresh = Space.of(w.project());
    final var found = fresh.componentsOf(Kind.of(fresh, "gates/not_gate")).get(0);
    assertEquals(before, found.origin().rawY());
  }

  @Test
  void movingAStagedComponentIsRefused() {
    final var space = Space.of(blankProject());
    final var staged = space.place(Kind.of(space, "gates/not_gate")).anchorAt(4, 4).place();
    assertThrows(UncommittedChangesException.class, () -> space.move(staged, 8, 8, true));
  }

  @Test
  void movingWithSomethingStagedIsRefused() {
    final var w = pinGatePin();
    w.space().place(Kind.of(w.space(), "gates/not_gate")).anchorAt(30, 30).place();
    assertThrows(UncommittedChangesException.class, () -> w.space().move(w.gate(), 12, 12, true));
  }

  @Test
  void removingACommittedComponentDeletesItFromTheCircuit() {
    final var w = pinGatePin();
    assertEquals(3, w.space().components().size());

    w.space().remove(w.gate());

    assertEquals(2, w.space().components().size());
    final var fresh = Space.of(w.project());
    assertEquals(2, fresh.components().size());
    assertTrue(fresh.componentsOf(Kind.of(fresh, "gates/not_gate")).isEmpty());
  }

  @Test
  void removingACommittedComponentIsUndoable() {
    final var w = pinGatePin();
    w.space().remove(w.gate());

    History.of(w.space()).undo();

    final var fresh = Space.of(w.project());
    assertEquals(3, fresh.components().size());
  }

  @Test
  void removingAStagedComponentStillJustDropsIt() {
    final var space = Space.of(blankProject());
    final var staged = space.place(Kind.of(space, "gates/not_gate")).anchorAt(4, 4).place();
    space.remove(staged);
    assertFalse(space.isDirty());
  }

  @Test
  void removingACommittedComponentWithSomethingStagedIsRefused() {
    final var w = pinGatePin();
    w.space().place(Kind.of(w.space(), "gates/not_gate")).anchorAt(30, 30).place();
    assertThrows(UncommittedChangesException.class, () -> w.space().remove(w.gate()));
  }
}
