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

/**
 * P2 acceptance criteria from the design doc, section 十一: {@code os}, {@code io}, {@code luajava}
 * and {@code require} must all be unreachable, and a runaway loop must be interrupted within the
 * instruction budget rather than hanging the calling thread forever.
 */
public class LuaSandboxSecurityTest {

  private static Space blankSpace() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return Space.of(project);
  }

  @Test
  public void testOsIoLuajavaAndRequireAreAllUnreachable() {
    final var sandbox = new LuaSandbox(blankSpace());
    for (final var name : new String[] {"os", "io", "luajava", "require", "debug", "package", "dofile", "loadfile"}) {
      assertEquals("nil", sandbox.eval("return type(" + name + ")"),
          name + " must not be reachable from a script");
    }
  }

  @Test
  public void testRunawayLoopIsInterruptedWithinBudget() {
    final var sandbox = new LuaSandbox(blankSpace(), 50_000L);
    final var ex = assertThrows(ScriptException.class, () -> sandbox.eval("while true do end"));
    assertEquals("LuaError", ex.type());
  }

  @Test
  public void testOrdinaryScriptStillWorksWithinBudget() {
    final var sandbox = new LuaSandbox(blankSpace());
    assertEquals("3", sandbox.eval("return tostring(1 + 2)"));
  }
}
