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
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** End-to-end HTTP test proving a live project mutation reaches a subscribed SSE session. */
class McpHttpSseIntegrationTest {
  private final McpServerManager manager = McpServerManager.getInstance();
  private final HttpClient client = HttpClient.newHttpClient();
  private Project project;
  private List<Project> openProjects;

  @BeforeEach
  void setUp() throws Exception {
    manager.close();
    try (InputStream input = getClass().getResourceAsStream("/htmlexport/and2.circ")) {
      assertNotNull(input);
      project = new Project(new Loader(null).openLogisimFile(input));
      for (final var circuit : project.getLogisimFile().getCircuits()) circuit.setProject(project);
    }
    openProjects = mutableOpenProjects();
    openProjects.add(project);
    manager.start(new McpServerConfig(true, "127.0.0.1", 0, "", 64 * 1024, false));
  }

  @AfterEach
  void tearDown() {
    manager.close();
    if (project != null) project.getSimulator().shutDown();
    if (openProjects != null) openProjects.remove(project);
  }

  @Test
  void subscribedSessionReceivesMutationAndOtherSessionDoesNot() throws Exception {
    final var endpoint = URI.create(manager.endpoint());
    final var subscribedSession = initialize(endpoint, 1);
    final var isolatedSession = initialize(endpoint, 2);
    final var context = projectContext(endpoint, subscribedSession);
    final var uri =
        "logisim://project/" + context.projectId() + "/circuit/" + context.circuitId();
    assertFalse(post(endpoint, subscribedSession, subscribeBody(3, uri)).has("error"));

    final var subscribedStream = openStream(endpoint, subscribedSession);
    final var isolatedStream = openStream(endpoint, isolatedSession);
    try (var subscribedBody = subscribedStream.body(); var isolatedBody = isolatedStream.body()) {
      final var isolatedLine = nextDataLine(isolatedBody);

      final var mutation = addBody(4, context, "qa-http-sse-add");
      final var result = post(endpoint, subscribedSession, mutation);
      assertFalse(result.has("error"), result.toString());

      final var notification =
          nextNotification(
              subscribedBody,
              value -> uri.equals(value.getAsJsonObject("params").get("uri").getAsString()));
      assertEquals("notifications/resources/updated", notification.get("method").getAsString());
      final var params = notification.getAsJsonObject("params");
      assertEquals(uri, params.get("uri").getAsString());
      assertEquals(context.projectId(), params.get("projectId").getAsString());
      assertEquals(1, params.get("revision").getAsLong());
      assertEquals("qa-http-sse-add", params.get("operationId").getAsString());
      assertFalse(isolatedLine.completeOnTimeout(null, 300, TimeUnit.MILLISECONDS).get() != null);
    }
  }

  @Test
  void concurrentStreamsInOneSessionReceiveTheSameMutation() throws Exception {
    final var endpoint = URI.create(manager.endpoint());
    final var session = initialize(endpoint, 20);
    final var context = projectContext(endpoint, session);
    final var uri =
        "logisim://project/" + context.projectId() + "/circuit/" + context.circuitId();
    assertFalse(post(endpoint, session, subscribeBody(21, uri)).has("error"));

    final var firstStream = openStream(endpoint, session);
    final var secondStream = openStream(endpoint, session);
    try (var firstBody = firstStream.body(); var secondBody = secondStream.body()) {
      final var firstNotification = nextNotificationAsync(firstBody, uri);
      final var secondNotification = nextNotificationAsync(secondBody, uri);

      final var result = post(endpoint, session, addBody(22, context, "qa-http-sse-multistream"));
      assertFalse(result.has("error"), result.toString());

      assertNotification(firstNotification.get(5, TimeUnit.SECONDS), uri, "qa-http-sse-multistream");
      assertNotification(secondNotification.get(5, TimeUnit.SECONDS), uri, "qa-http-sse-multistream");
    }
  }

  @Test
  void streamOpenedAfterMutationCatchesUpWithinTheSession() throws Exception {
    final var endpoint = URI.create(manager.endpoint());
    final var session = initialize(endpoint, 30);
    final var context = projectContext(endpoint, session);
    final var uri =
        "logisim://project/" + context.projectId() + "/circuit/" + context.circuitId();
    assertFalse(post(endpoint, session, subscribeBody(31, uri)).has("error"));

    final var result = post(endpoint, session, addBody(32, context, "qa-http-sse-catch-up"));
    assertFalse(result.has("error"), result.toString());

    final var stream = openStream(endpoint, session);
    try (var body = stream.body()) {
      assertNotification(
          nextNotification(
              body,
              value -> uri.equals(value.getAsJsonObject("params").get("uri").getAsString())),
          uri,
          "qa-http-sse-catch-up");
    }
  }

  @Test
  void disconnectedStreamCanReconnectAndReceiveTheMissedMutation() throws Exception {
    final var endpoint = URI.create(manager.endpoint());
    final var session = initialize(endpoint, 40);
    final var context = projectContext(endpoint, session);
    final var uri =
        "logisim://project/" + context.projectId() + "/circuit/" + context.circuitId();
    assertFalse(post(endpoint, session, subscribeBody(41, uri)).has("error"));

    final var disconnected = openStream(endpoint, session);
    disconnected.body().close();
    final var result = post(endpoint, session, addBody(42, context, "qa-http-sse-reconnect"));
    assertFalse(result.has("error"), result.toString());

    final var reconnected = openStream(endpoint, session);
    try (var body = reconnected.body()) {
      assertNotification(
          nextNotification(
              body,
              value -> uri.equals(value.getAsJsonObject("params").get("uri").getAsString())),
          uri,
          "qa-http-sse-reconnect");
    }
  }

  private String initialize(URI endpoint, int id) throws Exception {
    final var response =
        client.send(request(endpoint, null, rpc(id, "initialize", null)), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    return response.headers().firstValue("Mcp-Session-Id").orElseThrow();
  }

  private ProjectContext projectContext(URI endpoint, String sessionId) throws Exception {
    final var projects = structured(post(endpoint, sessionId, toolBody(10, "list_projects", new JsonObject())));
    final var projectId =
        projects.getAsJsonArray("projects").get(0).getAsJsonObject().get("projectId").getAsString();
    final var arguments = new JsonObject();
    arguments.addProperty("projectId", projectId);
    final var circuits = structured(post(endpoint, sessionId, toolBody(11, "list_circuits", arguments)));
    final var circuitId =
        circuits.getAsJsonArray("circuits").get(0).getAsJsonObject().get("circuitId").getAsString();
    return new ProjectContext(projectId, circuitId);
  }

  private HttpResponse<InputStream> openStream(URI endpoint, String sessionId) throws Exception {
    final var request =
        HttpRequest.newBuilder(endpoint)
            .header("Mcp-Session-Id", sessionId)
            .header("Accept", "text/event-stream")
            .GET()
            .build();
    final var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, response.statusCode());
    return response;
  }

  private static JsonObject nextNotification(
      InputStream input, Predicate<JsonObject> predicate) throws Exception {
    final var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      final var line = reader.readLine();
      if (line == null) throw new IllegalStateException("SSE stream closed");
      if (!line.startsWith("data: ")) continue;
      final var notification = JsonParser.parseString(line.substring(6)).getAsJsonObject();
      if (predicate.test(notification)) return notification;
    }
    throw new java.util.concurrent.TimeoutException("Expected SSE notification was not received");
  }

  private static CompletableFuture<String> nextDataLine(InputStream input) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            final var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
              if (line.startsWith("data: ")) return line.substring(6);
            }
            return null;
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  private static CompletableFuture<JsonObject> nextNotificationAsync(InputStream input, String uri) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return nextNotification(
                input, value -> uri.equals(value.getAsJsonObject("params").get("uri").getAsString()));
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  private static void assertNotification(JsonObject notification, String uri, String operationId) {
    assertEquals("notifications/resources/updated", notification.get("method").getAsString());
    final var params = notification.getAsJsonObject("params");
    assertEquals(uri, params.get("uri").getAsString());
    assertEquals(operationId, params.get("operationId").getAsString());
  }

  private JsonObject post(URI endpoint, String sessionId, JsonObject body) throws Exception {
    final var response =
        client.send(request(endpoint, sessionId, body), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    return JsonParser.parseString(response.body()).getAsJsonObject();
  }

  private static HttpRequest request(URI endpoint, String sessionId, JsonObject body) {
    final var request =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json");
    if (sessionId != null) request.header("Mcp-Session-Id", sessionId);
    return request.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
  }

  private static JsonObject rpc(int id, String method, JsonObject params) {
    final var request = new JsonObject();
    request.addProperty("jsonrpc", "2.0");
    request.addProperty("id", id);
    request.addProperty("method", method);
    if (params != null) request.add("params", params);
    return request;
  }

  private static JsonObject toolBody(int id, String name, JsonObject arguments) {
    final var params = new JsonObject();
    params.addProperty("name", name);
    params.add("arguments", arguments);
    return rpc(id, "tools/call", params);
  }

  private static JsonObject subscribeBody(int id, String uri) {
    final var params = new JsonObject();
    params.addProperty("uri", uri);
    return rpc(id, "resources/subscribe", params);
  }

  private static JsonObject addBody(int id, ProjectContext context, String operationId) {
    final var arguments = new JsonObject();
    arguments.addProperty("projectId", context.projectId());
    arguments.addProperty("circuitId", context.circuitId());
    arguments.addProperty("factory", "AND Gate");
    arguments.addProperty("x", 640);
    arguments.addProperty("y", 300);
    arguments.addProperty("expectedRevision", 0);
    arguments.addProperty("operationId", operationId);
    return toolBody(id, "add_component", arguments);
  }

  private static JsonObject structured(JsonObject response) {
    return response.getAsJsonObject("result").getAsJsonObject("structuredContent");
  }

  @SuppressWarnings("unchecked")
  private static List<Project> mutableOpenProjects() throws ReflectiveOperationException {
    final Field field = Projects.class.getDeclaredField("openProjects");
    field.setAccessible(true);
    return (ArrayList<Project>) field.get(null);
  }

  private record ProjectContext(String projectId, String circuitId) {}
}
