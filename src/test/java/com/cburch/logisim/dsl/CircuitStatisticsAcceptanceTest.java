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

import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.proj.Project;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Covers the "电路统计" (circuit statistics) addition: {@link CircuitStatistics} wraps {@link
 * com.cburch.logisim.file.FileStatistics} exactly the way the GUI's "Circuit Statistics" dialog
 * does. Exercises the recursive-count semantics through nested subcircuits rather than raw gates/
 * pins: a bare test fixture (no {@code default.templ}, see {@code
 * com.cburch.logisim.dsl.internal.KindRegistry}'s own class javadoc for why) has no top-level
 * {@code Gates}/{@code Wiring} library for {@code FileStatistics.sortCounts} to attribute a gate or
 * pin's row to, so only rows whose factory is one of this file's own circuits (always reachable via
 * {@code LogisimFile.getTools()}) are visible here -- a real project loaded from {@code
 * default.templ} does not have this gap. */
class CircuitStatisticsAcceptanceTest {

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  private static CircuitStatistics.Row rowFor(List<CircuitStatistics.Row> rows, String component) {
    return rows.stream().filter(r -> r.component().equals(component)).findFirst()
        .orElseThrow(() -> new AssertionError("no row for " + component + " in " + rows));
  }

  @Test
  void computeOnAFreshEmptyCircuitReturnsNoRowsAndZeroTotals() {
    final var project = blankProject();
    final var stats = CircuitStatistics.of(Space.of(project));

    final var report = stats.compute("main");

    assertTrue(report.rows().isEmpty());
    assertEquals(new CircuitStatistics.Totals(0, 0, 0), report.totalWithoutSubcircuits());
    assertEquals(new CircuitStatistics.Totals(0, 0, 0), report.totalWithSubcircuits());
  }

  @Test
  void computeCountsNestedSubcircuitsRecursively() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var circuits = Circuits.of(space);
    circuits.create("Leaf");
    circuits.create("Mid");

    final var midSpace = Space.of(project, "Mid");
    final var leafKind = Kind.of(midSpace, "circuit/Leaf");
    midSpace.place(leafKind).anchorAt(0, 0).place();
    midSpace.commit("place Leaf inside Mid");

    final var midKind = Kind.of(space, "circuit/Mid");
    space.place(midKind).anchorAt(0, 0).place();
    space.place(midKind).anchorAt(0, 20).place();
    space.commit("place two Mid instances in main");

    final var report = CircuitStatistics.of(space).compute("main");

    final var midRow = rowFor(report.rows(), "Mid");
    assertEquals(2, midRow.simpleCount());
    assertEquals(2, midRow.uniqueCount());
    assertEquals(2, midRow.recursiveCount());

    final var leafRow = rowFor(report.rows(), "Leaf");
    assertEquals(0, leafRow.simpleCount());
    assertEquals(1, leafRow.uniqueCount());
    assertEquals(2, leafRow.recursiveCount());

    // Mid and Leaf are both themselves one of this file's own circuits, so both are excluded from
    // the "without subcircuits" total -- it sees no gates/pins in this bare fixture (see class
    // javadoc), so it is entirely empty here.
    assertEquals(new CircuitStatistics.Totals(0, 0, 0), report.totalWithoutSubcircuits());
    assertEquals(new CircuitStatistics.Totals(2, 3, 4), report.totalWithSubcircuits());
  }

  @Test
  void computeThrowsForAnUnknownCircuitNameWithASuggestion() {
    final var project = blankProject();
    final var stats = CircuitStatistics.of(Space.of(project));

    final var thrown = assertThrows(UnknownCircuitException.class, () -> stats.compute("mian"));
    @SuppressWarnings("unchecked")
    final var nearNames = (List<String>) thrown.details().get("nearNames");
    assertTrue(nearNames.contains("main"));
  }
}
