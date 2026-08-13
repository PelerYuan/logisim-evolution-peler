/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Peler Edition Feature 11. Keeps the server's lock off the event dispatch thread's path.
 *
 * <p>Nothing here reads {@link com.cburch.logisim.prefs.AppPreferences}: the configuration is
 * handed in, which is why {@code start} takes one. See CLAUDE.md for why a test must not touch the
 * preference store.
 */
class McpServerManagerTest {

  private static McpServerConfig enabledOnAnyPort() {
    return new McpServerConfig(
        true, "127.0.0.1", 0, "", McpServerConfig.DEFAULT_MAX_REQUEST_BYTES, false);
  }

  @BeforeEach
  void startFromAKnownState() {
    McpServerManager.getInstance().close();
  }

  @AfterEach
  void stopTheServer() {
    McpServerManager.getInstance().close();
  }

  /**
   * Starting the server must not stop the event dispatch thread from asking about it.
   *
   * <p>This is how the application came to launch without ever showing a window. Starting builds
   * the project service, whose constructor hops to the event dispatch thread; the manager held its
   * lock across that hop; and the event dispatch thread was inside {@code LogisimMenuBar}, building
   * the MCP menu, which asked {@code isRunning()} and so wanted that same lock. Both waited, the
   * main window was never finished, and the process sat there with no interface and no message.
   * The menu no longer asks while it is being built, but anything on that thread may.
   *
   * <p>Same forced interleaving as {@link McpModelExecutorTest}: park the event dispatch thread,
   * let the starter get inside, then release it. With the lock held only around the fields, no
   * interleaving can block, so this cannot become flaky without becoming broken.
   */
  @Test
  void startingDoesNotBlockTheEventDispatchThread() throws Exception {
    final var manager = McpServerManager.getInstance();
    final var edtParked = new CountDownLatch(1);
    final var releaseEdt = new CountDownLatch(1);
    final var edtAnswered = new CountDownLatch(1);
    final var started = new CountDownLatch(1);
    final var failures = new AtomicInteger();

    SwingUtilities.invokeLater(
        () -> {
          edtParked.countDown();
          try {
            releaseEdt.await(5, TimeUnit.SECONDS);
            manager.isRunning();
            edtAnswered.countDown();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    assertTrue(edtParked.await(5, TimeUnit.SECONDS), "the event dispatch thread never started");

    final var starter =
        new Thread(
            () -> {
              try {
                manager.start(enabledOnAnyPort());
                started.countDown();
              } catch (Exception e) {
                failures.incrementAndGet();
              }
            },
            "mcp-server-manager-test-starter");
    starter.start();
    // Long enough for the starter to be inside start(), which is where the lock used to be held
    // while it waited for the event dispatch thread.
    Thread.sleep(250);
    releaseEdt.countDown();

    try {
      assertTrue(
          edtAnswered.await(5, TimeUnit.SECONDS),
          "the event dispatch thread is stuck waiting for the MCP server's lock -- the "
              + "application would never finish opening its window");
      assertTrue(started.await(10, TimeUnit.SECONDS), "the server never finished starting");
      assertEquals(0, failures.get(), "starting the server threw");
      assertTrue(manager.isRunning());
    } finally {
      // Unwedges the shared event dispatch thread if this ever fails, so one regression does not
      // take the rest of the run with it.
      starter.interrupt();
      starter.join(5000);
    }
  }

  @Test
  void disabledConfigurationBindsNothing() throws Exception {
    final var manager = McpServerManager.getInstance();

    manager.start(
        new McpServerConfig(
            false, "127.0.0.1", 0, "", McpServerConfig.DEFAULT_MAX_REQUEST_BYTES, false));

    assertFalse(manager.isRunning());
    assertEquals(null, manager.endpoint());
  }

  @Test
  void closingTwiceIsHarmless() throws Exception {
    final var manager = McpServerManager.getInstance();
    manager.start(enabledOnAnyPort());
    assertTrue(manager.isRunning());

    manager.close();
    manager.close();

    assertFalse(manager.isRunning());
    assertEquals(null, manager.endpoint());
  }
}
