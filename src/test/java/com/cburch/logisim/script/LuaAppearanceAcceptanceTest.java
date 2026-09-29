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

/** The {@code appearance} Lua global reaches {@link com.cburch.logisim.dsl.Appearance}. */
class LuaAppearanceAcceptanceTest {
  private static LuaSandbox sandbox() {
    final var file = LogisimFile.createNew(new Loader(null), null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return new LuaSandbox(Space.of(project));
  }

  @Test
  void drawShapesAndReadThemBack() {
    final var sb = sandbox();
    final var out =
        sb.eval(
            """
            local r = appearance:addRect(0, 0, 30, 20, {stroke = "#0000ff", fill = "#ffff00"})
            local p = appearance:addPoly({{0,0},{10,0},{5,8}})
            local t = appearance:addText(3, 3, "HI", {size = 10})
            local l = appearance:list()
            return appearance:style() .. ":" .. l[r+1].kind .. ":" .. l[p+1].kind .. ":" .. l[t+1].text
                .. ":" .. l[r+1].fill
            """);
    assertEquals("custom:rect:polygon:HI:#ffff00", out);
  }

  @Test
  void errorsAreStructured() {
    final var ex = assertThrows(ScriptException.class, () -> sandbox().eval("appearance:remove(42)"));
    assertEquals("UnknownAppearanceShapeException", ex.type());
  }
}
