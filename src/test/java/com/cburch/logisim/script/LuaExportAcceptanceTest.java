/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.dsl.Space;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.proj.Project;
import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Proves {@code space:exportImage}/{@code space:exportHtml} (backed by the new headless export
 * methods on {@link Space}) are actually reachable from a script -- {@code
 * SpaceExportAcceptanceTest} in the {@code dsl} package covers the Java-level behavior in more
 * depth. */
class LuaExportAcceptanceTest {

  @TempDir File tempDir;

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  @Test
  void testExportImageWritesAFileFromLua() throws Exception {
    final var project = blankProject();
    final var sandbox = new LuaSandbox(Space.of(project));
    final var target = new File(tempDir, "out.png");

    sandbox.eval("""
        space:place("gates/and_gate"):anchorAt(0, 0):place()
        space:commit("place a gate")
        space:exportImage("%s", "png")
        """.formatted(target.getAbsolutePath().replace("\\", "\\\\")));

    assertTrue(target.isFile());
    assertTrue(Files.size(target.toPath()) > 0);
  }

  @Test
  void testExportImageErrorSurfacesAsAStructuredScriptException() {
    final var project = blankProject();
    final var sandbox = new LuaSandbox(Space.of(project));

    final var ex = assertThrows(ScriptException.class,
        () -> sandbox.eval("space:exportImage(\"whatever.png\", \"bmp\")"));
    assertEquals("InvalidExportFormatException", ex.type());
  }

  @Test
  void testExportHtmlErrorSurfacesAsAStructuredScriptExceptionListingTheUnsupportedKind() {
    final var project = blankProject();
    final var sandbox = new LuaSandbox(Space.of(project));

    final var ex = assertThrows(ScriptException.class, () -> sandbox.eval("""
        space:place("Memory/RAM"):anchorAt(0, 0):place()
        space:commit("place a RAM")
        space:exportHtml("whatever.html")
        """));
    assertEquals("UnsupportedForHtmlExportException", ex.type());
    assertTrue(ex.getMessage().contains("RAM"));
  }
}
