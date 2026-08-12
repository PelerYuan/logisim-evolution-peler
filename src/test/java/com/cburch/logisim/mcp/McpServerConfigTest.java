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

  /**
   * Nothing configured means nothing listening.
   *
   * <p>This assertion used to read {@code assertTrue(config.enabled())} under the same test name,
   * which is how an endpoint that lets any local caller open files, load JAR libraries and rewrite
   * VHDL came to be running on every launch of a circuit editor. Turning it on is a decision, and
   * the decision lives in Preferences -> Peler's Features.
   */
  @Test
  void usesSafeDefaultsWhenNoPropertiesAreSet() {
    final var config = McpServerConfig.fromSystemProperties();

    assertFalse(config.enabled());
    assertFalse(config.stdio());
    assertEquals("127.0.0.1", config.host());
    assertEquals(McpServerConfig.DEFAULT_PORT, config.port());
    assertEquals("", config.token());
    assertEquals(McpServerConfig.DEFAULT_MAX_REQUEST_BYTES, config.maxRequestBytes());
  }

  /**
   * The interesting direction now that off is the default: asking for the server has to work, or
   * the stdio launcher and every client that starts this application as a child process break.
   */
  @Test
  void theEnabledPropertyCanStillTurnTheServerOn() {
    System.setProperty(ENABLED, "true");

    assertTrue(McpServerConfig.fromSystemProperties().enabled());
  }

  /**
   * The stored preference decides when nothing is set, and an explicit property still overrides it
   * -- so a client can ask for a port without disturbing what the user chose in Preferences.
   */
  @Test
  void preferencesActAsFallbacksAndPropertiesOverrideThem() {
    final var fromPreference = McpServerConfig.fromPreferences(true, 9100, "stored-token");
    assertTrue(fromPreference.enabled());
    assertEquals(9100, fromPreference.port());
    assertEquals("stored-token", fromPreference.token());

    System.setProperty(ENABLED, "off");
    System.setProperty(PORT, "9200");
    final var overridden = McpServerConfig.fromPreferences(true, 9100, "stored-token");
    assertFalse(overridden.enabled());
    assertEquals(9200, overridden.port());
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
