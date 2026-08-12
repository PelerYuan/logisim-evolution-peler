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
 * the EDT.  HTTP worker threads therefore never touch a Project directly.  The small monitor also
 * serializes two callers that arrive while a previous caller is waiting for the EDT.
 */
public final class McpModelExecutor implements AutoCloseable {
  private final Object monitor = new Object();
  private final AtomicBoolean closed = new AtomicBoolean();

  public <T> T call(Callable<T> task) throws Exception {
    if (closed.get()) throw new IllegalStateException("MCP model executor is stopped");
    synchronized (monitor) {
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
