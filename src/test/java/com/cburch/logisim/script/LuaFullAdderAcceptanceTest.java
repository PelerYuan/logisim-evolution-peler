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
 * P2 acceptance criterion from the design doc, section 十一: reproduce P1's full adder in Lua
 * instead of Java, and check it against {@link Analyze#computeTable} the same way. Also covers
 * "Java exceptions cross the boundary with fields intact" by triggering a
 * {@link com.cburch.logisim.dsl.PortDirectionException} from a script and reading it back out of
 * the {@link ScriptException} the sandbox throws.
 */
public class LuaFullAdderAcceptanceTest {

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  private static final String FULL_ADDER_SCRIPT = """
      local a = space:place("wiring/pin"):anchorAt(0, 0):with({type = "input"}):place()
      a:setLabel("A")
      local b = space:place("wiring/pin"):anchorAt(0, 8):with({type = "input"}):place()
      b:setLabel("B")
      local cin = space:place("wiring/pin"):anchorAt(0, 16):with({type = "input"}):place()
      cin:setLabel("Cin")

      local xor1 = space:place("gates/xor_gate"):anchorAt(8, 4):place()
      local and1 = space:place("gates/and_gate"):anchorAt(8, 12):place()
      local xor2 = space:place("gates/xor_gate"):anchorAt(16, 4):place()
      local and2 = space:place("gates/and_gate"):anchorAt(16, 20):place()
      local or1 = space:place("gates/or_gate"):anchorAt(24, 16):place()

      local sum = space:place("wiring/pin"):anchorAt(32, 4):with({type = "output"}):place()
      sum:setLabel("Sum")
      local cout = space:place("wiring/pin"):anchorAt(32, 16):with({type = "output"}):place()
      cout:setLabel("Cout")

      space:connect(a:outputs()[1], xor1:inputs()[1])
      space:connect(b:outputs()[1], xor1:inputs()[2])
      space:connect(a:outputs()[1], and1:inputs()[1])
      space:connect(b:outputs()[1], and1:inputs()[2])

      space:connect(xor1:outputs()[1], xor2:inputs()[1])
      space:connect(cin:outputs()[1], xor2:inputs()[2])
      space:connect(xor1:outputs()[1], and2:inputs()[1])
      space:connect(cin:outputs()[1], and2:inputs()[2])

      space:connect(and1:outputs()[1], or1:inputs()[1])
      space:connect(and2:outputs()[1], or1:inputs()[2])

      space:connect(xor2:outputs()[1], sum:inputs()[1])
      space:connect(or1:outputs()[1], cout:inputs()[1])

      space:commit("build full adder via Lua")
      return "ok"
      """;

  @Test
  public void testFullAdderBuiltInLuaMatchesTheJavaTruthTable() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var sandbox = new LuaSandbox(space);

    assertEquals("ok", sandbox.eval(FULL_ADDER_SCRIPT));

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
  public void testDslExceptionCrossesBackIntoJavaWithFieldsIntact() {
    final var space = Space.of(blankProject());
    final var sandbox = new LuaSandbox(space);

    final var script = """
        local p1 = space:place("wiring/pin"):anchorAt(0, 0):with({type = "input"}):place()
        local p2 = space:place("wiring/pin"):anchorAt(0, 4):with({type = "input"}):place()
        space:connect(p1:outputs()[1], p2:outputs()[1])
        """;

    final var ex = assertThrows(ScriptException.class, () -> sandbox.eval(script));
    assertEquals("PortDirectionException", ex.type());
    assertTrue(!ex.details().isEmpty(), "structured details must survive the Lua round trip");
  }
}
