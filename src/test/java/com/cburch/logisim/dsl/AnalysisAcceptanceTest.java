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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Combinational Analysis window's read side, run from a circuit. */
class AnalysisAcceptanceTest {
  @TempDir File tempDir;

  private static Space andOrCircuit() {
    final var file = LogisimFile.createNew(new Loader(null), null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    final var space = Space.of(project);
    // Y = (A AND B) OR C
    space.synthesize(Synthesis.of().input("A").input("B").input("C").output("Y", "A & B | C"));
    return space;
  }

  @Test
  void truthTableListsEveryRow() {
    final var table = Analysis.of(andOrCircuit()).truthTable();

    assertEquals(3, table.inputs().size());
    assertEquals(1, table.outputs().size());
    assertEquals(8, table.rows().size());
    // Row index i is the inputs read as a binary number, first input most significant.
    assertEquals("0", table.rows().get(0).outputs());
    assertEquals("1", table.rows().get(1).outputs());
    assertEquals("1", table.rows().get(6).outputs());
    assertEquals("0", table.rows().get(2).outputs());
  }

  @Test
  void expressionsAreReadOffTheGatesAndFeedBackIntoSynthesis() {
    final var space = andOrCircuit();
    final var exprs = Analysis.of(space).expressions(null);

    assertEquals(1, exprs.size());
    final var y = exprs.get("Y");
    assertTrue(y.contains("A") && y.contains("B") && y.contains("C"), y);
  }

  @Test
  void minimizedGivesTheSmallestForm() {
    final var sop = Analysis.of(andOrCircuit()).minimized("sop", "progbits");
    assertTrue(sop.get("Y").contains("C"), sop.get("Y"));
    assertThrows(AnalysisFailedException.class, () -> Analysis.of(andOrCircuit()).minimized("xyz", null));
  }

  @Test
  void exportsWriteFiles() throws Exception {
    final var analysis = Analysis.of(andOrCircuit());
    final var csv = new File(tempDir, "t.csv");
    final var txt = new File(tempDir, "t.txt");
    final var tex = new File(tempDir, "t.tex");

    analysis.exportTable(csv.getAbsolutePath());
    analysis.exportTable(txt.getAbsolutePath());
    analysis.exportLatex(tex.getAbsolutePath());

    assertTrue(Files.readString(csv.toPath()).contains("Y"));
    assertTrue(Files.size(txt.toPath()) > 0);
    assertTrue(Files.size(tex.toPath()) > 0);
    assertThrows(InvalidExportFormatException.class, () -> analysis.exportTable(new File(tempDir, "t.bin").getAbsolutePath()));
  }

  @Test
  void emptyCircuitsAndStagedChangesAreRefused() {
    final var file = LogisimFile.createNew(new Loader(null), null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    final var space = Space.of(project);
    assertThrows(AnalysisFailedException.class, () -> Analysis.of(space).truthTable());

    space.place(Kind.of(space, "wiring/pin")).anchorAt(4, 4).place();
    assertThrows(UncommittedChangesException.class, () -> Analysis.of(space).truthTable());
  }
}
