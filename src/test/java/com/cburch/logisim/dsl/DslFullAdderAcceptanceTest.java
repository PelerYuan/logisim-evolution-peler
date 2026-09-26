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

import com.cburch.logisim.analyze.model.AnalyzerModel;
import com.cburch.logisim.analyze.model.Entry;
import com.cburch.logisim.circuit.Analyze;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.proj.Project;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The P1 acceptance criterion from the design doc, section 十四: build a full adder purely through
 * {@link Space}/{@link Kind}/{@link Port}/{@link Net}, commit it, and check it against
 * {@link Analyze#computeTable} the same way a hand-drawn circuit would be checked -- plus the three
 * failure modes the layering rule (design doc, section 二) says must be caught in Java, not left to
 * silently produce a wrong circuit.
 */
public class DslFullAdderAcceptanceTest {

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  @Test
  public void testFullAdderTruthTableAndExactlyOneUndoEntry() {
    final var project = blankProject();
    final var space = Space.of(project);

    final var pin = Kind.of(space, "wiring/pin");
    final var andGate = Kind.of(space, "gates/and_gate");
    final var orGate = Kind.of(space, "gates/or_gate");
    final var xorGate = Kind.of(space, "gates/xor_gate");

    final var a = space.place(pin).anchorAt(0, 0).with(Attrs.of("type", "input")).place().label("A");
    final var b = space.place(pin).anchorAt(0, 8).with(Attrs.of("type", "input")).place().label("B");
    final var cin = space.place(pin).anchorAt(0, 16).with(Attrs.of("type", "input")).place().label("Cin");

    final var xor1 = space.place(xorGate).anchorAt(8, 4).place();
    final var and1 = space.place(andGate).anchorAt(8, 12).place();
    final var xor2 = space.place(xorGate).anchorAt(16, 4).place();
    final var and2 = space.place(andGate).anchorAt(16, 20).place();
    final var or1 = space.place(orGate).anchorAt(24, 16).place();

    final var sum = space.place(pin).anchorAt(32, 4).with(Attrs.of("type", "output")).place().label("Sum");
    final var cout = space.place(pin).anchorAt(32, 16).with(Attrs.of("type", "output")).place().label("Cout");

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

    assertTrue(space.check().ok(), "full adder should have no unconnected/undriven/multiply-driven ports");

    final var undoSizeBefore = project.getUndoActions().size();
    final var result = space.commit("build full adder");
    assertEquals(undoSizeBefore + 1, project.getUndoActions().size(),
        "commit must add exactly one undo-log entry, not one per component/wire");
    assertEquals(10, result.placed().size());

    final Map<Instance, String> pinLabels = new LinkedHashMap<>();
    pinLabels.put(Instance.getInstanceFor(a.rawComponent()), "A");
    pinLabels.put(Instance.getInstanceFor(b.rawComponent()), "B");
    pinLabels.put(Instance.getInstanceFor(cin.rawComponent()), "Cin");
    pinLabels.put(Instance.getInstanceFor(sum.rawComponent()), "Sum");
    pinLabels.put(Instance.getInstanceFor(cout.rawComponent()), "Cout");

    final var model = new AnalyzerModel();
    Analyze.computeTable(model, project, space.circuit(), pinLabels);
    final var table = model.getTruthTable();

    assertEquals(8, table.getRowCount());
    final var inputs = table.getInputColumnCount();
    var sumCol = -1;
    var coutCol = -1;
    final var outputBits = model.getOutputs().bits;
    for (var c = 0; c < table.getOutputColumnCount(); c++) {
      final var name = outputBits.get(c);
      if (name.equals("Sum")) sumCol = c;
      if (name.equals("Cout")) coutCol = c;
    }
    assertTrue(sumCol >= 0 && coutCol >= 0, "expected both Sum and Cout output columns");

    var aCol = -1;
    var bCol = -1;
    var cinCol = -1;
    final var inputBits = model.getInputs().bits;
    for (var c = 0; c < inputs; c++) {
      final var name = inputBits.get(c);
      if (name.equals("A")) aCol = c;
      if (name.equals("B")) bCol = c;
      if (name.equals("Cin")) cinCol = c;
    }
    assertTrue(aCol >= 0 && bCol >= 0 && cinCol >= 0, "expected A, B and Cin input columns");

    for (var row = 0; row < 8; row++) {
      final var av = com.cburch.logisim.analyze.model.TruthTable.isInputSet(row, aCol, inputs);
      final var bv = com.cburch.logisim.analyze.model.TruthTable.isInputSet(row, bCol, inputs);
      final var cv = com.cburch.logisim.analyze.model.TruthTable.isInputSet(row, cinCol, inputs);
      final var expectedSum = av ^ bv ^ cv;
      final var expectedCout = (av && bv) || (cv && (av ^ bv));
      assertEquals(expectedSum ? Entry.ONE : Entry.ZERO, table.getOutputEntry(row, sumCol),
          "Sum wrong for A=" + av + " B=" + bv + " Cin=" + cv);
      assertEquals(expectedCout ? Entry.ONE : Entry.ZERO, table.getOutputEntry(row, coutCol),
          "Cout wrong for A=" + av + " B=" + bv + " Cin=" + cv);
    }
  }

  @Test
  public void testConnectingTwoOutputsThrowsPortDirectionException() {
    final var space = Space.of(blankProject());
    final var pin = Kind.of(space, "wiring/pin");
    final var p1 = space.place(pin).anchorAt(0, 0).with(Attrs.of("type", "input")).place();
    final var p2 = space.place(pin).anchorAt(0, 4).with(Attrs.of("type", "input")).place();

    assertThrows(PortDirectionException.class,
        () -> space.connect(p1.outputs().get(0), p2.outputs().get(0)));
  }

  @Test
  public void testConnectingMismatchedWidthsThrowsWidthMismatchException() {
    final var space = Space.of(blankProject());
    final var pin = Kind.of(space, "wiring/pin");
    final var narrow = space.place(pin).anchorAt(0, 0).with(Attrs.of("type", "input")).place();
    final var wide = space.place(pin).anchorAt(0, 4)
        .with(Attrs.of("type", "output", "width", "2")).place();

    assertThrows(WidthMismatchException.class,
        () -> space.connect(narrow.outputs().get(0), wide.inputs().get(0)));
  }

  @Test
  public void testBlockedViaColumnHintThrowsRoutingExceptionInsteadOfSilentlyRerouting() {
    final var space = Space.of(blankProject());
    final var pin = Kind.of(space, "wiring/pin");
    final var driver = space.place(pin).anchorAt(0, 0).with(Attrs.of("type", "input")).place();
    final var target = space.place(pin).anchorAt(12, 12).with(Attrs.of("type", "output")).place();
    space.place(pin).anchorAt(6, 6).with(Attrs.of("type", "input")).place();

    final var net = space.connect(driver.outputs().get(0), target.inputs().get(0));
    net.viaColumn(6);

    assertThrows(RoutingException.class, () -> space.commit("blocked via column"));
  }
}
