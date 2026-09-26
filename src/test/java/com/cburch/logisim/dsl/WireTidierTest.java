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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.analyze.model.AnalyzerModel;
import com.cburch.logisim.analyze.model.Entry;
import com.cburch.logisim.analyze.model.TruthTable;
import com.cburch.logisim.circuit.Analyze;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.circuit.WireTidier;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.util.StringUtil;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The dedicated testing pass named in {@code docs/peler-edition/ROADMAP.md}, Feature 4, as the
 * gate for re-enabling Tidy Wires: "at minimum a fan-out net (one output to 3+ inputs) and an
 * obstacle-between-two-components case" -- neither had ever been run by a human (or a test) before
 * the feature shipped active in v1.0.7 and was pulled the same day for exactly that reason.
 *
 * <p>Lives in this package (rather than alongside {@link WireTidier} itself) purely to reuse the
 * {@link Space}/{@link Kind}/{@link Placement} fixture builder {@link DslFullAdderAcceptanceTest}
 * already established -- {@link WireTidier} itself has no idea a DSL exists and is tested here
 * exactly as it would be used from anywhere else: as a plain {@link Circuit} in, {@link
 * com.cburch.logisim.circuit.CircuitMutation} out.
 */
class WireTidierTest {

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  private static void apply(Project project, Circuit circuit) {
    final var mutation = WireTidier.buildTidyMutation(circuit);
    assertTrue(mutation != null, "expected the tidy pass to have something to route");
    project.doAction(mutation.toAction(StringUtil.constantGetter("tidy wires")));
  }

  /** Counts how many {@link Wire}s belong to the same electrical bundle as {@code start}. */
  private static long bundleSize(Circuit circuit, Wire start) {
    final var bundle = circuit.getWireSet(start);
    return circuit.getWires().stream().filter(bundle::containsWire).count();
  }

  private static Location locOf(Dot dot) {
    return Location.create(dot.rawX(), dot.rawY(), false);
  }

  @Test
  void emptyCircuitHasNothingToTidy() {
    final var project = blankProject();
    assertNull(WireTidier.buildTidyMutation(project.getCurrentCircuit()));
  }

  /** Fan-out: one driver feeding three sinks must remain one net, all reading the driver's value,
   * after every wire in the circuit is discarded and rebuilt from scratch. */
  @Test
  void fanOutNetStaysOneNetAfterTidy() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var pin = Kind.of(space, "wiring/pin");

    final var driver = space.place(pin).anchorAt(0, 0).with(Attrs.of("type", "input")).place();
    final var sink1 = space.place(pin).anchorAt(30, 0).with(Attrs.of("type", "output")).place();
    final var sink2 = space.place(pin).anchorAt(30, 10).with(Attrs.of("type", "output")).place();
    final var sink3 = space.place(pin).anchorAt(30, 20).with(Attrs.of("type", "output")).place();

    space.connect(driver.outputs().get(0), sink1.inputs().get(0));
    space.connect(driver.outputs().get(0), sink2.inputs().get(0));
    space.connect(driver.outputs().get(0), sink3.inputs().get(0));
    space.commit("build fan-out");

    final var circuit = project.getCurrentCircuit();
    apply(project, circuit);

    final var driverLoc = locOf(driver.outputs().get(0).at());
    final var wireAtDriver = circuit.getWires().stream()
        .filter(w -> w.getEnd0().equals(driverLoc) || w.getEnd1().equals(driverLoc))
        .findFirst()
        .orElseThrow(() -> new AssertionError("expected the driver to have at least one wire after tidying"));
    final var bundle = circuit.getWireSet(wireAtDriver);
    for (final var sink : new Comp[] {sink1, sink2, sink3}) {
      final var loc = locOf(sink.inputs().get(0).at());
      assertTrue(bundle.containsLocation(loc),
          "sink " + sink.id() + " fell out of the fan-out net after tidying");
    }

    final Map<Instance, String> labels = new LinkedHashMap<>();
    labels.put(Instance.getInstanceFor(driver.rawComponent()), "D");
    labels.put(Instance.getInstanceFor(sink1.rawComponent()), "S1");
    labels.put(Instance.getInstanceFor(sink2.rawComponent()), "S2");
    labels.put(Instance.getInstanceFor(sink3.rawComponent()), "S3");
    final var model = new AnalyzerModel();
    Analyze.computeTable(model, project, circuit, labels);
    final var table = model.getTruthTable();
    assertEquals(2, table.getRowCount());

    final var inputBits = model.getInputs().bits;
    final var outputBits = model.getOutputs().bits;
    final var dCol = inputBits.indexOf("D");
    assertTrue(dCol >= 0, "expected a D input column");
    for (var row = 0; row < table.getRowCount(); row++) {
      final var dVal = TruthTable.isInputSet(row, dCol, table.getInputColumnCount());
      final var expected = dVal ? Entry.ONE : Entry.ZERO;
      for (final var name : new String[] {"S1", "S2", "S3"}) {
        final var col = outputBits.indexOf(name);
        assertTrue(col >= 0, "expected an output column named " + name);
        assertEquals(expected, table.getOutputEntry(row, col), name + " diverged from D at row " + row);
      }
    }
  }

  /**
   * A component sitting squarely between two others' terminals must be routed around, not through
   * -- and an unrelated net physically close to the detour must not get pulled into it.
   *
   * <p>Layout: a driver at (0,0) and a sink at (40,0), same row. An AND gate is anchored at (20,1)
   * -- for a default east-facing medium gate this yields a bounding box of x:[150,200], y:[-15,35]
   * (see {@code AbstractGate.getOffsetBounds}), which straddles y=0 squarely between the driver and
   * the sink at x=[0,400]. The gate is nudged one grid step off that row (rather than anchored
   * exactly on it) purely so its own output pin does not land exactly at some point along the
   * straight A-C line -- if it did, adding that raw wire below would make it electrically touch the
   * gate's output where it crosses, which is a real Logisim behavior (a wire electrically taps any
   * pin its interior passes through, not just its own endpoints) but a short this fixture does not
   * intend. The gate still fully blocks the direct path either way. The gate has its own separate
   * driver pins well clear of that box and of the main row, forming further nets of its own.
   *
   * <p>The A-C wire is added directly as a raw {@link Wire}, bypassing {@link Space#connect} and its
   * commit-time {@code Router} entirely: that router refuses to cross the obstacle even with a
   * detour (it only tries two-bend paths and gives up), which is a real instance of the very
   * "inelegant/stuck" layout problem this whole effort is about, but is not what this test is
   * checking. Here it stands in for hand-drawn or externally-generated wiring that already runs
   * straight through a component -- exactly the mess {@link WireTidier} is supposed to clean up.
   */
  @Test
  void obstacleBetweenTwoComponentsIsRoutedAroundNotThrough() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var pin = Kind.of(space, "wiring/pin");
    final var andGate = Kind.of(space, "gates/and_gate");

    final var a = space.place(pin).anchorAt(0, 0).with(Attrs.of("type", "input")).place();
    final var c = space.place(pin).anchorAt(40, 0).with(Attrs.of("type", "output")).place();
    final var obstacle = space.place(andGate).anchorAt(20, 1).place();

    final var bin1 = space.place(pin).anchorAt(10, -16).with(Attrs.of("type", "input")).place();
    final var bin2 = space.place(pin).anchorAt(10, -8).with(Attrs.of("type", "input")).place();
    final var bout = space.place(pin).anchorAt(30, -12).with(Attrs.of("type", "output")).place();

    space.connect(bin1.outputs().get(0), obstacle.inputs().get(0));
    space.connect(bin2.outputs().get(0), obstacle.inputs().get(1));
    space.connect(obstacle.outputs().get(0), bout.inputs().get(0));
    space.commit("build obstacle fixture");

    final var circuit = project.getCurrentCircuit();

    final var aLoc = locOf(a.outputs().get(0).at());
    final var cLoc = locOf(c.inputs().get(0).at());
    final var directMutation = new CircuitMutation(circuit);
    directMutation.add(Wire.create(aLoc, cLoc));
    project.doAction(directMutation.toAction(StringUtil.constantGetter("connect A to C directly")));

    final var obstacleBounds = obstacle.rawComponent().getBounds();
    assertTrue(
        obstacleBounds.getX() > 0 && obstacleBounds.getX() + obstacleBounds.getWidth() < 400
            && obstacleBounds.getY() <= 0 && obstacleBounds.getY() + obstacleBounds.getHeight() >= 0,
        "test fixture is wrong: the gate at " + obstacleBounds + " does not actually straddle the "
            + "straight line from " + aLoc + " to " + cLoc);

    apply(project, circuit);

    final var directWire = circuit.getWires().stream()
        .anyMatch(w -> (w.getEnd0().equals(aLoc) && w.getEnd1().equals(cLoc))
            || (w.getEnd0().equals(cLoc) && w.getEnd1().equals(aLoc)));
    assertFalse(directWire, "A and C were joined by one direct wire, which would have to cut through the obstacle");

    final var wireAtA = circuit.getWires().stream()
        .filter(w -> w.getEnd0().equals(aLoc) || w.getEnd1().equals(aLoc))
        .findFirst()
        .orElseThrow(() -> new AssertionError("expected A to still have a wire after tidying"));
    assertTrue(circuit.getWireSet(wireAtA).containsLocation(cLoc), "A and C ended up in different nets");
    assertTrue(bundleSize(circuit, wireAtA) >= 2, "expected the detour to need more than one segment");

    final var boutLoc = locOf(bout.inputs().get(0).at());
    assertFalse(circuit.getWireSet(wireAtA).containsLocation(boutLoc),
        "the obstacle's own net got merged into the unrelated A-C net");

    final Map<Instance, String> labels = new LinkedHashMap<>();
    labels.put(Instance.getInstanceFor(a.rawComponent()), "A");
    labels.put(Instance.getInstanceFor(c.rawComponent()), "C");
    labels.put(Instance.getInstanceFor(bin1.rawComponent()), "B1");
    labels.put(Instance.getInstanceFor(bin2.rawComponent()), "B2");
    labels.put(Instance.getInstanceFor(bout.rawComponent()), "BOUT");
    final var model = new AnalyzerModel();
    Analyze.computeTable(model, project, circuit, labels);
    final var table = model.getTruthTable();
    final var inputBits = model.getInputs().bits;
    final var outputBits = model.getOutputs().bits;
    final var aCol = inputBits.indexOf("A");
    final var b1Col = inputBits.indexOf("B1");
    final var b2Col = inputBits.indexOf("B2");
    final var cCol = outputBits.indexOf("C");
    final var boutCol = outputBits.indexOf("BOUT");
    assertTrue(aCol >= 0 && b1Col >= 0 && b2Col >= 0 && cCol >= 0 && boutCol >= 0);

    for (var row = 0; row < table.getRowCount(); row++) {
      final var inputCount = table.getInputColumnCount();
      final var av = TruthTable.isInputSet(row, aCol, inputCount);
      final var b1v = TruthTable.isInputSet(row, b1Col, inputCount);
      final var b2v = TruthTable.isInputSet(row, b2Col, inputCount);
      assertEquals(av ? Entry.ONE : Entry.ZERO, table.getOutputEntry(row, cCol),
          "C should just echo A (row " + row + ")");
      assertEquals((b1v && b2v) ? Entry.ONE : Entry.ZERO, table.getOutputEntry(row, boutCol),
          "BOUT should be B1 AND B2, unaffected by the nearby detour (row " + row + ")");
    }
  }
}
