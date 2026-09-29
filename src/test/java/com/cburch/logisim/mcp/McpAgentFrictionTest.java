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
class McpAgentFrictionTest {
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


  private String run(String circuit, String script) {
    final var a = args();
    a.addProperty("script", script);
    if (circuit != null) a.addProperty("circuit", circuit);
    final var r = callRaw("eval", a, ++n);
    if (r.has("error")) throw new AssertionError(r.get("error").toString());
    return r.getAsJsonObject("result").getAsJsonObject("structuredContent").get("result").getAsString();
  }

  private String failure(String circuit, String script) {
    final var a = args();
    a.addProperty("script", script);
    if (circuit != null) a.addProperty("circuit", circuit);
    final var r = callRaw("eval", a, ++n);
    assertTrue(r.has("error"), "expected an error");
    return r.get("error").toString();
  }

  private int n = 100;

  private JsonObject args() {
    final var args = new JsonObject();
    args.addProperty("projectId", registry.projectId(project));
    return args;
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

  @Test
  void theReturnedTableComesBackAsJsonAndAStringStaysRaw() {
    assertEquals("[1,2,3]", run(null, "return {1,2,3}"));
    assertEquals("{\"a\":1,\"b\":\"x\"}", run(null, "return {a=1,b='x'}"));
    assertEquals("plain 00", run(null, "return 'plain 00'"));
    assertEquals("7", run(null, "return 7"));
  }

  @Test
  void kindsAndDescribeKindTellAScriptWhatItCanPlace() {
    assertTrue(run(null, "return space:kinds('flip')").contains("Memory/D Flip-Flop"));
    final var described = run(null, "return space:describeKind('Memory/D Flip-Flop')");
    assertTrue(described.contains("\"trigger\""), described);
    assertTrue(described.contains("rising"), described);
    assertTrue(described.contains("inputs()[2]"), described);
    assertTrue(described.contains("Clock"), described);
  }

  @Test
  void theWrongAttributeNameOrValueIsAStructuredErrorThatListsWhatIsValid() {
    final var unknown = failure(null,
        "space:place('gates/and_gate'):anchorAt(20,20):with({bogus=3}):place()");
    assertTrue(unknown.contains("UnknownAttributeException"), unknown);
    assertTrue(unknown.contains("inputs"), unknown);
    final var badValue = failure(null,
        "space:place('gates/and_gate'):anchorAt(20,20):with({facing='up'}):place()");
    assertTrue(badValue.contains("InvalidAttributeValueException"), badValue);
    assertTrue(badValue.contains("north"), badValue);
  }

  @Test
  void attributesApplyInAnyOrderAConstantKeepsItsValueWhateverTheWidthOrder() {
    assertEquals("[1,1]", run(null,
        "local a = space:place('Wiring/Constant'):anchorAt(20,20):with({value=1, width=4}):place()\n"
            + "local b = space:place('Wiring/Constant'):anchorAt(20,40):with({width=4, value=1}):place()\n"
            + "return {tonumber(a:attrs().value), tonumber(b:attrs().value)}"));
  }

  @Test
  void theCircuitCannotBeNamedLikeABuiltInComponent() {
    final var refused = failure(null, "circuits:create('Counter')");
    assertTrue(refused.contains("InvalidCircuitNameException"), refused);
    assertTrue(refused.contains("Memory/Counter"), refused);
  }

  @Test
  void theNumericPortIndexIsAnIndexNotALabel() {
    assertEquals("ok", run(null,
        "local s = space:place('Wiring/Splitter'):anchorAt(30,30):with({fanout=4, incoming=4}):place()\n"
            + "s:port(0); s:port(1)\nreturn 'ok'"));
  }

  @Test
  void theBusPinCanFeedASplitterAndTheSplitBitsReadBack() {
    run(null,
        "local a = space:place('wiring/pin'):anchorAt(10,10):with({type='input', width=4, label='A'}):place()\n"
            + "local sp = space:place('Wiring/Splitter'):anchorAt(30,10):with({fanout=4, incoming=4}):place()\n"
            + "local o = space:place('wiring/pin'):anchorAt(60,5):with({type='output', label='O'}):place()\n"
            + "space:connect(a:outputs()[1], sp:port(0))\n"
            + "space:connect(sp:port(1), o:inputs()[1])\n"
            + "space:commit('bus')");
    assertEquals("1", run(null,
        "simulation:writePin('A', 1); return simulation:readPin('O').value"));
    assertEquals("0", run(null,
        "simulation:writePin('A', 2); return simulation:readPin('O').value"));
  }

  @Test
  void buildingAndSimulatingInOneEvalDoesNotStallOnTheEventThread() {
    run(null,
        "local a = space:place('wiring/pin'):anchorAt(10,10):with({type='input', label='A'}):place()\n"
            + "local y = space:place('wiring/pin'):anchorAt(30,10):with({type='output', label='Y'}):place()\n"
            + "space:connect(a:outputs()[1], y:inputs()[1])\n"
            + "space:commit('wire')");
    final var started = System.nanoTime();
    assertEquals("1", run(null,
        "local n = space:place('gates/not_gate'):anchorAt(60,10):place()\n"
            + "local a = space:place('wiring/pin'):anchorAt(40,30):with({type='input', label='B'}):place()\n"
            + "local y = space:place('wiring/pin'):anchorAt(80,10):with({type='output', label='Z'}):place()\n"
            + "space:connect(a:outputs()[1], n:inputs()[1])\n"
            + "space:connect(n:outputs()[1], y:inputs()[1])\n"
            + "space:commit('inverter')\n"
            + "simulation:writePin('B', 0)\n"
            + "return simulation:readPin('Z').value"));
    assertTrue(System.nanoTime() - started < 4_000_000_000L, "the simulator handshake timed out");
  }

  @Test
  void theCounterCountsFromItsVeryFirstClockEdge() {
    run(null,
        "local clk = space:place('Wiring/Clock'):anchorAt(10,10):place()\n"
            + "local reg = space:place('memory/register'):anchorAt(60,20):with({width=4}):place()\n"
            + "local add = space:place('Arithmetic/Adder'):anchorAt(30,20):with({width=4}):place()\n"
            + "local one = space:place('Wiring/Constant'):anchorAt(10,30):with({width=4, value=1}):place()\n"
            + "local q = space:place('wiring/pin'):anchorAt(80,20):with({type='output', width=4, label='Q'}):place()\n"
            + "space:connect(reg:outputs()[1], add:inputs()[1])\n"
            + "space:connect(one:outputs()[1], add:inputs()[2])\n"
            + "space:connect(add:outputs()[1], reg:inputs()[1])\n"
            + "space:connect(clk:outputs()[1], reg:inputs()[2])\n"
            + "space:connect(reg:outputs()[1], q:inputs()[1])\n"
            + "space:commit('counter')");
    assertEquals("[[\"0\"],[\"1\"],[\"2\"],[\"3\"]]",
        run(null, "return simulation:trace({'Q'}, 4, 2).rows"));
  }

  @Test
  void checkDoesNotFlagAnUnusedOutput() {
    assertTrue(run(null,
        "local a = space:place('wiring/pin'):anchorAt(10,10):with({type='input'}):place()\n"
            + "local add = space:place('Arithmetic/Adder'):anchorAt(40,20):with({width=1}):place()\n"
            + "local b = space:place('wiring/pin'):anchorAt(10,30):with({type='input'}):place()\n"
            + "space:connect(a:outputs()[1], add:inputs()[1])\n"
            + "space:connect(b:outputs()[1], add:inputs()[2])\n"
            + "space:commit('adder')\n"
            + "return space:check().unconnected").contains("Carry In"));
    assertFalse(run(null, "return space:check().unconnected").contains("Carry Out"));
  }

  @Test
  void theSubcircuitPlacedNextToAComponentLandsOnTheGridAndAWrongSpotExplainsItself() {
    run(null,
        "circuits:create('half')");
    run("half",
        "local a = space:place('wiring/pin'):anchorAt(10,10):with({type='input', label='A'}):place()\n"
            + "local y = space:place('wiring/pin'):anchorAt(30,10):with({type='output', label='Y'}):place()\n"
            + "space:connect(a:outputs()[1], y:inputs()[1])\n"
            + "space:commit('half')");
    final var next = run(null,
        "local p = space:place('wiring/pin'):anchorAt(10,10):with({type='input'}):place()\n"
            + "local h = space:place('circuit/half'):rightOf(p):place()\n"
            + "local at = h:origin()\n"
            + "return at:col() ~= nil and h:origin():onGrid()");
    assertEquals("true", next);
    final var collision = failure(null,
        "space:place('circuit/half'):anchorAt(12,10):place()");
    assertTrue(collision.contains("cols"), collision);
    assertTrue(collision.contains("rightOf"), collision);
  }

  @Test
  void describeReadsTheCircuitBackAsText() {
    final var text = run(null,
        "local a = space:place('wiring/pin'):anchorAt(10,10):with({type='input', label='A'}):place()\n"
            + "local n = space:place('gates/not_gate'):anchorAt(40,10):place()\n"
            + "space:connect(a:outputs()[1], n:inputs()[1])\n"
            + "space:commit('t')\n"
            + "return space:describe()");
    assertTrue(text.contains("wiring/pin \"A\""), text);
    assertTrue(text.contains("pin_0.out[1]"), text);
    assertTrue(text.contains("not_gate_0.in[1]"), text);
  }

  @Test
  void bitOperationsAreAvailableAndAMissingCircuitNamesTheOnesThatExist() {
    assertEquals("5", run(null, "return bit32.band(13, 7)"));
    final var missing = failure("nosuch", "return 1");
    assertTrue(missing.contains("UnknownCircuitException"), missing);
    assertTrue(missing.contains("main"), missing);
  }
}
