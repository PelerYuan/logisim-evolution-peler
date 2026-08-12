/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Bounded resource-update journal shared by polling and per-session SSE delivery. */
final class McpChangeJournal {
  private static final int MAX_CHANGES = 2048;
  private static final int MAX_POLL_LIMIT = 500;

  private final ArrayDeque<Change> changes = new ArrayDeque<>();
  private final Map<String, LinkedHashSet<String>> subscriptions = new LinkedHashMap<>();
  private final Map<String, Long> subscriptionStarts = new LinkedHashMap<>();
  private long nextSequence = 1;

  synchronized long record(
      String type,
      String projectId,
      long revision,
      Collection<String> resourceUris,
      String operationId) {
    final var uris = new ArrayList<String>();
    for (final var uri : resourceUris) {
      if (uri != null && !uri.isBlank() && !uris.contains(uri)) uris.add(uri);
    }
    final var sequence = nextSequence++;
    changes.addLast(new Change(sequence, type, projectId, revision, List.copyOf(uris), operationId));
    while (changes.size() > MAX_CHANGES) changes.removeFirst();
    notifyAll();
    return sequence;
  }

  synchronized void subscribe(String sessionId, String uri) {
    final var key = normalizeSession(sessionId);
    final var values = subscriptions.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
    subscriptionStarts.put(key + "\n" + uri, nextSequence);
    values.add(uri);
  }

  synchronized void subscribe(String uri) {
    subscribe(McpJsonRpcDispatcher.LOCAL_SESSION_ID, uri);
  }

  synchronized void unsubscribe(String sessionId, String uri) {
    final var key = normalizeSession(sessionId);
    final var values = subscriptions.get(key);
    if (values == null) return;
    values.remove(uri);
    subscriptionStarts.remove(key + "\n" + uri);
    if (values.isEmpty()) subscriptions.remove(key);
  }

  synchronized void closeSession(String sessionId) {
    final var key = normalizeSession(sessionId);
    subscriptions.remove(key);
    subscriptionStarts.keySet().removeIf(value -> value.startsWith(key + "\n"));
    notifyAll();
  }

  synchronized void wakeWaiters() {
    notifyAll();
  }

  synchronized EventBatch waitForNotifications(String sessionId, long afterSequence, long timeoutMillis)
      throws InterruptedException {
    final var deadline = System.nanoTime() + Math.max(0, timeoutMillis) * 1_000_000L;
    EventBatch result;
    while ((result = notifications(sessionId, afterSequence)).notifications().isEmpty()) {
      final var remainingNanos = deadline - System.nanoTime();
      if (remainingNanos <= 0) return result;
      final var millis = remainingNanos / 1_000_000L;
      final var nanos = (int) (remainingNanos % 1_000_000L);
      wait(millis, nanos);
    }
    return result;
  }

  synchronized EventBatch notificationsAfter(String sessionId, long afterSequence) {
    return notifications(sessionId, afterSequence);
  }

  synchronized JsonObject poll(String sessionId, JsonObject arguments)
      throws McpJsonRpcDispatcher.McpRpcException {
    final var afterSequence = longParameter(arguments, "afterSequence", 0);
    final var limit = intParameter(arguments, "limit", 100);
    if (afterSequence < 0) {
      throw new McpJsonRpcDispatcher.McpRpcException(
          -32602, "afterSequence must be zero or greater");
    }
    if (limit < 1 || limit > MAX_POLL_LIMIT) {
      throw new McpJsonRpcDispatcher.McpRpcException(
          -32602, "limit must be between 1 and " + MAX_POLL_LIMIT);
    }
    final var projectId = stringParameter(arguments, "projectId");
    final var resourceUri = stringParameter(arguments, "resourceUri");
    final var subscribedOnly = booleanParameter(arguments, "subscribedOnly", false);
    final var oldestSequence = oldestSequence();
    final var latestSequence = nextSequence - 1;
    final var resyncRequired = afterSequence < oldestSequence - 1;

    final var values = new JsonArray();
    var cursor = afterSequence;
    var hasMore = false;
    for (final var change : changes) {
      if (change.sequence() <= afterSequence) continue;
      if (projectId != null && !projectId.equals(change.projectId())) continue;
      if (resourceUri != null && !change.resourceUris().contains(resourceUri)) continue;
      if (subscribedOnly && !matchesSubscription(sessionId, change)) continue;
      if (values.size() >= limit) {
        hasMore = true;
        break;
      }
      values.add(change.toJson());
      cursor = change.sequence();
    }
    if (!hasMore) cursor = Math.max(afterSequence, latestSequence);

    final var result = new JsonObject();
    result.addProperty("afterSequence", afterSequence);
    result.addProperty("oldestSequence", oldestSequence);
    result.addProperty("latestSequence", latestSequence);
    result.addProperty("nextSequence", cursor);
    result.addProperty("resyncRequired", resyncRequired);
    result.addProperty("hasMore", hasMore);
    result.add("changes", values);
    result.add("subscriptions", subscriptionsJson(sessionId));
    if (resyncRequired) result.add("resyncResources", subscriptionsJson(sessionId));
    return result;
  }

  synchronized JsonObject poll(JsonObject arguments)
      throws McpJsonRpcDispatcher.McpRpcException {
    return poll(McpJsonRpcDispatcher.LOCAL_SESSION_ID, arguments);
  }

  synchronized long latestSequence() {
    return nextSequence - 1;
  }

  private EventBatch notifications(String sessionId, long afterSequence) {
    final var latest = nextSequence - 1;
    final var values = new ArrayList<JsonObject>();
    final var oldest = oldestSequence();
    if (afterSequence < oldest - 1) {
      values.add(resyncNotification(sessionId, oldest, latest));
    }
    for (final var change : changes) {
      if (change.sequence() <= afterSequence) continue;
      values.addAll(subscribedNotifications(sessionId, change));
    }
    return new EventBatch(latest, List.copyOf(values));
  }

  private List<JsonObject> subscribedNotifications(String sessionId, Change change) {
    final var key = normalizeSession(sessionId);
    final var subscribed = subscriptions.get(key);
    if (subscribed == null || subscribed.isEmpty()) return List.of();
    final var result = new ArrayList<JsonObject>();
    for (final var uri : change.resourceUris()) {
      final var start = subscriptionStarts.get(key + "\n" + uri);
      if (subscribed.contains(uri) && (start == null || change.sequence() >= start)) {
        result.add(change.notification(uri));
      }
    }
    return result;
  }

  private long oldestSequence() {
    return changes.isEmpty() ? nextSequence : changes.getFirst().sequence();
  }

  private JsonObject resyncNotification(String sessionId, long oldest, long latest) {
    final var notification = new JsonObject();
    notification.addProperty("jsonrpc", "2.0");
    notification.addProperty("method", "notifications/logisim/resync-required");
    final var params = new JsonObject();
    params.addProperty("oldestSequence", oldest);
    params.addProperty("latestSequence", latest);
    params.add("resources", subscriptionsJson(sessionId));
    notification.add("params", params);
    return notification;
  }

  private JsonArray subscriptionsJson(String sessionId) {
    final var result = new JsonArray();
    final var values = subscriptions.get(normalizeSession(sessionId));
    if (values != null) for (final var uri : values) result.add(uri);
    return result;
  }

  private boolean matchesSubscription(String sessionId, Change change) {
    final var key = normalizeSession(sessionId);
    final var values = subscriptions.get(key);
    if (values == null || values.isEmpty()) return false;
    for (final var uri : change.resourceUris()) {
      final var start = subscriptionStarts.get(key + "\n" + uri);
      if (values.contains(uri) && (start == null || change.sequence() >= start)) return true;
    }
    return false;
  }

  private static String normalizeSession(String sessionId) {
    return sessionId == null || sessionId.isBlank()
        ? McpJsonRpcDispatcher.LOCAL_SESSION_ID
        : sessionId;
  }

  private static long longParameter(JsonObject object, String name, long fallback)
      throws McpJsonRpcDispatcher.McpRpcException {
    final var value = object.get(name);
    if (value == null || value.isJsonNull()) return fallback;
    try {
      return value.getAsLong();
    } catch (RuntimeException e) {
      throw new McpJsonRpcDispatcher.McpRpcException(-32602, name + " must be an integer");
    }
  }

  private static int intParameter(JsonObject object, String name, int fallback)
      throws McpJsonRpcDispatcher.McpRpcException {
    final var value = object.get(name);
    if (value == null || value.isJsonNull()) return fallback;
    try {
      return value.getAsInt();
    } catch (RuntimeException e) {
      throw new McpJsonRpcDispatcher.McpRpcException(-32602, name + " must be an integer");
    }
  }

  private static boolean booleanParameter(JsonObject object, String name, boolean fallback)
      throws McpJsonRpcDispatcher.McpRpcException {
    final var value = object.get(name);
    if (value == null || value.isJsonNull()) return fallback;
    try {
      return value.getAsBoolean();
    } catch (RuntimeException e) {
      throw new McpJsonRpcDispatcher.McpRpcException(-32602, name + " must be a boolean");
    }
  }

  private static String stringParameter(JsonObject object, String name)
      throws McpJsonRpcDispatcher.McpRpcException {
    final var value = object.get(name);
    if (value == null || value.isJsonNull()) return null;
    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
      throw new McpJsonRpcDispatcher.McpRpcException(-32602, name + " must be a string");
    }
    return value.getAsString();
  }

  record EventBatch(long nextSequence, List<JsonObject> notifications) {}

  private record Change(
      long sequence,
      String type,
      String projectId,
      long revision,
      List<String> resourceUris,
      String operationId) {
    JsonObject toJson() {
      final var result = new JsonObject();
      result.addProperty("sequence", sequence);
      result.addProperty("type", type);
      if (projectId != null) result.addProperty("projectId", projectId);
      result.addProperty("revision", revision);
      if (operationId != null) result.addProperty("operationId", operationId);
      final var uris = new JsonArray();
      final var notifications = new JsonArray();
      for (final var uri : resourceUris) {
        uris.add(uri);
        notifications.add(notification(uri));
      }
      result.add("resourceUris", uris);
      result.add("notifications", notifications);
      return result;
    }

    List<JsonObject> notifications() {
      final var values = new ArrayList<JsonObject>();
      for (final var uri : resourceUris) values.add(notification(uri));
      return values;
    }

    private JsonObject notification(String uri) {
      final var notification = new JsonObject();
      notification.addProperty("jsonrpc", "2.0");
      notification.addProperty("method", "notifications/resources/updated");
      final var params = new JsonObject();
      params.addProperty("uri", uri);
      if (projectId != null) params.addProperty("projectId", projectId);
      params.addProperty("revision", revision);
      params.addProperty("eventId", sequence);
      if (operationId != null) params.addProperty("operationId", operationId);
      notification.add("params", params);
      return notification;
    }
  }
}
