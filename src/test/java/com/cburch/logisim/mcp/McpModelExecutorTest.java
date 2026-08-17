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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
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

  /**
   * A call from the event dispatch thread must not be able to wait behind a worker call.
   *
   * <p>This is the shape that froze the application on startup. The executor held a monitor across
   * {@code invokeAndWait}, so the simulator thread -- arriving from {@code propagationCompleted} --
   * held it while waiting for the event dispatch thread, and the event dispatch thread, already
   * inside {@code windowOpened} delivering to the project-list listener, waited for that monitor.
   * The interface never came up, and every MCP tool call afterwards blocked on the same monitor
   * forever. A thread dump does not name it a deadlock: one edge is {@code invokeAndWait}'s
   * wait/notify, which the detector does not graph.
   *
   * <p>The interleaving is forced rather than hoped for: the event dispatch thread is parked inside
   * a task, the worker is given time to take the monitor that used to exist, and only then is the
   * event dispatch thread let go. Once the monitor is gone the test cannot be timing-sensitive --
   * every interleaving completes -- so a failure here means the monitor is back.
   */
  @Test
  void callsFromTheEventDispatchThreadDoNotWaitBehindAWorker() throws Exception {
    executor = new McpModelExecutor();
    final var edtParked = new CountDownLatch(1);
    final var releaseEdt = new CountDownLatch(1);
    final var edtFinished = new CountDownLatch(1);
    final var workerFinished = new CountDownLatch(1);

    SwingUtilities.invokeLater(
        () -> {
          edtParked.countDown();
          try {
            releaseEdt.await(5, TimeUnit.SECONDS);
            executor.call(() -> "from the event dispatch thread");
            edtFinished.countDown();
          } catch (Exception e) {
            Thread.currentThread().interrupt();
          }
        });
    assertTrue(edtParked.await(5, TimeUnit.SECONDS), "the event dispatch thread never started");

    final var worker =
        new Thread(
            () -> {
              try {
                executor.call(() -> "from a worker");
                workerFinished.countDown();
              } catch (Exception e) {
                Thread.currentThread().interrupt();
              }
            },
            "mcp-model-executor-test-worker");
    worker.start();
    // Long enough for the worker to reach invokeAndWait, which is where it used to be holding the
    // monitor. Only the broken version depends on this; the fixed one passes at any timing.
    Thread.sleep(250);
    releaseEdt.countDown();

    try {
      assertTrue(
          edtFinished.await(5, TimeUnit.SECONDS),
          "the event dispatch thread is stuck behind a worker call -- the application would be "
              + "frozen here, and every later MCP tool call would hang");
      assertTrue(workerFinished.await(5, TimeUnit.SECONDS), "the worker call never completed");
    } finally {
      // Interrupting the worker releases invokeAndWait, so a regression fails this one test
      // instead of leaving the shared event dispatch thread wedged for the rest of the run.
      worker.interrupt();
      worker.join(5000);
    }
  }

  @Test
  void theModelIsReachableUntilTheExecutorIsStopped() {
    executor = new McpModelExecutor();

    assertTrue(executor.canReachModel(), "the model should be reachable while the server runs");
    executor.close();
    assertFalse(executor.canReachModel(), "a stopped executor cannot reach the model");
  }

  /**
   * Quitting must not hang, which means a shutdown hook must never wait for the event dispatch
   * thread.
   *
   * <p>This is the third freeze in this code with one shape: a hop to the event dispatch thread
   * from somewhere that thread can no longer serve. Here quitting calls {@code System.exit} from
   * it -- {@code ProjectActions.doQuit} always has, and on macOS the Dock's Quit is the ordinary
   * way in -- so the thread is inside the shutdown sequence when the MCP shutdown hook runs and
   * asks it to detach some listeners. The hook waits for a thread that is waiting for the hook.
   * The window stayed on screen, nothing responded, and a terminate signal did nothing either,
   * shutdown having already started, so it took a kill.
   *
   * <p>A separate process, because a shutdown that has begun cannot be called off: there is no
   * state to assert on afterwards, only whether the process ends. Forked rather than simulated for
   * the same reason -- nothing in this JVM can put it into shutdown and get it back.
   */
  @Test
  void shutdownHooksDoNotWaitForTheEventDispatchThread() throws Exception {
    final var command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add("-Djava.awt.headless=true");
    // The child must not reach the real preference store either; see the note on the test task.
    forwardProperty(command, "java.util.prefs.userRoot");
    forwardProperty(command, "java.util.prefs.systemRoot");
    command.add(McpShutdownHookProbe.class.getName());

    final var process = new ProcessBuilder(command).inheritIO().start();
    try {
      assertTrue(
          process.waitFor(30, TimeUnit.SECONDS),
          "the process never exited -- a shutdown hook is waiting for the event dispatch thread, "
              + "which is the freeze seen when quitting from the macOS Dock");
      assertEquals(0, process.exitValue(), "the child exited abnormally");
    } finally {
      // A JVM wedged inside its own shutdown ignores a terminate signal, so a failing run leaves
      // the process behind unless it is killed outright.
      if (process.isAlive()) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
    }
  }

  private static void forwardProperty(List<String> command, String name) {
    final var value = System.getProperty(name);
    if (value != null) command.add("-D" + name + "=" + value);
  }
}
