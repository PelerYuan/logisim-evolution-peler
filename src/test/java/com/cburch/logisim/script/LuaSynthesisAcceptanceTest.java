/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.analyze.model.AnalyzerModel;
import com.cburch.logisim.analyze.model.Entry;
import com.cburch.logisim.analyze.model.TruthTable;
import com.cburch.logisim.circuit.Analyze;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.dsl.Space;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The P5 declarative generation layer (design doc, section 十一), reached through Lua exactly the
 * way an MCP client would: a single {@code space:synthesize(spec)} call, not a hand-drawn
 * place()/connect() sequence. Checked against {@link Analyze#computeTable}, the same standard every
 * other phase's acceptance test in this repo holds itself to.
 */
public class LuaSynthesisAcceptanceTest {

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  private static final String FULL_ADDER_SCRIPT = """
      local result = space:synthesize({
        inputs = {"A", "B", "Cin"},
        outputs = {
          Sum = "A xor B xor Cin",
          Cout = "(A and B) or (Cin and (A xor B))",
        },
      })
      return tostring(#result.placed > 0)
      """;

  @Test
  public void testSynthesizedFullAdderMatchesTheJavaTruthTable() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var sandbox = new LuaSandbox(space);

    assertEquals("true", sandbox.eval(FULL_ADDER_SCRIPT), "synthesize must report the components it built");

    final var circuit = project.getCurrentCircuit();
    final Map<Instance, String> pinLabels = new LinkedHashMap<>();
    for (final var component : circuit.getComponents()) {
      final AttributeSet attrs = component.getAttributeSet();
      if (!attrs.containsAttribute(StdAttr.LABEL)) continue;
      final var label = attrs.getValue(StdAttr.LABEL);
      if (label != null && !label.isEmpty()) {
        pinLabels.put(Instance.getInstanceFor(component), label);
      }
    }
    assertEquals(5, pinLabels.size(), "expected exactly the five labeled pins (A, B, Cin, Sum, Cout)");

    final var model = new AnalyzerModel();
    Analyze.computeTable(model, project, circuit, pinLabels);
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
  public void testSynthesizeExceptionCrossesBackIntoJavaWithFieldsIntact() {
    final var space = Space.of(blankProject());
    final var sandbox = new LuaSandbox(space);

    final var script = """
        space:synthesize({inputs = {"A"}, outputs = {Y = "A and Nope"}})
        """;

    final var ex = assertThrows(ScriptException.class, () -> sandbox.eval(script));
    assertEquals("ExpressionSyntaxException", ex.type());
    assertTrue(!ex.details().isEmpty(), "structured details must survive the Lua round trip");
  }
}
