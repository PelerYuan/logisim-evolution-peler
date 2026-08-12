/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Peler Edition Feature 11. Drives the shipped {@code bridge.js} the way an MCP host does.
 *
 * <p>The bridge is the only part of this feature that is not Java, so nothing else in the build
 * would notice it breaking -- and its job is entirely about details that are invisible until a
 * client fails: sending the bearer token, keeping the session header, staying alive when Logisim
 * is not there. It is exercised here against a stub of the real endpoint rather than against
 * Logisim, so the test needs no window and no port of ours.
 *
 * <p>Skipped where {@code node} is absent, since the bundle only ever runs under the Node runtime
 * the client ships.
 */
class McpBridgeScriptTest {

  private static final String TOKEN = "test-token-0123456789";
  private static final String SESSION = "test-session-id";

  @TempDir Path directory;

  private HttpServer stub;
  private Process bridge;
  private BufferedWriter toBridge;
  private BufferedReader fromBridge;

  @AfterEach
  void stopEverything() {
    if (bridge != null) bridge.destroyForcibly();
    if (stub != null) stub.stop(0);
  }

  @Test
  void carriesTheTokenAndTheSessionOnEveryRequestAfterInitialize() throws Exception {
    assumeTrue(nodeAvailable(), "node is not on the PATH");
    final var seenAuthorization = new AtomicReference<String>();
    final var seenSession = new AtomicReference<String>();
    startStub(seenAuthorization, seenSession);
    startBridge(endpoint(), TOKEN);

    final var initialize = request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");
    assertEquals("ok", initialize.getAsJsonObject("result").get("stub").getAsString());
    assertEquals("Bearer " + TOKEN, seenAuthorization.get(), "the token was not sent");

    final var listed = request("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
    assertEquals("ok", listed.getAsJsonObject("result").get("stub").getAsString());
    assertEquals(
        SESSION,
        seenSession.get(),
        "the session issued by initialize was not sent back on the next request");
  }

  /** A notification is answered with 202 and no body, and must produce no line on stdout. */
  @Test
  void saysNothingBackForANotification() throws Exception {
    assumeTrue(nodeAvailable(), "node is not on the PATH");
    startStub(new AtomicReference<>(), new AtomicReference<>());
    startBridge(endpoint(), TOKEN);
    request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");

    send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
    // Anything the notification wrongly emitted would be read here instead of the real reply.
    final var next = request("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}");

    assertEquals(7, next.get("id").getAsInt(), "a notification produced a reply of its own");
  }

  /**
   * Closing Logisim must not kill the bridge. The client keeps this process for its whole session,
   * so an exit here is an extension that never works again until it is reinstalled.
   */
  @Test
  void answersAndStaysAliveWhenNothingIsListening() throws Exception {
    assumeTrue(nodeAvailable(), "node is not on the PATH");
    startStub(new AtomicReference<>(), new AtomicReference<>());
    final var address = endpoint();
    startBridge(address, TOKEN);
    request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");

    stub.stop(0);
    stub = null;

    final var reply = request("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
    final var error = reply.getAsJsonObject("error");
    assertNotNull(error, "a dead endpoint has to produce an error, not silence");
    assertEquals(-32001, error.get("code").getAsInt());
    assertTrue(error.get("message").getAsString().contains(address));
    assertTrue(bridge.isAlive(), "the bridge exited when the endpoint went away");
  }

  private String endpoint() {
    return "http://127.0.0.1:" + stub.getAddress().getPort() + "/mcp";
  }

  private void startStub(AtomicReference<String> authorization, AtomicReference<String> session)
      throws IOException {
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext(
        "/mcp",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          final var body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          final var request = JsonParser.parseString(body).getAsJsonObject();
          final var isInitialize =
              request.has("method") && "initialize".equals(request.get("method").getAsString());
          if (isInitialize) {
            exchange.getResponseHeaders().set("Mcp-Session-Id", SESSION);
          } else {
            session.set(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
          }
          if (!request.has("id")) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
          }
          final var result = new JsonObject();
          result.addProperty("stub", "ok");
          final var response = new JsonObject();
          response.addProperty("jsonrpc", "2.0");
          response.add("id", request.get("id"));
          response.add("result", result);
          final var data = response.toString().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, data.length);
          exchange.getResponseBody().write(data);
          exchange.close();
        });
    stub.start();
  }

  private void startBridge(String url, String token) throws IOException {
    final var script = directory.resolve("bridge.js");
    try (final var in =
        McpBundleWriter.class.getResourceAsStream("/resources/logisim/mcp/bridge.js")) {
      assertNotNull(in, "bridge.js is not on the classpath");
      Files.write(script, in.readAllBytes());
    }
    final var builder = new ProcessBuilder("node", script.toString());
    builder.environment().put("LOGISIM_MCP_URL", url);
    builder.environment().put("LOGISIM_MCP_TOKEN", token);
    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
    bridge = builder.start();
    toBridge = new BufferedWriter(new OutputStreamWriter(bridge.getOutputStream(), StandardCharsets.UTF_8));
    fromBridge = new BufferedReader(new InputStreamReader(bridge.getInputStream(), StandardCharsets.UTF_8));
  }

  private void send(String json) throws IOException {
    toBridge.write(json);
    toBridge.write("\n");
    toBridge.flush();
  }

  private JsonObject request(String json) throws IOException {
    send(json);
    final var line = fromBridge.readLine();
    assertNotNull(line, "the bridge closed its output");
    return JsonParser.parseString(line).getAsJsonObject();
  }

  private static boolean nodeAvailable() {
    try {
      final var probe = new ProcessBuilder("node", "--version");
      probe.redirectErrorStream(true);
      probe.redirectOutput(ProcessBuilder.Redirect.DISCARD);
      final var process = probe.start();
      return process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      return false;
    }
  }
}
