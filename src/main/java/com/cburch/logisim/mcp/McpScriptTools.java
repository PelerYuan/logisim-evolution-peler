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

  /** How far {@link #autoTidyAndFit} insists every component and wire stay from both axes --
   * see {@link com.cburch.logisim.dsl.Space#ensureAwayFromOrigin} for why a circuit built flush
   * against {@code (0, 0)} can end up with content no amount of zooming or centering can reach. Two
   * grid cells: enough that nothing touches the edge, small enough to never visibly matter. */
  private static final int SAFE_MARGIN = 20;

  /**
   * Runs after every successful {@code eval}, so a session never has to end with correct-but-
   * cluttered wiring or a view scrolled off whatever the script just placed: re-tidies the
   * circuit's wiring (space.tidyWires()'s own no-op case already keeps this a no-op when there was
   * nothing to fix -- see WireTidier's old-wires-equal-new-wires check), nudges the whole circuit
   * away from the canvas's coordinate axes if a component ended up flush against one (see
   * {@link com.cburch.logisim.dsl.Space#ensureAwayFromOrigin} -- otherwise part of it can be
   * permanently unreachable regardless of zoom/center), and, when a GUI window is actually attached
   * to this project (it may not be, e.g. under test), zooms/centers its Layout canvas to fit the
   * circuit's current bounds -- the same computation as the toolbar's "Auto" zoom button. Skipped
   * entirely, silently, when the script left something staged but uncommitted
   * ({@code space.isDirty()}): both {@code tidyWires()} and {@code ensureAwayFromOrigin()} would
   * throw for that, and it is a completely ordinary mid-build state a later {@code eval} call in the
   * same session is expected to finish. Best-effort by design -- a problem in this convenience step
   * must never turn an otherwise-successful eval into a failed one, so any exception here is
   * swallowed rather than surfaced.
   */
  private void autoTidyAndFit(Session session) {
    if (session.space.isDirty()) return;
    try {
      session.space.tidyWires();
      session.space.ensureAwayFromOrigin(SAFE_MARGIN);
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
      -- Note: keep placements clear of (0, 0) -- an east-facing pin's own body is drawn to the
      -- left of its anchor, so anchoring right at the axes can push part of the circuit into
      -- coordinate space the canvas can never scroll to.
      local a = space:place("wiring/pin"):anchorAt(20, 20):with({type = "input"}):place()
      a:setLabel("A")
      local b = space:place("wiring/pin"):anchorAt(20, 28):with({type = "input"}):place()
      b:setLabel("B")
      local cin = space:place("wiring/pin"):anchorAt(20, 36):with({type = "input"}):place()
      cin:setLabel("Cin")

      local xor1 = space:place("gates/xor_gate"):anchorAt(28, 24):place()
      local and1 = space:place("gates/and_gate"):anchorAt(28, 32):place()
      local xor2 = space:place("gates/xor_gate"):anchorAt(36, 24):place()
      local and2 = space:place("gates/and_gate"):anchorAt(36, 40):place()
      local or1 = space:place("gates/or_gate"):anchorAt(44, 36):place()

      local sum = space:place("wiring/pin"):anchorAt(52, 24):with({type = "output"}):place()
      sum:setLabel("Sum")
      local cout = space:place("wiring/pin"):anchorAt(52, 36):with({type = "output"}):place()
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
          "gates/xnor_gate", "gates/buffer", "memory/register" for these common ones, or -- for
          anything else built in, loaded into the project, or already placed by hand -- the raw
          "<library>/<component>" pair Logisim itself uses internally (e.g. "Plexers/Multiplexer",
          "Memory/RAM"), or "circuit/<name>" for a subcircuit. Kind.of on a wrong or misspelled key
          throws with the nearest real keys attached, so a typo is self-correcting without a
          separate lookup call. A custom component installed in any loaded component library (see
          global `pcomp` below) is internally just a subcircuit too, so it places the same
          "circuit/<name>" way -- but <name> there is the component's mainCircuit field from
          pcomp:list(...), not its display name (e.g. "circuit/Half_Adder_v1" to place what
          pcomp:list(...) shows as name "Half Adder" version 1) -- no separate placement API of its
          own.
        space:byId(id) / space:byLabel(label) -> Comp or nil. Use these, not a saved Lua local, to
          address a component from a later eval() call: a script's locals never survive past the
          single call they were declared in.
        space:componentsOf(kind) -> {Comp,...}; space:components() -> {Comp,...}; space:nets() ->
          {Net,...}; space:near(comp, cells) -> {Comp,...}; space:summary() -> table.
        space:connect(portOrNet, portOrNet) -> Net; space:disconnect(net).
        space:remove(comp): drops a still-staged component, or -- for one the circuit already holds
          (committed earlier, or hand-drawn) -- deletes it as one immediate, undo-logged action like
          selecting it and pressing Delete: wires that ran to it stay behind, ending at nothing.
          The immediate form needs nothing staged (errors with UncommittedChangesException).
        space:move(comp, col, row[, keepConnections]) -> {unconnectedPorts}: moves a component the
          circuit already holds so its anchor (comp:origin()) lands on grid (col,row), as one
          immediate, undo-logged action. keepConnections (default true) re-routes the attached wires
          to follow it, exactly like dragging it on the canvas; unconnectedPorts counts ports left
          with no wire to where they connected. Needs nothing staged, and a committed component
          (ComponentNotCommittedException otherwise). Comp objects and ids survive; Nets do not.
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
        space:exportImage(path, format[, scale[, printerView]]): renders this circuit to an image
          or vector file at an absolute path. format is one of "png", "gif", "jpg", "svg", "tikz"
          (case-insensitive); scale defaults to 1.0 (a linear size multiplier, not a percentage);
          printerView defaults to false (true draws in black-on-white print style rather than
          on-screen colors). Needs no open GUI window -- works the same headless. Throws
          InvalidExportFormatException for an unrecognized format, ExportFailedException (wrapping
          the underlying I/O error) if writing the file fails.
        space:exportHtml(path): writes this circuit out as a self-contained, interactively-
          simulatable HTML page (absolute path). Refuses up front with UnsupportedForHtmlExportException
          (details.unsupportedKinds lists every offending component's kind, looking inside
          subcircuits too) if the circuit uses a component this export cannot simulate -- fix or
          remove those first rather than getting a page that would silently compute the wrong
          answer. ExportFailedException wraps any I/O failure while writing.

      Global `circuits` (a com.cburch.logisim.dsl.Circuits): project-level circuit management,
        independent of whichever circuit `space` is currently open on.
        circuits:list() -> {name,...} (file order); circuits:mainName() -> string or nil.
        circuits:create(name); circuits:remove(name); circuits:rename(oldName, newName);
        circuits:setMain(name) -- each is its own immediate, undo-logged action, not staged like
        space:place()/commit().
        circuits:setEverywhere(attrName, value [, kind]) -> count: sets one attribute, by the name
          comp:attrs() shows, on every component in every circuit of the project that carries it,
          as ONE undo entry (the Project menu's bulk commands, e.g. TTL chip drawing =
          "ShowInternalStructure" with "true"/"false"). value is read like comp:set reads it; kind,
          when given, is a kinds key that restricts the sweep to that one kind. Returns how many
          components changed; already-equal ones are skipped and no undo entry is made when none
          change. An unreadable value throws InvalidAttributeValueException before anything changes.
        To then work inside a circuit this call just created or renamed,
        pass its name as this tool's own top-level "circuit" argument on the next call (eval,
        describe and reset all accept it) -- `space` is always bound to one specific circuit for
        the lifetime of a single eval() call and cannot be redirected mid-script.

      Global `libraries` (a com.cburch.logisim.dsl.Libraries): project-level library management --
        load an external file/directory as a top-level library of this project, or unload one.
        libraries:list() -> {name,...}: every top-level library currently loaded (not recursive).
        libraries:loadCircuit(path) -> name: loads an external .circ/.pcirc file as a library.
        libraries:loadJar(path, className) -> name: loads a Library/ComponentFactory class out of
          an external JAR; className is the fully-qualified class the JAR exposes.
        libraries:loadPcomp(path) -> name: loads a Peler Edition component-library directory (one
          created via the GUI's "New Library..." or libraries:createPcomp below), the multi-library
          successor to the single built-in "My Components" catalog.
        libraries:createPcomp(path, name) -> name: creates a brand-new, empty component-library
          directory at path (display name `name`) and loads it in one step. Throws
          PcompLibraryCreateFailedException on failure. Once loaded, publish components into it with
          global `pcomp` below.
        libraries:exportPcomp(name, destinationZip): exports a loaded component library's whole
          directory as a .zip that someone else can hand back to loadPcomp -- name is the library's
          own name (from list(), or "My Components" for the default one), not its display name.
          Throws PcompLibraryExportFailedException on failure.
        libraries:unload(name): removes a top-level library, refusing with a structured reason if
          anything in the project still places a component from it.
        libraries:reload(name): reads a loaded library's file/JAR/directory again after it changed
          on disk (not undo-logged, as in the GUI). Throws LibraryNotReloadableException for a
          built-in library or the default "My Components" catalog, LibraryLoadFailedException if
          the source can no longer be read.
        loadCircuit/loadJar/loadPcomp/createPcomp/unload are each their own immediate, undo-logged
        action, like `circuits`' methods, not staged like space:place()/commit()
        (exportPcomp changes nothing in the project, so it is not undo-logged at all). A name
        collision, an unreadable/malformed file, or an unload while still in use each throw a
        structured exception (DuplicateLibraryNameException, LibraryLoadFailedException,
        LibraryInUseException, UnknownLibraryException, NotAPcompLibraryException) rather than ever
        popping a GUI dialog.

      Global `tests` (a com.cburch.logisim.dsl.TestVectors): the Test window. The circuit must be
      committed. tests:run(text) / tests:runFile(path) evaluate a test vector -- the window's
      format: a header line naming pins (a clock as <clk>), then one row of values per line, '#'
      comments, optional <DC> / <FLOAT> entries -- on a private circuit state, leaving `simulation`
      untouched, and return {passed, failed, failures={{row (1-based data row),
      mismatches={{column,expected,computed,oscillating},...}},...}}. Values are binary strings.
      InvalidTestVectorException for an unreadable vector or one that does not match the pins.
      Global `analysis` (a com.cburch.logisim.dsl.Analysis): the Combinational Analysis window run
      backwards from the circuit `space` is bound to. Inputs/outputs are its pins by label
      (unlabeled pins get default names; a multi-bit pin gives name[i] per bit). The circuit must be
      committed, and have at least one input and one output pin; at most 16 input bits.
        analysis:truthTable() -> {inputs={names}, outputs={names}, rows={{inputs="010",
          outputs="1"},...}}: rows in binary order of the inputs (first input most significant);
          output characters are "0","1","x" (undefined) or "E" (error/oscillation).
        analysis:expressions([notation]) -> {outputName = expression}: read structurally off the
          gates (AnalysisFailedException with feedback loops or components it cannot express; use
          truthTable()/minimized() then). analysis:minimized([format][, notation]) -> the same map
          from the truth table, minimized; format "sop" (default) or "pos". notation is one of
          "progbits" (default: ~ & | ^, ASCII, and accepted by space:synthesize), "progbools",
          "mathematical", "logic", "altlogic", "latex".
        analysis:exportTable(path) writes the truth table (.txt or .csv by extension);
        analysis:exportLatex(path) writes the LaTeX document the window's Export button writes.
      Global `memory` (a com.cburch.logisim.dsl.Memory): the hex editor and the RAM/ROM popup's
      Clear / Load / Save, for a memory/rom or memory/ram component `c` (kinds "Memory/ROM",
      "Memory/RAM"; the dual-port RAM also works).
        memory:info(c) -> {kind,addressBits,dataBits,words,live}; memory:read(c,addr);
        memory:readRange(c,start,count) -> {values}; memory:write(c,addr,value);
        memory:writeRange(c,start,{values}); memory:fill(c,start,count,value); memory:clear(c).
        memory:dump(c) -> the whole memory as a "v2.0 raw" image (hex words, N*word repeats);
        memory:load(c,image) replaces the whole memory from such text; memory:loadFile(c,path) /
        memory:saveFile(c,path) do the same with a file (the GUI's other file formats are chosen
        in a dialog and are not supported). A ROM's contents belong to the component, are saved
        with the circuit, and each edit is one undo entry. A RAM's contents are simulation state:
        not saved, not undoable, and a RAM must be committed before its contents can be edited.
        Errors: InvalidMemoryAccessException (bad address, value too wide, bad image),
        NotAMemoryException.
      Global `pla` (a com.cburch.logisim.dsl.PlaTables): the PLA program editor for a "Gates/PLA".
        pla:getTable(c) -> text, one row per line: input bits over 0/1/x, a space, output bits over
        0/1 (MSB first), optional "# comment"; pla:setTable(c,text) replaces it, resizing the
        component's input/output widths to match, one undo entry. InvalidPlaTableException on a bad
        row, unequal widths or no rows (the GUI would pop a dialog instead).
      Other content editors: a PLA-ROM's and a programmable generator's contents are plain string
        attributes, set with c:set("Contents", text). SoC components' own windows are not scripted.
      Global `appearance` (a com.cburch.logisim.dsl.Appearance): the GUI's appearance editor, for
      the circuit `space` is bound to -- how the circuit is drawn when placed inside another.
        appearance:style() -> "classic"|"evolution"|"fpga"|"custom"; appearance:setStyle(name).
        appearance:list() -> {{index,kind,x,y,width,height,text,pin,stroke,fill,strokeWidth},...}:
          the custom shape list, bottom layer first. kind is rect/roundrect/oval/line/polygon/
          polyline/text, or "port" (one per pin of the circuit; `pin` is the pin's label) or
          "anchor" (the point that lands on the placed component's location). stroke/fill are
          "#rrggbb"; fields that do not apply are absent.
        appearance:addRect(x,y,w,h[,opts]) / addOval(x,y,w,h[,opts]) / addRoundRect(x,y,w,h,radius
          [,opts]) / addLine(x1,y1,x2,y2[,opts]) / addPoly({{x,y},...}[,closed=true][,opts]) /
          addText(x,y,text[,opts]) -> index of the new shape. opts is a table: stroke, fill
          ("#rrggbb"), strokeWidth (int), size (text only). Fill applies to rect/roundrect/oval/
          closed polygon; setting fill without stroke gives a filled shape with no outline.
        appearance:remove(index); appearance:clear() -> count removed (keeps ports and anchor);
          appearance:move(index,dx,dy) (also how a port or the anchor is positioned; ports and the
          anchor cannot be removed); appearance:reorder(index,"up"|"down"|"top"|"bottom");
          appearance:setAnchorFacing("east"|"north"|"west"|"south").
        appearance:resetDefault(): the editor's "restore default custom appearance" (the plain box
          with every port); appearance:loadLogisimDefault(): "clear appearance and load logisim
          default" (the built-in symbol as editable shapes).
        A shape edit switches the circuit to the "custom" style in the same undo entry, since an
        edit under another style would be invisible. Each call is its own undoable action, like
        `circuits`' methods. A custom component's circuit refuses edits (AppearanceLockedException),
        as the editor does. Errors: UnknownAppearanceShapeException, InvalidAppearanceEditException.
      Global `pcomp` (a com.cburch.logisim.dsl.Pcomp): manages what is installed inside a component
        library -- publishing a circuit as a new reusable component, importing/deleting one, or
        replacing every placed instance of one version with another -- the counterpart to
        `libraries` for a library's own contents rather than for the library as a whole. In every
        method, libraryName is one of `libraries:list()`'s entries (the always-present default
        library is named "My Components"); the library must actually be a component library, not an
        external .circ/JAR one, or this throws NotAPcompLibraryException.
        pcomp:list(libraryName) -> {{id, version, name, mainCircuit, locked}, ...}: every installed
          version of every component in that library.
        pcomp:saveAsComponent(circuitName, componentName, libraryName) -> {id, version, name,
          mainCircuit, path, libraryName}: publishes an existing circuit as a new, first-version
          component. The port layout is always derived automatically from the circuit's own pins --
          each port's name comes from whatever label its pin already carries (set the ordinary way,
          e.g. pin:setLabel("A")), and side/position are assigned the same way the GUI's own
          "Arrange for Me" button does; there is no interactive layout step to drive from a script.
          Throws UnknownCircuitException for an unknown circuitName; InvalidComponentNameException
          if componentName is empty or cannot become a valid circuit name;
          InvalidComponentLayoutException if the circuit has no pins or two pins share a label;
          DuplicatePcompNameException if the library already has a different component answering to
          the same underlying name; PcompSaveFailedException if writing/installing the file fails.
        pcomp:importFile(libraryName, path) -> {id, version, name, mainCircuit, path, libraryName}:
          copies an existing .pcomp file into the library and installs it. Throws
          PcompImportFailedException (not a valid component file, a same-named file already present,
          or the copy/install itself failing) or DuplicatePcompNameException (same underlying-name
          collision as saveAsComponent).
        pcomp:delete(libraryName, id, version): removes one installed version and deletes its file.
          Throws UnknownPcompComponentException if no such id/version is installed, or
          PcompComponentInUseException if this project still places an instance of it -- replace it
          with another version first, or remove every placed instance.
        pcomp:replace(libraryName, id, fromVersion, toVersion) -> {uses, replaced}: replaces every
          instance of one installed version, throughout this project, with another installed version
          of the same id -- wires are untouched. uses is how many instances were found; replaced is
          false (nothing changed, no undo entry created) when uses is 0. Throws
          UnknownPcompComponentException if either version is not installed. This mutates the
          project directly, not through `space`'s own place/commit bookkeeping, so `space`'s
          component queries (components/componentsOf/byId/...) still reflect the pre-replace state
          for the rest of this eval call -- call pcomp:replace() near the end of a script, or query
          the result on the next eval, where `space` is rebuilt fresh.
        saveAsComponent/importFile/delete/replace are each their own immediate, undo-logged action,
        like `circuits`'/`libraries`'/`vhdlEntities`' methods, not staged like space:place()/commit().
        To create a brand-new component library to publish into, or to export one as a shareable
        zip, use `libraries:createPcomp(path, name)` / `libraries:exportPcomp(name, destinationZip)`.

      Global `vhdlEntities` (a com.cburch.logisim.dsl.VhdlEntities): project-level VHDL entity
        management -- a VHDL entity is a named "black box" component backed by hand-written VHDL
        source, placeable into a circuit exactly like a subcircuit, via kind key "vhdl/<name>".
        vhdlEntities:list() -> {name,...} (file order).
        vhdlEntities:create(name): adds a new entity from the same blank template the GUI's "Add
          VHDL Entity" menu item uses.
        vhdlEntities:importFile(path) -> name: reads path as a VHDL source file and adds it as a
          new entity named after its own "entity ... is" declaration (need not match the file
          name); returns that name.
        vhdlEntities:remove(name): removes an entity, refusing with a structured reason if any
          circuit in the project still places it as a component.
        vhdlEntities:rename(oldName, newName): renames an entity, independent of whether it has any
          placed instance (the GUI only exposes renaming through a placed instance's attribute
          table).
        vhdlEntities:getSource(name) -> string: the entity's full VHDL text, as the GUI's VHDL
          editor shows it.
        vhdlEntities:setSource(name, text): replaces that text, like editing in the GUI editor and
          pressing "Validate and Save". The text must parse and still declare the same entity name
          (InvalidVhdlSourceException otherwise -- use rename() to change a name). Placed instances
          pick up port changes as they do after a GUI edit.
        vhdlEntities:ports(name) -> {{name, direction ("input"/"output"/"inout"), width},...}: the
          ports the entity's current source declares.
        vhdlEntities:exportFile(name, path): writes the source to a .vhd file (the editor's Save).
        create/importFile/remove/rename/setSource are each their own immediate, undo-logged action,
        like `circuits`'/`libraries`' methods, not staged like space:place()/commit(). An invalid or
        already-used name, a file that is not valid VHDL, an unknown entity name, or a remove while
        still in use each throw a structured exception (InvalidVhdlNameException, Duplicate
        VhdlNameException, VhdlImportFailedException, UnknownVhdlEntityException,
        VhdlEntityInUseException) rather than ever popping a GUI dialog.

      Global `circuitStatistics` (a com.cburch.logisim.dsl.CircuitStatistics): read-only component
        counts for a named circuit, exactly what the GUI's "Circuit Statistics" menu item shows.
        circuitStatistics:compute(name) -> {rows={...}, totalWithoutSubcircuits={...},
          totalWithSubcircuits={...}}. Each row is {component, library, simpleCount, uniqueCount,
          recursiveCount}: simpleCount counts direct instances of that component kind in this
          circuit; uniqueCount counts instances of it anywhere in the whole project file; recursive
          Count counts instances if this circuit were fully flattened, multiplying through nested
          subcircuits. The two totals sum simpleCount/uniqueCount/recursiveCount across all rows,
          the "without" variant excluding rows that are themselves one of this file's own circuits
          used as a subcircuit. Throws UnknownCircuitException (with a "did you mean" suggestion)
          for a circuit name this project does not have.

      Global `history` (a com.cburch.logisim.dsl.History): undo/redo of already-committed actions,
        independent of whichever circuit `space` is currently open on. One "unit" here is exactly
        one undo-log entry -- one space:commit(...) call (which batches everything placed/connected
        since the last commit into one entry) or one direct circuits:*/libraries:*/vhdlEntities:*/
        space:tidyWires()/space:synthesize() call.
        history:canUndo() / canRedo() -> boolean.
        history:nextUndoDescription() / nextRedoDescription() -> string or nil: what the next
          undo()/redo() would revert/reapply, without doing it.
        history:undo() / redo() -> string: performs it, returning that same description. Throws
          NothingToUndoException/NothingToRedoException if the respective log is empty -- check
          canUndo()/canRedo() first, or catch and ignore, rather than assuming either always
          succeeds.

      Global `simulation` (a com.cburch.logisim.dsl.Simulation): runs the project's own simulator
        against the circuit `space` is open on -- the same one the GUI's Simulate menu drives, so
        state (running/stopped, tick frequency, pin values) persists across separate eval() calls
        exactly like it would across separate clicks in the GUI. Every method here waits for the
        requested step to actually finish before returning.
        simulation:reset(): mirrors Simulate -> Reset Simulation.
        simulation:step(): propagates one step by hand; mirrors Simulate -> Single Step.
        simulation:tick(halfCycles): advances every clock by halfCycles half-periods (1 = Tick Half
          Period, 2 = Tick Full Period). Throws NoClockException if this circuit (or any subcircuit
          it contains) has no Clock component -- never pops the GUI's "choose a clock" dialog.
        simulation:isAutoTicking() / setAutoTicking(bool): mirrors Simulate -> Ticks Enabled; turning
          it on throws NoClockException under the same condition as tick().
        simulation:isAutoPropagating() / setAutoPropagation(bool): mirrors Simulate -> Run/Stop
          Simulation.
        simulation:getTickFrequency() / setTickFrequency(hz): the clock rate used while auto-ticking.
        simulation:isOscillating() / isExceptionEncountered(): diagnostic state the GUI's status bar
          also shows.
        simulation:readPin(label) -> {value, known, error}: the current value of the wiring/pin
          component labeled `label`, input or output. `known` is false (value meaningless, always -1)
          for a pin with any floating bit; `error` is true for a width/conflict error state.
        simulation:writePin(label, value): drives an input pin to `value` and propagates the change;
          mirrors clicking the GUI's poke tool. Throws PinNotWritableException if `label` names an
          output pin instead.
        Both pin methods throw UnknownPinException (with a "did you mean" suggestion when no label
          matches at all) if `label` does not name a wiring/pin component in this circuit.
        simulation:trace({labels}[, samples=1][, halfCyclesPerSample=2][, path]) -> {signals,
          rows={{"0","5",...},...}}: the Log window's recording, headless. Samples the named pins now,
          then after each further advance of halfCyclesPerSample clock half-periods (needs a clock
          when samples > 1). Cells are decimal numbers, "x" (any bit unknown) or "E" (error). With
          `path`, also writes the Log window's tab-separated file. Only pins can be traced: wire an
          internal signal to a labeled output pin to record it. InvalidTraceException on bad counts.
        simulation:isVhdlSimulationAvailable(): true only if QuestaSim's path is configured in the
          application's Preferences -> Software (this cannot be set from a script).
        simulation:isVhdlSimulationEnabled() / setVhdlSimulationEnabled(bool): mirrors Simulate ->
          VHDL Simulation Enabled. Enabling throws VhdlSimulatorUnavailableException unless
          QuestaSim is available -- never the file-chooser dialog the GUI falls back to.
        simulation:generateVhdlSimulationFiles(): mirrors Simulate -> Generate VHDL Simulation
          Files (regenerates the co-simulation sources and restarts the co-simulator); throws
          VhdlSimulatorUnavailableException while co-simulation is not enabled.

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
