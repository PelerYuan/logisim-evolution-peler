/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * MCP v2 design doc, section 二: the {@code dsl} package layer must be usable by anything, not just
 * the eventual Lua/MCP layers, which means it cannot import back into either -- and it certainly
 * cannot import Swing, since none of this runs on the event dispatch thread. Checked by scanning
 * source text, the same technique {@code PelerOptionsTest} uses, rather than by constructing any
 * real object.
 */
public class DslPackageBoundaryTest {
  private static final Path DSL_ROOT = Path.of("src/main/java/com/cburch/logisim/dsl");

  private static final List<String> FORBIDDEN =
      List.of("javax.swing", "com.sun.net", "com.cburch.logisim.mcp");

  @Test
  public void testDslPackageImportsNothingFromSwingOrHttpOrMcp() throws IOException {
    final var violations = new ArrayList<String>();
    for (final var file : javaFiles()) {
      final var source = Files.readString(file);
      for (final var forbidden : FORBIDDEN) {
        if (source.contains(forbidden)) {
          violations.add(file + " references " + forbidden);
        }
      }
    }
    assertTrue(violations.isEmpty(), String.join("\n", violations));
  }

  private static List<Path> javaFiles() throws IOException {
    assertTrue(Files.isDirectory(DSL_ROOT), "dsl package has moved; this test needs updating");
    try (final Stream<Path> walk = Files.walk(DSL_ROOT)) {
      final var files = walk.filter(p -> p.toString().endsWith(".java")).toList();
      assertFalse(files.isEmpty(), "no .java files found under " + DSL_ROOT);
      return files;
    }
  }
}
