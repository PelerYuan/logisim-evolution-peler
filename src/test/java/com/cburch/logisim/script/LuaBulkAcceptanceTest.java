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
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import org.junit.jupiter.api.Test;

/** {@code circuits:setEverywhere}, {@code libraries:reload} and {@code space:copyRegion}. */
class LuaBulkAcceptanceTest {
  private static LuaSandbox sandbox() {
    final var file = LogisimFile.createNew(new Loader(null), null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return new LuaSandbox(Space.of(project));
  }

  @Test
  void setEverywhereAndCopyRegionFromLua() {
    final var out =
        sandbox()
            .eval(
                """
                space:place("gates/and_gate"):anchorAt(16, 6):place()
                space:commit("gate")
                local n = circuits:setEverywhere("inputs", 4)
                local again = circuits:setEverywhere("inputs", "4", "gates/and_gate")
                local c = space:copyRegion(0, 0, 30, 20, 0, 30)
                return n .. ":" .. again .. ":" .. c.components .. ":" .. c.wires
                """);
    assertEquals("1:0:1:0", out);
  }

  @Test
  void errorsAreStructured() {
    final var bad =
        assertThrows(
            ScriptException.class,
            () ->
                sandbox()
                    .eval(
                        """
                        space:place("gates/and_gate"):anchorAt(16, 6):place()
                        space:commit("gate")
                        circuits:setEverywhere("width", "abc")
                        """));
    assertEquals("InvalidAttributeValueException", bad.type());
    final var copy =
        assertThrows(
            ScriptException.class,
            () ->
                sandbox()
                    .eval(
                        """
                        space:place("gates/and_gate"):anchorAt(16, 6):place()
                        space:commit("gate")
                        space:copyRegion(0, 0, 30, 20, 0, 0)
                        """));
    assertEquals("CopyRegionException", copy.type());
    final var reload =
        assertThrows(ScriptException.class, () -> sandbox().eval("libraries:reload('Nope')"));
    assertEquals("UnknownLibraryException", reload.type());
  }
}
