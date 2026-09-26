/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package org.luaj.vm2.lib;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.BaseLib;
import org.luaj.vm2.lib.VarArgFunction;

/** Proves {@link McpInstructionBudgetHook} actually interrupts a runaway script before any real
 * sandbox/binding layer is built on top of it, and that {@code debug} is never reachable. */
public class McpInstructionBudgetHookSmokeTest {

  @Test
  public void testRunawayLoopIsInterruptedWithinBudget() {
    final var globals = new Globals();
    globals.load(new BaseLib());
    LuaC.install(globals);

    final var count = new int[1];
    McpInstructionBudgetHook.install(globals, 1000, new VarArgFunction() {
      @Override
      public Varargs invoke(Varargs args) {
        count[0]++;
        if (count[0] > 5) throw new LuaError("instruction budget exceeded");
        return LuaValue.NONE;
      }
    });

    final var chunk = globals.load("while true do end", "runaway");
    assertThrows(LuaError.class, () -> chunk.call());
  }

  @Test
  public void testDebugGlobalIsNeverInstalled() {
    final var globals = new Globals();
    globals.load(new BaseLib());
    LuaC.install(globals);
    McpInstructionBudgetHook.install(globals, 1000, new VarArgFunction() {
      @Override
      public Varargs invoke(Varargs args) {
        return LuaValue.NONE;
      }
    });
    assertTrue(globals.get("debug").isnil(), "debug must never become a reachable global");
  }
}
