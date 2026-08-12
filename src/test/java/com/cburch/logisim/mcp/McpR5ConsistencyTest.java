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
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.Simulator;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFileActions;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.Projects;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** R5 contract tests for revisions, idempotency, stable IDs, and resource updates. */
class McpR5ConsistencyTest {
  private Project project;
  private McpModelExecutor executor;
  private McpProjectRegistry registry;
  private McpProjectService service;
  private McpJsonRpcDispatcher dispatcher;
  private List<Project> openProjects;

  @BeforeEach
  void setUp() throws Exception {
    try (InputStream input = getClass().getResourceAsStream("/htmlexport/and2.circ")) {
      assertNotNull(input, "test circuit fixture is missing");
      final var loader = new Loader(null);
      project = new Project(loader.openLogisimFile(input));
      for (final var circuit : project.getLogisimFile().getCircuits()) {
        circuit.setProject(project);
      }
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
  }

  @Test
  void exactOperationReplayReturnsOriginalResultWithoutAnotherMutation() {
    final var context = projectContext();
    final var before = snapshot(context);
    final var arguments = addArguments(context, 640, 300, 0);
    arguments.addProperty("operationId", "qa-add-replay-1");

    final var first = call("add_component", arguments, 1);
    final var replay = call("add_component", arguments, 2);
    final var after = snapshot(context);

    assertEquals(1, first.get("revision").getAsLong());
    assertEquals(first.get("revision"), replay.get("revision"));
    assertEquals(first.get("componentId"), replay.get("componentId"));
    assertEquals("qa-add-replay-1", replay.get("operationId").getAsString());
    assertEquals(
        before.getAsJsonArray("components").size() + 1,
        after.getAsJsonArray("components").size());
    assertEquals(1, after.get("revision").getAsLong());
    assertEquals(1, project.getUndoActions().size());
  }

  @Test
  void staleRevisionHasNoModelUndoOrRevisionSideEffects() {
    final var context = projectContext();
    final var first = addArguments(context, 640, 300, 0);
    first.addProperty("operationId", "qa-conflict-baseline");
    call("add_component", first, 10);

    final var beforeConflict = snapshot(context);
    final var undoCount = project.getUndoActions().size();
    final var stale = addArguments(context, 700, 300, 0);
    stale.addProperty("operationId", "qa-conflict-stale");
    final var response = callRaw("add_component", stale, 11);
    final var afterConflict = snapshot(context);

    assertEquals(-32009, response.getAsJsonObject("error").get("code").getAsInt());
    assertEquals(
        beforeConflict.getAsJsonArray("components").size(),
        afterConflict.getAsJsonArray("components").size());
    assertEquals(beforeConflict.get("revision"), afterConflict.get("revision"));
    assertEquals(undoCount, project.getUndoActions().size());
  }

  @Test
  void guiActionMcpActionUndoAndRedoShareOneMonotonicRevisionStream() throws Exception {
    final var context = projectContext();
    executor.run(
        () -> {
          final var circuit = project.getCurrentCircuit();
          final var factory = circuit.getSubcircuitFactory();
          final var component =
              factory.createComponent(
                  Location.create(520, 280, true), factory.createAttributeSet());
          final var mutation = new CircuitMutation(circuit);
          mutation.add(component);
          project.doAction(mutation.toAction(null));
        });
    assertEquals(1, snapshot(context).get("revision").getAsLong());

    final var add = addArguments(context, 640, 300, 1);
    add.addProperty("operationId", "qa-revision-add");
    assertEquals(2, call("add_component", add, 20).get("revision").getAsLong());

    final var undo = new JsonObject();
    undo.addProperty("projectId", context.projectId());
    undo.addProperty("expectedRevision", 2);
    undo.addProperty("operationId", "qa-revision-undo");
    undo.addProperty("targetOperationId", "qa-revision-add");
    assertEquals(3, call("undo", undo, 21).get("revision").getAsLong());

    final var redo = new JsonObject();
    redo.addProperty("projectId", context.projectId());
    redo.addProperty("expectedRevision", 3);
    redo.addProperty("operationId", "qa-revision-redo");
    redo.addProperty("targetOperationId", "qa-revision-add");
    assertEquals(4, call("redo", redo, 22).get("revision").getAsLong());
    assertEquals(4, snapshot(context).get("revision").getAsLong());
  }

  @Test
  void snapshotsMoveUndoAndRedoKeepTheSameComponentId() {
    final var context = projectContext();
    final var firstSnapshot = snapshot(context);
    final var component = firstSnapshot.getAsJsonArray("components").get(0).getAsJsonObject();
    final var componentId = component.get("componentId").getAsString();
    final var originalLocation = component.getAsJsonObject("location");

    assertEquals(componentId, component(snapshot(context), componentId).get("componentId").getAsString());

    final var move = new JsonObject();
    move.addProperty("componentId", componentId);
    move.addProperty("x", 700);
    move.addProperty("y", 400);
    final var moves = new JsonArray();
    moves.add(move);
    final var moveArguments = new JsonObject();
    moveArguments.addProperty("projectId", context.projectId());
    moveArguments.addProperty("expectedRevision", 0);
    moveArguments.addProperty("operationId", "qa-stable-id-move");
    moveArguments.add("moves", moves);
    call("move_components", moveArguments, 30);
    assertLocation(snapshot(context), componentId, 700, 400);

    final var undo = new JsonObject();
    undo.addProperty("projectId", context.projectId());
    undo.addProperty("expectedRevision", 1);
    undo.addProperty("operationId", "qa-stable-id-undo");
    undo.addProperty("targetOperationId", "qa-stable-id-move");
    call("undo", undo, 31);
    assertLocation(
        snapshot(context),
        componentId,
        originalLocation.get("x").getAsInt(),
        originalLocation.get("y").getAsInt());

    final var redo = new JsonObject();
    redo.addProperty("projectId", context.projectId());
    redo.addProperty("expectedRevision", 2);
    redo.addProperty("operationId", "qa-stable-id-redo");
    redo.addProperty("targetOperationId", "qa-stable-id-move");
    call("redo", redo, 32);
    assertLocation(snapshot(context), componentId, 700, 400);
    assertEquals(componentId, component(snapshot(context), componentId).get("componentId").getAsString());
  }

  @Test
  void initializeAdvertisesResourceSubscriptions() {
    final var initialize = dispatch("initialize", null, 40);

    assertTrue(
        initialize
            .getAsJsonObject("result")
            .getAsJsonObject("capabilities")
            .getAsJsonObject("resources")
            .get("subscribe")
            .getAsBoolean());
  }

  @Test
  void subscribedMutationProducesCurrentRevisionResourceNotifications() {
    final var context = projectContext();
    final var circuitUri =
        "logisim://project/" + context.projectId() + "/circuit/" + context.circuitId();
    final var subscribe = new JsonObject();
    subscribe.addProperty("uri", circuitUri);
    assertFalse(dispatch("resources/subscribe", subscribe, 41).has("error"));

    final var add = addArguments(context, 640, 300, 0);
    add.addProperty("operationId", "qa-subscription-add");
    final var added = call("add_component", add, 42);

    final var pollArguments = new JsonObject();
    pollArguments.addProperty("afterSequence", 0);
    pollArguments.addProperty("subscribedOnly", true);
    final var changes = call("poll_changes", pollArguments, 43);
    assertFalse(changes.get("resyncRequired").getAsBoolean());
    assertFalse(changes.getAsJsonArray("changes").isEmpty());
    for (final var value : changes.getAsJsonArray("changes")) {
      final var change = value.getAsJsonObject();
      assertEquals(added.get("revision").getAsLong(), change.get("revision").getAsLong());
      assertEquals("qa-subscription-add", change.get("operationId").getAsString());
      assertTrue(hasResourceNotification(change, circuitUri));
    }
  }

  @Test
  void operationIdCannotBeReusedForDifferentParameters() {
    final var context = projectContext();
    final var original = addArguments(context, 640, 300, 0);
    original.addProperty("operationId", "qa-operation-id-collision");
    call("add_component", original, 50);

    final var conflicting = addArguments(context, 700, 300, 1);
    conflicting.addProperty("operationId", "qa-operation-id-collision");
    final var response = callRaw("add_component", conflicting, 51);

    assertEquals(-32010, response.getAsJsonObject("error").get("code").getAsInt());
    assertEquals(5, snapshot(context).getAsJsonArray("components").size());
    assertEquals(1, snapshot(context).get("revision").getAsLong());
    assertEquals(1, project.getUndoActions().size());
  }

  @Test
  void missingRevisionIsRejectedAndUndoRefusesAGuiStackHead() throws Exception {
    final var context = projectContext();
    final var missingRevision = addArguments(context, 640, 300, 0);
    missingRevision.remove("expectedRevision");
    assertEquals(
        -32602,
        callRaw("add_component", missingRevision, 60)
            .getAsJsonObject("error")
            .get("code")
            .getAsInt());

    final var owned = addArguments(context, 640, 300, 0);
    owned.addProperty("operationId", "qa-owned-before-gui");
    call("add_component", owned, 61);
    executor.run(
        () -> {
          final var circuit = project.getCurrentCircuit();
          final var factory = circuit.getSubcircuitFactory();
          final var component =
              factory.createComponent(
                  Location.create(720, 340, true), factory.createAttributeSet());
          final var mutation = new CircuitMutation(circuit);
          mutation.add(component);
          project.doAction(mutation.toAction(null));
        });

    final var undo = new JsonObject();
    undo.addProperty("projectId", context.projectId());
    undo.addProperty("expectedRevision", 2);
    undo.addProperty("operationId", "qa-refused-undo");
    undo.addProperty("targetOperationId", "qa-owned-before-gui");
    final var response = callRaw("undo", undo, 62);

    assertEquals(-32011, response.getAsJsonObject("error").get("code").getAsInt());
    assertEquals(2, project.getUndoActions().size());
    assertEquals(2, snapshot(context).get("revision").getAsLong());
  }

  @Test
  void libraryAndSimulatorEventsReachSubscribedProjectResource() throws Exception {
    final var context = projectContext();
    final var projectUri = "logisim://project/" + context.projectId() + "/snapshot";
    final var subscribe = new JsonObject();
    subscribe.addProperty("uri", projectUri);
    assertFalse(dispatch("resources/subscribe", subscribe, 70).has("error"));

    final var beforeLibrary = pollSequence();
    executor.run(() -> project.getLogisimFile().setName("r5-library-event"));
    final var libraryChanges = pollAfter(beforeLibrary);
    assertTrue(hasChangeType(libraryChanges, "project_name_changed"));

    final var beforeSimulator = libraryChanges.get("nextSequence").getAsLong();
    final var reset = new CountDownLatch(1);
    final Simulator.StatusListener observer =
        new Simulator.StatusListener() {
          @Override
          public void simulatorReset(Simulator.Event event) {
            reset.countDown();
          }

          @Override
          public void simulatorStateChanged(Simulator.Event event) {}
        };
    project.getSimulator().addSimulatorListener(observer);
    try {
      project.getSimulator().reset();
      assertTrue(reset.await(5, TimeUnit.SECONDS));
    } finally {
      project.getSimulator().removeSimulatorListener(observer);
    }
    final var simulatorChanges = pollAfter(beforeSimulator);
    assertTrue(hasChangeType(simulatorChanges, "simulator_reset"));
  }

  @Test
  void simulatorResourceCanBeReadAndReceivesSubscribedUpdates() throws Exception {
    final var context = projectContext();
    final var uri = "logisim://project/" + context.projectId() + "/simulation";
    final var read = new JsonObject();
    read.addProperty("uri", uri);
    final var readResponse = dispatch("resources/read", read, 71);
    assertFalse(readResponse.has("error"), readResponse.toString());
    final var text =
        readResponse
            .getAsJsonObject("result")
            .getAsJsonArray("contents")
            .get(0)
            .getAsJsonObject()
            .get("text")
            .getAsString();
    assertEquals(context.projectId(), JsonParser.parseString(text).getAsJsonObject().get("projectId").getAsString());

    final var subscribe = new JsonObject();
    subscribe.addProperty("uri", uri);
    assertFalse(dispatch("resources/subscribe", subscribe, 72).has("error"));
    final var before = pollSequence();
    final var reset = new CountDownLatch(1);
    final Simulator.StatusListener observer =
        new Simulator.StatusListener() {
          @Override
          public void simulatorReset(Simulator.Event event) {
            reset.countDown();
          }

          @Override
          public void simulatorStateChanged(Simulator.Event event) {}
        };
    project.getSimulator().addSimulatorListener(observer);
    try {
      project.getSimulator().reset();
      assertTrue(reset.await(5, TimeUnit.SECONDS));
    } finally {
      project.getSimulator().removeSimulatorListener(observer);
    }
    final var changes = pollAfter(before);
    assertTrue(hasChangeType(changes, "simulator_reset"));
    assertTrue(
        changes.getAsJsonArray("changes").asList().stream()
            .map(JsonElement::getAsJsonObject)
            .flatMap(change -> change.getAsJsonArray("resourceUris").asList().stream())
            .anyMatch(value -> uri.equals(value.getAsString())));
  }

  @Test
  void projectSwitchAndCircuitAttributeEventsArePublished() throws Exception {
    final var context = projectContext();
    final var projectUri = "logisim://project/" + context.projectId() + "/snapshot";
    final var circuitUri =
        "logisim://project/" + context.projectId() + "/circuit/" + context.circuitId();
    for (final var uri : List.of(projectUri, circuitUri)) {
      final var subscribe = new JsonObject();
      subscribe.addProperty("uri", uri);
      assertFalse(dispatch("resources/subscribe", subscribe, 75).has("error"));
    }
    final var before = pollSequence();
    executor.run(
        () -> {
          final var circuit = project.getCurrentCircuit();
          final var mutation = new CircuitMutation(circuit);
          final var attribute = circuit.getStaticAttributes().getAttribute("clabel");
          mutation.setForCircuit(attribute, attribute.parse("desktop-label"));
          project.doAction(mutation.toAction(null));
        });
    final var attributeChanges = pollAfter(before);
    assertTrue(hasChangeType(attributeChanges, "action_complete"));

    final var afterAttribute = attributeChanges.get("nextSequence").getAsLong();
    executor.run(
        () -> {
          final var secondary = new Circuit("secondary", project.getLogisimFile(), project);
          project.doAction(LogisimFileActions.addCircuit(secondary));
          project.setCurrentCircuit(secondary);
        });
    final var switchChanges = pollAfter(afterAttribute);
    assertTrue(hasChangeType(switchChanges, "current_circuit_changed"));
  }

  @Test
  void closeDetachesLibraryAndSimulatorListeners() throws Exception {
    final var closedService = service;
    final var before = closedService.latestChangeSequence();
    service.close();
    service = null;

    executor.run(() -> project.getLogisimFile().setName("after-mcp-close"));
    final var stateChanged = new CountDownLatch(1);
    final Simulator.StatusListener observer =
        new Simulator.StatusListener() {
          @Override
          public void simulatorReset(Simulator.Event event) {}

          @Override
          public void simulatorStateChanged(Simulator.Event event) {
            stateChanged.countDown();
          }
        };
    project.getSimulator().addSimulatorListener(observer);
    try {
      project.getSimulator().setAutoPropagation(!project.getSimulator().isAutoPropagating());
      assertTrue(stateChanged.await(5, TimeUnit.SECONDS));
    } finally {
      project.getSimulator().removeSimulatorListener(observer);
    }
    assertEquals(before, closedService.latestChangeSequence());
  }

  private JsonObject addArguments(
      ProjectContext context, int x, int y, long expectedRevision) {
    final var arguments = new JsonObject();
    arguments.addProperty("projectId", context.projectId());
    arguments.addProperty("circuitId", context.circuitId());
    arguments.addProperty("factory", "AND Gate");
    arguments.addProperty("x", x);
    arguments.addProperty("y", y);
    arguments.addProperty("expectedRevision", expectedRevision);
    return arguments;
  }

  private ProjectContext projectContext() {
    final var projects = call("list_projects", new JsonObject(), 100);
    final var projectId =
        projects
            .getAsJsonArray("projects")
            .get(0)
            .getAsJsonObject()
            .get("projectId")
            .getAsString();
    final var arguments = new JsonObject();
    arguments.addProperty("projectId", projectId);
    final var circuits = call("list_circuits", arguments, 101);
    final var circuitId =
        circuits
            .getAsJsonArray("circuits")
            .get(0)
            .getAsJsonObject()
            .get("circuitId")
            .getAsString();
    return new ProjectContext(projectId, circuitId);
  }

  private JsonObject snapshot(ProjectContext context) {
    final var arguments = new JsonObject();
    arguments.addProperty("projectId", context.projectId());
    arguments.addProperty("circuitId", context.circuitId());
    return call("get_circuit_snapshot", arguments, 102);
  }

  private long pollSequence() {
    return call("poll_changes", new JsonObject(), 103).get("latestSequence").getAsLong();
  }

  private JsonObject pollAfter(long afterSequence) {
    final var arguments = new JsonObject();
    arguments.addProperty("afterSequence", afterSequence);
    arguments.addProperty("subscribedOnly", true);
    return call("poll_changes", arguments, 104);
  }

  private static boolean hasChangeType(JsonObject poll, String type) {
    return poll.getAsJsonArray("changes").asList().stream()
        .map(JsonElement::getAsJsonObject)
        .anyMatch(change -> type.equals(change.get("type").getAsString()));
  }

  private JsonObject call(String name, JsonObject arguments, int id) {
    final var response = callRaw(name, arguments, id);
    assertFalse(response.has("error"), response.toString());
    return response.getAsJsonObject("result").getAsJsonObject("structuredContent");
  }

  private JsonObject callRaw(String name, JsonObject arguments, int id) {
    final var request = new JsonObject();
    request.addProperty("jsonrpc", "2.0");
    request.addProperty("id", id);
    request.addProperty("method", "tools/call");
    final var params = new JsonObject();
    params.addProperty("name", name);
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

  private static JsonObject component(JsonObject snapshot, String componentId) {
    return snapshot.getAsJsonArray("components").asList().stream()
        .map(JsonElement::getAsJsonObject)
        .filter(item -> componentId.equals(item.get("componentId").getAsString()))
        .findFirst()
        .orElseThrow();
  }

  private static void assertLocation(
      JsonObject snapshot, String componentId, int expectedX, int expectedY) {
    final var location = component(snapshot, componentId).getAsJsonObject("location");
    assertEquals(expectedX, location.get("x").getAsInt());
    assertEquals(expectedY, location.get("y").getAsInt());
  }

  private static boolean hasResourceNotification(JsonObject change, String uri) {
    return change.getAsJsonArray("notifications").asList().stream()
        .map(JsonElement::getAsJsonObject)
        .anyMatch(
            notification ->
                "notifications/resources/updated"
                        .equals(notification.get("method").getAsString())
                    && uri.equals(
                        notification.getAsJsonObject("params").get("uri").getAsString()));
  }

  @SuppressWarnings("unchecked")
  private static List<Project> mutableOpenProjects() throws ReflectiveOperationException {
    final Field field = Projects.class.getDeclaredField("openProjects");
    field.setAccessible(true);
    return (ArrayList<Project>) field.get(null);
  }

  private record ProjectContext(String projectId, String circuitId) {}
}
