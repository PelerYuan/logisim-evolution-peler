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

/** Proves the {@code circuitStatistics} Lua global (backed by {@link
 * com.cburch.logisim.dsl.CircuitStatistics}) is actually reachable from a script -- {@code
 * CircuitStatisticsAcceptanceTest} in the {@code dsl} package covers the Java-level counting
 * semantics in more depth. */
class LuaCircuitStatisticsAcceptanceTest {

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  @Test
  void testComputeReturnsRowsAndTotalsFromLua() {
    final var project = blankProject();
    final var sandbox = new LuaSandbox(Space.of(project));

    sandbox.eval("""
        circuits:create("Sub")
        space:place("circuit/Sub"):anchorAt(0, 0):place()
        space:commit("place Sub")
        """);
    final var simpleCount = sandbox.eval("""
        local report = circuitStatistics:compute("main")
        return tostring(report.rows[1].simpleCount) .. "," .. tostring(report.totalWithSubcircuits.simpleCount)
        """);

    assertEquals("1,1", simpleCount);
  }

  @Test
  void testCircuitStatisticsErrorSurfacesAsAStructuredScriptException() {
    final var project = blankProject();
    final var sandbox = new LuaSandbox(Space.of(project));

    final var ex = assertThrows(ScriptException.class,
        () -> sandbox.eval("circuitStatistics:compute(\"NoSuchCircuit\")"));
    assertEquals("UnknownCircuitException", ex.type());
  }
}
