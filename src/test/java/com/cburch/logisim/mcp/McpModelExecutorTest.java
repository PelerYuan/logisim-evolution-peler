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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class McpModelExecutorTest {
  private McpModelExecutor executor;

  @AfterEach
  void closeExecutor() {
    if (executor != null) executor.close();
  }

  @Test
  void runsWorkerCallsOnTheEventDispatchThread() throws Exception {
    executor = new McpModelExecutor();

    final var onEdt = executor.call(SwingUtilities::isEventDispatchThread);

    assertTrue(onEdt);
  }

  @Test
  void runsDirectCallsInlineWhenAlreadyOnTheEventDispatchThread() throws Exception {
    executor = new McpModelExecutor();
    final var sameThread = new AtomicBoolean();

    SwingUtilities.invokeAndWait(
        () -> {
          try {
            final var edt = Thread.currentThread();
            sameThread.set(executor.call(() -> Thread.currentThread() == edt));
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });

    assertTrue(sameThread.get());
  }

  @Test
  void propagatesCheckedFailuresToTheCaller() throws Exception {
    executor = new McpModelExecutor();
    final var expected = new Exception("expected MCP failure");

    final var actual =
        assertThrows(
            Exception.class,
            () ->
                executor.call(
                    () -> {
                      throw expected;
                    }));

    assertEquals(expected, actual);
  }

  @Test
  void closeRejectsNewCalls() {
    executor = new McpModelExecutor();
    executor.close();

    assertThrows(IllegalStateException.class, () -> executor.call(() -> null));
  }

  @Test
  void serializesConcurrentCallersInSubmissionOrder() throws Exception {
    executor = new McpModelExecutor();
    final ExecutorService callers = Executors.newFixedThreadPool(2);
    try {
      final List<Integer> order = new ArrayList<>();
      final var firstEntered = new CountDownLatch(1);
      final var releaseFirst = new CountDownLatch(1);
      final var first =
          callers.submit(
              () -> {
                executor.run(
                    () -> {
                      order.add(1);
                      firstEntered.countDown();
                      try {
                        releaseFirst.await(2, TimeUnit.SECONDS);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                    });
                return null;
              });
      assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
      final var second =
          callers.submit(
              () -> {
                executor.run(() -> order.add(2));
                return null;
              });
      releaseFirst.countDown();

      first.get(2, TimeUnit.SECONDS);
      second.get(2, TimeUnit.SECONDS);
      assertEquals(List.of(1, 2), order);
    } catch (ExecutionException e) {
      throw (e.getCause() instanceof Exception exception) ? exception : e;
    } finally {
      callers.shutdownNow();
      assertTrue(callers.awaitTermination(2, TimeUnit.SECONDS));
    }
  }
}
