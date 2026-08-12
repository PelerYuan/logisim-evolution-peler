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
 */
public final class McpModelExecutor implements AutoCloseable {
  private final AtomicBoolean closed = new AtomicBoolean();

  public <T> T call(Callable<T> task) throws Exception {
    if (closed.get()) throw new IllegalStateException("MCP model executor is stopped");
    if (SwingUtilities.isEventDispatchThread()) return task.call();
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

  private static final class Result<T> {
    private T value;
    private Throwable failure;
  }
}
