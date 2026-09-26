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

import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.proj.Project;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

/**
 * The P4 acceptance criterion from the design doc, section 十一: "打开一个用户手画的电路，读出
 * 全部连接关系正确，在其上追加一个门并连上，不破坏原有布线". A circuit built and committed
 * through the DSL is real {@link com.cburch.logisim.circuit.Circuit} state -- indistinguishable at
 * the component/wire level from anything drawn by hand -- so building the fixture with one {@link
 * Space}, discarding it, then opening a brand new one exercises exactly the discovery path P4
 * adds: the new {@link Space} has no memory of what built the circuit, only whatever {@code
 * discoverExisting()} can read back from {@link com.cburch.logisim.circuit.Circuit} itself.
 */
public class SpaceExistingCircuitAcceptanceTest {

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  @Test
  public void testFreshSpaceDiscoversHandDrawnWiringAndExtendsItWithoutDisturbingIt() {
    final var project = blankProject();

    // Build the "hand-drawn" fixture: A, B -> AND -> OUT, fully wired and committed.
    final var setup = Space.of(project);
    final var pin = Kind.of(setup, "wiring/pin");
    final var andGate = Kind.of(setup, "gates/and_gate");

    final var a = setup.place(pin).anchorAt(0, 0).with(Attrs.of("type", "input")).place().label("A");
    final var b = setup.place(pin).anchorAt(0, 8).with(Attrs.of("type", "input")).place().label("B");
    final var and1 = setup.place(andGate).anchorAt(8, 4).place();
    final var out = setup.place(pin).anchorAt(16, 4).with(Attrs.of("type", "output")).place().label("R");

    setup.connect(a.outputs().get(0), and1.inputs().get(0));
    setup.connect(b.outputs().get(0), and1.inputs().get(1));
    setup.connect(and1.outputs().get(0), out.inputs().get(0));
    setup.commit("build fixture");

    final var originalWires = new HashSet<Wire>(setup.circuit().getWires());
    assertTrue(!originalWires.isEmpty(), "the fixture must actually route some wires to be a meaningful test");

    // A brand new Space, with no memory of what built the circuit, must read the same wiring back.
    final var reopened = Space.of(project);
    assertEquals(4, reopened.components().size());
    assertTrue(reopened.check().ok(),
        "a fully wired hand-drawn circuit should have no unconnected/undriven/multiply-driven ports");

    final var rA = reopened.byLabel("A").orElseThrow();
    final var rB = reopened.byLabel("B").orElseThrow();
    final var rOut = reopened.byLabel("R").orElseThrow();
    final var rAnd = reopened.componentsOf(Kind.of(reopened, "gates/and_gate")).get(0);

    final var netAtoAnd = rA.outputs().get(0).net().orElseThrow();
    assertTrue(netAtoAnd.ports().contains(rAnd.inputs().get(0)));
    final var netBtoAnd = rB.outputs().get(0).net().orElseThrow();
    assertTrue(netBtoAnd.ports().contains(rAnd.inputs().get(1)));
    final var netAndToOut = rAnd.outputs().get(0).net().orElseThrow();
    assertTrue(netAndToOut.ports().contains(rOut.inputs().get(0)));
    assertTrue(netAndToOut.isCommitted(), "a pre-existing wire's net should already read as committed");

    // Add a new gate on top, wired into the pre-existing net, without disturbing the original wiring.
    final var notGate = Kind.of(reopened, "gates/not_gate");
    final var not1 = reopened.place(notGate).anchorAt(24, 4).place();
    final var inv = reopened.place(pin).anchorAt(32, 4).with(Attrs.of("type", "output")).place().label("Y");
    reopened.connect(not1.inputs().get(0), netAndToOut);
    reopened.connect(not1.outputs().get(0), inv.inputs().get(0));
    reopened.commit("add inverter");

    assertTrue(reopened.circuit().getWires().containsAll(originalWires),
        "the original hand-drawn wiring must not be disturbed by an unrelated commit");

    // Re-read from scratch again to confirm the extension is real, committed circuit state.
    final var reread = Space.of(project);
    assertEquals(6, reread.components().size());
    final var fAnd = reread.componentsOf(Kind.of(reread, "gates/and_gate")).get(0);
    final var fOut = reread.byLabel("R").orElseThrow();
    final var fInv = reread.byLabel("Y").orElseThrow();
    final var fNot = reread.componentsOf(Kind.of(reread, "gates/not_gate")).get(0);

    final var extendedNet = fAnd.outputs().get(0).net().orElseThrow();
    assertTrue(extendedNet.ports().contains(fOut.inputs().get(0)), "original OUT connection must survive");
    assertTrue(extendedNet.ports().contains(fNot.inputs().get(0)), "new NOT gate must join the extended net");

    final var notOutputNet = fNot.outputs().get(0).net().orElseThrow();
    assertTrue(notOutputNet.ports().contains(fInv.inputs().get(0)));

    assertTrue(reread.check().ok(),
        "the extended circuit should still have no unconnected/undriven/multiply-driven ports");
  }

  @Test
  public void testPlacementRejectsOverlappingAnExistingHandDrawnComponent() {
    final var project = blankProject();

    final var setup = Space.of(project);
    final var pin = Kind.of(setup, "wiring/pin");
    setup.place(pin).anchorAt(0, 0).with(Attrs.of("type", "input")).place();
    setup.commit("place existing pin");

    final var reopened = Space.of(project);
    final var samePin = Kind.of(reopened, "wiring/pin");
    assertThrows(PlacementException.class,
        () -> reopened.place(samePin).anchorAt(0, 0).with(Attrs.of("type", "input")).place(),
        "a new placement landing exactly on an existing hand-drawn pin must be rejected");
  }
}
