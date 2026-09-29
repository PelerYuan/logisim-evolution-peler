/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.script;

import com.cburch.logisim.dsl.Analysis;
import com.cburch.logisim.dsl.Appearance;
import com.cburch.logisim.dsl.Circuits;
import com.cburch.logisim.dsl.CircuitStatistics;
import com.cburch.logisim.dsl.History;
import com.cburch.logisim.dsl.Libraries;
import com.cburch.logisim.dsl.Memory;
import com.cburch.logisim.dsl.Pcomp;
import com.cburch.logisim.dsl.PlaTables;
import com.cburch.logisim.dsl.Simulation;
import com.cburch.logisim.dsl.Space;
import com.cburch.logisim.dsl.TestVectors;
import com.cburch.logisim.dsl.VhdlEntities;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.BaseLib;
import org.luaj.vm2.lib.MathLib;
import org.luaj.vm2.lib.McpInstructionBudgetHook;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * One eval-able Lua session over a {@link Space} (design doc, section 8). Every instance builds a
 * fresh, hand-assembled {@link Globals} rather than {@code JsePlatform.standardGlobals()}: the
 * standard globals include {@code os} (has {@code os.execute}), {@code io}, and
 * {@code luajava} -- reflective instantiation of arbitrary Java classes, i.e. one line of RCE on a
 * server that is network-reachable, if only over loopback with a token (CLAUDE.md's
 * "anything that listens on a socket is off until the user says otherwise" is exactly the rule this
 * class exists to honor for the eval path). {@code PackageLib} and {@code DebugLib} are excluded
 * too: the former is how {@code require} would exist, the latter is how a script could read call
 * stacks or clear its own instruction hook via {@code debug.sethook}. Only {@code BaseLib} (minus
 * {@code dofile}/{@code loadfile}), {@code TableLib}, {@code StringLib} and {@code MathLib} are
 * loaded.
 *
 * <p>A per-instance instruction budget guards against {@code while true do end}: every
 * {@value #INSTRUCTIONS_PER_CHECK} bytecodes, {@link org.luaj.vm2.lib.McpInstructionBudgetHook}
 * (which lives in {@code org.luaj.vm2.lib} solely to reach {@link org.luaj.vm2.lib.DebugLib}'s
 * package-private {@code globals} field without installing the {@code debug} global) invokes a
 * counting callback that raises once the budget is spent.
 */
public final class LuaSandbox {
  private static final int INSTRUCTIONS_PER_CHECK = 1000;
  private static final long DEFAULT_INSTRUCTION_BUDGET = 5_000_000L;

  private final Globals globals;

  public LuaSandbox(Space space) {
    this(space, DEFAULT_INSTRUCTION_BUDGET, () -> {});
  }

  public LuaSandbox(Space space, long instructionBudget) {
    this(space, instructionBudget, () -> {});
  }

  /** Runs {@code onCommit} after every successful {@code space:commit(...)} inside a script (the
   * {@code eval} MCP tool's optional {@code stream} parameter -- design doc, 13.4 -- uses this to
   * repaint the canvas progressively instead of only after the whole script returns). */
  public LuaSandbox(Space space, Runnable onCommit) {
    this(space, DEFAULT_INSTRUCTION_BUDGET, onCommit);
  }

  private LuaSandbox(Space space, long instructionBudget, Runnable onCommit) {
    this.globals = new Globals();
    globals.load(new BaseLib());

    // TableLib/StringLib/MathLib each unconditionally register themselves into
    // package.loaded.<name> as a side effect of installing -- not because a script can reach
    // require() through them, but because that is how every LuaJ library announces itself. With
    // no PackageLib ever loaded (the design forbids it: PackageLib is how require() would exist),
    // "package" is nil and that write throws "attempt to index a nil value". A scratch table
    // satisfies it during construction only; it is torn back down before any script sees these
    // globals, so package/package.loaded are never reachable from eval().
    final var scratchPackage = new LuaTable();
    scratchPackage.set("loaded", new LuaTable());
    globals.set("package", scratchPackage);
    globals.load(new TableLib());
    globals.load(new StringLib());
    globals.load(new MathLib());
    globals.set("package", LuaValue.NIL);

    LuaC.install(globals);

    // BaseLib brings dofile/loadfile along; the design forbids reading arbitrary files even though
    // JseIoLib/JseOsLib themselves are never loaded.
    globals.set("dofile", LuaValue.NIL);
    globals.set("loadfile", LuaValue.NIL);

    final var executed = new long[1];
    McpInstructionBudgetHook.install(globals, INSTRUCTIONS_PER_CHECK, new VarArgFunction() {
      @Override
      public Varargs invoke(Varargs args) {
        executed[0] += INSTRUCTIONS_PER_CHECK;
        if (executed[0] > instructionBudget) {
          throw new LuaError("instruction budget exceeded (" + instructionBudget + ") -- "
              + "the script is either too large or contains a runaway loop");
        }
        return LuaValue.NONE;
      }
    });

    globals.set("space", LuaBindings.wrap(space, onCommit));
    globals.set("circuits", LuaBindings.wrap(Circuits.of(space)));
    globals.set("libraries", LuaBindings.wrap(Libraries.of(space)));
    globals.set("vhdlEntities", LuaBindings.wrap(VhdlEntities.of(space)));
    globals.set("circuitStatistics", LuaBindings.wrap(CircuitStatistics.of(space)));
    globals.set("history", LuaBindings.wrap(History.of(space)));
    globals.set("simulation", LuaBindings.wrap(Simulation.of(space)));
    globals.set("pcomp", LuaBindings.wrap(Pcomp.of(space)));
    globals.set("appearance", LuaBindings.wrap(Appearance.of(space)));
    globals.set("tests", LuaBindings.wrap(TestVectors.of(space)));
    globals.set("analysis", LuaBindings.wrap(Analysis.of(space)));
    globals.set("memory", LuaBindings.wrap(Memory.of(space)));
    globals.set("pla", LuaBindings.wrap(PlaTables.of(space)));
  }

  /** Runs {@code script} to completion and returns whatever it returns, coerced to a
   * human-readable string (the {@code eval} MCP tool -- P3 -- is the one that will care about
   * richer return shapes; P2 only has to prove the round trip works). Throws
   * {@link ScriptException} for both a raised {@link DslException} (structured, see
   * {@link LuaBindings#errorTable}) and any other uncaught Lua error. */
  public String eval(String script) {
    try {
      final var chunk = globals.load(script, "eval");
      final var result = chunk.call();
      return result.isnil() ? "" : result.tojstring();
    } catch (LuaError e) {
      throw toScriptException(e);
    }
  }

  private ScriptException toScriptException(LuaError e) {
    final var value = e.getMessageObject();
    if (value instanceof LuaTable table) {
      final var type = table.get("type");
      final var message = table.get("message");
      final var suggestion = table.get("suggestion");
      final var details = new java.util.LinkedHashMap<String, Object>();
      final var detailsTable = table.get("details");
      if (detailsTable instanceof LuaTable dt) {
        LuaValue key = LuaValue.NIL;
        while (true) {
          final var next = dt.next(key);
          if (next.arg1().isnil()) break;
          key = next.arg1();
          details.put(key.tojstring(), next.arg(2).tojstring());
        }
      }
      return new ScriptException(
          message.isnil() ? e.getMessage() : message.tojstring(),
          type.isnil() ? "LuaError" : type.tojstring(),
          details,
          suggestion.isnil() ? null : suggestion.tojstring());
    }
    return new ScriptException(e.getMessage(), "LuaError", java.util.Map.of(), null);
  }
}
