/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;

/**
 * Serializes MCP model access through Swing's event dispatch thread.
 *
 * <p>The circuit model is guarded by {@code CircuitLocker} and GUI actions are expected to run on
 * the EDT. HTTP worker threads therefore never touch a Project directly.
 *
 * <p>The event dispatch thread is the whole of the serialization, and deliberately so. This class
 * used to hold a monitor across {@link SwingUtilities#invokeAndWait}, which deadlocked the
 * application on startup: the simulator thread reaches here from {@code propagationCompleted},
 * takes the monitor and waits for the event dispatch thread, while the event dispatch thread is
 * delivering {@code windowOpened} to the project-list listener, reaches here, and waits for the
 * monitor. Neither can proceed, the interface never responds, and every later tool call piles up
 * behind the same monitor. It does not appear in a thread dump's deadlock report, because one of
 * the two edges is {@code invokeAndWait}'s wait/notify rather than a monitor the detector can
 * graph. The monitor bought nothing to begin with: the event dispatch thread runs one task at a
 * time, so two callers were already serialized without it.
 *
 * <p>The consequence to keep in mind when editing: a task must not itself wait for another
 * {@code call}, because on the event dispatch thread that wait can never be satisfied.
 *
 * <p>The same shape returned once more, from the other end of the application's life. Quitting
 * calls {@code System.exit} from the event dispatch thread -- {@code ProjectActions.doQuit} always
 * has, and on macOS the Dock's Quit is the usual way in. That thread is then inside the shutdown
 * sequence and will never take another event, so a shutdown hook that hops here waits for a thread
 * that is waiting for the hook. The application hung with its window still on screen, and would not
 * even answer a terminate signal, because the JVM was already shutting down. Hence
 * {@link #canReachModel()}: during shutdown the hop is refused rather than attempted, and callers
 * that must run at teardown ask first.
 */
public final class McpModelExecutor implements AutoCloseable {
  private final AtomicBoolean closed = new AtomicBoolean();

  /**
   * Whether a task handed to {@link #call} could still reach the circuit model.
   *
   * <p>False once the executor is stopped, and false while the JVM is shutting down: the event
   * dispatch thread may already be inside {@code System.exit}, in which case waiting for it never
   * ends. Teardown code should ask, and skip work that only tidies state the exiting process is
   * about to drop anyway.
   */
  public boolean canReachModel() {
    if (closed.get()) return false;
    return SwingUtilities.isEventDispatchThread() || !jvmIsShuttingDown();
  }

  public <T> T call(Callable<T> task) throws Exception {
    if (closed.get()) throw new IllegalStateException("MCP model executor is stopped");
    if (SwingUtilities.isEventDispatchThread()) return task.call();
    if (jvmIsShuttingDown()) {
      // Refused, not attempted. See the class comment: the thread this would wait for may be the
      // one that started the shutdown, and then the wait outlives the process.
      throw new IllegalStateException(
          "MCP model task refused: the JVM is shutting down and the event dispatch thread can no "
              + "longer be reached");
    }
    final var result = new Result<T>();
    try {
      SwingUtilities.invokeAndWait(
          () -> {
            try {
              result.value = task.call();
            } catch (Throwable throwable) {
              result.failure = throwable;
            }
          });
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw e;
    } catch (InvocationTargetException e) {
      throw e;
    }
    if (result.failure != null) {
      if (result.failure instanceof Exception exception) throw exception;
      if (result.failure instanceof Error error) throw error;
      throw new RuntimeException(result.failure);
    }
    return result.value;
  }

  /** Executes a fire-and-forget model task and exposes failures as unchecked exceptions. */
  public void run(Runnable task) {
    try {
      call(
          () -> {
            task.run();
            return null;
          });
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("MCP model task failed", e);
    }
  }

  @Override
  public void close() {
    closed.set(true);
  }

  /**
   * Whether the JVM has begun shutting down.
   *
   * <p>Asked by registering a hook and taking it straight back out again: the runtime refuses both
   * once shutdown has started, and that refusal is the only answer it offers. A flag set by a hook
   * of our own would depend on hook ordering, which is unspecified -- this does not. The thread is
   * constructed but never started, so it costs an object and no operating system thread.
   */
  private static boolean jvmIsShuttingDown() {
    final var probe = new Thread(() -> {}, "logisim-mcp-shutdown-probe");
    try {
      Runtime.getRuntime().addShutdownHook(probe);
      Runtime.getRuntime().removeShutdownHook(probe);
      return false;
    } catch (IllegalStateException e) {
      return true;
    }
  }

  private static final class Result<T> {
    private T value;
    private Throwable failure;
  }
}
