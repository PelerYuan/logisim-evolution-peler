/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */
package com.cburch.logisim.dsl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.dsl.internal.Router;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import org.junit.jupiter.api.Test;

/** Wires of different nets must never touch: the rules {@link Router} follows and the guard in commit. */
class WiringSafetyAcceptanceTest {
  private static Project blankProject() {
    final var file = LogisimFile.createNew(new Loader(null), null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  @Test
  void connectsFollowsLogisimsJunctionRules() {
    // Two runs crossing through each other's interiors stay separate.
    assertFalse(Router.connects(0, 50, 100, 50, 50, 0, 50, 100));
    // A shared corner, an end landing on the other's run and a collinear overlap all join.
    assertTrue(Router.connects(0, 50, 50, 50, 50, 50, 50, 100));
    assertTrue(Router.connects(0, 50, 100, 50, 50, 50, 50, 100));
    assertTrue(Router.connects(0, 50, 100, 50, 60, 50, 200, 50));
    // Same line, no shared point.
    assertFalse(Router.connects(0, 50, 40, 50, 60, 50, 200, 50));
  }

  @Test
  void hintedRouteThatWouldTouchAnotherNetIsRefused() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var pin = Kind.of(space, "wiring/pin");
    final var a = space.place(pin).anchorAt(10, 10).with(Attrs.of("type", "input")).place();
    final var b = space.place(pin).anchorAt(30, 20).with(Attrs.of("type", "output")).place();
    final var c = space.place(pin).anchorAt(15, 5).with(Attrs.of("type", "input")).place();
    final var d = space.place(pin).anchorAt(25, 25).with(Attrs.of("type", "output")).place();
    space.connect(a.outputs().get(0), b.inputs().get(0)).viaColumn(20);
    space.connect(c.outputs().get(0), d.inputs().get(0)).viaColumn(20);
    assertThrows(RoutingException.class, () -> space.commit("crossing net"));
    assertTrue(space.isDirty());
  }

  @Test
  void manualWireTouchingTwoNetsIsRefusedBeforeAnythingIsCommitted() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var pin = Kind.of(space, "wiring/pin");
    final var a = space.place(pin).anchorAt(10, 10).with(Attrs.of("type", "input")).place();
    final var b = space.place(pin).anchorAt(30, 10).with(Attrs.of("type", "output")).place();
    final var c = space.place(pin).anchorAt(10, 20).with(Attrs.of("type", "input")).place();
    final var d = space.place(pin).anchorAt(30, 20).with(Attrs.of("type", "output")).place();
    space.connect(a.outputs().get(0), b.inputs().get(0));
    space.connect(c.outputs().get(0), d.inputs().get(0));
    final var wires = space.wires();
    wires.add(wires.dotAt(10, 10), wires.dotAt(10, 20));
    assertThrows(RoutingException.class, () -> space.commit("short"));
    assertTrue(space.isDirty());
    assertTrue(project.getCurrentCircuit().getWires().isEmpty());
  }
}
