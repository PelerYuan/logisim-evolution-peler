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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Peler Edition Feature 11. Guards the shape of the exported MCP Bundle.
 *
 * <p>What a client does with the file is out of reach here, so what is checked is everything a
 * client would refuse it for: the two entries have to be present under the names the manifest
 * promises, the manifest has to parse, and the endpoint and token have to be the ones the caller
 * asked for. Getting any of those wrong produces a file that installs and then quietly does
 * nothing, which is the failure worth catching in a build.
 */
class McpBundleWriterTest {

  private static final String ENDPOINT = "http://127.0.0.1:8765/mcp";
  private static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef";

  @TempDir Path directory;

  @Test
  void writesTheTwoEntriesTheManifestNames() throws Exception {
    final var bundle = directory.resolve("bundle.mcpb");

    McpBundleWriter.write(bundle, ENDPOINT, TOKEN);

    final var entries = read(bundle);
    assertTrue(entries.containsKey("manifest.json"), "entries: " + entries.keySet());
    assertTrue(entries.containsKey("server/bridge.js"), "entries: " + entries.keySet());
    assertEquals(2, entries.size(), "unexpected extra entries: " + entries.keySet());

    final var manifest = JsonParser.parseString(entries.get("manifest.json")).getAsJsonObject();
    final var server = manifest.getAsJsonObject("server");
    assertEquals("server/bridge.js", server.get("entry_point").getAsString());
  }

  @Test
  void pointsTheBundleAtTheRunningServer() throws Exception {
    final var bundle = directory.resolve("bundle.mcpb");

    McpBundleWriter.write(bundle, ENDPOINT, TOKEN);

    final var manifest = JsonParser.parseString(read(bundle).get("manifest.json")).getAsJsonObject();
    final var env =
        manifest.getAsJsonObject("server").getAsJsonObject("mcp_config").getAsJsonObject("env");
    assertEquals(ENDPOINT, env.get("LOGISIM_MCP_URL").getAsString());
    assertEquals(TOKEN, env.get("LOGISIM_MCP_TOKEN").getAsString());
  }

  @Test
  void describesItselfTheWayTheBundleFormatRequires() throws Exception {
    final var bundle = directory.resolve("bundle.mcpb");

    McpBundleWriter.write(bundle, ENDPOINT, TOKEN);

    final var manifest = JsonParser.parseString(read(bundle).get("manifest.json")).getAsJsonObject();
    // The five the specification calls mandatory, plus the server block itself.
    for (final var required :
        new String[] {"manifest_version", "name", "version", "description", "author", "server"}) {
      assertTrue(manifest.has(required), "manifest is missing " + required);
    }
    assertEquals(McpBundleWriter.MANIFEST_VERSION, manifest.get("manifest_version").getAsString());
    assertTrue(manifest.getAsJsonObject("author").has("name"));

    final var server = manifest.getAsJsonObject("server");
    // Only a local process can be described, which is the whole reason a bridge is shipped at all.
    assertEquals("node", server.get("type").getAsString());
    final var config = server.getAsJsonObject("mcp_config");
    assertEquals("node", config.get("command").getAsString());
    final var args = config.getAsJsonArray("args");
    assertEquals(1, args.size());
    assertTrue(
        args.get(0).getAsString().startsWith("${__dirname}/"),
        "the entry point has to be resolved against the installed directory: " + args.get(0));
  }

  @Test
  void shipsTheBridgeItselfRatherThanAReferenceToIt() throws Exception {
    final var bundle = directory.resolve("bundle.mcpb");

    McpBundleWriter.write(bundle, ENDPOINT, TOKEN);

    final var bridge = read(bundle).get("server/bridge.js");
    assertNotNull(bridge);
    assertTrue(bridge.contains("LOGISIM_MCP_URL"), "the bridge does not read its endpoint");
    assertTrue(bridge.contains("Mcp-Session-Id"), "the bridge does not carry the session header");
  }

  @Test
  void refusesToWriteABundleThatPointsNowhere() {
    final var bundle = directory.resolve("bundle.mcpb");

    assertThrows(IOException.class, () -> McpBundleWriter.write(bundle, null, TOKEN));
    assertThrows(IOException.class, () -> McpBundleWriter.write(bundle, "  ", TOKEN));
    assertFalse(Files.exists(bundle), "a refused export must not leave a file behind");
  }

  /** An unauthenticated server is possible through the system property; the bundle must say so. */
  @Test
  void writesAnEmptyTokenRatherThanOmittingIt() throws Exception {
    final var bundle = directory.resolve("bundle.mcpb");

    McpBundleWriter.write(bundle, ENDPOINT, null);

    final var manifest = JsonParser.parseString(read(bundle).get("manifest.json")).getAsJsonObject();
    final var env =
        manifest.getAsJsonObject("server").getAsJsonObject("mcp_config").getAsJsonObject("env");
    assertTrue(env.has("LOGISIM_MCP_TOKEN"));
    assertEquals("", env.get("LOGISIM_MCP_TOKEN").getAsString());
  }

  @Test
  void namesTheFileAfterThePortSoTwoExportsAreToldApart() {
    assertTrue(McpBundleWriter.suggestedFileName(8765).endsWith(".mcpb"));
    assertTrue(McpBundleWriter.suggestedFileName(8765).contains("8765"));
    assertFalse(McpBundleWriter.suggestedFileName(8765).equals(McpBundleWriter.suggestedFileName(9000)));
  }

  private static Map<String, String> read(Path bundle) throws IOException {
    final var entries = new LinkedHashMap<String, String>();
    try (final var in = new ZipInputStream(Files.newInputStream(bundle))) {
      for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return entries;
  }
}
