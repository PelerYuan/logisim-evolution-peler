/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Streamable HTTP transport for one MCP JSON-RPC endpoint. */
final class McpHttpHandler implements HttpHandler, AutoCloseable {
  private static final long SSE_HEARTBEAT_MILLIS = 15_000;

  private final McpJsonRpcDispatcher dispatcher;
  private final McpProjectService projectService;
  private final McpServerConfig config;
  private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();

  McpHttpHandler(
      McpJsonRpcDispatcher dispatcher,
      McpProjectService projectService,
      McpServerConfig config) {
    this.dispatcher = dispatcher;
    this.projectService = projectService;
    this.config = config;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try {
      addCorsHeaders(exchange.getResponseHeaders());
      if (!originAllowed(exchange.getRequestHeaders())) {
        sendError(exchange, 403, "Origin is not allowed");
        return;
      }
      if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(204, -1);
        return;
      }
      if (!authorized(exchange.getRequestHeaders())) {
        exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
        sendError(exchange, 401, "MCP authorization required");
        return;
      }
      if (closed.get()) {
        sendError(exchange, 503, "MCP server is shutting down");
        return;
      }
      switch (exchange.getRequestMethod().toUpperCase(java.util.Locale.ROOT)) {
        case "POST" -> handlePost(exchange);
        case "GET" -> handleEventStream(exchange);
        case "DELETE" -> handleDelete(exchange);
        default -> {
          exchange.getResponseHeaders().set("Allow", "POST, GET, DELETE, OPTIONS");
          sendError(exchange, 405, "Unsupported MCP HTTP method");
        }
      }
    } finally {
      exchange.close();
    }
  }

  private void handlePost(HttpExchange exchange) throws IOException {
    final JsonObject request;
    try {
      final var parsed = JsonParser.parseString(
          readBody(exchange.getRequestBody(), config.maxRequestBytes()));
      if (!parsed.isJsonObject()) {
        sendError(exchange, 400, "Only one JSON-RPC object is supported");
        return;
      }
      request = parsed.getAsJsonObject();
    } catch (JsonParseException | IllegalStateException e) {
      sendError(exchange, 400, "Invalid JSON");
      return;
    }

    final var initialize = isInitialize(request);
    final Session session;
    if (initialize) {
      session = createSession();
    } else {
      session = requireSession(exchange);
      if (session == null) return;
    }

    final var response = dispatcher.dispatch(request, session.id());
    if (response == null) {
      exchange.sendResponseHeaders(202, -1);
      return;
    }
    if (initialize) exchange.getResponseHeaders().set("Mcp-Session-Id", session.id());
    sendJson(exchange, 200, response);
  }

  private void handleEventStream(HttpExchange exchange) throws IOException {
    final var session = requireSession(exchange);
    if (session == null) return;
    final var accept = exchange.getRequestHeaders().getFirst("Accept");
    if (accept == null || !accept.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream")) {
      sendError(exchange, 406, "Accept must include text/event-stream");
      return;
    }

    final var stream = new EventStream(exchange, session.startSequence());
    if (!session.streams().add(stream)) {
      sendError(exchange, 503, "Unable to open MCP event stream");
      return;
    }
    final var headers = exchange.getResponseHeaders();
    headers.set("Content-Type", "text/event-stream; charset=utf-8");
    headers.set("Cache-Control", "no-cache, no-transform");
    headers.set("Connection", "keep-alive");
    exchange.sendResponseHeaders(200, 0);
    try {
      stream.run(session);
    } finally {
      session.streams().remove(stream);
    }
  }

  private void handleDelete(HttpExchange exchange) throws IOException {
    final var session = requireSession(exchange);
    if (session == null) return;
    closeSession(session.id());
    exchange.sendResponseHeaders(204, -1);
  }

  private Session createSession() {
    Session session;
    do {
      session =
          new Session(
              UUID.randomUUID().toString(),
              projectService.latestChangeSequence(),
              ConcurrentHashMap.newKeySet());
    } while (sessions.putIfAbsent(session.id(), session) != null);
    return session;
  }

  private Session requireSession(HttpExchange exchange) throws IOException {
    final var id = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
    if (id == null || id.isBlank()) {
      sendError(exchange, 400, "Mcp-Session-Id is required after initialize");
      return null;
    }
    final var session = sessions.get(id);
    if (session == null) {
      sendError(exchange, 404, "Unknown or expired MCP session");
      return null;
    }
    return session;
  }

  private void closeSession(String id) {
    final var session = sessions.remove(id);
    if (session == null) return;
    for (final var stream : session.streams()) stream.close();
    projectService.closeSession(id);
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    for (final var id : sessions.keySet()) closeSession(id);
    projectService.wakeEventStreams();
  }

  private static boolean isInitialize(JsonObject request) {
    final var method = request.get("method");
    return method != null && method.isJsonPrimitive() && "initialize".equals(method.getAsString());
  }

  private boolean authorized(Headers headers) {
    final var token = config.token();
    if (token == null || token.isBlank()) return true;
    final var value = headers.getFirst("Authorization");
    return value != null && value.equals("Bearer " + token);
  }

  private static boolean originAllowed(Headers headers) {
    final var origin = headers.getFirst("Origin");
    if (origin == null || origin.isBlank() || "null".equals(origin)) return true;
    return origin.equals("http://localhost")
        || origin.startsWith("http://localhost:")
        || origin.equals("http://127.0.0.1")
        || origin.startsWith("http://127.0.0.1:");
  }

  private static void addCorsHeaders(Headers headers) {
    headers.set("Access-Control-Allow-Origin", "http://localhost");
    headers.set("Access-Control-Allow-Headers", "Authorization, Content-Type, Accept, Mcp-Session-Id");
    headers.set("Access-Control-Allow-Methods", "POST, GET, DELETE, OPTIONS");
  }

  private static String readBody(InputStream input, int limit) throws IOException {
    final var length = input.available();
    if (length > limit) throw new IOException("MCP request is too large");
    final var buffer = new byte[8192];
    final var output = new java.io.ByteArrayOutputStream(Math.min(Math.max(length, 0), limit));
    var total = 0;
    int count;
    while ((count = input.read(buffer)) >= 0) {
      total += count;
      if (total > limit) throw new IOException("MCP request is too large");
      output.write(buffer, 0, count);
    }
    return output.toString(StandardCharsets.UTF_8);
  }

  private static void writeSseComment(java.io.OutputStream output, String comment)
      throws IOException {
    output.write((": " + comment + "\n\n").getBytes(StandardCharsets.UTF_8));
    output.flush();
  }

  private static void writeSseData(java.io.OutputStream output, String json) throws IOException {
    output.write(("event: message\ndata: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
    output.flush();
  }

  private static void sendJson(HttpExchange exchange, int status, JsonObject response)
      throws IOException {
    final var data = response.toString().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, data.length);
    exchange.getResponseBody().write(data);
  }

  private static void sendError(HttpExchange exchange, int status, String message)
      throws IOException {
    final var response = new JsonObject();
    response.addProperty("jsonrpc", "2.0");
    response.add("id", JsonNull.INSTANCE);
    final var error = new JsonObject();
    error.addProperty("code", status == 405 ? -32600 : -32603);
    error.addProperty("message", message);
    response.add("error", error);
    sendJson(exchange, status, response);
  }

  private final class EventStream {
    private final HttpExchange exchange;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private long sequence;

    private EventStream(HttpExchange exchange, long sequence) {
      this.exchange = exchange;
      this.sequence = sequence;
    }

    private void run(Session session) throws IOException {
      final var output = exchange.getResponseBody();
      writeSseComment(output, "connected");
      try {
        final var initial = projectService.resourceNotifications(session.id(), sequence);
        writeNotifications(output, initial);
        while (open.get() && !closed.get() && sessions.get(session.id()) == session) {
          final var batch =
              projectService.waitForResourceNotifications(
                  session.id(), sequence, SSE_HEARTBEAT_MILLIS);
          if (batch.notifications().isEmpty()) {
            writeSseComment(output, "keepalive");
            continue;
          }
          writeNotifications(output, batch);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (IOException ignored) {
        // Closing the client response body is the normal way to end an SSE connection.
      }
    }

    private void writeNotifications(
        java.io.OutputStream output, McpChangeJournal.EventBatch batch) throws IOException {
      for (final var notification : batch.notifications()) {
        writeSseData(output, notification.toString());
      }
      sequence = batch.nextSequence();
    }

    private void close() {
      if (!open.compareAndSet(true, false)) return;
      try {
        exchange.getResponseBody().close();
      } catch (IOException ignored) {
        // The peer may already have closed the response body.
      }
    }
  }

  private record Session(String id, long startSequence, java.util.Set<EventStream> streams) {}
}
