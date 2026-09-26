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

import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.proj.Project;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the P3 tool surface from the design doc, section 九: {@code eval}, {@code describe} and
 * {@code reset}. Uses a blank, Frame-less project exactly like {@code DslFullAdderAcceptanceTest}
 * and {@code LuaFullAdderAcceptanceTest} rather than the {@code McpProjectLifecycleToolsTest}
 * fixture: constructing a {@code javax.swing.JFrame} throws under this module's headless test task
 * (see that class's header comment), and none of these tests need one.
 */
class McpScriptToolsTest {
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
  void toolsListExposesExactlyEvalDescribeAndReset() {
    final var request = new JsonObject();
    request.addProperty("jsonrpc", "2.0");
    request.addProperty("id", 0);
    request.addProperty("method", "tools/list");
    final var response = dispatcher.dispatch(request);
    final var names =
        response.getAsJsonObject("result").getAsJsonArray("tools").asList().stream()
            .map(tool -> tool.getAsJsonObject().get("name").getAsString())
            .collect(Collectors.toSet());
    assertEquals(Set.of("eval", "describe", "reset"), names);
  }

  @Test
  void describeReturnsACopyEditableExampleAndTheCurrentSummary() {
    final var result = call("describe", args(), 1);
    assertTrue(result.get("example").getAsString().contains("space:place"));
    final var summary = result.getAsJsonObject("summary");
    assertEquals(0, summary.get("inputPins").getAsInt());
    assertEquals(0, summary.get("outputPins").getAsInt());
    assertTrue(summary.has("circuitName"));
  }

  @Test
  void evalRunsAScriptAgainstTheRealCircuitAndReturnsItsResult() {
    final var args = args();
    args.addProperty("script", "space:place(\"wiring/pin\"):anchorAt(0,0):place()\nreturn \"placed\"");
    final var result = call("eval", args, 2);
    assertEquals("placed", result.get("result").getAsString());
  }

  @Test
  void evalSurfacesAStructuredDslExceptionAsAScriptError() {
    final var args = args();
    args.addProperty(
        "script",
        "local p1 = space:place(\"wiring/pin\"):anchorAt(0,0):with({type=\"input\"}):place()\n"
            + "local p2 = space:place(\"wiring/pin\"):anchorAt(0,4):with({type=\"input\"}):place()\n"
            + "space:connect(p1:outputs()[1], p2:outputs()[1])");
    final var response = callRaw("eval", args, 3);
    final var error = response.getAsJsonObject("error");
    assertEquals(-32010, error.get("code").getAsInt());
    final var data = error.getAsJsonObject("data");
    assertEquals("PortDirectionException", data.get("type").getAsString());
    assertFalse(data.getAsJsonObject("details").entrySet().isEmpty());
  }

  @Test
  void evalReusesTheSessionUntilResetIsCalled() {
    final var place = args();
    place.addProperty("script", "space:place(\"wiring/pin\"):anchorAt(0,0):place()\nreturn \"ok\"");
    call("eval", place, 4);

    final var countArgs = args();
    countArgs.addProperty("script", "return tostring(#space:components())");
    assertEquals("1", call("eval", countArgs, 5).get("result").getAsString(),
        "an uncommitted placement should still be visible from a later eval() in the same session");

    call("reset", args(), 6);
    assertEquals("0", call("eval", countArgs, 7).get("result").getAsString(),
        "reset() must force a fresh session, discarding uncommitted state");
  }

  private JsonObject args() {
    final var args = new JsonObject();
    args.addProperty("projectId", registry.projectId(project));
    return args;
  }

  private JsonObject call(String method, JsonObject arguments, int id) {
    final var response = callRaw(method, arguments, id);
    assertFalse(response.has("error"), response.toString());
    return response.getAsJsonObject("result").getAsJsonObject("structuredContent");
  }

  private JsonObject callRaw(String method, JsonObject arguments, int id) {
    final var request = new JsonObject();
    request.addProperty("jsonrpc", "2.0");
    request.addProperty("id", id);
    request.addProperty("method", "tools/call");
    final var params = new JsonObject();
    params.addProperty("name", method);
    params.add("arguments", arguments);
    request.add("params", params);
    return dispatcher.dispatch(request);
  }

  @SuppressWarnings("unchecked")
  private static List<Project> mutableOpenProjects() throws ReflectiveOperationException {
    final Field field = com.cburch.logisim.proj.Projects.class.getDeclaredField("openProjects");
    field.setAccessible(true);
    return (ArrayList<Project>) field.get(null);
  }
}
