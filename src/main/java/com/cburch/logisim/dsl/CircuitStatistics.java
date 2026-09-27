/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.file.FileStatistics;
import com.cburch.logisim.proj.Project;
import java.util.ArrayList;
import java.util.List;

/**
 * Project-level circuit statistics -- component counts for a given circuit, the counterpart to
 * {@link Circuits} for read-only reporting rather than mutation. Wraps {@link FileStatistics}
 * exactly the way the GUI's own "Circuit Statistics" dialog ({@code
 * com.cburch.logisim.gui.main.StatisticsDialog}) and the {@code -tty stats} headless mode ({@code
 * com.cburch.logisim.gui.start.TtyInterface#displayStatistics}) both already do -- {@link
 * FileStatistics} itself is pure data computation with no Swing/AWT involvement, so unlike most of
 * this session's other additions there is no dialog or thread hazard to design around here.
 */
public final class CircuitStatistics {
  private final Project proj;

  private CircuitStatistics(Project proj) {
    this.proj = proj;
  }

  public static CircuitStatistics of(Space space) {
    return new CircuitStatistics(space.project());
  }

  /** One {@link FileStatistics.Count} row: a single component kind, where it comes from, and its
   * three counts within the circuit statistics were computed for -- see {@link
   * FileStatistics.Count} for exact semantics of {@code simpleCount} (direct instances in this
   * circuit), {@code uniqueCount} (instances anywhere in the whole project file), and {@code
   * recursiveCount} (instances if this circuit were fully flattened, multiplying through nested
   * subcircuits). */
  public record Row(String component, String library, int simpleCount, int uniqueCount, int recursiveCount) {}

  /** One of the two trailer totals the GUI dialog shows -- see {@link
   * FileStatistics#getTotalWithoutSubcircuits()}/{@link FileStatistics#getTotalWithSubcircuits()}. */
  public record Totals(int simpleCount, int uniqueCount, int recursiveCount) {}

  public record Report(List<Row> rows, Totals totalWithoutSubcircuits, Totals totalWithSubcircuits) {}

  /** Computes statistics for the named circuit, exactly what the GUI's "Circuit Statistics" menu
   * item shows for whichever circuit happens to be open. */
  public Report compute(String circuitName) {
    final var circuit = require(circuitName);
    final var stats = FileStatistics.compute(proj.getLogisimFile(), circuit);
    final var rows = new ArrayList<Row>();
    for (final var count : stats.getCounts()) {
      final var library = count.getLibrary();
      rows.add(new Row(
          count.getFactory().getDisplayName(),
          library == null ? null : library.getDisplayName(),
          count.getSimpleCount(),
          count.getUniqueCount(),
          count.getRecursiveCount()));
    }
    return new Report(List.copyOf(rows), totalsOf(stats.getTotalWithoutSubcircuits()),
        totalsOf(stats.getTotalWithSubcircuits()));
  }

  private static Totals totalsOf(FileStatistics.Count count) {
    return new Totals(count.getSimpleCount(), count.getUniqueCount(), count.getRecursiveCount());
  }

  private Circuit require(String name) {
    final var circuit = proj.getLogisimFile().getCircuit(name);
    if (circuit == null) throw new UnknownCircuitException(name, nearest(name));
    return circuit;
  }

  private List<String> nearest(String name) {
    final var candidates = new ArrayList<String>();
    for (final var circuit : proj.getLogisimFile().getCircuits()) candidates.add(circuit.getName());
    candidates.sort((a, b) -> distance(name, a) - distance(name, b));
    final var top = new ArrayList<String>();
    for (final var candidate : candidates) {
      if (distance(name, candidate) <= Math.max(3, name.length() / 2)) top.add(candidate);
      if (top.size() == 3) break;
    }
    return top;
  }

  /** Plain Levenshtein distance -- same purpose and shape as {@code Circuits}'/{@code
   * VhdlEntities}'/{@code KindRegistry}'s own copies, kept separate rather than shared since all
   * are small, private, and belong to unrelated key spaces. */
  private static int distance(String a, String b) {
    final var dp = new int[a.length() + 1][b.length() + 1];
    for (var i = 0; i <= a.length(); i++) dp[i][0] = i;
    for (var j = 0; j <= b.length(); j++) dp[0][j] = j;
    for (var i = 1; i <= a.length(); i++) {
      for (var j = 1; j <= b.length(); j++) {
        final var cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
      }
    }
    return dp[a.length()][b.length()];
  }
}
