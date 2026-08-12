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
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes an MCP Bundle a client can install by double-clicking it.
 *
 * <p>A bundle is a zip holding a {@code manifest.json} and the server it describes. The format only
 * knows how to launch a local process, so what goes in is not this server -- that one is inside a
 * running Logisim, reachable over HTTP -- but {@code bridge.js}, which forwards stdin to it. See
 * the comment at the top of that file for why the indirection is the point rather than a
 * workaround.
 *
 * <p>The endpoint and token are written into the manifest rather than asked for at install time,
 * so installing is one click and there is no secret for anyone to type in badly. The cost is that
 * a bundle is only as current as the settings it was exported from: change the port, export again.
 */
public final class McpBundleWriter {

  /** The manifest schema this file writes. See https://github.com/modelcontextprotocol/mcpb. */
  static final String MANIFEST_VERSION = "0.3";

  private static final String BRIDGE_RESOURCE = "/resources/logisim/mcp/bridge.js";
  private static final String BRIDGE_PATH = "server/bridge.js";

  private McpBundleWriter() {}

  /** The name to suggest in the save dialog, distinct per port so two do not look alike. */
  public static String suggestedFileName(int port) {
    return "logisim-evolution-peler-mcp-" + port + ".mcpb";
  }

  /**
   * Writes a bundle wired to {@code endpoint} with {@code token}.
   *
   * <p>Takes both rather than reading the running server, so the result depends only on its
   * arguments and can be checked without one.
   */
  public static void write(Path destination, String endpoint, String token) throws IOException {
    if (endpoint == null || endpoint.isBlank()) {
      throw new IOException("The MCP server is not running, so there is no endpoint to point at");
    }
    final var bridge = bridgeSource();
    try (final var out = new ZipOutputStream(Files.newOutputStream(destination))) {
      writeEntry(out, "manifest.json", manifest(endpoint, token).getBytes(StandardCharsets.UTF_8));
      writeEntry(out, BRIDGE_PATH, bridge);
    }
  }

  /** The manifest as it is written into the bundle. Package-private so a test can read it back. */
  static String manifest(String endpoint, String token) {
    final var author = new JsonObject();
    author.addProperty("name", "PelerYuan");
    author.addProperty("url", BuildInfo.url);

    final var env = new JsonObject();
    env.addProperty("LOGISIM_MCP_URL", endpoint);
    // Present but empty when the server runs without authentication, which is possible through the
    // system property. An empty value tells the bridge to send no Authorization header at all.
    env.addProperty("LOGISIM_MCP_TOKEN", token == null ? "" : token);

    final var args = new JsonArray();
    args.add("${__dirname}/" + BRIDGE_PATH);

    final var mcpConfig = new JsonObject();
    mcpConfig.addProperty("command", "node");
    mcpConfig.add("args", args);
    mcpConfig.add("env", env);

    final var server = new JsonObject();
    server.addProperty("type", "node");
    server.addProperty("entry_point", BRIDGE_PATH);
    server.add("mcp_config", mcpConfig);

    final var keywords = new JsonArray();
    keywords.add("logisim");
    keywords.add("circuit");
    keywords.add("digital-logic");

    final var root = new JsonObject();
    root.addProperty("manifest_version", MANIFEST_VERSION);
    root.addProperty("name", "logisim-evolution-peler");
    root.addProperty("display_name", "Logisim-evolution (Peler's Edition)");
    root.addProperty("version", BuildInfo.version.toString());
    root.addProperty(
        "description",
        "Read, edit and simulate the circuit open in Logisim-evolution (Peler's Edition).");
    root.addProperty(
        "long_description",
        "Connects to a running Logisim-evolution (Peler's Edition) window over its local MCP "
            + "endpoint, so the circuit being edited is the one on screen. Logisim must be "
            + "running with MCP switched on in Preferences -> Peler's Features.");
    root.add("author", author);
    root.addProperty("homepage", BuildInfo.url);
    root.addProperty("license", "GPL-3.0-or-later");
    root.add("keywords", keywords);
    root.add("server", server);
    // HTML escaping off. Gson escapes apostrophes and angle brackets into numeric escapes by
    // default, which is valid JSON but leaves the display name and the description unreadable
    // for anyone who opens the manifest -- and this one is meant to be opened and checked.
    final var gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    return gson.toJson(root) + "\n";
  }

  private static byte[] bridgeSource() throws IOException {
    try (final var in = McpBundleWriter.class.getResourceAsStream(BRIDGE_RESOURCE)) {
      if (in == null) throw new IOException("The MCP bridge script is missing from the jar");
      return in.readAllBytes();
    }
  }

  private static void writeEntry(ZipOutputStream out, String name, byte[] content)
      throws IOException {
    final var entry = new ZipEntry(name);
    // A fixed timestamp so exporting twice from the same settings gives the same file, which
    // makes "did anything actually change" answerable by looking.
    entry.setTime(0L);
    out.putNextEntry(entry);
    out.write(content);
    out.closeEntry();
  }
}
