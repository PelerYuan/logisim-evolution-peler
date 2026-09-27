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

/** Proves the {@code simulation} Lua global (backed by {@link com.cburch.logisim.dsl.Simulation})
 * is actually reachable from a script -- {@code SimulationAcceptanceTest} in the {@code dsl}
 * package covers the Java-level simulation semantics in more depth. */
class LuaSimulationAcceptanceTest {

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  @Test
  void testWritePinAndReadPinRoundTripFromLua() {
    final var project = blankProject();
    final var sandbox = new LuaSandbox(Space.of(project));

    sandbox.eval("""
        local pin = space:place("wiring/pin")
        local inA = pin:anchorAt(0, 0):with({type = "input"}):place()
        inA:setLabel("InA")
        local outA = space:place("wiring/pin"):anchorAt(8, 0):with({type = "output"}):place()
        outA:setLabel("OutA")
        space:connect(inA:outputs()[1], outA:inputs()[1])
        space:commit("wire InA to OutA")
        """);

    final var result = sandbox.eval("""
        simulation:writePin("InA", 1)
        local v = simulation:readPin("OutA")
        return tostring(v.value) .. "," .. tostring(v.known)
        """);

    assertEquals("1,true", result);
  }

  @Test
  void testTickWithNoClockSurfacesAsAStructuredScriptException() {
    final var project = blankProject();
    final var sandbox = new LuaSandbox(Space.of(project));

    sandbox.eval("""
        space:place("wiring/pin"):anchorAt(0, 0):with({type = "input"}):place()
        space:commit("place one input pin, no clock")
        """);

    final var ex = assertThrows(ScriptException.class, () -> sandbox.eval("simulation:tick(1)"));
    assertEquals("NoClockException", ex.type());
  }
}
