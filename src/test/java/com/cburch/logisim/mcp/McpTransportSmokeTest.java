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

import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpTransportSmokeTest {
  private final McpServerManager manager = McpServerManager.getInstance();
  private final HttpClient client = HttpClient.newHttpClient();

  @BeforeEach
  void resetServer() {
    manager.close();
  }

  @AfterEach
  void stopServer() {
    manager.close();
  }

  @Test
  void loopbackHttpInitializesWithAuthenticationAndSession() throws Exception {
    manager.start(new McpServerConfig(true, "127.0.0.1", 0, "test-token", 64 * 1024, false));
    final var endpoint = URI.create(manager.endpoint());
    final var request =
        HttpRequest.newBuilder(endpoint)
            .header("Authorization", "Bearer test-token")
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"))
            .build();

    final var response = client.send(request, HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode());
    assertNotNull(response.headers().firstValue("Mcp-Session-Id").orElse(null));
    final var json = JsonParser.parseString(response.body()).getAsJsonObject();
    assertEquals(1, json.get("id").getAsInt());
    assertEquals(
        McpJsonRpcDispatcher.PROTOCOL_VERSION,
        json.getAsJsonObject("result").get("protocolVersion").getAsString());
  }

  @Test
  void loopbackHttpInitializeOwnsSessionIdentifier() throws Exception {
    manager.start(new McpServerConfig(true, "127.0.0.1", 0, "test-token", 64 * 1024, false));
    final var endpoint = URI.create(manager.endpoint());
    final var initialize = post(endpoint, initializeBody(20), "client-chosen-session");

    assertEquals(200, initialize.statusCode());
    final var sessionId = initialize.headers().firstValue("Mcp-Session-Id").orElseThrow();
    assertFalse("client-chosen-session".equals(sessionId));
    assertEquals(sessionId, UUID.fromString(sessionId).toString());
  }

  @Test
  void loopbackHttpAcceptsAnIssuedSession() throws Exception {
    manager.start(new McpServerConfig(true, "127.0.0.1", 0, "test-token", 64 * 1024, false));
    final var endpoint = URI.create(manager.endpoint());
    final var initialize = post(endpoint, initializeBody(21), null);
    final var sessionId = initialize.headers().firstValue("Mcp-Session-Id").orElseThrow();

    assertEquals(200, post(endpoint, pingBody(22), sessionId).statusCode());
  }

  @Test
  void loopbackHttpRejectsMissingSessionAfterInitialize() throws Exception {
    manager.start(new McpServerConfig(true, "127.0.0.1", 0, "test-token", 64 * 1024, false));
    final var endpoint = URI.create(manager.endpoint());
    assertEquals(200, post(endpoint, initializeBody(23), null).statusCode());

    assertEquals(400, post(endpoint, pingBody(24), null).statusCode());
  }

  @Test
  void loopbackHttpRejectsAnUnknownSession() throws Exception {
    manager.start(new McpServerConfig(true, "127.0.0.1", 0, "test-token", 64 * 1024, false));
    final var endpoint = URI.create(manager.endpoint());
    assertEquals(200, post(endpoint, initializeBody(25), null).statusCode());

    assertEquals(404, post(endpoint, pingBody(26), UUID.randomUUID().toString()).statusCode());
  }

  @Test
  void loopbackHttpSessionOpensResourceEventStream() throws Exception {
    manager.start(new McpServerConfig(true, "127.0.0.1", 0, "test-token", 64 * 1024, false));
    final var endpoint = URI.create(manager.endpoint());
    final var initialized = post(endpoint, initializeBody(30), null);
    final var sessionId = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
    final var request =
        HttpRequest.newBuilder(endpoint)
            .header("Authorization", "Bearer test-token")
            .header("Mcp-Session-Id", sessionId)
            .header("Accept", "text/event-stream")
            .GET()
            .build();

    final var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (var body = response.body()) {
      assertEquals(200, response.statusCode());
      assertTrue(
          response
              .headers()
              .firstValue("Content-Type")
              .orElse("")
              .startsWith("text/event-stream"));
    }
  }

  @Test
  void loopbackHttpDeleteClosesOnlyTheRequestedSession() throws Exception {
    manager.start(new McpServerConfig(true, "127.0.0.1", 0, "test-token", 64 * 1024, false));
    final var endpoint = URI.create(manager.endpoint());
    final var first = post(endpoint, initializeBody(31), null);
    final var second = post(endpoint, initializeBody(32), null);
    final var firstId = first.headers().firstValue("Mcp-Session-Id").orElseThrow();
    final var secondId = second.headers().firstValue("Mcp-Session-Id").orElseThrow();

    final var delete =
        HttpRequest.newBuilder(endpoint)
            .header("Authorization", "Bearer test-token")
            .header("Mcp-Session-Id", firstId)
            .DELETE()
            .build();
    assertEquals(204, client.send(delete, HttpResponse.BodyHandlers.ofString()).statusCode());
    assertEquals(404, post(endpoint, pingBody(33), firstId).statusCode());
    assertEquals(200, post(endpoint, pingBody(34), secondId).statusCode());
  }

  @Test
  void loopbackHttpRejectsMissingTokenAndForeignOrigin() throws Exception {
    manager.start(new McpServerConfig(true, "127.0.0.1", 0, "test-token", 64 * 1024, false));
    final var endpoint = URI.create(manager.endpoint());

    final var missingToken =
        HttpRequest.newBuilder(endpoint)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
            .build();
    final var unauthorized = client.send(missingToken, HttpResponse.BodyHandlers.ofString());
    assertEquals(401, unauthorized.statusCode());

    final var foreignOrigin =
        HttpRequest.newBuilder(endpoint)
            .header("Authorization", "Bearer test-token")
            .header("Origin", "https://evil.example")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
            .build();
    final var forbidden = client.send(foreignOrigin, HttpResponse.BodyHandlers.ofString());
    assertEquals(403, forbidden.statusCode());
  }

  @Test
  void loopbackHttpReturnsJsonParseErrors() throws Exception {
    manager.start(new McpServerConfig(true, "127.0.0.1", 0, "", 64 * 1024, false));
    final var request =
        HttpRequest.newBuilder(URI.create(manager.endpoint()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("not-json"))
            .build();

    final var response = client.send(request, HttpResponse.BodyHandlers.ofString());

    assertEquals(400, response.statusCode());
    assertEquals(-32603, JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("error").get("code").getAsInt());
  }

  @Test
  void managerReleasesLoopbackPortOnClose() throws Exception {
    manager.start(new McpServerConfig(true, "127.0.0.1", 0, "", 64 * 1024, false));
    final var endpoint = manager.endpoint();
    assertTrue(manager.isRunning());
    assertNotNull(endpoint);

    manager.close();

    assertFalse(manager.isRunning());
    assertEquals(null, manager.endpoint());
  }

  @Test
  void stdioEmitsOnlyJsonRpcResponses() throws Exception {
    final var input =
        new ByteArrayInputStream(
            ("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}\n"
                    + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                    + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}\n")
                .getBytes(StandardCharsets.UTF_8));
    final var output = new ByteArrayOutputStream();

    McpStdioServer.run(input, output);

    final var lines = output.toString(StandardCharsets.UTF_8).lines().toList();
    assertEquals(2, lines.size());
    for (final var line : lines) {
      final var response = JsonParser.parseString(line).getAsJsonObject();
      assertEquals("2.0", response.get("jsonrpc").getAsString());
      assertTrue(response.has("id"));
    }
  }

  private HttpResponse<String> post(URI endpoint, String body, String sessionId)
      throws Exception {
    final var request =
        HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer test-token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (sessionId != null) request.header("Mcp-Session-Id", sessionId);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String initializeBody(int id) {
    return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"initialize\"}";
  }

  private static String pingBody(int id) {
    return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"ping\"}";
  }
}
