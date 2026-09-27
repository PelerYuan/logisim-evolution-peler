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
import org.junit.jupiter.api.Test;

/** Proves the {@code vhdlEntities} Lua global (backed by {@link
 * com.cburch.logisim.dsl.VhdlEntities}) is actually reachable from a script -- {@code
 * VhdlEntitiesAcceptanceTest} in the {@code dsl} package covers the Java-level behavior in more
 * depth. */
class LuaVhdlEntitiesAcceptanceTest {

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  @Test
  void testCreateAndListFromLua() {
    final var project = blankProject();
    final var sandbox = new LuaSandbox(Space.of(project));

    sandbox.eval("vhdlEntities:create(\"MyEntity\")");
    final var listed = sandbox.eval("return table.concat(vhdlEntities:list(), \",\")");

    assertTrue(listed.contains("MyEntity"));
  }

  @Test
  void testPlaceACreatedEntityFromLua() {
    final var project = blankProject();
    final var sandbox = new LuaSandbox(Space.of(project));

    sandbox.eval("""
        vhdlEntities:create("MyEntity")
        space:place("vhdl/MyEntity"):anchorAt(0, 0):place()
        space:commit("place a VHDL entity")
        """);
    final var count = sandbox.eval("return #space:componentsOf(\"vhdl/MyEntity\")");

    assertEquals("1", count);
  }

  @Test
  void testVhdlEntitiesErrorSurfacesAsAStructuredScriptException() {
    final var project = blankProject();
    final var sandbox = new LuaSandbox(Space.of(project));

    final var ex = assertThrows(ScriptException.class,
        () -> sandbox.eval("vhdlEntities:remove(\"NoSuchEntity\")"));
    assertEquals("UnknownVhdlEntityException", ex.type());
  }
}
