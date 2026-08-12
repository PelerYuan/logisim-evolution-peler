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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class McpJsonRpcDispatcherTest {
  @Test
  void initializeAdvertisesTheImplementedCapabilities() {
    final var dispatcher = new McpJsonRpcDispatcher("test-server", "1", null);
    final var response = dispatcher.dispatch(request(1, "initialize"));

    assertEquals(1, response.get("id").getAsInt());
    final var result = response.getAsJsonObject("result");
    assertEquals(McpJsonRpcDispatcher.PROTOCOL_VERSION, result.get("protocolVersion").getAsString());
    assertEquals("test-server", result.getAsJsonObject("serverInfo").get("name").getAsString());
    assertFalse(
        result
            .getAsJsonObject("capabilities")
            .getAsJsonObject("resources")
            .get("subscribe")
            .getAsBoolean());
  }

  @Test
  void listsAndCallsRegisteredToolsWithStructuredContent() {
    final var dispatcher = new McpJsonRpcDispatcher("test-server", "1", null);
    final var schema = new JsonObject();
    schema.addProperty("type", "object");
    dispatcher.registerTool(
        new McpToolDefinition(
            "echo", "Echoes a value", schema, arguments -> arguments));

    final var listed = dispatcher.dispatch(request(1, "tools/list"));
    final var tools = listed.getAsJsonObject("result").getAsJsonArray("tools");
    assertEquals(1, tools.size());
    assertEquals("echo", tools.get(0).getAsJsonObject().get("name").getAsString());

    final var call = request(2, "tools/call");
    final var params = new JsonObject();
    params.addProperty("name", "echo");
    final var arguments = new JsonObject();
    arguments.addProperty("message", "hello");
    params.add("arguments", arguments);
    call.add("params", params);
    final var response = dispatcher.dispatch(call);
    final var result = response.getAsJsonObject("result");
    assertFalse(result.get("isError").getAsBoolean());
    assertEquals("hello", result.getAsJsonObject("structuredContent").get("message").getAsString());
    assertEquals(1, result.getAsJsonArray("content").size());
  }

  @Test
  void returnsStableErrorsForMalformedRequestsAndUnknownTools() {
    final var dispatcher = new McpJsonRpcDispatcher("test-server", "1", null);

    final var invalid = new JsonObject();
    invalid.addProperty("jsonrpc", "1.0");
    invalid.addProperty("id", 7);
    final var invalidResponse = dispatcher.dispatch(invalid);
    assertEquals(-32600, invalidResponse.getAsJsonObject("error").get("code").getAsInt());

    final var missingParams = request(8, "tools/call");
    final var missingParamsResponse = dispatcher.dispatch(missingParams);
    assertEquals(-32602, missingParamsResponse.getAsJsonObject("error").get("code").getAsInt());

    final var unknown = request(9, "tools/call");
    final var unknownParams = new JsonObject();
    unknownParams.addProperty("name", "missing");
    unknown.add("params", unknownParams);
    final var unknownResponse = dispatcher.dispatch(unknown);
    assertEquals(-32602, unknownResponse.getAsJsonObject("error").get("code").getAsInt());
  }

  @Test
  void suppressesResponsesForNotifications() {
    final var dispatcher = new McpJsonRpcDispatcher("test-server", "1", null);
    final var notification = request(null, "notifications/initialized");

    assertNull(dispatcher.dispatch(notification));
  }

  @Test
  void supportsResourcesAndReportsProviderErrors() {
    final var resource =
        new McpJsonRpcDispatcher.McpResourceProvider() {
          @Override
          public JsonObject list() {
            final var result = new JsonObject();
            result.addProperty("count", 1);
            return result;
          }

          @Override
          public JsonObject read(String uri) {
            final var result = new JsonObject();
            result.addProperty("uri", uri);
            return result;
          }
        };
    final var dispatcher = new McpJsonRpcDispatcher("test-server", "1", resource);

    final var list = dispatcher.dispatch(request(1, "resources/list"));
    assertEquals(1, list.getAsJsonObject("result").get("count").getAsInt());
    final var read = request(2, "resources/read");
    final var readParams = new JsonObject();
    readParams.addProperty("uri", "logisim://projects");
    read.add("params", readParams);
    final var readResponse = dispatcher.dispatch(read);
    assertNotNull(readResponse.getAsJsonObject("result"));
    assertEquals(
        "logisim://projects",
        readResponse.getAsJsonObject("result").get("uri").getAsString());
  }

  @Test
  void parsesJsonAndReturnsParseErrorForNonObjects() {
    final var dispatcher = new McpJsonRpcDispatcher("test-server", "1", null);

    final var parseError = dispatcher.dispatchJson("{not-json");
    assertEquals(-32700, parseError.getAsJsonObject("error").get("code").getAsInt());
    final var objectError = dispatcher.dispatchJson("[]");
    assertEquals(-32600, objectError.getAsJsonObject("error").get("code").getAsInt());
    final var valid = dispatcher.dispatchJson("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");
    assertTrue(valid.getAsJsonObject("result").entrySet().isEmpty());
    assertEquals(1, JsonParser.parseString(valid.toString()).getAsJsonObject().get("id").getAsInt());
  }

  @Test
  void toolsCallRejectsNonObjectArguments() {
    final var dispatcher = new McpJsonRpcDispatcher("test-server", "1", null);
    dispatcher.registerTool(new McpToolDefinition("echo", "Echoes", null, args -> args));

    final var call = request(1, "tools/call");
    final var params = new JsonObject();
    params.addProperty("name", "echo");
    params.addProperty("arguments", "not-an-object");
    call.add("params", params);

    final var response = dispatcher.dispatch(call);
    assertEquals(-32602, response.getAsJsonObject("error").get("code").getAsInt());
    assertTrue(response.getAsJsonObject("error").get("message").getAsString()
        .contains("tools/call.arguments must be a JSON object"));
  }

  @Test
  void toolsListIncludesAnnotations() {
    final var dispatcher = new McpJsonRpcDispatcher("test-server", "1", null);

    // read-only tool by name prefix
    dispatcher.registerTool(new McpToolDefinition("get_project", "Gets project", null, args -> args));

    // idempotent tool — schema has operationId in properties
    final var writeSchema = new JsonObject();
    final var props = new JsonObject();
    props.add("operationId", new JsonObject());
    writeSchema.addProperty("type", "object");
    writeSchema.add("properties", props);
    dispatcher.registerTool(new McpToolDefinition("add_component", "Adds component", writeSchema, args -> args));

    // destructive tool by exact name
    dispatcher.registerTool(new McpToolDefinition("remove_circuit", "Removes circuit", null, args -> args));

    // plain tool — no annotations expected
    dispatcher.registerTool(new McpToolDefinition("configure_simulator", "Configures", null, args -> args));

    final var listed = dispatcher.dispatch(request(1, "tools/list"));
    final var tools = listed.getAsJsonObject("result").getAsJsonArray("tools");

    JsonObject getProject = null, addComponent = null, removeCircuit = null, configure = null;
    for (final var elem : tools) {
      final var obj = elem.getAsJsonObject();
      switch (obj.get("name").getAsString()) {
        case "get_project" -> getProject = obj;
        case "add_component" -> addComponent = obj;
        case "remove_circuit" -> removeCircuit = obj;
        case "configure_simulator" -> configure = obj;
        default -> { /* ignored */ }
      }
    }

    assertNotNull(getProject);
    assertTrue(getProject.getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());

    assertNotNull(addComponent);
    assertTrue(addComponent.getAsJsonObject("annotations").get("idempotentHint").getAsBoolean());

    assertNotNull(removeCircuit);
    assertTrue(removeCircuit.getAsJsonObject("annotations").get("destructiveHint").getAsBoolean());

    assertNotNull(configure);
    assertFalse(configure.has("annotations"));
  }

  @Test
  void registeringDuplicateToolThrows() {
    final var dispatcher = new McpJsonRpcDispatcher("test-server", "1", null);
    dispatcher.registerTool(new McpToolDefinition("echo", "First", null, args -> args));

    assertThrows(IllegalArgumentException.class,
        () -> dispatcher.registerTool(new McpToolDefinition("echo", "Duplicate", null, args -> args)));
  }

  private static JsonObject request(Integer id, String method) {
    final var request = new JsonObject();
    request.addProperty("jsonrpc", "2.0");
    if (id != null) request.addProperty("id", id);
    request.addProperty("method", method);
    return request;
  }
}
