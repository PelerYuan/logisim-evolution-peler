/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;

/** Bounded per-owner replay cache for idempotent MCP writes. */
final class McpOperationLedger {
  private static final int MAX_OPERATIONS_PER_PROJECT = 512;

  private final IdentityHashMap<Object, ProjectOperations> projects = new IdentityHashMap<>();

  JsonElement execute(
      Object owner,
      String toolName,
      JsonObject arguments,
      Operation operation)
      throws Exception {
    final var operationId = operationId(arguments);
    if (operationId == null) return operation.execute();

    final var fingerprint = fingerprint(toolName, arguments);
    final var operations =
        projects.computeIfAbsent(owner, ignored -> new ProjectOperations());
    final var previous = operations.get(operationId);
    if (previous != null) {
      if (!previous.fingerprint().equals(fingerprint)) {
        final var data = new JsonObject();
        data.addProperty("operationId", operationId);
        data.addProperty("originalTool", previous.toolName());
        data.addProperty("requestedTool", toolName);
        throw new McpJsonRpcDispatcher.McpRpcException(
            -32010, "operationId was already used with different parameters", data);
      }
      return previous.result().deepCopy();
    }

    final var result = operation.execute();
    final var stored = result == null ? JsonNull.INSTANCE : result.deepCopy();
    if (stored.isJsonObject()) {
      stored.getAsJsonObject().addProperty("operationId", operationId);
      stored.getAsJsonObject().addProperty("idempotentReplay", false);
    }
    operations.put(operationId, new Entry(toolName, fingerprint, stored));
    return stored.deepCopy();
  }

  void forget(Object owner) {
    projects.remove(owner);
  }

  static String operationId(JsonObject arguments)
      throws McpJsonRpcDispatcher.McpRpcException {
    final var value = arguments.get("operationId");
    if (value == null || value.isJsonNull()) return null;
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw new McpJsonRpcDispatcher.McpRpcException(
          -32602, "operationId must be a non-empty string");
    }
    final var operationId = value.getAsString().trim();
    if (operationId.isEmpty()) {
      throw new McpJsonRpcDispatcher.McpRpcException(
          -32602, "operationId must be a non-empty string");
    }
    if (operationId.length() > 128) {
      throw new McpJsonRpcDispatcher.McpRpcException(
          -32602, "operationId must not exceed 128 characters");
    }
    return operationId;
  }

  private static String fingerprint(String toolName, JsonObject arguments) {
    final var copy = arguments.deepCopy();
    copy.remove("operationId");
    final var result = new StringBuilder(toolName.length() + 64);
    result.append(toolName).append(':');
    appendCanonical(result, copy);
    return result.toString();
  }

  private static void appendCanonical(StringBuilder result, JsonElement value) {
    if (value == null || value.isJsonNull()) {
      result.append("null");
    } else if (value.isJsonPrimitive()) {
      result.append(value);
    } else if (value.isJsonArray()) {
      result.append('[');
      var first = true;
      for (final var item : value.getAsJsonArray()) {
        if (!first) result.append(',');
        first = false;
        appendCanonical(result, item);
      }
      result.append(']');
    } else {
      result.append('{');
      final var names = new ArrayList<String>();
      for (final var entry : value.getAsJsonObject().entrySet()) names.add(entry.getKey());
      names.sort(String::compareTo);
      var first = true;
      for (final var name : names) {
        if (!first) result.append(',');
        first = false;
        result.append(new com.google.gson.JsonPrimitive(name)).append(':');
        appendCanonical(result, value.getAsJsonObject().get(name));
      }
      result.append('}');
    }
  }

  @FunctionalInterface
  interface Operation {
    JsonElement execute() throws Exception;
  }

  private record Entry(String toolName, String fingerprint, JsonElement result) {}

  private static final class ProjectOperations extends LinkedHashMap<String, Entry> {
    private static final long serialVersionUID = 1L;

    ProjectOperations() {
      super(32, 0.75f, true);
    }

    @Override
    protected boolean removeEldestEntry(java.util.Map.Entry<String, Entry> eldest) {
      return size() > MAX_OPERATIONS_PER_PROJECT;
    }
  }
}
