/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The P3 acceptance criterion from the design doc, section 十一/13.5: building a standard full
 * adder through the new {@code eval}/{@code describe}/{@code reset} tool surface must take at most
 * two {@code eval()} calls and at most a fixed, small number of response bytes -- pinned into
 * {@code ./gradlew check} instead of the one-off manual measurement section 十一 originally asked
 * for, so a later change cannot quietly push the call count or the token cost back up without the
 * build noticing (see design doc 13.5 for why this superseded the manual comparison).
 */
class McpEfficiencyRegressionTest {
  /** Generous headroom over the ~150-byte response actually observed for the example script's
   * single eval() call -- enough to absorb reasonable growth in the DSL's own JSON shapes without
   * being so loose that the regression it exists to catch could sneak through unnoticed. */
  private static final int MAX_TOTAL_RESPONSE_BYTES = 1024;
  private static final int MAX_EVAL_CALLS = 2;

  private Project project;
  private List<Project> openProjects;
  private McpModelExecutor executor;
  private McpProjectRegistry registry;
  private McpScriptTools tools;
  private McpJsonRpcDispatcher dispatcher;

  @BeforeEach
  void setUp() throws Exception {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    openProjects = mutableOpenProjects();
    openProjects.add(project);

    executor = new McpModelExecutor();
    registry = new McpProjectRegistry();
    registry.register(project);
    tools = new McpScriptTools(executor, registry);
    dispatcher = new McpJsonRpcDispatcher("test", "1", null);
    tools.registerTools(dispatcher);
  }

  @AfterEach
  void tearDown() {
    if (tools != null) tools.close();
    if (executor != null) executor.close();
    if (project != null) project.getSimulator().shutDown();
    if (openProjects != null) openProjects.remove(project);
  }

  @Test
  void buildingAStandardFullAdderTakesAtMostTwoEvalCallsAndStaysUnderTheByteBudget() {
    final var args = new JsonObject();
    args.addProperty("projectId", registry.projectId(project));
    args.addProperty("script", McpScriptTools.FULL_ADDER_EXAMPLE);

    var evalCalls = 0;
    var totalBytes = 0;

    final var request = new JsonObject();
    request.addProperty("jsonrpc", "2.0");
    request.addProperty("id", 1);
    request.addProperty("method", "tools/call");
    final var params = new JsonObject();
    params.addProperty("name", "eval");
    params.add("arguments", args);
    request.add("params", params);

    final var response = dispatcher.dispatch(request);
    evalCalls++;
    totalBytes += response.toString().getBytes(StandardCharsets.UTF_8).length;

    assertFalse(response.has("error"), response.toString());
    assertEquals(
        "ok", response.getAsJsonObject("result").getAsJsonObject("structuredContent")
            .get("result").getAsString());

    assertTrue(
        evalCalls <= MAX_EVAL_CALLS,
        "building a standard full adder must take at most " + MAX_EVAL_CALLS + " eval() calls, took "
            + evalCalls);
    assertTrue(
        totalBytes <= MAX_TOTAL_RESPONSE_BYTES,
        "total eval() response bytes must stay under " + MAX_TOTAL_RESPONSE_BYTES + ", was "
            + totalBytes);

    assertCircuitIsACorrectFullAdder();
  }

  private void assertCircuitIsACorrectFullAdder() {
    final var circuit = project.getCurrentCircuit();
    final Map<Instance, String> pinLabels = new LinkedHashMap<>();
    for (final var component : circuit.getComponents()) {
      final AttributeSet attrs = component.getAttributeSet();
      if (!attrs.containsAttribute(StdAttr.LABEL)) continue;
      final var label = attrs.getValue(StdAttr.LABEL);
      if (label != null && !label.isEmpty()) pinLabels.put(Instance.getInstanceFor(component), label);
    }
    assertEquals(5, pinLabels.size());

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

  @SuppressWarnings("unchecked")
  private static List<Project> mutableOpenProjects() throws ReflectiveOperationException {
    final Field field = com.cburch.logisim.proj.Projects.class.getDeclaredField("openProjects");
    field.setAccessible(true);
    return (ArrayList<Project>) field.get(null);
  }
}
