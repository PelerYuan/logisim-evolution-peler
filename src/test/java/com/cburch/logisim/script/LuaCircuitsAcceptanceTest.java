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

/** Proves the {@code circuits} Lua global (backed by {@link com.cburch.logisim.dsl.Circuits}) is
 * actually reachable from a script, not just from Java -- {@code CircuitsAcceptanceTest} in the
 * {@code dsl} package covers the Java-level behavior in more depth. */
public class LuaCircuitsAcceptanceTest {

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  @Test
  public void testCircuitsGlobalCanListCreateRenameAndSetMain() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var sandbox = new LuaSandbox(space);

    final var before = sandbox.eval("return table.concat(circuits:list(), \",\")");
    assertEquals("main", before);

    sandbox.eval("circuits:create(\"Helper\")");
    final var afterCreate = sandbox.eval("return table.concat(circuits:list(), \",\")");
    assertEquals("main,Helper", afterCreate);

    sandbox.eval("circuits:setMain(\"Helper\")");
    assertEquals("Helper", sandbox.eval("return circuits:mainName()"));

    sandbox.eval("circuits:rename(\"Helper\", \"Top\")");
    assertEquals("main,Top", sandbox.eval("return table.concat(circuits:list(), \",\")"));
  }

  @Test
  public void testCircuitsErrorSurfacesAsAStructuredScriptException() {
    final var project = blankProject();
    final var sandbox = new LuaSandbox(Space.of(project));

    final var ex = assertThrows(ScriptException.class,
        () -> sandbox.eval("circuits:remove(\"main\")"));
    assertEquals("CircuitInUseException", ex.type());
    assertTrue(ex.getMessage().contains("main"));
  }
}
