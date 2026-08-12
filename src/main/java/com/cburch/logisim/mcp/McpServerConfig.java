/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.mcp;

import java.util.Locale;

/** Configuration for the embedded MCP server. */
public record McpServerConfig(
    boolean enabled,
    String host,
    int port,
    String token,
    int maxRequestBytes,
    boolean stdio) {

  public static final int DEFAULT_PORT = 8765;
  public static final int DEFAULT_MAX_REQUEST_BYTES = 2 * 1024 * 1024;

  public McpServerConfig {
    host = host == null || host.isBlank() ? "127.0.0.1" : host;
    token = token == null ? "" : token;
    if (port < 0 || port > 65535) {
      throw new IllegalArgumentException("MCP port must be between 0 and 65535");
    }
    if (maxRequestBytes < 1024) {
      throw new IllegalArgumentException("MCP request limit must be at least 1024 bytes");
    }
  }

  /**
   * Reads configuration without making preferences or GUI state mandatory.
   *
   * <p>Off unless something asks for it. An MCP endpoint is a way for anything else on the machine
   * to drive this application -- open and save files, load a JAR library, rewrite VHDL -- so it
   * cannot be something a person ends up running because they installed a circuit editor. The
   * property and the environment variable both still turn it on, which is what the stdio launcher
   * and the tests use.
   */
  public static McpServerConfig fromSystemProperties() {
    return fromPreferences(false, DEFAULT_PORT, "");
  }

  /**
   * The same reading, with the stored preferences as the fallbacks. An explicit system property or
   * environment variable still wins, so a client that launches this application as a child process
   * can ask for a port without disturbing what the user chose in Preferences.
   */
  public static McpServerConfig fromPreferences(
      boolean enabledDefault, int portDefault, String tokenDefault) {
    final var enabled = boolProperty("logisim.mcp.enabled", "LOGISIM_MCP_ENABLED", enabledDefault);
    final var stdio = boolProperty("logisim.mcp.stdio", "LOGISIM_MCP_STDIO", false);
    final var host = firstProperty("logisim.mcp.host", "LOGISIM_MCP_HOST", "127.0.0.1");
    final var token = firstProperty("logisim.mcp.token", "LOGISIM_MCP_TOKEN", tokenDefault);
    final var port = intProperty("logisim.mcp.port", "LOGISIM_MCP_PORT", portDefault);
    final var max = intProperty(
        "logisim.mcp.maxRequestBytes",
        "LOGISIM_MCP_MAX_REQUEST_BYTES",
        DEFAULT_MAX_REQUEST_BYTES);
    return new McpServerConfig(enabled, host, port, token, max, stdio);
  }

  private static String firstProperty(String property, String env, String fallback) {
    final var value = System.getProperty(property);
    if (value != null && !value.isBlank()) return value.trim();
    final var envValue = System.getenv(env);
    return envValue == null || envValue.isBlank() ? fallback : envValue.trim();
  }

  private static boolean boolProperty(String property, String env, boolean fallback) {
    final var value = firstProperty(property, env, Boolean.toString(fallback));
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "1", "true", "yes", "y", "on" -> true;
      case "0", "false", "no", "n", "off" -> false;
      default -> fallback;
    };
  }

  private static int intProperty(String property, String env, int fallback) {
    final var value = firstProperty(property, env, Integer.toString(fallback));
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }
}
