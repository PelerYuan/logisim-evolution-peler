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

import com.cburch.logisim.file.Loader;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.Projects;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R9 end-to-end smoke test: exercises the complete MCP user journey through a real HTTP loopback
 * transport without using the singleton McpServerManager. Covers the P0 guarantee that all
 * operations target the same in-memory project with no file reload between steps.
 *
 * <p>Journey: initialize → tools/list → list_projects → get_circuit_snapshot → add_component →
 * set_component_attributes → add_wire → undo → save_project_as
 */
class McpR9SmokeTest {

  @TempDir Path tempDir;

  private Project project;
  private McpModelExecutor executor;
  private McpProjectRegistry registry;
  private McpProjectService service;
  private McpJsonRpcDispatcher dispatcher;
  private McpHttpHandler httpHandler;
  private HttpServer httpServer;
  private URI endpoint;
  private final HttpClient client = HttpClient.newHttpClient();
  private List<Project> openProjects;

  // No authentication token — keeps request helpers simple for the smoke path.
  private static final String TOKEN = "";

  @BeforeEach
  void setUp() throws Exception {
    System.setProperty("logisim.mcp.allowedPaths", tempDir.toString());

    try (InputStream input = getClass().getResourceAsStream("/htmlexport/and2.circ")) {
      assertNotNull(input, "and2.circ fixture is missing from test resources");
      final var loader = new Loader(null);
      project = new Project(loader.openLogisimFile(input));
      for (final var circuit : project.getLogisimFile().getCircuits()) {
        circuit.setProject(project);
      }
    }

    // Mirror the open-projects list that Projects.getOpenProjects() returns so that
    // the project appears globally open (same pattern as McpProjectServiceTest).
    openProjects = mutableOpenProjects();
    openProjects.add(project);

    // Build the MCP stack independently of the singleton McpServerManager.
    executor = new McpModelExecutor();
    registry = new McpProjectRegistry();
    registry.register(project);
    service = new McpProjectService(executor, registry);
    dispatcher = new McpJsonRpcDispatcher("logisim-r9-test", "0", service);
    service.registerTools(dispatcher);

    final var config = new McpServerConfig(true, "127.0.0.1", 0, TOKEN, 64 * 1024, false);
    httpHandler = new McpHttpHandler(dispatcher, service, config);
    httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    httpServer.createContext("/mcp", httpHandler);
    httpServer.setExecutor(Executors.newCachedThreadPool());
    httpServer.start();

    final var port = httpServer.getAddress().getPort();
    endpoint = URI.create("http://127.0.0.1:" + port + "/mcp");
  }

  @AfterEach
  void tearDown() {
    if (httpServer != null) httpServer.stop(0);
    if (httpHandler != null) httpHandler.close();
    if (service != null) service.close();
    if (executor != null) executor.close();
    if (project != null) project.getSimulator().shutDown();
    if (openProjects != null) openProjects.remove(project);
    System.clearProperty("logisim.mcp.allowedPaths");
  }

  // -------------------------------------------------------------------------
  // R9 end-to-end P0 user journey
  // -------------------------------------------------------------------------

  @Test
  void endToEndP0UserJourney() throws Exception {
    // ---- initialize --------------------------------------------------------
    final var initResp = post(rpc("initialize", null, 1), null);
    assertEquals(200, initResp.statusCode(), "initialize must return 200");
    final var sessionId = initResp.headers().firstValue("Mcp-Session-Id").orElse(null);
    assertNotNull(sessionId, "server must issue a session ID on initialize");
    final var initResult = result(initResp);
    assertEquals(
        McpJsonRpcDispatcher.PROTOCOL_VERSION,
        initResult.get("protocolVersion").getAsString(),
        "protocol version mismatch");

    // ---- tools/list --------------------------------------------------------
    final var toolsResp = post(rpc("tools/list", null, 2), sessionId);
    assertEquals(200, toolsResp.statusCode());
    final var toolsList =
        result(toolsResp).getAsJsonArray("tools");
    assertFalse(toolsList.isEmpty(), "tools/list must return at least one tool");
    final var toolNames =
        toolsList.asList().stream()
            .map(t -> t.getAsJsonObject().get("name").getAsString())
            .toList();
    assertTrue(toolNames.contains("list_projects"), "list_projects tool must be registered");
    assertTrue(toolNames.contains("add_component"), "add_component tool must be registered");
    assertTrue(toolNames.contains("add_wire"), "add_wire tool must be registered");
    assertTrue(toolNames.contains("undo"), "undo tool must be registered");
    assertTrue(toolNames.contains("save_project_as"), "save_project_as tool must be registered");

    // ---- list_projects -----------------------------------------------------
    final var projectsResp = toolCall("list_projects", new JsonObject(), 3, sessionId);
    final var projectsResult = toolResult(projectsResp);
    assertEquals(1, projectsResult.get("count").getAsInt(), "exactly one project must be listed");
    final var projectId =
        projectsResult
            .getAsJsonArray("projects")
            .get(0)
            .getAsJsonObject()
            .get("projectId")
            .getAsString();
    assertNotNull(projectId);

    // ---- list_circuits / get circuit ID ------------------------------------
    final var circuitsArgs = new JsonObject();
    circuitsArgs.addProperty("projectId", projectId);
    final var circuitsResp = toolCall("list_circuits", circuitsArgs, 4, sessionId);
    final var circuitsResult = toolResult(circuitsResp);
    final var circuitId =
        circuitsResult
            .getAsJsonArray("circuits")
            .get(0)
            .getAsJsonObject()
            .get("circuitId")
            .getAsString();
    assertNotNull(circuitId);

    // ---- get_circuit_snapshot (P0: in-memory, no file I/O) -----------------
    final var snapArgs = new JsonObject();
    snapArgs.addProperty("projectId", projectId);
    snapArgs.addProperty("circuitId", circuitId);
    final var snapResp = toolCall("get_circuit_snapshot", snapArgs, 5, sessionId);
    final var snap0 = toolResult(snapResp);
    assertEquals(projectId, snap0.get("projectId").getAsString());
    assertEquals(circuitId, snap0.get("circuitId").getAsString());
    assertEquals(0L, snap0.get("revision").getAsLong(), "baseline revision must be 0");
    final int initialComponents = snap0.getAsJsonArray("components").size();
    assertTrue(initialComponents >= 1, "fixture must have at least one component");

    // ---- add_component (AND Gate) ------------------------------------------
    final var addArgs = new JsonObject();
    addArgs.addProperty("projectId", projectId);
    addArgs.addProperty("circuitId", circuitId);
    addArgs.addProperty("factory", "AND Gate");
    addArgs.addProperty("x", 500);
    addArgs.addProperty("y", 220);
    addArgs.addProperty("expectedRevision", 0);
    addArgs.addProperty("operationId", "r9-add-gate");
    final var addResp = toolCall("add_component", addArgs, 6, sessionId);
    final var addResult = toolResult(addResp);
    assertEquals(1L, addResult.get("revision").getAsLong(), "add_component must advance revision to 1");
    final var componentId = addResult.get("componentId").getAsString();
    assertNotNull(componentId);

    // Verify the component appears in a fresh snapshot (P0: same in-memory project).
    final var snap1 = toolResult(toolCall("get_circuit_snapshot", snapArgs, 7, sessionId));
    assertEquals(initialComponents + 1, snap1.getAsJsonArray("components").size(),
        "component count must increase by 1 after add_component");

    // ---- set_component_attributes (label) ----------------------------------
    final var attrValues = new JsonObject();
    attrValues.addProperty("label", "r9_gate");
    final var attrArgs = new JsonObject();
    attrArgs.addProperty("projectId", projectId);
    attrArgs.addProperty("componentId", componentId);
    attrArgs.addProperty("expectedRevision", 1);
    attrArgs.addProperty("operationId", "r9-set-attr");
    attrArgs.add("attributes", attrValues);
    final var attrResp = toolCall("set_component_attributes", attrArgs, 8, sessionId);
    assertEquals(2L, toolResult(attrResp).get("revision").getAsLong(),
        "set_component_attributes must advance revision to 2");

    // ---- add_wire ----------------------------------------------------------
    final var wireArgs = new JsonObject();
    wireArgs.addProperty("projectId", projectId);
    wireArgs.addProperty("circuitId", circuitId);
    wireArgs.addProperty("x1", 460);
    wireArgs.addProperty("y1", 220);
    wireArgs.addProperty("x2", 500);
    wireArgs.addProperty("y2", 220);
    wireArgs.addProperty("expectedRevision", 2);
    wireArgs.addProperty("operationId", "r9-add-wire");
    final var wireResp = toolCall("add_wire", wireArgs, 9, sessionId);
    final var wireResult = toolResult(wireResp);
    assertEquals(3L, wireResult.get("revision").getAsLong(),
        "add_wire must advance revision to 3");
    assertNotNull(wireResult.get("wireId").getAsString());

    // ---- undo (remove the add_wire action) ---------------------------------
    final var undoArgs = new JsonObject();
    undoArgs.addProperty("projectId", projectId);
    undoArgs.addProperty("expectedRevision", 3);
    undoArgs.addProperty("targetOperationId", "r9-add-wire");
    final var undoResp = toolCall("undo", undoArgs, 10, sessionId);
    final var undoResult = toolResult(undoResp);
    assertEquals(4L, undoResult.get("revision").getAsLong(),
        "undo must advance revision to 4");

    // ---- save_project_as ---------------------------------------------------
    final var savePath = tempDir.resolve("r9-smoke.pcirc");
    final var saveArgs = new JsonObject();
    saveArgs.addProperty("projectId", projectId);
    saveArgs.addProperty("expectedRevision", 4);
    saveArgs.addProperty("operationId", "r9-save");
    saveArgs.addProperty("path", savePath.toString());
    final var saveResp = toolCall("save_project_as", saveArgs, 11, sessionId);
    final var saveResult = toolResult(saveResp);
    assertEquals(savePath.toAbsolutePath().toString(), saveResult.get("savedPath").getAsString());
    assertFalse(saveResult.get("overwrote").getAsBoolean());
    assertTrue(Files.size(savePath) > 0, "saved file must be non-empty");
    assertFalse(project.isFileDirty(), "project must not be dirty after save");

    // ---- P0 guarantee: component count is still right ----------------------
    final var snapFinal = toolResult(toolCall("get_circuit_snapshot", snapArgs, 12, sessionId));
    // After undo of add_wire, wire count returns to initial; component count stays +1 (AND Gate).
    assertEquals(initialComponents + 1, snapFinal.getAsJsonArray("components").size(),
        "P0: component count must reflect in-memory state, not a stale file reload");
  }

  // -------------------------------------------------------------------------
  // McpServerManager unit: clientConfigJson() and tokenConfigured()
  // -------------------------------------------------------------------------

  @Test
  void clientConfigJsonIsNullWhenServerNotRunning() {
    final var manager = McpServerManager.getInstance();
    manager.close(); // ensure it is stopped
    assertFalse(manager.isRunning());
    assertEquals(null, manager.clientConfigJson(),
        "clientConfigJson() must return null when the server is not running");
    assertFalse(manager.tokenConfigured(),
        "tokenConfigured() must return false when the server is not running");
  }

  @Test
  void clientConfigJsonContainsEndpointAndNoHeaderWhenNoToken() throws Exception {
    final var manager = McpServerManager.getInstance();
    manager.close();
    try {
      manager.start(new McpServerConfig(true, "127.0.0.1", 0, "", 64 * 1024, false));
      assertTrue(manager.isRunning());
      final var json = manager.clientConfigJson();
      assertNotNull(json, "clientConfigJson() must not be null when the server is running");
      assertTrue(json.contains("\"mcpServers\""), "must contain mcpServers key");
      assertTrue(json.contains("\"logisim\""), "must contain logisim entry");
      assertTrue(json.contains(manager.endpoint()), "must embed the live endpoint URL");
      assertFalse(json.contains("Authorization"), "no Authorization header when token is empty");
      assertFalse(manager.tokenConfigured(), "tokenConfigured() must be false for empty token");
    } finally {
      manager.close();
    }
  }

  @Test
  void clientConfigJsonContainsBearerTokenWhenTokenIsSet() throws Exception {
    final var manager = McpServerManager.getInstance();
    manager.close();
    try {
      manager.start(new McpServerConfig(true, "127.0.0.1", 0, "secret-r9", 64 * 1024, false));
      assertTrue(manager.isRunning());
      final var json = manager.clientConfigJson();
      assertNotNull(json);
      assertTrue(json.contains("Bearer secret-r9"), "must embed the token in Authorization header");
      assertTrue(manager.tokenConfigured(), "tokenConfigured() must be true when token is set");
    } finally {
      manager.close();
    }
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  /** Builds a JSON-RPC request body string. */
  private static String rpc(String method, JsonObject params, int id) {
    final var obj = new JsonObject();
    obj.addProperty("jsonrpc", "2.0");
    obj.addProperty("id", id);
    obj.addProperty("method", method);
    if (params != null) obj.add("params", params);
    return obj.toString();
  }

  /** Builds a tools/call JSON-RPC request body string. */
  private static String toolRpc(String name, JsonObject arguments, int id) {
    final var params = new JsonObject();
    params.addProperty("name", name);
    params.add("arguments", arguments);
    return rpc("tools/call", params, id);
  }

  /** POSTs body to the test endpoint, optionally including a session header. */
  private HttpResponse<String> post(String body, String sessionId) throws Exception {
    final var builder =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (sessionId != null) builder.header("Mcp-Session-Id", sessionId);
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** Shorthand: post a tools/call request. */
  private HttpResponse<String> toolCall(
      String name, JsonObject arguments, int id, String sessionId) throws Exception {
    return post(toolRpc(name, arguments, id), sessionId);
  }

  /**
   * Extracts result from a JSON-RPC response, asserting no error and HTTP 200.
   */
  private static JsonObject result(HttpResponse<String> resp) {
    assertEquals(200, resp.statusCode(), "HTTP status must be 200");
    final var json = JsonParser.parseString(resp.body()).getAsJsonObject();
    assertFalse(json.has("error"), "unexpected JSON-RPC error: " + resp.body());
    return json.getAsJsonObject("result");
  }

  /** Extracts structuredContent from a tools/call response. */
  private static JsonObject toolResult(HttpResponse<String> resp) {
    return result(resp).getAsJsonObject("structuredContent");
  }

  @SuppressWarnings("unchecked")
  private static List<Project> mutableOpenProjects() throws ReflectiveOperationException {
    final Field field = Projects.class.getDeclaredField("openProjects");
    field.setAccessible(true);
    return (ArrayList<Project>) field.get(null);
  }
}
