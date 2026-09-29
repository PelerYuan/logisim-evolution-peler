/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** VHDL source editing and co-simulation guards. */
class VhdlSourceAcceptanceTest {
  @TempDir File tempDir;

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  private static final String XOR_SOURCE =
      """
      library ieee;
      use ieee.std_logic_1164.all;

      entity Widget is
        port (
          a : in std_logic;
          b : in std_logic;
          y : out std_logic
        );
      end Widget;

      architecture behavior of Widget is
      begin
        y <= a xor b;
      end behavior;
      """;

  @Test
  void sourceOfANewEntityIsReadableAndItsPortsAreListed() {
    final var space = Space.of(blankProject());
    final var vhdl = VhdlEntities.of(space);
    vhdl.create("Widget");

    assertTrue(vhdl.getSource("Widget").toLowerCase().contains("entity widget"));
    assertFalse(vhdl.ports("Widget").isEmpty());
  }

  @Test
  void setSourceReplacesTheTextAndPortsAndIsUndoable() {
    final var space = Space.of(blankProject());
    final var vhdl = VhdlEntities.of(space);
    vhdl.create("Widget");
    final var original = vhdl.getSource("Widget");

    vhdl.setSource("Widget", XOR_SOURCE);

    assertEquals(XOR_SOURCE, vhdl.getSource("Widget"));
    assertEquals(3, vhdl.ports("Widget").size());
    assertEquals("a", vhdl.ports("Widget").get(0).name());
    assertEquals("input", vhdl.ports("Widget").get(0).direction());

    History.of(space).undo();
    assertEquals(original, vhdl.getSource("Widget"));
  }

  @Test
  void setSourceRefusesUnparseableText() {
    final var space = Space.of(blankProject());
    final var vhdl = VhdlEntities.of(space);
    vhdl.create("Widget");
    final var before = vhdl.getSource("Widget");

    assertThrows(InvalidVhdlSourceException.class, () -> vhdl.setSource("Widget", "this is not vhdl"));
    assertEquals(before, vhdl.getSource("Widget"));
  }

  @Test
  void setSourceRefusesADifferentEntityName() {
    final var space = Space.of(blankProject());
    final var vhdl = VhdlEntities.of(space);
    vhdl.create("Gadget");

    final var thrown =
        assertThrows(InvalidVhdlSourceException.class, () -> vhdl.setSource("Gadget", XOR_SOURCE));
    assertEquals("Gadget", thrown.details().get("entity"));
  }

  @Test
  void unknownEntitiesAreReported() {
    final var vhdl = VhdlEntities.of(Space.of(blankProject()));
    assertThrows(UnknownVhdlEntityException.class, () -> vhdl.getSource("Nope"));
    assertThrows(UnknownVhdlEntityException.class, () -> vhdl.setSource("Nope", XOR_SOURCE));
  }

  @Test
  void exportFileWritesTheSource() throws Exception {
    final var space = Space.of(blankProject());
    final var vhdl = VhdlEntities.of(space);
    vhdl.create("Widget");
    vhdl.setSource("Widget", XOR_SOURCE);
    final var out = new File(tempDir, "widget.vhd");

    vhdl.exportFile("Widget", out.getAbsolutePath());

    assertTrue(Files.readString(out.toPath()).contains("y <= a xor b;"));
  }

  @Test
  void coSimulationCannotBeEnabledWithoutQuestaSim() {
    final var sim = Simulation.of(Space.of(blankProject()));
    // The test JVM has no QuestaSim configured; the point is that this refuses rather than
    // popping the co-simulator's file-chooser dialog.
    if (sim.isVhdlSimulationAvailable()) return;

    assertFalse(sim.isVhdlSimulationEnabled());
    assertThrows(VhdlSimulatorUnavailableException.class, () -> sim.setVhdlSimulationEnabled(true));
    assertThrows(VhdlSimulatorUnavailableException.class, sim::generateVhdlSimulationFiles);
  }
}
