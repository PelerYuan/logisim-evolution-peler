/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Transport-independent JSON-RPC 2.0 dispatcher for MCP requests. */
public final class McpJsonRpcDispatcher {
  public static final String PROTOCOL_VERSION = "2025-06-18";
  static final String LOCAL_SESSION_ID = "local";
  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
  private static final ThreadLocal<String> CURRENT_SESSION = new ThreadLocal<>();

  private final Map<String, McpToolDefinition> tools = new LinkedHashMap<>();
  private final McpResourceProvider resources;
  private final String serverName;
  private final String serverVersion;

  public McpJsonRpcDispatcher(
      String serverName, String serverVersion, McpResourceProvider resources) {
    this.serverName = serverName;
    this.serverVersion = serverVersion;
    this.resources = resources;
  }

  public void registerTool(McpToolDefinition definition) {
    if (definition == null || definition.name() == null || definition.name().isBlank()) {
      throw new IllegalArgumentException("MCP tool name must not be blank");
    }
    if (tools.containsKey(definition.name())) {
      throw new IllegalArgumentException("MCP tool already registered: " + definition.name());
    }
    tools.put(definition.name(), definition);
  }

  public void registerTools(Collection<McpToolDefinition> definitions) {
    for (final var definition : definitions) registerTool(definition);
  }

  /** Dispatches one parsed JSON-RPC request. A notification returns {@code null}. */
  public JsonObject dispatch(JsonObject request) {
    return dispatch(request, LOCAL_SESSION_ID);
  }

  /** Dispatches one request in an MCP transport session. */
  JsonObject dispatch(JsonObject request, String sessionId) {
    final var previous = CURRENT_SESSION.get();
    CURRENT_SESSION.set(sessionId == null ? LOCAL_SESSION_ID : sessionId);
    try {
      return dispatchInSession(request);
    } finally {
      if (previous == null) CURRENT_SESSION.remove();
      else CURRENT_SESSION.set(previous);
    }
  }

  static String currentSessionId() {
    final var sessionId = CURRENT_SESSION.get();
    return sessionId == null ? LOCAL_SESSION_ID : sessionId;
  }

  McpResourceProvider resources() {
    return resources;
  }

  private JsonObject dispatchInSession(JsonObject request) {
    if (request == null || !request.has("jsonrpc") || !"2.0".equals(request.get("jsonrpc").getAsString())) {
      return error(request == null ? JsonNull.INSTANCE : request.get("id"), -32600, "Invalid Request", null);
    }
    final var methodElement = request.get("method");
    if (methodElement == null || !methodElement.isJsonPrimitive()) {
      return error(idOf(request), -32600, "Invalid Request", null);
    }
    final var method = methodElement.getAsString();
    final var id = idOf(request);
    try {
      final var result = switch (method) {
        case "notifications/initialized", "notifications/cancelled" -> null;
        case "initialize" -> initialize();
        case "ping" -> new JsonObject();
        case "tools/list" -> listTools();
        case "tools/call" -> callTool(request);
        case "resources/list" -> resources == null ? emptyResources() : resources.list();
        case "resources/templates/list" ->
            resources == null ? emptyResourceTemplates() : resources.templates();
        case "resources/read" -> readResource(request);
        case "resources/subscribe" -> subscribeResource(request);
        case "resources/unsubscribe" -> unsubscribeResource(request);
        default -> throw new McpRpcException(-32601, "Method not found: " + method);
      };
      return result == null || !request.has("id") ? null : success(id, result);
    } catch (McpRpcException e) {
      return request.has("id") ? error(id, e.code, e.getMessage(), e.data) : null;
    } catch (Exception e) {
      return request.has("id") ? error(id, -32603, safeMessage(e), null) : null;
    }
  }

  public JsonObject dispatchJson(String json) {
    try {
      final var parsed = JsonParser.parseString(json);
      if (!parsed.isJsonObject()) {
        return error(JsonNull.INSTANCE, -32600, "Only one JSON-RPC object is supported", null);
      }
      return dispatch(parsed.getAsJsonObject());
    } catch (JsonParseException | IllegalStateException e) {
      return error(JsonNull.INSTANCE, -32700, "Parse error", null);
    }
  }

  private JsonObject initialize() {
    final var result = new JsonObject();
    result.addProperty("protocolVersion", PROTOCOL_VERSION);
    final var capabilities = new JsonObject();
    final var toolsCapability = new JsonObject();
    toolsCapability.addProperty("listChanged", false);
    capabilities.add("tools", toolsCapability);
    final var resourcesCapability = new JsonObject();
    resourcesCapability.addProperty(
        "subscribe", resources != null && resources.supportsSubscriptions());
    resourcesCapability.addProperty("listChanged", false);
    capabilities.add("resources", resourcesCapability);
    result.add("capabilities", capabilities);
    final var serverInfo = new JsonObject();
    serverInfo.addProperty("name", serverName);
    serverInfo.addProperty("version", serverVersion);
    result.add("serverInfo", serverInfo);
    return result;
  }

  private JsonObject listTools() {
    final var result = new JsonObject();
    final var values = new JsonArray();
    for (final var definition : tools.values()) {
      final var tool = new JsonObject();
      tool.addProperty("name", definition.name());
      tool.addProperty("description", definition.description());
      tool.add("inputSchema", definition.inputSchema() == null ? objectSchema() : definition.inputSchema());
      final var annotations = toolAnnotations(definition);
      if (annotations.size() > 0) tool.add("annotations", annotations);
      values.add(tool);
    }
    result.add("tools", values);
    return result;
  }

  private static JsonObject toolAnnotations(McpToolDefinition def) {
    final var name = def.name();
    final var annotations = new JsonObject();
    final var isReadOnly = name.startsWith("get_") || name.startsWith("list_")
        || name.startsWith("find_") || name.startsWith("poll_")
        || name.equals("analyze_circuit") || name.equals("get_available_tools")
        || name.equals("get_simulator_state");
    if (isReadOnly) annotations.addProperty("readOnlyHint", true);
    final var isDestructive = name.equals("remove_components") || name.equals("remove_wires")
        || name.equals("remove_circuit") || name.equals("close_project")
        || name.equals("unload_library") || name.equals("cancel_job") || name.equals("remove_job");
    if (isDestructive) annotations.addProperty("destructiveHint", true);
    final var schema = def.inputSchema();
    final var isIdempotent = schema != null && schema.has("properties")
        && schema.getAsJsonObject("properties").has("operationId");
    if (isIdempotent) annotations.addProperty("idempotentHint", true);
    return annotations;
  }

  private JsonObject callTool(JsonObject request) throws Exception {
    final var params = objectParam(request);
    final var name = stringParam(params, "name", true);
    final var definition = tools.get(name);
    if (definition == null) throw new McpRpcException(-32602, "Unknown tool: " + name);
    final JsonObject args;
    if (!params.has("arguments") || params.get("arguments").isJsonNull()) {
      args = new JsonObject();
    } else if (!params.get("arguments").isJsonObject()) {
      throw new McpRpcException(-32602, "tools/call.arguments must be a JSON object");
    } else {
      args = params.getAsJsonObject("arguments");
    }
    final var value = definition.handler().handle(args);
    final var result = new JsonObject();
    final var content = new JsonArray();
    final var text = new JsonObject();
    text.addProperty("type", "text");
    text.addProperty("text", value == null ? "null" : GSON.toJson(value));
    content.add(text);
    result.add("content", content);
    result.add("structuredContent", value == null ? new JsonObject() : value);
    result.addProperty("isError", false);
    return result;
  }

  private JsonObject readResource(JsonObject request) throws Exception {
    if (resources == null) throw new McpRpcException(-32601, "Resources are not available");
    final var uri = stringParam(objectParam(request), "uri", true);
    return resources.read(uri);
  }

  private JsonObject subscribeResource(JsonObject request) throws Exception {
    if (resources == null) throw new McpRpcException(-32601, "Resources are not available");
    final var uri = stringParam(objectParam(request), "uri", true);
    resources.subscribe(uri);
    return new JsonObject();
  }

  private JsonObject unsubscribeResource(JsonObject request) throws Exception {
    if (resources == null) throw new McpRpcException(-32601, "Resources are not available");
    final var uri = stringParam(objectParam(request), "uri", true);
    resources.unsubscribe(uri);
    return new JsonObject();
  }

  private static JsonObject emptyResources() {
    final var result = new JsonObject();
    result.add("resources", new JsonArray());
    return result;
  }

  private static JsonObject emptyResourceTemplates() {
    final var result = new JsonObject();
    result.add("resourceTemplates", new JsonArray());
    return result;
  }

  private static JsonObject objectSchema() {
    final var schema = new JsonObject();
    schema.addProperty("type", "object");
    return schema;
  }

  private static JsonObject objectParam(JsonObject request) throws McpRpcException {
    if (!request.has("params") || !request.get("params").isJsonObject()) {
      throw new McpRpcException(-32602, "params must be an object");
    }
    return request.getAsJsonObject("params");
  }

  static String stringParam(JsonObject object, String name, boolean required) throws McpRpcException {
    final var value = object.get(name);
    if (value == null || value.isJsonNull()) {
      if (required) throw new McpRpcException(-32602, "Missing parameter: " + name);
      return null;
    }
    if (!value.isJsonPrimitive()) throw new McpRpcException(-32602, "Parameter must be a string: " + name);
    return value.getAsString();
  }

  private static JsonElement idOf(JsonObject request) {
    return request != null && request.has("id") ? request.get("id") : JsonNull.INSTANCE;
  }

  private static JsonObject success(JsonElement id, JsonObject result) {
    final var response = new JsonObject();
    response.addProperty("jsonrpc", "2.0");
    response.add("id", id == null ? JsonNull.INSTANCE : id);
    response.add("result", result);
    return response;
  }

  private static JsonObject error(JsonElement id, int code, String message, JsonElement data) {
    final var response = new JsonObject();
    response.addProperty("jsonrpc", "2.0");
    response.add("id", id == null ? JsonNull.INSTANCE : id);
    final var error = new JsonObject();
    error.addProperty("code", code);
    error.addProperty("message", message);
    if (data != null && !data.isJsonNull()) error.add("data", data);
    response.add("error", error);
    return response;
  }

  private static String safeMessage(Exception e) {
    return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
  }

  static class McpRpcException extends Exception {
    private final int code;
    private final JsonElement data;

    McpRpcException(int code, String message) {
      this(code, message, null);
    }

    McpRpcException(int code, String message, JsonElement data) {
      super(message);
      this.code = code;
      this.data = data;
    }
  }

  public interface McpResourceProvider {
    JsonObject list() throws Exception;

    JsonObject read(String uri) throws Exception;

    default JsonObject templates() throws Exception {
      return emptyResourceTemplates();
    }

    default boolean supportsSubscriptions() {
      return false;
    }

    default void subscribe(String uri) throws Exception {}

    default void unsubscribe(String uri) throws Exception {}
  }
}
