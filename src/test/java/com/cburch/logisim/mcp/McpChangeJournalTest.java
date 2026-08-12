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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpChangeJournalTest {
  @Test
  void boundedJournalSignalsResyncWhenTheClientFallsBehind() throws Exception {
    final var journal = new McpChangeJournal();
    for (var revision = 1; revision <= 2050; revision++) {
      journal.record(
          "action",
          "project-1",
          revision,
          List.of("logisim://project/project-1/snapshot"),
          "operation-" + revision);
    }

    final var arguments = new JsonObject();
    arguments.addProperty("afterSequence", 0);
    arguments.addProperty("limit", 10);
    final var result = journal.poll(arguments);

    assertTrue(result.get("resyncRequired").getAsBoolean());
    assertEquals(3, result.get("oldestSequence").getAsLong());
    assertEquals(2050, result.get("latestSequence").getAsLong());
    assertEquals(10, result.getAsJsonArray("changes").size());
    assertTrue(result.get("hasMore").getAsBoolean());
  }

  @Test
  void subscriptionFilterReturnsOnlyMatchingResourceUpdates() throws Exception {
    final var journal = new McpChangeJournal();
    final var subscribed = "logisim://project/project-1/circuit/circuit-1";
    journal.subscribe(subscribed);
    journal.record("action", "project-1", 1, List.of(subscribed), "operation-1");
    journal.record(
        "action",
        "project-2",
        1,
        List.of("logisim://project/project-2/circuit/circuit-2"),
        "operation-2");

    final var arguments = new JsonObject();
    arguments.addProperty("subscribedOnly", true);
    final var result = journal.poll(arguments);

    assertFalse(result.get("resyncRequired").getAsBoolean());
    assertEquals(1, result.getAsJsonArray("changes").size());
    assertEquals(
        "operation-1",
        result
            .getAsJsonArray("changes")
            .get(0)
            .getAsJsonObject()
            .get("operationId")
            .getAsString());
    assertEquals(subscribed, result.getAsJsonArray("subscriptions").get(0).getAsString());
  }

  @Test
  void eventStreamEmitsOnlyTheExactSubscribedUriFromAMultiResourceChange() {
    final var journal = new McpChangeJournal();
    final var subscribed = "logisim://project/project-1/circuit/circuit-1";
    journal.subscribe("session-1", subscribed);
    journal.record(
        "action",
        "project-1",
        1,
        List.of(
            "logisim://projects",
            "logisim://project/project-1/snapshot",
            subscribed),
        "operation-1");

    final var batch = journal.notificationsAfter("session-1", 0);

    assertEquals(1, batch.notifications().size());
    assertEquals(
        subscribed,
        batch.notifications().get(0).getAsJsonObject("params").get("uri").getAsString());
  }

  @Test
  void eventStreamSignalsResyncAfterSubscriptionFallsBehindTheRingBuffer() {
    final var journal = new McpChangeJournal();
    final var subscribed = "logisim://project/project-1/snapshot";
    journal.subscribe("session-1", subscribed);
    for (var revision = 1; revision <= 2050; revision++) {
      journal.record("action", "project-1", revision, List.of(subscribed), "op-" + revision);
    }

    final var batch = journal.notificationsAfter("session-1", 0);

    assertEquals(
        "notifications/logisim/resync-required", batch.notifications().get(0).get("method").getAsString());
    assertEquals(
        subscribed,
        batch
            .notifications()
            .get(0)
            .getAsJsonObject("params")
            .getAsJsonArray("resources")
            .get(0)
            .getAsString());
  }
}
