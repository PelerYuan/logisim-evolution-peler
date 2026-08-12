/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import com.cburch.logisim.generated.BuildInfo;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/** Line-delimited JSON-RPC transport for clients that launch Logisim as a child process. */
public final class McpStdioServer {
  private McpStdioServer() {}

  public static void run(InputStream input, OutputStream output) throws IOException {
    final var dispatcher = new McpJsonRpcDispatcher(BuildInfo.name, BuildInfo.version.toString(), null);
    final var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    final var writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true);
    String line;
    while ((line = reader.readLine()) != null) {
      if (line.isBlank()) continue;
      final var response = dispatcher.dispatchJson(line);
      if (response != null) writer.println(response);
      writer.flush();
    }
  }
}
