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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Test window's vector runner and the Log window's recording, headless. */
class TestVectorsAcceptanceTest {
  @TempDir File tempDir;

  private static Space newSpace() {
    final var file = LogisimFile.createNew(new Loader(null), null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return Space.of(project);
  }

  private static Space andGate() {
    final var space = newSpace();
    space.synthesize(Synthesis.of().input("A").input("B").output("Y", "A & B"));
    return space;
  }

  @Test
  void passingVectorReportsNoFailures() {
    final var result = TestVectors.of(andGate()).run("A B Y\n0 0 0\n0 1 0\n1 0 0\n1 1 1\n");
    assertEquals(4, result.passed());
    assertEquals(0, result.failed());
    assertTrue(result.failures().isEmpty());
  }

  @Test
  void failingRowIsReportedWithExpectedAndComputed() {
    final var result = TestVectors.of(andGate()).run("A B Y\n0 0 0\n1 1 0\n");
    assertEquals(1, result.passed());
    assertEquals(1, result.failed());
    final var failure = result.failures().get(0);
    assertEquals(2, failure.row());
    assertEquals("Y", failure.mismatches().get(0).column());
    assertEquals("0", failure.mismatches().get(0).expected());
    assertEquals("1", failure.mismatches().get(0).computed());
  }

  @Test
  void vectorFilesWork() throws Exception {
    final var file = new File(tempDir, "and.txt");
    Files.writeString(file.toPath(), "A B Y\n1 1 1\n");
    assertEquals(1, TestVectors.of(andGate()).runFile(file.getAbsolutePath()).passed());
  }

  @Test
  void badVectorsAreStructuredErrors() {
    final var tests = TestVectors.of(andGate());
    assertThrows(InvalidTestVectorException.class, () -> tests.run(""));
    assertThrows(InvalidTestVectorException.class, () -> tests.run("A Nope Y\n0 0 0\n"));
    assertThrows(InvalidTestVectorException.class, () -> tests.runFile(new File(tempDir, "missing.txt").getAbsolutePath()));
  }

  @Test
  void traceSamplesAClockAndWritesTheLogFile() throws Exception {
    final var space = newSpace();
    final var clock = space.place(Kind.of(space, "Wiring/Clock")).anchorAt(4, 4).place();
    final var out =
        space.place(Kind.of(space, "wiring/pin")).anchorAt(20, 4).with(Attrs.of("type", "output")).place().label("Q");
    space.connect(clock.outputs().get(0), out.inputs().get(0));
    space.commit("clock");

    final var path = new File(tempDir, "log.txt");
    final var trace = Simulation.of(space).trace(List.of("Q"), 4, 1, path.getAbsolutePath());

    assertEquals(4, trace.rows().size());
    assertEquals(trace.rows().get(0), trace.rows().get(2));
    assertEquals(trace.rows().get(1), trace.rows().get(3));
    assertTrue(!trace.rows().get(0).equals(trace.rows().get(1)));
    final var lines = Files.readAllLines(path.toPath());
    assertEquals("Q", lines.get(0));
    assertEquals(5, lines.size());
    assertThrows(InvalidTraceException.class, () -> Simulation.of(space).trace(List.of("Q"), 0, 1, null));
    assertThrows(UnknownPinException.class, () -> Simulation.of(space).trace(List.of("Nope"), 1, 1, null));
  }
}
