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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Proves the {@code libraries} Lua global (backed by {@link com.cburch.logisim.dsl.Libraries}) is
 * actually reachable from a script -- {@code LibrariesAcceptanceTest} in the {@code dsl} package
 * covers the Java-level behavior in more depth. */
class LuaLibrariesAcceptanceTest {

  @TempDir File tempDir;

  private static final String WIDGET_CIRC =
      """
      <?xml version="1.0" encoding="UTF-8" standalone="no"?>
      <project source="4.1.0" version="1.0">
        <main name="Widget"/>
        <circuit name="Widget">
        </circuit>
      </project>
      """;

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  @Test
  void testLoadCircuitAndUnloadFromLua() throws Exception {
    final var circFile = new File(tempDir, "Widget.circ");
    Files.writeString(circFile.toPath(), WIDGET_CIRC, StandardCharsets.UTF_8);

    final var project = blankProject();
    final var sandbox = new LuaSandbox(Space.of(project));
    final var quotedPath = circFile.getAbsolutePath().replace("\\", "\\\\");

    final var name = sandbox.eval("return libraries:loadCircuit(\"%s\")".formatted(quotedPath));
    assertEquals("Widget", name);

    final var listed = sandbox.eval("return table.concat(libraries:list(), \",\")");
    assertTrue(listed.contains("Widget"));

    sandbox.eval("libraries:unload(\"Widget\")");
    final var afterUnload = sandbox.eval("return table.concat(libraries:list(), \",\")");
    assertTrue(!afterUnload.contains("Widget"));
  }

  @Test
  void testLibrariesErrorSurfacesAsAStructuredScriptException() {
    final var project = blankProject();
    final var sandbox = new LuaSandbox(Space.of(project));

    final var ex = assertThrows(ScriptException.class,
        () -> sandbox.eval("libraries:unload(\"NoSuchLibrary\")"));
    assertEquals("UnknownLibraryException", ex.type());
  }
}
