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
import com.cburch.logisim.analyze.model.TruthTable;
import com.cburch.logisim.circuit.Analyze;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The P5 declarative generation layer (design doc, section 十一): {@link Space#synthesize} builds
 * an entire circuit from a truth-table-style spec via {@code CircuitBuilder} instead of the
 * place()/connect() operation layer -- exercised here the same way P1's acceptance test exercises
 * the operation layer, by checking the result against {@link Analyze#computeTable}, not just
 * "no exception was thrown".
 */
public class SynthesisAcceptanceTest {

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  @Test
  public void testSynthesizedFullAdderMatchesTheJavaTruthTable() {
    final var project = blankProject();
    final var space = Space.of(project);

    final var undoSizeBefore = project.getUndoActions().size();
    final var result = space.synthesize(
        Synthesis.of()
            .input("A")
            .input("B")
            .input("Cin")
            .output("Sum", "A xor B xor Cin")
            .output("Cout", "(A and B) or (Cin and (A xor B))"));
    assertEquals(undoSizeBefore + 1, project.getUndoActions().size(),
        "synthesize must add exactly one undo-log entry");

    // The same Space that ran synthesize() must already see the result -- no fresh Space.of()
    // round trip required (design doc 13.5: minimize the round trips an AI model needs).
    assertTrue(space.check().ok());
    assertEquals(result.placed().size(), space.components().size());

    final Map<Instance, String> pinLabels = new LinkedHashMap<>();
    for (final var component : space.circuit().getComponents()) {
      final AttributeSet attrs = component.getAttributeSet();
      if (!attrs.containsAttribute(StdAttr.LABEL)) continue;
      final var label = attrs.getValue(StdAttr.LABEL);
      if (label != null && !label.isEmpty()) pinLabels.put(Instance.getInstanceFor(component), label);
    }
    assertEquals(5, pinLabels.size());

    final var model = new AnalyzerModel();
    Analyze.computeTable(model, project, space.circuit(), pinLabels);
    final var table = model.getTruthTable();
    assertEquals(8, table.getRowCount());

    final var inputBits = model.getInputs().bits;
    final var outputBits = model.getOutputs().bits;
    final var aCol = inputBits.indexOf("A");
    final var bCol = inputBits.indexOf("B");
    final var cinCol = inputBits.indexOf("Cin");
    final var sumCol = outputBits.indexOf("Sum");
    final var coutCol = outputBits.indexOf("Cout");
    assertTrue(aCol >= 0 && bCol >= 0 && cinCol >= 0 && sumCol >= 0 && coutCol >= 0);

    final var inputs = table.getInputColumnCount();
    for (var row = 0; row < 8; row++) {
      final var av = TruthTable.isInputSet(row, aCol, inputs);
      final var bv = TruthTable.isInputSet(row, bCol, inputs);
      final var cv = TruthTable.isInputSet(row, cinCol, inputs);
      final var expectedSum = av ^ bv ^ cv;
      final var expectedCout = (av && bv) || (cv && (av ^ bv));
      assertEquals(expectedSum ? Entry.ONE : Entry.ZERO, table.getOutputEntry(row, sumCol));
      assertEquals(expectedCout ? Entry.ONE : Entry.ZERO, table.getOutputEntry(row, coutCol));
    }
  }

  @Test
  public void testSynthesizeRejectsAReferenceToAnUndeclaredInput() {
    final var space = Space.of(blankProject());
    final var exception = assertThrows(ExpressionSyntaxException.class,
        () -> space.synthesize(Synthesis.of().input("A").output("Y", "A and Nope")));
    assertEquals("Y", exception.details().get("output"));
    assertEquals("A and Nope", exception.details().get("expression"));
  }

  @Test
  public void testSynthesizeRefusesANonEmptyCircuit() {
    final var space = Space.of(blankProject());
    final var pin = Kind.of(space, "wiring/pin");
    space.place(pin).anchorAt(0, 0).with(Attrs.of("type", "input")).place();
    space.commit("place a pin first");

    assertThrows(NonEmptyCircuitException.class,
        () -> space.synthesize(Synthesis.of().input("A").output("Y", "A")));
  }
}
