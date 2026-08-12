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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class McpServerConfigTest {
  private static final String ENABLED = "logisim.mcp.enabled";
  private static final String STDIO = "logisim.mcp.stdio";
  private static final String HOST = "logisim.mcp.host";
  private static final String PORT = "logisim.mcp.port";
  private static final String TOKEN = "logisim.mcp.token";
  private static final String MAX_BYTES = "logisim.mcp.maxRequestBytes";

  @AfterEach
  void clearProperties() {
    System.clearProperty(ENABLED);
    System.clearProperty(STDIO);
    System.clearProperty(HOST);
    System.clearProperty(PORT);
    System.clearProperty(TOKEN);
    System.clearProperty(MAX_BYTES);
  }

  @Test
  void usesSafeDefaultsWhenNoPropertiesAreSet() {
    final var config = McpServerConfig.fromSystemProperties();

    assertTrue(config.enabled());
    assertFalse(config.stdio());
    assertEquals("127.0.0.1", config.host());
    assertEquals(McpServerConfig.DEFAULT_PORT, config.port());
    assertEquals("", config.token());
    assertEquals(McpServerConfig.DEFAULT_MAX_REQUEST_BYTES, config.maxRequestBytes());
  }

  @Test
  void parsesExplicitPropertiesAndCommonBooleanSpellings() {
    System.setProperty(ENABLED, "off");
    System.setProperty(STDIO, "YES");
    System.setProperty(HOST, " 127.0.0.1 ");
    System.setProperty(PORT, "9001");
    System.setProperty(TOKEN, " secret ");
    System.setProperty(MAX_BYTES, "4096");

    final var config = McpServerConfig.fromSystemProperties();

    assertFalse(config.enabled());
    assertTrue(config.stdio());
    assertEquals("127.0.0.1", config.host());
    assertEquals(9001, config.port());
    assertEquals("secret", config.token());
    assertEquals(4096, config.maxRequestBytes());
  }

  @Test
  void malformedNumbersFallBackWithoutBreakingStartup() {
    System.setProperty(PORT, "not-a-port");
    System.setProperty(MAX_BYTES, "NaN");

    final var config = McpServerConfig.fromSystemProperties();

    assertEquals(McpServerConfig.DEFAULT_PORT, config.port());
    assertEquals(McpServerConfig.DEFAULT_MAX_REQUEST_BYTES, config.maxRequestBytes());
  }

  @Test
  void rejectsInvalidPortAndRequestLimit() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new McpServerConfig(true, "127.0.0.1", -1, "", 1024, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new McpServerConfig(true, "127.0.0.1", 65536, "", 1024, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new McpServerConfig(true, "127.0.0.1", 8765, "", 1023, false));
  }

  @Test
  void trimsBlankHostAndNullToken() {
    final var config = new McpServerConfig(true, "  ", 0, null, 1024, false);

    assertEquals("127.0.0.1", config.host());
    assertEquals("", config.token());
    assertEquals(0, config.port());
  }
}
