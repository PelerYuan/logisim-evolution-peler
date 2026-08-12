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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.Projects;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpProjectServiceTest {
  @TempDir Path tempDir;
  private Project project;
  private McpModelExecutor executor;
  private McpProjectRegistry registry;
  private McpProjectService service;
  private McpJsonRpcDispatcher dispatcher;
  private List<Project> openProjects;

  @BeforeEach
  void setUp() throws Exception {
    System.setProperty("logisim.mcp.allowedPaths", tempDir.toString());
    try (InputStream input = getClass().getResourceAsStream("/htmlexport/and2.circ")) {
      assertNotNull(input, "test circuit fixture is missing");
      final var loader = new Loader(null);
      project = new Project(loader.openLogisimFile(input));
      for (final var circuit : project.getLogisimFile().getCircuits()) circuit.setProject(project);
    }
    openProjects = mutableOpenProjects();
    openProjects.add(project);

    executor = new McpModelExecutor();
    registry = new McpProjectRegistry();
    registry.register(project);
    service = new McpProjectService(executor, registry);
    dispatcher = new McpJsonRpcDispatcher("test", "1", service);
    service.registerTools(dispatcher);
  }

  @AfterEach
  void tearDown() {
    if (service != null) service.close();
    if (executor != null) executor.close();
    if (project != null) project.getSimulator().shutDown();
    if (openProjects != null) openProjects.remove(project);
    System.clearProperty("logisim.mcp.allowedPaths");
  }

  @Test
  void listsLiveProjectCircuitsAndSnapshotObjects() {
    final var projects = call("list_projects", new JsonObject(), 1);
    assertEquals(1, projects.get("count").getAsInt());
    final var projectId = projects.getAsJsonArray("projects").get(0).getAsJsonObject().get("projectId").getAsString();

    final var circuitsArgs = new JsonObject();
    circuitsArgs.addProperty("projectId", projectId);
    final var circuits = call("list_circuits", circuitsArgs, 2);
    final var circuit = circuits.getAsJsonArray("circuits").get(0).getAsJsonObject();
    final var circuitId = circuit.get("circuitId").getAsString();
    assertEquals(4, circuit.get("componentCount").getAsInt());
    assertEquals(5, circuit.get("wireCount").getAsInt());

    final var snapshotArgs = new JsonObject();
    snapshotArgs.addProperty("projectId", projectId);
    snapshotArgs.addProperty("circuitId", circuitId);
    final var snapshot = call("get_circuit_snapshot", snapshotArgs, 3);
    assertEquals(projectId, snapshot.get("projectId").getAsString());
    assertEquals(circuitId, snapshot.get("circuitId").getAsString());
    assertEquals(4, snapshot.getAsJsonArray("components").size());
    assertEquals(5, snapshot.getAsJsonArray("wires").size());
    assertEquals(0, snapshot.get("revision").getAsLong());
    assertStableComponentIds(snapshot.getAsJsonArray("components"));

    final var toolsArgs = new JsonObject();
    toolsArgs.addProperty("projectId", projectId);
    final var tools = call("get_available_tools", toolsArgs, 30).getAsJsonArray("tools");
    assertTrue(
        tools.asList().stream()
            .map(JsonElement::getAsJsonObject)
            .anyMatch(tool -> "AND Gate".equals(tool.get("factory").getAsString())));
  }

  @Test
  void addComponentChangesLiveModelAndSupportsRevisionConflictAndUndo() {
    final var context = projectContext();
    final var before = snapshot(context.projectId(), context.circuitId());

    final var addArgs = new JsonObject();
    addArgs.addProperty("projectId", context.projectId());
    addArgs.addProperty("circuitId", context.circuitId());
    addArgs.addProperty("factory", "AND Gate");
    addArgs.addProperty("x", 500);
    addArgs.addProperty("y", 220);
    addArgs.addProperty("expectedRevision", before.get("revision").getAsLong());
    addArgs.addProperty("operationId", "service-add-undo-target");
    final var added = call("add_component", addArgs, 4);
    assertEquals(1, added.get("revision").getAsLong());
    assertTrue(added.has("componentId"));
    assertEquals(5, snapshot(context.projectId(), context.circuitId()).getAsJsonArray("components").size());
    assertEquals(1, project.getUndoActions().size());

    final var conflictArgs = addArgs.deepCopy();
    conflictArgs.addProperty("x", 560);
    conflictArgs.addProperty("expectedRevision", 0);
    conflictArgs.addProperty("operationId", "service-add-stale-conflict");
    final var conflict = callRaw("add_component", conflictArgs, 5);
    assertEquals(-32009, conflict.getAsJsonObject("error").get("code").getAsInt());
    assertEquals(5, snapshot(context.projectId(), context.circuitId()).getAsJsonArray("components").size());

    final var undoArgs = new JsonObject();
    undoArgs.addProperty("projectId", context.projectId());
    undoArgs.addProperty("expectedRevision", 1);
    undoArgs.addProperty("targetOperationId", "service-add-undo-target");
    final var undone = call("undo", undoArgs, 6);
    assertEquals(2, undone.get("revision").getAsLong());
    assertEquals(4, snapshot(context.projectId(), context.circuitId()).getAsJsonArray("components").size());
  }

  @Test
  void unknownFactoryAndInvalidWireReturnStructuredErrorsWithoutMutation() {
    final var context = projectContext();
    final var addArgs = new JsonObject();
    addArgs.addProperty("projectId", context.projectId());
    addArgs.addProperty("circuitId", context.circuitId());
    addArgs.addProperty("factory", "does-not-exist");
    addArgs.addProperty("x", 500);
    addArgs.addProperty("y", 220);
    addArgs.addProperty("expectedRevision", 0);
    final var unknown = callRaw("add_component", addArgs, 7);
    assertEquals(-32602, unknown.getAsJsonObject("error").get("code").getAsInt());

    final var wireArgs = new JsonObject();
    wireArgs.addProperty("projectId", context.projectId());
    wireArgs.addProperty("circuitId", context.circuitId());
    wireArgs.addProperty("x1", 10);
    wireArgs.addProperty("y1", 10);
    wireArgs.addProperty("x2", 10);
    wireArgs.addProperty("y2", 10);
    wireArgs.addProperty("expectedRevision", 0);
    final var invalidWire = callRaw("add_wire", wireArgs, 8);
    assertEquals(-32602, invalidWire.getAsJsonObject("error").get("code").getAsInt());
    assertEquals(4, snapshot(context.projectId(), context.circuitId()).getAsJsonArray("components").size());
  }

  @Test
  void exposesStandardProjectAndCircuitResources() {
    final var context = projectContext();
    final var listed = dispatch("resources/list", null, 40).getAsJsonObject("result");
    final var uris =
        listed.getAsJsonArray("resources").asList().stream()
            .map(JsonElement::getAsJsonObject)
            .map(resource -> resource.get("uri").getAsString())
            .toList();
    final var projectUri = "logisim://project/" + context.projectId() + "/snapshot";
    final var circuitUri =
        "logisim://project/" + context.projectId() + "/circuit/" + context.circuitId();
    assertTrue(uris.contains("logisim://projects"));
    assertTrue(uris.contains(projectUri));
    assertTrue(uris.contains(circuitUri));

    final var params = new JsonObject();
    params.addProperty("uri", circuitUri);
    final var read = dispatch("resources/read", params, 41).getAsJsonObject("result");
    final var content = read.getAsJsonArray("contents").get(0).getAsJsonObject();
    assertEquals("application/json", content.get("mimeType").getAsString());
    final var snapshot = JsonParser.parseString(content.get("text").getAsString()).getAsJsonObject();
    assertEquals(context.circuitId(), snapshot.get("circuitId").getAsString());

    final var templates =
        dispatch("resources/templates/list", null, 42).getAsJsonObject("result");
    assertEquals(3, templates.getAsJsonArray("resourceTemplates").size());

    final var invalidParams = new JsonObject();
    invalidParams.addProperty("uri", "logisim://missing");
    final var invalid = dispatch("resources/read", invalidParams, 43);
    assertEquals(-32004, invalid.getAsJsonObject("error").get("code").getAsInt());
  }

  @Test
  void desktopActionIsVisibleInSubsequentSnapshot() throws Exception {
    final var context = projectContext();
    final var before = snapshot(context.projectId(), context.circuitId());
    executor.run(
        () -> {
          final var circuit = project.getCurrentCircuit();
          final var factory = circuit.getSubcircuitFactory();
          final var component = factory.createComponent(com.cburch.logisim.data.Location.create(500, 260, true), factory.createAttributeSet());
          final var mutation = new CircuitMutation(circuit);
          mutation.add(component);
          project.doAction(mutation.toAction(null));
        });
    final var after = snapshot(context.projectId(), context.circuitId());
    assertEquals(before.get("revision").getAsLong() + 1, after.get("revision").getAsLong());
    assertEquals(5, after.getAsJsonArray("components").size());
  }

  @Test
  void moveKeepsComponentIdStableAcrossUndoAndRedo() {
    final var context = projectContext();
    final var before = snapshot(context.projectId(), context.circuitId());
    final var original = before.getAsJsonArray("components").get(0).getAsJsonObject();
    final var componentId = original.get("componentId").getAsString();
    final var originalLocation = original.getAsJsonObject("location");

    final var move = new JsonObject();
    move.addProperty("componentId", componentId);
    move.addProperty("x", 700);
    move.addProperty("y", 400);
    final var moves = new JsonArray();
    moves.add(move);
    final var args = new JsonObject();
    args.addProperty("projectId", context.projectId());
    args.addProperty("expectedRevision", 0);
    args.addProperty("operationId", "service-stable-move");
    args.add("moves", moves);
    final var moved = call("move_components", args, 50);
    assertEquals(componentId, moved.getAsJsonArray("components").get(0).getAsJsonObject().get("componentId").getAsString());
    assertLocation(snapshot(context.projectId(), context.circuitId()), componentId, 700, 400);

    final var undoArgs = new JsonObject();
    undoArgs.addProperty("projectId", context.projectId());
    undoArgs.addProperty("expectedRevision", 1);
    undoArgs.addProperty("targetOperationId", "service-stable-move");
    call("undo", undoArgs, 51);
    assertLocation(
        snapshot(context.projectId(), context.circuitId()),
        componentId,
        originalLocation.get("x").getAsInt(),
        originalLocation.get("y").getAsInt());

    final var redoArgs = new JsonObject();
    redoArgs.addProperty("projectId", context.projectId());
    redoArgs.addProperty("expectedRevision", 2);
    redoArgs.addProperty("targetOperationId", "service-stable-move");
    call("redo", redoArgs, 52);
    assertLocation(snapshot(context.projectId(), context.circuitId()), componentId, 700, 400);
  }

  @Test
  void coreEditToolsShareOneUndoableRevisionStream() {
    final var context = projectContext();
    final var baseline = snapshot(context.projectId(), context.circuitId());
    final var baselineComponents = baseline.getAsJsonArray("components").size();
    final var baselineWires = baseline.getAsJsonArray("wires").size();

    final var add = new JsonObject();
    add.addProperty("projectId", context.projectId());
    add.addProperty("circuitId", context.circuitId());
    add.addProperty("factory", "AND Gate");
    add.addProperty("x", 800);
    add.addProperty("y", 400);
    add.addProperty("expectedRevision", 0);
    add.addProperty("operationId", "service-stream-add");
    final var added = call("add_component", add, 60);
    final var componentId = added.get("componentId").getAsString();

    final var attributes = new JsonObject();
    attributes.addProperty("label", "mcp_gate");
    final var set = new JsonObject();
    set.addProperty("projectId", context.projectId());
    set.addProperty("componentId", componentId);
    set.addProperty("expectedRevision", 1);
    set.addProperty("operationId", "service-stream-set");
    set.add("attributes", attributes);
    assertEquals(2, call("set_component_attributes", set, 61).get("revision").getAsLong());
    assertEquals(
        "mcp_gate",
        component(snapshot(context.projectId(), context.circuitId()), componentId)
            .getAsJsonObject("attributes")
            .get("label")
            .getAsString());

    final var move = new JsonObject();
    move.addProperty("componentId", componentId);
    move.addProperty("x", 850);
    move.addProperty("y", 450);
    final var moves = new JsonArray();
    moves.add(move);
    final var moveArgs = new JsonObject();
    moveArgs.addProperty("projectId", context.projectId());
    moveArgs.addProperty("expectedRevision", 2);
    moveArgs.addProperty("operationId", "service-stream-move");
    moveArgs.add("moves", moves);
    assertEquals(3, call("move_components", moveArgs, 62).get("revision").getAsLong());

    final var wire = new JsonObject();
    wire.addProperty("projectId", context.projectId());
    wire.addProperty("circuitId", context.circuitId());
    wire.addProperty("x1", 760);
    wire.addProperty("y1", 450);
    wire.addProperty("x2", 800);
    wire.addProperty("y2", 450);
    wire.addProperty("expectedRevision", 3);
    wire.addProperty("operationId", "service-stream-wire");
    final var wired = call("add_wire", wire, 63);
    final var wireId = wired.get("wireId").getAsString();
    assertEquals(baselineWires + 1, snapshot(context.projectId(), context.circuitId()).getAsJsonArray("wires").size());

    final var removeWire = new JsonObject();
    final var wireIds = new JsonArray();
    wireIds.add(wireId);
    removeWire.addProperty("projectId", context.projectId());
    removeWire.addProperty("expectedRevision", 4);
    removeWire.addProperty("operationId", "service-stream-remove-wire");
    removeWire.add("wireIds", wireIds);
    assertEquals(5, call("remove_wires", removeWire, 64).get("revision").getAsLong());

    final var removeComponent = new JsonObject();
    final var componentIds = new JsonArray();
    componentIds.add(componentId);
    removeComponent.addProperty("projectId", context.projectId());
    removeComponent.addProperty("expectedRevision", 5);
    removeComponent.addProperty("operationId", "service-stream-remove-component");
    removeComponent.add("componentIds", componentIds);
    assertEquals(
        6, call("remove_components", removeComponent, 65).get("revision").getAsLong());
    assertEquals(
        baselineComponents,
        snapshot(context.projectId(), context.circuitId()).getAsJsonArray("components").size());

    final var undo = new JsonObject();
    undo.addProperty("projectId", context.projectId());
    undo.addProperty("expectedRevision", 6);
    undo.addProperty("targetOperationId", "service-stream-remove-component");
    call("undo", undo, 66);
    assertNotNull(component(snapshot(context.projectId(), context.circuitId()), componentId));

    final var redo = new JsonObject();
    redo.addProperty("projectId", context.projectId());
    redo.addProperty("expectedRevision", 7);
    redo.addProperty("targetOperationId", "service-stream-remove-component");
    call("redo", redo, 67);
    assertFalse(hasComponent(snapshot(context.projectId(), context.circuitId()), componentId));
  }

  @Test
  void revisionTracksDesktopActionThatPredatesFirstMcpRead() throws Exception {
    executor.run(
        () -> {
          final var circuit = project.getCurrentCircuit();
          final var factory = circuit.getSubcircuitFactory();
          final var component =
              factory.createComponent(
                  com.cburch.logisim.data.Location.create(520, 280, true),
                  factory.createAttributeSet());
          final var mutation = new CircuitMutation(circuit);
          mutation.add(component);
          project.doAction(mutation.toAction(null));
        });

    final var context = projectContext();
    final var after = snapshot(context.projectId(), context.circuitId());

    assertEquals(1, after.get("revision").getAsLong());
  }

  @Test
  void rotatesComponentsAsOneUndoableAction() {
    final var context = projectContext();
    final var before = snapshot(context.projectId(), context.circuitId());
    final var gate =
        before.getAsJsonArray("components").asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(component -> "AND Gate".equals(component.get("factory").getAsString()))
            .findFirst()
            .orElseThrow();
    final var componentId = gate.get("componentId").getAsString();
    final var ids = new JsonArray();
    ids.add(componentId);
    final var arguments = new JsonObject();
    arguments.addProperty("projectId", context.projectId());
    arguments.addProperty("expectedRevision", 0);
    arguments.addProperty("operationId", "r4-rotate");
    arguments.addProperty("quarterTurns", 1);
    arguments.add("componentIds", ids);

    final var rotated = call("rotate_components", arguments, 80);

    assertEquals(1, rotated.get("revision").getAsLong());
    assertEquals(1, project.getUndoActions().size());
    assertFalse(
        gate.getAsJsonObject("attributes").get("facing").equals(
            component(snapshot(context.projectId(), context.circuitId()), componentId)
                .getAsJsonObject("attributes")
                .get("facing")));
  }

  @Test
  void setsCircuitAttributesThroughOneUndoableAction() {
    final var context = projectContext();
    final var values = new JsonObject();
    values.addProperty("clabel", "r4-circuit-label");
    final var arguments = new JsonObject();
    arguments.addProperty("projectId", context.projectId());
    arguments.addProperty("circuitId", context.circuitId());
    arguments.addProperty("expectedRevision", 0);
    arguments.addProperty("operationId", "r4-set-circuit-attributes");
    arguments.add("attributes", values);

    final var result = call("set_circuit_attributes", arguments, 81);

    assertEquals(1, result.get("revision").getAsLong());
    assertEquals(1, project.getUndoActions().size());
    assertEquals(
        "r4-circuit-label",
        snapshot(context.projectId(), context.circuitId())
            .getAsJsonObject("attributes")
            .get("clabel")
            .getAsString());
  }

  @Test
  void batchEditIsAtomicAndCreatesOneUndoEntry() {
    final var context = projectContext();
    final var before = snapshot(context.projectId(), context.circuitId());
    final var add = new JsonObject();
    add.addProperty("type", "add_component");
    add.addProperty("factory", "AND Gate");
    add.addProperty("x", 820);
    add.addProperty("y", 420);
    final var wire = new JsonObject();
    wire.addProperty("type", "add_wire");
    wire.addProperty("x1", 760);
    wire.addProperty("y1", 420);
    wire.addProperty("x2", 800);
    wire.addProperty("y2", 420);
    final var operations = new JsonArray();
    operations.add(add);
    operations.add(wire);
    final var arguments = new JsonObject();
    arguments.addProperty("projectId", context.projectId());
    arguments.addProperty("circuitId", context.circuitId());
    arguments.addProperty("expectedRevision", 0);
    arguments.addProperty("operationId", "r4-batch-success");
    arguments.add("operations", operations);

    final var result = call("batch_edit", arguments, 82);

    assertEquals(1, result.get("revision").getAsLong());
    assertEquals(1, project.getUndoActions().size());
    final var after = snapshot(context.projectId(), context.circuitId());
    assertEquals(
        before.getAsJsonArray("components").size() + 1,
        after.getAsJsonArray("components").size());
    assertEquals(before.getAsJsonArray("wires").size() + 1, after.getAsJsonArray("wires").size());

    final var invalidOperations = operations.deepCopy();
    invalidOperations.get(1).getAsJsonObject().addProperty("x2", 760);
    final var invalid = arguments.deepCopy();
    invalid.addProperty("expectedRevision", 1);
    invalid.addProperty("operationId", "r4-batch-rollback");
    invalid.add("operations", invalidOperations);
    final var failed = callRaw("batch_edit", invalid, 83);

    assertTrue(failed.has("error"));
    assertEquals(1, project.getUndoActions().size());
    final var afterFailure = snapshot(context.projectId(), context.circuitId());
    assertEquals(after.get("revision"), afterFailure.get("revision"));
    assertEquals(after.get("components"), afterFailure.get("components"));
    assertEquals(after.get("wires"), afterFailure.get("wires"));
  }

  @Test
  void managesCircuitsWithUndoAndExplicitDestructiveConfirmation() {
    final var context = projectContext();
    final var create = writeArgs(context.projectId(), 0, "r6-create-circuit");
    create.addProperty("name", "McpAux");

    final var created = call("create_circuit", create, 90);
    final var createdId = created.get("circuitId").getAsString();
    assertEquals(2, project.getLogisimFile().getCircuitCount());
    assertEquals("McpAux", project.getCurrentCircuit().getName());
    assertEquals(2, created.get("revision").getAsLong());

    final var rename = writeArgs(context.projectId(), 2, "r6-rename-circuit");
    rename.addProperty("circuitId", createdId);
    rename.addProperty("name", "McpRenamed");
    assertEquals("McpRenamed", call("rename_circuit", rename, 91).get("name").getAsString());

    final var setMain = writeArgs(context.projectId(), 3, "r6-set-main");
    setMain.addProperty("circuitId", createdId);
    call("set_main_circuit", setMain, 92);
    assertEquals("McpRenamed", project.getLogisimFile().getMainCircuit().getName());

    final var switchBack = writeArgs(context.projectId(), 4, "r6-switch-circuit");
    switchBack.addProperty("circuitId", context.circuitId());
    final var undoCount = project.getUndoActions().size();
    assertEquals(context.circuitId(), call("switch_circuit", switchBack, 93).get("circuitId").getAsString());
    assertEquals(undoCount, project.getUndoActions().size());

    final var refused = writeArgs(context.projectId(), 5, "r6-remove-refused");
    refused.addProperty("circuitId", createdId);
    final var refusal = callRaw("remove_circuit", refused, 94);
    assertEquals(-32012, refusal.getAsJsonObject("error").get("code").getAsInt());
    assertEquals(2, project.getLogisimFile().getCircuitCount());

    final var remove = writeArgs(context.projectId(), 5, "r6-remove-confirmed");
    remove.addProperty("circuitId", createdId);
    remove.addProperty("confirm", true);
    assertEquals(1, call("remove_circuit", remove, 95).get("revision").getAsLong() - 5);
    assertEquals(1, project.getLogisimFile().getCircuitCount());
  }

  @Test
  void saveAsRequiresOverwriteConfirmationAndRoundTrips() throws Exception {
    final var context = projectContext();
    final var destination = tempDir.resolve("mcp-r6.pcirc");
    final var save = writeArgs(context.projectId(), 0, "r6-save-new");
    save.addProperty("path", destination.toString());

    final var saved = call("save_project_as", save, 96);
    assertEquals(destination.toAbsolutePath().toString(), saved.get("savedPath").getAsString());
    assertFalse(saved.get("overwrote").getAsBoolean());
    assertTrue(Files.size(destination) > 0);
    assertFalse(project.isFileDirty());

    final var refused = writeArgs(context.projectId(), 0, "r6-save-refused");
    refused.addProperty("path", destination.toString());
    final var response = callRaw("save_project_as", refused, 97);
    assertEquals(-32012, response.getAsJsonObject("error").get("code").getAsInt());

    final var overwrite = writeArgs(context.projectId(), 0, "r6-save-overwrite");
    overwrite.addProperty("path", destination.toString());
    overwrite.addProperty("confirm", true);
    assertTrue(call("save_project_as", overwrite, 98).get("overwrote").getAsBoolean());
    try (InputStream input = Files.newInputStream(destination)) {
      assertNotNull(new Loader(null).openLogisimFile(input));
    }
  }

  @Test
  void simulatorControlsDoNotCreateEditUndoEntries() {
    final var context = projectContext();
    final var read = new JsonObject();
    read.addProperty("projectId", context.projectId());
    final var before = call("get_simulator_state", read, 99);
    final var undoCount = project.getUndoActions().size();

    final var mode = writeArgs(context.projectId(), 0, "r6-sim-mode");
    mode.addProperty("autoPropagation", false);
    mode.addProperty("tickFrequency", 8.0);
    final var modeResult = call("set_simulator_mode", mode, 100);
    assertFalse(modeResult.get("autoPropagation").getAsBoolean());
    assertEquals(8.0, modeResult.get("tickFrequency").getAsDouble());

    final var step = writeArgs(context.projectId(), 0, "r6-sim-step");
    call("simulator_step", step, 101);
    final var reset = writeArgs(context.projectId(), 0, "r6-sim-reset");
    call("simulator_reset", reset, 102);
    assertEquals(undoCount, project.getUndoActions().size());
    assertEquals(before.get("revision"), call("get_simulator_state", read, 103).get("revision"));

    final var invalidTick = writeArgs(context.projectId(), 0, "r6-sim-invalid-tick");
    invalidTick.addProperty("count", 0);
    assertEquals(-32602, callRaw("simulator_tick", invalidTick, 104).getAsJsonObject("error").get("code").getAsInt());
  }

  @Test
  void jarLibraryLoadRequiresConfirmation() {
    final var context = projectContext();
    final var args = writeArgs(context.projectId(), 0, "r6-jar-no-confirm");
    args.addProperty("kind", "jar");
    args.addProperty("path", tempDir.resolve("dummy.jar").toString());
    args.addProperty("className", "com.example.Lib");
    final var response = callRaw("load_library", args, 200);
    assertEquals(-32012, response.getAsJsonObject("error").get("code").getAsInt());
    final var data = response.getAsJsonObject("error").getAsJsonObject("data");
    assertTrue(data.get("requiresConfirmation").getAsBoolean());
  }

  @Test
  void testVectorJobLifecycle() throws Exception {
    final var context = projectContext();
    final var vectorPath = tempDir.resolve("and2.txt");
    Files.writeString(vectorPath, "A B Out\n0 0 0\n0 1 0\n1 0 0\n1 1 1\n");

    final var runArgs = new JsonObject();
    runArgs.addProperty("projectId", context.projectId());
    runArgs.addProperty("vectorPath", vectorPath.toString());
    final var jobResponse = call("run_test_vector", runArgs, 201);
    assertTrue(jobResponse.has("jobId"));
    final var jobId = jobResponse.get("jobId").getAsString();

    final var listArgs = new JsonObject();
    listArgs.addProperty("projectId", context.projectId());
    final var listed = call("list_jobs", listArgs, 202);
    assertTrue(listed.get("count").getAsInt() >= 1);

    JsonObject jobStatus = null;
    for (int i = 0; i < 50; i++) {
      final var getArgs = new JsonObject();
      getArgs.addProperty("jobId", jobId);
      jobStatus = call("get_job", getArgs, 210 + i);
      final var status = jobStatus.get("status").getAsString();
      if ("succeeded".equals(status) || "failed".equals(status) || "canceled".equals(status)) break;
      Thread.sleep(100);
    }
    assertNotNull(jobStatus);
    final var finalStatus = jobStatus.get("status").getAsString();
    assertTrue("succeeded".equals(finalStatus) || "failed".equals(finalStatus),
        "Unexpected terminal status: " + finalStatus);

    final var cancelArgs = new JsonObject();
    cancelArgs.addProperty("jobId", "nonexistent-job-id");
    final var canceled = call("cancel_job", cancelArgs, 270);
    assertFalse(canceled.get("canceled").getAsBoolean());

    final var removeArgs = new JsonObject();
    removeArgs.addProperty("jobId", jobId);
    final var removed = call("remove_job", removeArgs, 280);
    assertTrue(removed.get("removed").getAsBoolean());

    final var getAfterRemove = new JsonObject();
    getAfterRemove.addProperty("jobId", jobId);
    assertEquals(-32007, callRaw("get_job", getAfterRemove, 281).getAsJsonObject("error").get("code").getAsInt());
  }

  @Test
  void configureSimulatorDoesNotCheckRevision() {
    final var context = projectContext();
    final var add = new JsonObject();
    add.addProperty("projectId", context.projectId());
    add.addProperty("circuitId", context.circuitId());
    add.addProperty("factory", "AND Gate");
    add.addProperty("x", 500);
    add.addProperty("y", 220);
    add.addProperty("expectedRevision", 0);
    call("add_component", add, 400);

    final var configArgs = new JsonObject();
    configArgs.addProperty("projectId", context.projectId());
    configArgs.addProperty("autoPropagation", false);
    final var result = call("configure_simulator", configArgs, 401);
    assertFalse(result.get("autoPropagation").getAsBoolean());
    assertEquals(1, result.get("revision").getAsLong());
    assertEquals(1, project.getUndoActions().size());
  }

  @Test
  void pathPolicyRejectsOutsideAllowedRoots() {
    final var context = projectContext();
    final var args = writeArgs(context.projectId(), 0, "r6-path-reject");
    args.addProperty("path", tempDir.getParent().resolve("outside-allowed.pcirc").toString());
    final var response = callRaw("save_project_as", args, 500);
    assertEquals(-32016, response.getAsJsonObject("error").get("code").getAsInt());
  }

  private ProjectContext projectContext() {
    final var projects = call("list_projects", new JsonObject(), 20);
    final var projectId = projects.getAsJsonArray("projects").get(0).getAsJsonObject().get("projectId").getAsString();
    final var args = new JsonObject();
    args.addProperty("projectId", projectId);
    final var circuits = call("list_circuits", args, 21);
    final var circuitId = circuits.getAsJsonArray("circuits").get(0).getAsJsonObject().get("circuitId").getAsString();
    return new ProjectContext(projectId, circuitId);
  }

  private JsonObject snapshot(String projectId, String circuitId) {
    final var args = new JsonObject();
    args.addProperty("projectId", projectId);
    args.addProperty("circuitId", circuitId);
    return call("get_circuit_snapshot", args, 22);
  }

  private static JsonObject writeArgs(String projectId, long revision, String operationId) {
    final var args = new JsonObject();
    args.addProperty("projectId", projectId);
    args.addProperty("expectedRevision", revision);
    args.addProperty("operationId", operationId);
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

  private JsonObject dispatch(String method, JsonObject params, int id) {
    final var request = new JsonObject();
    request.addProperty("jsonrpc", "2.0");
    request.addProperty("id", id);
    request.addProperty("method", method);
    if (params != null) request.add("params", params);
    return dispatcher.dispatch(request);
  }

  private static void assertStableComponentIds(JsonArray components) {
    assertEquals(components.size(), components.asList().stream().map(item -> item.getAsJsonObject().get("componentId").getAsString()).distinct().count());
  }

  private static void assertLocation(
      JsonObject snapshot, String componentId, int expectedX, int expectedY) {
    final var component =
        snapshot.getAsJsonArray("components").asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(item -> componentId.equals(item.get("componentId").getAsString()))
            .findFirst()
            .orElseThrow();
    assertEquals(expectedX, component.getAsJsonObject("location").get("x").getAsInt());
    assertEquals(expectedY, component.getAsJsonObject("location").get("y").getAsInt());
  }

  private static JsonObject component(JsonObject snapshot, String componentId) {
    return snapshot.getAsJsonArray("components").asList().stream()
        .map(JsonElement::getAsJsonObject)
        .filter(item -> componentId.equals(item.get("componentId").getAsString()))
        .findFirst()
        .orElseThrow();
  }

  private static boolean hasComponent(JsonObject snapshot, String componentId) {
    return snapshot.getAsJsonArray("components").asList().stream()
        .map(JsonElement::getAsJsonObject)
        .anyMatch(item -> componentId.equals(item.get("componentId").getAsString()));
  }

  @SuppressWarnings("unchecked")
  private static List<Project> mutableOpenProjects() throws ReflectiveOperationException {
    final Field field = Projects.class.getDeclaredField("openProjects");
    field.setAccessible(true);
    return (ArrayList<Project>) field.get(null);
  }

  @Test
  void analyzeCircuitReturnsTruthTableAndExpressions() {
    final var context = projectContext();
    final var args = new JsonObject();
    args.addProperty("projectId", context.projectId());
    args.addProperty("circuitId", context.circuitId());
    final var result = call("analyze_circuit", args, 700);
    assertEquals(2, result.getAsJsonArray("inputs").size());
    assertEquals(1, result.getAsJsonArray("outputs").size());
    assertEquals(4, result.get("rowCount").getAsInt());
    assertFalse(result.get("truncated").getAsBoolean());
    assertEquals(4, result.getAsJsonArray("truthTable").size());
    assertFalse(result.has("expressionsError"), result.toString());
    assertTrue(result.has("expressions"));
    assertTrue(result.getAsJsonObject("expressions").has("Y"));
  }

  @Test
  void exportHtmlRequiresConfirmToOverwrite() {
    final var context = projectContext();
    final var htmlPath = tempDir.resolve("circuit.html").toString();
    final var args1 = new JsonObject();
    args1.addProperty("projectId", context.projectId());
    args1.addProperty("path", htmlPath);
    call("export_html", args1, 710);
    final var args2 = new JsonObject();
    args2.addProperty("projectId", context.projectId());
    args2.addProperty("path", htmlPath);
    final var conflict = callRaw("export_html", args2, 711);
    assertEquals(-32012, conflict.getAsJsonObject("error").get("code").getAsInt());
    assertTrue(conflict.getAsJsonObject("error").has("data"));
    final var args3 = new JsonObject();
    args3.addProperty("projectId", context.projectId());
    args3.addProperty("path", htmlPath);
    args3.addProperty("confirm", true);
    final var result = call("export_html", args3, 712);
    assertTrue(result.get("overwrote").getAsBoolean());
    assertEquals(htmlPath, result.get("savedPath").getAsString());
  }

  @Test
  void setVhdlContentRejectsInvalidContent() {
    final var context = projectContext();
    final var args = new JsonObject();
    args.addProperty("projectId", context.projectId());
    args.addProperty("name", "TestEntity");
    args.addProperty("content", "not valid vhdl content");
    args.addProperty("expectedRevision", 0);
    args.addProperty("operationId", "r7-set-vhdl-invalid");
    final var response = callRaw("set_vhdl_content", args, 720);
    assertEquals(-32602, response.getAsJsonObject("error").get("code").getAsInt());
  }

  private record ProjectContext(String projectId, String circuitId) {}
}
