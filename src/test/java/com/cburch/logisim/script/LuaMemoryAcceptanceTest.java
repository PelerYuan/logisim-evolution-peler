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

/** The {@code memory} and {@code pla} Lua globals. */
class LuaMemoryAcceptanceTest {
  private static LuaSandbox sandbox() {
    final var file = LogisimFile.createNew(new Loader(null), null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return new LuaSandbox(Space.of(project));
  }

  @Test
  void romAndPlaFromLua() {
    final var out =
        sandbox()
            .eval(
                """
                local rom = space:place("Memory/ROM"):anchorAt(10, 10):place()
                local plac = space:place("Gates/PLA"):anchorAt(70, 10):place()
                space:commit("memories")
                memory:writeRange(rom, 4, {5, 6, 7})
                pla:setTable(plac, "01 1\\n10 1\\n")
                local r = memory:readRange(rom, 4, 3)
                return memory:info(rom).kind .. ":" .. r[1] .. r[2] .. r[3] .. ":" .. #pla:getTable(plac)
                """);
    assertEquals("rom:567:10", out);
  }

  @Test
  void errorsAreStructured() {
    final var ex =
        assertThrows(
            ScriptException.class,
            () ->
                sandbox()
                    .eval(
                        """
                        local rom = space:place("Memory/ROM"):anchorAt(10, 10):place()
                        space:commit("rom")
                        memory:write(rom, 100000, 1)
                        """));
    assertEquals("InvalidMemoryAccessException", ex.type());
  }
}
