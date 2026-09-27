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

import com.cburch.logisim.analyze.model.AnalyzerModel;
import com.cburch.logisim.analyze.model.Entry;
import com.cburch.logisim.analyze.model.TruthTable;
import com.cburch.logisim.circuit.Analyze;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.proj.Project;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Acceptance tests for {@link Space#ensureAwayFromOrigin(int)}, the safety net behind {@code
 * McpScriptTools#autoTidyAndFit} for the reported bug where an MCP-built circuit anchored flush
 * against {@code (0, 0)} could never be scrolled fully into view: an east-facing pin's own body is
 * drawn to the left of its anchor (see {@link com.cburch.logisim.std.wiring.Probe#getOffsetBounds}),
 * so {@link com.cburch.logisim.circuit.Circuit#getBounds()} for such a circuit is genuinely
 * negative on the x axis, and the canvas's scrollable area cannot reach negative coordinates no
 * matter how correctly it zooms or centers.
 */
class SpaceEnsureAwayFromOriginAcceptanceTest {

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  @Test
  void emptyCircuitHasNothingToShiftAndReturnsFalse() {
    final var space = Space.of(blankProject());
    assertFalse(space.ensureAwayFromOrigin(20));
  }

  @Test
  void uncommittedPlacementBlocksTheShiftRatherThanSilentlyDroppingIt() {
    final var space = Space.of(blankProject());
    final var pin = Kind.of(space, "wiring/pin");
    space.place(pin).anchorAt(50, 50).with(Attrs.of("type", "input")).place();

    assertTrue(space.isDirty());
    final var thrown =
        assertThrows(UncommittedChangesException.class, () -> space.ensureAwayFromOrigin(20));
    assertEquals(1, thrown.details().get("pendingComponentCount"));
  }

  @Test
  void circuitAlreadyClearOfBothAxesIsLeftUntouched() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var pin = Kind.of(space, "wiring/pin");
    space.place(pin).anchorAt(50, 50).with(Attrs.of("type", "input")).place();
    space.commit("place far from the origin");

    assertFalse(space.ensureAwayFromOrigin(20));
  }

  /** The exact shape of the reported bug: an east-facing input pin anchored at {@code (0, 0)} has
   * its own drawn body at negative x (an east-facing pin's body sits to the left of its anchor),
   * so the circuit's real bounds cross the x axis even though every component's own anchor point
   * is non-negative. */
  @Test
  void pinAnchoredAtTheOriginReallyDoesProduceNegativeBounds() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var pin = Kind.of(space, "wiring/pin");
    space.place(pin).anchorAt(0, 0).with(Attrs.of("type", "input")).place();
    space.commit("place flush against the origin");

    assertTrue(project.getCurrentCircuit().getBounds().getX() < 0,
        "an east-facing pin anchored at x=0 should draw part of its body at negative x");
  }

  @Test
  void shiftsAPinAwayFromTheOriginAndReportsTrue() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var pin = Kind.of(space, "wiring/pin");
    space.place(pin).anchorAt(0, 0).with(Attrs.of("type", "input")).place();
    space.commit("place flush against the origin");

    assertTrue(space.ensureAwayFromOrigin(20));

    final var bounds = project.getCurrentCircuit().getBounds();
    assertTrue(bounds.getX() >= 20, "expected bounds.x >= margin, was " + bounds.getX());
    assertTrue(bounds.getY() >= 20, "expected bounds.y >= margin, was " + bounds.getY());
  }

  /** Full adder built and committed exactly like {@link SpaceTidyWiresAcceptanceTest}'s, with its
   * pins flush against the origin like the MCP tool's own example script used to place them. The
   * point is entirely behavioral: the same {@link Analyze#computeTable} check must still pass once
   * every component and wire has been rebuilt at a shifted location, and the components this
   * {@link Space} already handed out must still resolve to the same ids afterward. */
  @Test
  void fullAdderStillWorksAndKeepsItsComponentIdsAfterBeingShifted() {
    final var project = blankProject();
    final var space = Space.of(project);

    final var pin = Kind.of(space, "wiring/pin");
    final var andGate = Kind.of(space, "gates/and_gate");
    final var orGate = Kind.of(space, "gates/or_gate");
    final var xorGate = Kind.of(space, "gates/xor_gate");

    final var a = space.place(pin).anchorAt(0, 0).with(Attrs.of("type", "input")).place().label("A");
    final var b = space.place(pin).anchorAt(0, 8).with(Attrs.of("type", "input")).place().label("B");
    final var cin =
        space.place(pin).anchorAt(0, 16).with(Attrs.of("type", "input")).place().label("Cin");

    final var xor1 = space.place(xorGate).anchorAt(8, 4).place();
    final var and1 = space.place(andGate).anchorAt(8, 12).place();
    final var xor2 = space.place(xorGate).anchorAt(16, 4).place();
    final var and2 = space.place(andGate).anchorAt(16, 20).place();
    final var or1 = space.place(orGate).anchorAt(24, 16).place();

    final var sum =
        space.place(pin).anchorAt(32, 4).with(Attrs.of("type", "output")).place().label("Sum");
    final var cout =
        space.place(pin).anchorAt(32, 16).with(Attrs.of("type", "output")).place().label("Cout");

    space.connect(a.outputs().get(0), xor1.inputs().get(0));
    space.connect(b.outputs().get(0), xor1.inputs().get(1));
    space.connect(a.outputs().get(0), and1.inputs().get(0));
    space.connect(b.outputs().get(0), and1.inputs().get(1));

    space.connect(xor1.outputs().get(0), xor2.inputs().get(0));
    space.connect(cin.outputs().get(0), xor2.inputs().get(1));
    space.connect(xor1.outputs().get(0), and2.inputs().get(0));
    space.connect(cin.outputs().get(0), and2.inputs().get(1));

    space.connect(and1.outputs().get(0), or1.inputs().get(0));
    space.connect(and2.outputs().get(0), or1.inputs().get(1));

    space.connect(xor2.outputs().get(0), sum.inputs().get(0));
    space.connect(or1.outputs().get(0), cout.inputs().get(0));

    space.commit("build full adder");
    assertFalse(space.isDirty());

    final var aId = a.id();
    final var bId = b.id();
    final var cinId = cin.id();
    final var sumId = sum.id();
    final var coutId = cout.id();

    assertTrue(space.ensureAwayFromOrigin(20), "expected the flush-against-the-origin pins to force a shift");

    final var bounds = project.getCurrentCircuit().getBounds();
    assertTrue(bounds.getX() >= 20, "expected bounds.x >= margin, was " + bounds.getX());
    assertTrue(bounds.getY() >= 20, "expected bounds.y >= margin, was " + bounds.getY());

    assertEquals(aId, space.byId(aId).orElseThrow().id());
    assertEquals(bId, space.byId(bId).orElseThrow().id());
    assertEquals(cinId, space.byId(cinId).orElseThrow().id());
    assertEquals(sumId, space.byId(sumId).orElseThrow().id());
    assertEquals(coutId, space.byId(coutId).orElseThrow().id());

    final Map<Instance, String> labels = new LinkedHashMap<>();
    labels.put(Instance.getInstanceFor(space.byId(aId).orElseThrow().rawComponent()), "A");
    labels.put(Instance.getInstanceFor(space.byId(bId).orElseThrow().rawComponent()), "B");
    labels.put(Instance.getInstanceFor(space.byId(cinId).orElseThrow().rawComponent()), "Cin");
    labels.put(Instance.getInstanceFor(space.byId(sumId).orElseThrow().rawComponent()), "Sum");
    labels.put(Instance.getInstanceFor(space.byId(coutId).orElseThrow().rawComponent()), "Cout");

    final var model = new AnalyzerModel();
    Analyze.computeTable(model, project, project.getCurrentCircuit(), labels);
    final var table = model.getTruthTable();
    final var inputBits = model.getInputs().bits;
    final var outputBits = model.getOutputs().bits;
    final var aCol = inputBits.indexOf("A");
    final var bCol = inputBits.indexOf("B");
    final var cinCol = inputBits.indexOf("Cin");
    final var sumCol = outputBits.indexOf("Sum");
    final var coutCol = outputBits.indexOf("Cout");

    for (var row = 0; row < table.getRowCount(); row++) {
      final var inputCount = table.getInputColumnCount();
      final var av = TruthTable.isInputSet(row, aCol, inputCount);
      final var bv = TruthTable.isInputSet(row, bCol, inputCount);
      final var cv = TruthTable.isInputSet(row, cinCol, inputCount);
      final var sum3 = (av ? 1 : 0) + (bv ? 1 : 0) + (cv ? 1 : 0);
      assertEquals(sum3 % 2 == 1 ? Entry.ONE : Entry.ZERO, table.getOutputEntry(row, sumCol),
          "Sum wrong at row " + row);
      assertEquals(sum3 >= 2 ? Entry.ONE : Entry.ZERO, table.getOutputEntry(row, coutCol),
          "Cout wrong at row " + row);
    }
  }
}
