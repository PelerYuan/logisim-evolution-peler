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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.circuit.WireTidier;
import com.cburch.logisim.data.Location;
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

  /**
   * The motivating scenario for the automatic post-eval hook (see {@code
   * McpScriptTools#autoTidyAndFit}): one {@code eval} call wires A directly to C, a later,
   * separate {@code eval} call then places a component that lands squarely in the path of that
   * already-committed wiring -- {@code commit()}'s own router only ever routes the nets *it* was
   * asked to draw, so it has no reason to touch a wire from an earlier call, exactly the
   * across-session staleness {@link WireTidier} exists to fix. Neither script calls
   * {@code space:tidyWires()} itself; if the automatic hook did not fire after the second
   * {@code eval}, the A-C wiring would still be sitting exactly where the first eval() left it,
   * cutting through the new component's bounding box.
   *
   * <p>This does not assert on the exact wire shape the first eval() leaves behind: the automatic
   * hook already runs after that first call too, and {@link WireTidier}'s own routing of even a
   * trivial, obstacle-free two-pin net does not necessarily keep it as a single straight segment
   * along the pins' own row (a pre-existing WireTidier characteristic, unrelated to this feature).
   * Instead this captures the actual member wires of the A-C bundle right after the first eval(),
   * then asserts that set is different after the second eval() -- proving the automatic hook
   * reacted to the newly-placed obstacle rather than leaving stale wiring untouched -- while still
   * confirming A and C remain connected and the circuit ends up fully converged.
   */
  @Test
  void evalAutomaticallyTidiesStaleWiringLeftByAnEarlierEvalCallWithNoExplicitTidyWiresCall() {
    final var buildDirectWire = args();
    buildDirectWire.addProperty(
        "script",
        "local a = space:place(\"wiring/pin\"):anchorAt(0,0):with({type=\"input\"}):place()\n"
            + "a:setLabel(\"A\")\n"
            + "local c = space:place(\"wiring/pin\"):anchorAt(40,0):with({type=\"output\"}):place()\n"
            + "c:setLabel(\"C\")\n"
            + "space:connect(a:outputs()[1], c:inputs()[1])\n"
            + "space:commit(\"build A-C direct wire\")\n"
            + "return \"ok\"");
    assertEquals("ok", call("eval", buildDirectWire, 8).get("result").getAsString());

    // A is placed at (0,0), which the automatic hook's ensureAwayFromOrigin() step (see
    // McpScriptTools#autoTidyAndFit) is expected to nudge away from the axes -- so A and C's actual
    // post-eval locations are looked up by label rather than assumed, exactly the scenario this
    // fixture exists to exercise.
    final var circuit = project.getCurrentCircuit();
    final var aLoc = locationOfLabeled("A");
    final var cLoc = locationOfLabeled("C");
    final var wireAtABefore = circuit.getWires().stream()
        .filter(w -> w.getEnd0().equals(aLoc) || w.getEnd1().equals(aLoc))
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "test fixture is wrong: A should have a wire after the first eval()"));
    final var bundleBefore = circuit.getWireSet(wireAtABefore);
    assertTrue(bundleBefore.containsLocation(cLoc),
        "test fixture is wrong: A and C should already be connected after the first eval()");
    final var acWiresBefore = circuit.getWires().stream()
        .filter(bundleBefore::containsWire)
        .collect(java.util.stream.Collectors.toSet());

    // ensureAwayFromOrigin() rounds its shift to the grid, so A's post-shift location is always an
    // exact multiple of 10 -- safe to convert back to the col/row units anchorAt() takes. The
    // obstacle fixture below is placed relative to that, at the same offsets the original,
    // origin-flush fixture used, so it still lands squarely in the path of the A-C wire regardless
    // of whatever the first eval()'s automatic hook shifted A and C by.
    final var aCol = aLoc.getX() / 10;
    final var aRow = aLoc.getY() / 10;
    final var placeObstacle = args();
    placeObstacle.addProperty(
        "script",
        String.format(
            "local obstacle = space:place(\"gates/and_gate\"):anchorAt(%d,%d):place()\n"
                + "local bin1 = space:place(\"wiring/pin\"):anchorAt(%d,%d):with({type=\"input\"}):place()\n"
                + "local bin2 = space:place(\"wiring/pin\"):anchorAt(%d,%d):with({type=\"input\"}):place()\n"
                + "local bout = space:place(\"wiring/pin\"):anchorAt(%d,%d):with({type=\"output\"}):place()\n"
                + "space:connect(bin1:outputs()[1], obstacle:inputs()[1])\n"
                + "space:connect(bin2:outputs()[1], obstacle:inputs()[2])\n"
                + "space:connect(obstacle:outputs()[1], bout:inputs()[1])\n"
                + "space:commit(\"build obstacle fixture\")\n"
                + "return \"ok\"",
            aCol + 20, aRow + 2,
            aCol + 10, aRow - 16,
            aCol + 10, aRow - 8,
            aCol + 30, aRow - 12));
    assertEquals("ok", call("eval", placeObstacle, 9).get("result").getAsString());

    // The second eval's own auto-tidy-and-fit pass can nudge the whole circuit again (the new
    // obstacle fixture reaches to negative rows of its own), so A and C's locations are re-read
    // rather than reusing the values captured after the first eval.
    final var aLocAfter = locationOfLabeled("A");
    final var cLocAfter = locationOfLabeled("C");
    final var wireAtA = circuit.getWires().stream()
        .filter(w -> w.getEnd0().equals(aLocAfter) || w.getEnd1().equals(aLocAfter))
        .findFirst()
        .orElseThrow(() -> new AssertionError("A should still have a wire after tidying"));
    final var bundleAfter = circuit.getWireSet(wireAtA);
    assertTrue(bundleAfter.containsLocation(cLocAfter),
        "A and C should still be electrically connected after the automatic tidy pass");
    final var acWiresAfter = circuit.getWires().stream()
        .filter(bundleAfter::containsWire)
        .collect(java.util.stream.Collectors.toSet());
    assertFalse(acWiresBefore.equals(acWiresAfter),
        "placing a component directly in the path of the A-C net's existing wiring should have "
            + "triggered a real reroute -- if the automatic post-eval tidy pass hadn't fired, this "
            + "wiring would be untouched by the second eval(), which only routes its own new nets");

    assertNull(WireTidier.buildTidyMutation(circuit),
        "circuit should already be in fully-tidied form immediately after eval() returns, with no "
            + "explicit space:tidyWires() call in either script");
  }

  /** Finds the current {@link Location} of the component labeled {@code label} in {@link
   * #project}'s current circuit -- used instead of a hardcoded {@link Location} wherever the
   * automatic post-eval hook's {@code ensureAwayFromOrigin} step might have moved a component since
   * it was placed. */
  private Location locationOfLabeled(String label) {
    for (final var comp : project.getCurrentCircuit().getComponents()) {
      final var attr = comp.getAttributeSet().getAttribute("label");
      if (attr == null) continue;
      @SuppressWarnings("unchecked")
      final var typed = (com.cburch.logisim.data.Attribute<Object>) attr;
      if (label.equals(comp.getAttributeSet().getValue(typed))) {
        return comp.getLocation();
      }
    }
    throw new AssertionError("no component labeled " + label + " in the current circuit");
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
