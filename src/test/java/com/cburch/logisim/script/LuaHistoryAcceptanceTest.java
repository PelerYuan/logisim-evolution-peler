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

import com.cburch.logisim.dsl.Space;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.proj.Project;
import org.junit.jupiter.api.Test;

/** Proves the {@code history} Lua global (backed by {@link com.cburch.logisim.dsl.History}) is
 * actually reachable from a script -- {@code HistoryAcceptanceTest} in the {@code dsl} package
 * covers the Java-level undo/redo semantics in more depth. */
class LuaHistoryAcceptanceTest {

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  @Test
  void testUndoAndRedoFromLua() {
    final var project = blankProject();
    final var sandbox = new LuaSandbox(Space.of(project));

    sandbox.eval("circuits:create(\"Helper\")");
    final var afterCreate = sandbox.eval("return table.concat(circuits:list(), \",\")");

    sandbox.eval("history:undo()");
    final var afterUndo = sandbox.eval("return table.concat(circuits:list(), \",\")");

    sandbox.eval("history:redo()");
    final var afterRedo = sandbox.eval("return table.concat(circuits:list(), \",\")");

    assertEquals("main,Helper", afterCreate);
    assertEquals("main", afterUndo);
    assertEquals("main,Helper", afterRedo);
  }

  @Test
  void testHistoryErrorSurfacesAsAStructuredScriptException() {
    final var project = blankProject();
    final var sandbox = new LuaSandbox(Space.of(project));

    final var ex = assertThrows(ScriptException.class, () -> sandbox.eval("history:undo()"));
    assertEquals("NothingToUndoException", ex.type());
  }
}
