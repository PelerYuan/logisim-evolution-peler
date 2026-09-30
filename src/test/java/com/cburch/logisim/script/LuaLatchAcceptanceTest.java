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

import com.cburch.logisim.dsl.Space;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import org.junit.jupiter.api.Test;

/**
 * A gated D latch is the smallest circuit that exercises three things an AI client relies on: two
 * feedback nets that must be routed past each other without touching, wire tidying that must not
 * join them afterwards, and pin writes that must settle behind several gates.
 */
class LuaLatchAcceptanceTest {
  private static LuaSandbox sandbox() {
    final var file = LogisimFile.createNew(new Loader(null), null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return new LuaSandbox(Space.of(project));
  }

  private static final String BUILD =
      """
      local d = space:place("wiring/pin"):anchorAt(20, 20):with({type = "input"}):place() d:setLabel("D")
      local e = space:place("wiring/pin"):anchorAt(20, 40):with({type = "input"}):place() e:setLabel("E")
      local nd = space:place("gates/not_gate"):anchorAt(28, 28):place()
      local n1 = space:place("gates/nand_gate"):anchorAt(40, 20):place()
      local n2 = space:place("gates/nand_gate"):anchorAt(40, 40):place()
      local n3 = space:place("gates/nand_gate"):anchorAt(56, 20):place()
      local n4 = space:place("gates/nand_gate"):anchorAt(56, 40):place()
      local q = space:place("wiring/pin"):anchorAt(72, 20):with({type = "output"}):place() q:setLabel("Q")
      local qn = space:place("wiring/pin"):anchorAt(72, 40):with({type = "output"}):place() qn:setLabel("Qn")
      space:connect(d:outputs()[1], n1:inputs()[1])
      space:connect(e:outputs()[1], n1:inputs()[2])
      space:connect(d:outputs()[1], nd:inputs()[1])
      space:connect(nd:outputs()[1], n2:inputs()[1])
      space:connect(e:outputs()[1], n2:inputs()[2])
      space:connect(n1:outputs()[1], n3:inputs()[1])
      space:connect(n2:outputs()[1], n4:inputs()[2])
      space:connect(n3:outputs()[1], n4:inputs()[1])
      space:connect(n4:outputs()[1], n3:inputs()[2])
      space:connect(n3:outputs()[1], q:inputs()[1])
      space:connect(n4:outputs()[1], qn:inputs()[1])
      space:commit("latch")
      """;

  private static final String SEQUENCE =
      """
      local function rd(n) local v = simulation:readPin(n) return v.known and tostring(v.value) or "x" end
      local r = {}
      local function step(d, e) simulation:writePin("E", e) simulation:writePin("D", d)
        r[#r + 1] = rd("Q") .. rd("Qn") end
      step(1, 1) step(0, 1) step(1, 0) step(1, 1) step(0, 0)
      return table.concat(r, " ")
      """;

  @Test
  void feedbackNetsStaySeparateAndTheLatchBehaves() {
    final var sb = sandbox();
    sb.eval(BUILD);
    assertEquals("true:7", sb.eval("local c = space:check() return tostring(c.ok) .. ':' .. #space:nets()"));
    assertEquals("10 01 01 10 10", sb.eval(SEQUENCE));
  }

  @Test
  void tidyingKeepsTheFeedbackNetsSeparate() {
    final var sb = sandbox();
    sb.eval(BUILD);
    sb.eval("space:tidyWires()");
    assertEquals("true:7", sb.eval("local c = space:check() return tostring(c.ok) .. ':' .. #space:nets()"));
    assertEquals("10 01 01 10 10", sb.eval(SEQUENCE));
  }

  @Test
  void writingAPinSettlesBehindManyGatesAndKeepsAutoPropagationOn() {
    final var out =
        sandbox()
            .eval(
                """
                local i = space:place("wiring/pin"):anchorAt(20, 20):with({type = "input"}):place() i:setLabel("I")
                local g = {}
                for k = 1, 6 do g[k] = space:place("gates/not_gate"):anchorAt(20 + 10 * k, 20):place() end
                local o = space:place("wiring/pin"):anchorAt(100, 20):with({type = "output"}):place() o:setLabel("O")
                space:connect(i:outputs()[1], g[1]:inputs()[1])
                for k = 1, 5 do space:connect(g[k]:outputs()[1], g[k + 1]:inputs()[1]) end
                space:connect(g[6]:outputs()[1], o:inputs()[1])
                space:commit("chain")
                local r = {}
                for _, v in ipairs({0, 1, 0, 1}) do
                  simulation:writePin("I", v)
                  r[#r + 1] = v .. "->" .. simulation:readPin("O").value
                end
                return table.concat(r, " ") .. " auto=" .. tostring(simulation:isAutoPropagating())
                """);
    assertEquals("0->0 1->1 0->0 1->1 auto=true", out);
  }
}
