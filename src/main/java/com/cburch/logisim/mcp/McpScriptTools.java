/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import com.cburch.logisim.dsl.Space;
import com.cburch.logisim.mcp.McpJsonRpcDispatcher.McpRpcException;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.script.LuaSandbox;
import com.cburch.logisim.script.ScriptException;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;

/**
 * The three-tool MCP surface from the design doc, section 九: {@code eval}, {@code describe} and
 * {@code reset}, replacing the 47 flat tools torn down in P0 with a single entry point onto the
 * P1 Java DSL (package {@code com.cburch.logisim.dsl}) via the P2 Lua sandbox
 * ({@link com.cburch.logisim.script.LuaSandbox}).
 *
 * <p>A session is a {@link Space}/{@link LuaSandbox} pair cached per (project, circuit), created
 * lazily on first {@code eval} and torn down by {@code reset}. Per design doc section 13.1, this
 * is deliberately a performance detail and not a correctness one: a script's {@code local}
 * variables never survive past the single {@code eval} call they were declared in regardless of
 * whether the underlying sandbox is reused, so cross-turn addressing always goes through
 * {@code space:byId(...)} or {@code space:byLabel(...)}. Reusing the sandbox only saves the cost
 * of rebuilding it and lets a script's own top-level Lua functions/globals survive between calls;
 * {@code reset} exists for a caller that wants to discard that and start clean.
 */
final class McpScriptTools implements AutoCloseable {
  private final McpModelExecutor executor;
  private final McpProjectRegistry registry;
  private final Map<String, Session> sessions = new HashMap<>();

  McpScriptTools(McpModelExecutor executor, McpProjectRegistry registry) {
    this.executor = executor;
    this.registry = registry;
  }

  @Override
  public void close() {
    // Sessions hold no listeners or background threads (a Space is a plain object over a Circuit
    // already owned by the Project), so there is nothing to release beyond letting them be
    // collected. Kept as a real method for the same reason as McpProjectLifecycleTools.close():
    // McpServerManager's teardown path calls every tool class's close() unconditionally.
    sessions.clear();
  }

  void registerTools(McpJsonRpcDispatcher dispatcher) {
    dispatcher.registerTool(
        new McpToolDefinition("eval", EVAL_DESCRIPTION, evalSchema(), this::eval));
    dispatcher.registerTool(
        new McpToolDefinition(
            "describe",
            "Get a copy-and-edit example circuit plus a summary of the current circuit.",
            schema("projectId", "string", false, "circuit", "string", false),
            this::describe));
    dispatcher.registerTool(
        new McpToolDefinition(
            "reset",
            "Discard the cached Lua session for a circuit; the next eval starts clean.",
            schema("projectId", "string", false, "circuit", "string", false),
            this::reset));
  }

  private JsonObject eval(JsonObject args) throws Exception {
    return executor.call(
        () -> {
          final var session = session(args);
          session.streaming = booleanValue(args, "stream", false);
          final String result;
          try {
            result = session.sandbox.eval(required(args, "script"));
          } catch (ScriptException e) {
            final var data = new JsonObject();
            data.addProperty("type", e.type());
            final var details = new JsonObject();
            for (final var entry : e.details().entrySet()) {
              details.addProperty(entry.getKey(), String.valueOf(entry.getValue()));
            }
            data.add("details", details);
            e.suggestion().ifPresent(s -> data.addProperty("suggestion", s));
            throw new McpRpcException(-32010, e.getMessage(), data);
          }
          autoTidyAndFit(session);
          final var out = new JsonObject();
          out.addProperty("result", result);
          return out;
        });
  }

  /**
   * Runs after every successful {@code eval}, so a session never has to end with correct-but-
   * cluttered wiring or a view scrolled off whatever the script just placed: re-tidies the
   * circuit's wiring (space.tidyWires()'s own no-op case already keeps this a no-op when there was
   * nothing to fix -- see WireTidier's old-wires-equal-new-wires check) and, when a GUI window is
   * actually attached to this project (it may not be, e.g. under test), zooms/centers its Layout
   * canvas to fit the circuit's current bounds -- the same computation as the toolbar's "Auto" zoom
   * button. Skipped entirely, silently, when the script left something staged but uncommitted
   * ({@code space.isDirty()}): {@code tidyWires()} would throw for that, and it is a completely
   * ordinary mid-build state a later {@code eval} call in the same session is expected to finish.
   * Best-effort by design -- a problem in this convenience step must never turn an otherwise-
   * successful eval into a failed one, so any exception here is swallowed rather than surfaced.
   */
  private void autoTidyAndFit(Session session) {
    if (session.space.isDirty()) return;
    try {
      session.space.tidyWires();
      final var frame = session.project.getFrame();
      if (frame != null) frame.getCanvas().autoZoom(frame.getZoomModel());
    } catch (RuntimeException ignored) {
      // Best-effort convenience only -- see method Javadoc.
    }
  }

  private JsonObject describe(JsonObject args) throws Exception {
    return executor.call(
        () -> {
          final var project = requireProject(args);
          final var space = resolveSpace(project, optional(args, "circuit"));
          final var result = new JsonObject();
          result.addProperty("example", FULL_ADDER_EXAMPLE);
          result.add("summary", summaryOf(space));
          return result;
        });
  }

  private JsonObject reset(JsonObject args) throws Exception {
    return executor.call(
        () -> {
          final var project = requireProject(args);
          final var removed = sessions.remove(sessionKey(project, optional(args, "circuit"))) != null;
          final var result = new JsonObject();
          result.addProperty("reset", removed);
          return result;
        });
  }

  private Session session(JsonObject args) throws McpRpcException {
    final var project = requireProject(args);
    final var circuitName = optional(args, "circuit");
    return sessions.computeIfAbsent(
        sessionKey(project, circuitName), key -> new Session(resolveSpace(project, circuitName), project));
  }

  private static Space resolveSpace(Project project, String circuitName) {
    return circuitName == null ? Space.of(project) : Space.of(project, circuitName);
  }

  private String sessionKey(Project project, String circuitName) {
    return registry.projectId(project) + "::" + (circuitName == null ? "" : circuitName);
  }

  private static JsonObject summaryOf(Space space) {
    final var summary = space.summary();
    final var result = new JsonObject();
    final var counts = new JsonObject();
    for (final var entry : summary.countsByKind().entrySet()) {
      counts.addProperty(entry.getKey(), entry.getValue());
    }
    result.add("countsByKind", counts);
    result.addProperty("inputPins", summary.inputPins());
    result.addProperty("outputPins", summary.outputPins());
    result.addProperty("circuitName", space.circuitName());
    return result;
  }

  private Project requireProject(JsonObject args) throws McpRpcException {
    final var project = registry.resolve(optional(args, "projectId"));
    if (project == null) throw new McpRpcException(-32001, "No open Logisim project");
    return project;
  }

  private static String required(JsonObject object, String name) throws McpRpcException {
    final var value = object.get(name);
    if (value == null || value.isJsonNull() || !value.isJsonPrimitive() || value.getAsString().isBlank()) {
      throw new McpRpcException(-32602, "Missing parameter: " + name);
    }
    return value.getAsString();
  }

  private static String optional(JsonObject object, String name) {
    final var value = object.get(name);
    return value == null || value.isJsonNull() ? null : value.getAsString();
  }

  private static boolean booleanValue(JsonObject object, String name, boolean fallback)
      throws McpRpcException {
    final var value = object.get(name);
    if (value == null || value.isJsonNull()) return fallback;
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
      throw new McpRpcException(-32602, name + " must be a boolean");
    }
    return value.getAsBoolean();
  }

  private static JsonObject schema(Object... values) {
    final var object = new JsonObject();
    object.addProperty("type", "object");
    final var properties = new JsonObject();
    final var required = new com.google.gson.JsonArray();
    for (var i = 0; i < values.length; i += 3) {
      final var property = new JsonObject();
      property.addProperty("type", (String) values[i + 1]);
      properties.add((String) values[i], property);
      if ((Boolean) values[i + 2]) required.add((String) values[i]);
    }
    object.add("properties", properties);
    object.add("required", required);
    return object;
  }

  private static JsonObject evalSchema() {
    final var object = schema(
        "script", "string", true,
        "projectId", "string", false,
        "circuit", "string", false,
        "stream", "boolean", false);
    return object;
  }

  private static final class Session {
    private final Space space;
    private final Project project;
    private final LuaSandbox sandbox;
    private boolean streaming;

    Session(Space space, Project project) {
      this.space = space;
      this.project = project;
      this.sandbox = new LuaSandbox(space, () -> {
        if (streaming) repaintIfPossible(project);
      });
    }
  }

  private static void repaintIfPossible(Project project) {
    final var frame = project.getFrame();
    if (frame == null) return;
    final var canvas = frame.getCanvas();
    canvas.paintImmediately(canvas.getVisibleRect());
  }

  /**
   * The full-adder script handed back by {@code describe} and reused by
   * {@code McpEfficiencyRegressionTest} -- copy-and-edit material, not API reference (design doc,
   * 13.2). Method-level documentation belongs in {@link #EVAL_DESCRIPTION} instead, which the MCP
   * client loads once at connection time rather than once per call.
   */
  static final String FULL_ADDER_EXAMPLE =
      """
      -- Example: a full adder, built and wired in one eval() call.
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

      space:commit("build full adder")
      return "ok"
      """;

  private static final String EVAL_DESCRIPTION =
      """
      Run a Lua script against the current circuit and return its result.

      Global `space` (a com.cburch.logisim.dsl.Space):
        space:place(kind) -> Placement: kind is one of "wiring/pin", "gates/and_gate",
          "gates/or_gate", "gates/nand_gate", "gates/nor_gate", "gates/not_gate", "gates/xor_gate",
          "gates/xnor_gate", "gates/buffer", "memory/register".
        space:byId(id) / space:byLabel(label) -> Comp or nil. Use these, not a saved Lua local, to
          address a component from a later eval() call: a script's locals never survive past the
          single call they were declared in.
        space:componentsOf(kind) -> {Comp,...}; space:components() -> {Comp,...}; space:nets() ->
          {Net,...}; space:near(comp, cells) -> {Comp,...}; space:summary() -> table.
        space:connect(portOrNet, portOrNet) -> Net; space:disconnect(net); space:remove(comp).
        space:wires() -> WireOps (dotAt(col,row), add(dot,dot), isOccupied(dot)) for manual wiring.
        space:check() -> {ok, unconnected, undriven, multiplyDriven}.
        space:commit(actionName) -> {placed, nets}: stages since the last commit/rollback become one
          undo-log entry. space:rollback() discards them instead.
        space:synthesize(spec) -> {placed}: generates an entire gate-level circuit from a
          truth-table-style spec in one call, as an alternative to place()/connect() -- only works on
          a still-empty circuit. spec = {inputs = {"A","B",...}, outputs = {Name = "boolean expr",
          ...}, twoInputGatesOnly = false, nandOnly = false}. Expressions use input names with
          and/or/xor/not (or symbolic +,*,',~) and parentheses, e.g. "A xor B xor Cin". Every
          input/output is 1 bit. Result components are immediately visible via
          space:components()/byLabel() in the same session, no fresh call needed.
        space:tidyWires() -> boolean: re-routes every wire already in the circuit for readability,
          without moving, adding, or removing a single component -- the fix when connections are
          correct but the layout drawn by place()/connect()'s own router looks cluttered. Requires
          space:commit(...) to have already been called (errors if space:isDirty()); returns false
          with nothing changed if there was no wiring worth touching. Component ids and labels are
          unaffected and space:byId(id)/space:byLabel(label) still resolve the same components
          afterward -- just call it right after commit, e.g.:
            space:commit("build full adder")
            space:tidyWires()
          Calling this explicitly is no longer required for its own sake: every eval() that ends
          with nothing staged (space:isDirty() false) automatically tidies wiring and, if a GUI
          window is open for this project, zooms/centers its Layout canvas to fit the circuit --
          same as clicking the toolbar's "Auto" zoom button. Call it yourself only if a script needs
          the tidied result mid-eval, e.g. to inspect wire geometry before returning.

      Placement (returned by space:place): anchorAt(col,row) / at(col,row), rightOf(comp,gap) /
        below(comp,gap), with(attrTable) (e.g. {type="input"} or {inputs="3"}), facing(dir)
        ("east"/"north"/"west"/"south"), place() -> Comp. All but place() return self, so calls
        chain: space:place("wiring/pin"):anchorAt(0,0):with({type="input"}):place().

      Comp: kind(), id(), label(), setLabel(s), ports() / inputs() / outputs() -> {Port,...} (1-based),
        port(nameOrIndex), attrs(), set(name,value), facing(dir), origin() -> Dot, bounds().

      Port: owner() -> Comp, index(), dir() ("IN"/"OUT"/"INOUT"), width(), name(), net() -> Net or
        nil, isConnected().

      Net (also returned by space:connect): id(), width(), ports(), drivers(), path(), preferAbove()
        / preferBelow() / preferLeft() / preferRight() / viaColumn(n) / viaRow(n) -- routing hints,
        chainable, use when the router's default path would cross something it should not.

      A Java exception raised by the DSL (wrong port direction, width mismatch, a routing hint that
      cannot be satisfied, ...) surfaces as a Lua error whose message is a table with `type`,
      `message`, `details`, and often `suggestion` fields -- catch it with pcall to inspect them, or
      let it propagate: the eval tool call then fails with that same structured data attached.

      Optional `stream` (default false): when true, each space:commit() inside the script repaints
      the canvas immediately instead of waiting for the whole script to finish, so a person watching
      the window sees the circuit built up progressively rather than appearing all at once.
      """;
}
