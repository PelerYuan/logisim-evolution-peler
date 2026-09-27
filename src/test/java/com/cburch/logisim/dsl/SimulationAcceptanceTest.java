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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.proj.Project;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Covers the "仿真控制" (simulation control) addition: {@link Simulation} drives the project's own,
 * always-live {@link com.cburch.logisim.circuit.Simulator} rather than a private one, and waits
 * synchronously for each requested step to actually complete on the background {@code SimThread}
 * (see {@link Simulation}'s class javadoc). This is the first test in the suite to exercise the
 * simulator/propagator at all, so the fixtures below are built from scratch rather than reused from
 * the placement-only tests. */
class SimulationAcceptanceTest {

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  @Test
  void writePinPropagatesToAConnectedOutputPin() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var pin = Kind.of(space, "wiring/pin");

    final var in = space.place(pin).anchorAt(0, 0).with(Attrs.of("type", "input")).place().label("InA");
    final var out = space.place(pin).anchorAt(8, 0).with(Attrs.of("type", "output")).place().label("OutA");
    space.connect(in.outputs().get(0), out.inputs().get(0));
    space.commit("wire InA to OutA");

    final var simulation = Simulation.of(space);
    simulation.writePin("InA", 1);

    final var value = simulation.readPin("OutA");
    assertTrue(value.known());
    assertFalse(value.error());
    assertEquals(1, value.value());

    simulation.writePin("InA", 0);
    assertEquals(0, simulation.readPin("OutA").value());
  }

  @Test
  void readPinWorksOnAnInputPinToo() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var pin = Kind.of(space, "wiring/pin");
    space.place(pin).anchorAt(0, 0).with(Attrs.of("type", "input")).place().label("InA");
    space.commit("place one input pin");

    final var simulation = Simulation.of(space);
    simulation.writePin("InA", 1);

    assertEquals(1, simulation.readPin("InA").value());
  }

  @Test
  void resetRunsWithoutThrowingEvenWithNoClockPresent() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var pin = Kind.of(space, "wiring/pin");
    space.place(pin).anchorAt(0, 0).with(Attrs.of("type", "input")).place().label("InA");
    space.commit("place one input pin");

    final var simulation = Simulation.of(space);
    simulation.reset();

    assertFalse(simulation.isOscillating());
    assertFalse(simulation.isExceptionEncountered());
  }

  @Test
  void tickWithNoClockInTheCircuitThrowsNoClockException() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var pin = Kind.of(space, "wiring/pin");
    space.place(pin).anchorAt(0, 0).with(Attrs.of("type", "input")).place();
    space.commit("place one input pin, no clock");

    final var simulation = Simulation.of(space);

    assertThrows(NoClockException.class, () -> simulation.tick(1));
    assertThrows(NoClockException.class, () -> simulation.setAutoTicking(true));
  }

  @Test
  void tickAdvancesAClockConnectedToAnOutputPin() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var clock = Kind.of(space, "Wiring/Clock");
    final var pin = Kind.of(space, "wiring/pin");

    final var clk = space.place(clock).anchorAt(0, 0).place();
    final var q = space.place(pin).anchorAt(8, 0).with(Attrs.of("type", "output")).place().label("Q");
    space.connect(clk.outputs().get(0), q.inputs().get(0));
    space.commit("wire Clock to Q");

    final var simulation = Simulation.of(space);

    simulation.tick(1);
    final var afterOneTick = simulation.readPin("Q");
    assertTrue(afterOneTick.known());

    simulation.tick(1);
    final var afterTwoTicks = simulation.readPin("Q");
    assertTrue(afterTwoTicks.known());
    assertNotEquals(afterOneTick.value(), afterTwoTicks.value(),
        "one more half-cycle tick should flip the clock's output");
  }

  @Test
  void readPinThrowsUnknownPinExceptionForAMissingLabelWithASuggestion() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var pin = Kind.of(space, "wiring/pin");
    space.place(pin).anchorAt(0, 0).with(Attrs.of("type", "input")).place().label("InputPin");
    space.commit("place one input pin");

    final var simulation = Simulation.of(space);
    final var thrown = assertThrows(UnknownPinException.class, () -> simulation.readPin("InputPn"));
    @SuppressWarnings("unchecked")
    final var nearNames = (List<String>) thrown.details().get("nearNames");
    assertTrue(nearNames.contains("InputPin"));
  }

  @Test
  void readPinThrowsUnknownPinExceptionWhenTheLabelNamesANonPinComponent() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var andGate = Kind.of(space, "gates/and_gate");
    space.place(andGate).anchorAt(0, 0).place().label("G");
    space.commit("place one AND gate");

    final var simulation = Simulation.of(space);
    final var thrown = assertThrows(UnknownPinException.class, () -> simulation.readPin("G"));
    assertEquals("gates/and_gate", thrown.details().get("foundKind"));
  }

  @Test
  void writePinThrowsPinNotWritableExceptionForAnOutputPin() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var pin = Kind.of(space, "wiring/pin");
    space.place(pin).anchorAt(0, 0).with(Attrs.of("type", "output")).place().label("OutA");
    space.commit("place one output pin");

    final var simulation = Simulation.of(space);
    assertThrows(PinNotWritableException.class, () -> simulation.writePin("OutA", 1));
  }
}
