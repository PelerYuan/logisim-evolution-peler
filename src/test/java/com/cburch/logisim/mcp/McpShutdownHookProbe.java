/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import javax.swing.SwingUtilities;

/**
 * The child process for {@code McpModelExecutorTest}'s shutdown-hook test, and not a test itself.
 *
 * <p>Reproduces quitting the application: a shutdown hook that wants the circuit model, and a
 * {@code System.exit} issued from the event dispatch thread, which is how {@code
 * ProjectActions.doQuit} has always ended and the only way out on macOS, where the Dock's Quit is
 * the usual one. The hook below stands in for {@code McpProjectService.close}, asking the same
 * question before hopping.
 *
 * <p>It has to be a separate process. A shutdown that has begun cannot be called off, so there is
 * no state left to assert on afterwards -- the only observable answer is whether this process ever
 * ends, and the parent has to be alive to see it not.
 */
public final class McpShutdownHookProbe {
  private McpShutdownHookProbe() {}

  public static void main(String[] args) throws Exception {
    final var executor = new McpModelExecutor();
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  if (executor.canReachModel()) executor.run(() -> {});
                  System.out.println("shutdown hook finished");
                },
                "logisim-mcp-shutdown-probe"));

    // Starts the event dispatch thread, then has it quit the way doQuit does.
    SwingUtilities.invokeAndWait(() -> {});
    SwingUtilities.invokeLater(() -> System.exit(0));

    // Only reached if the exit above never happens; the parent kills this process either way.
    Thread.sleep(60_000);
  }
}
