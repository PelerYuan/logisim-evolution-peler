/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package org.luaj.vm2.lib;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;

/**
 * Installs a count-based instruction hook without installing the {@code debug} Lua global.
 *
 * <p>Lives in {@code org.luaj.vm2.lib} -- not {@code com.cburch.logisim.script} -- purely to reach
 * {@link DebugLib}'s package-private {@code globals} field. {@link DebugLib#call} is the normal way
 * to wire a hook up, but it also does two things the MCP v2 sandbox design (design doc, section 8)
 * forbids: it makes {@code debug} reachable from every script (a script could read call stacks or
 * clear its own hook via {@code debug.sethook}), and it indexes {@code package.loaded}, which throws
 * because this sandbox never loads {@code PackageLib} either. Setting the two fields directly gets
 * the interpreter to call {@link DebugLib#onInstruction} every bytecode -- which is all a count hook
 * needs -- without either side effect.
 */
public final class McpInstructionBudgetHook {
  private McpInstructionBudgetHook() {}

  /** Runs {@code onCount} every {@code instructionsPerCheck} bytecodes on {@code globals}'s current
   * thread. {@code onCount} throws to abort the script; see {@code InstructionBudget} in the script
   * layer for the actual counting policy. */
  public static void install(Globals globals, int instructionsPerCheck, LuaValue onCount) {
    final var debugLib = new DebugLib();
    debugLib.globals = globals;
    globals.debuglib = debugLib;
    final var state = globals.running.state;
    state.hookfunc = onCount;
    state.hookcount = instructionsPerCheck;
  }
}
