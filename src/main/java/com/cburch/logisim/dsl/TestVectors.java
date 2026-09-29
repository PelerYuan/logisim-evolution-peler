/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.data.TestException;
import com.cburch.logisim.circuit.TestVectorEvaluator;
import com.cburch.logisim.data.TestVector;
import com.cburch.logisim.proj.Project;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The GUI's Test window: run a test vector against the circuit and report which rows fail.
 *
 * <p>A test vector is the text file the window loads: a header line naming pins (a clock as
 * {@code <clk>}), then one row of values per line, {@code #} comments, and optional {@code <DC>} /
 * {@code <FLOAT>} entries. It is evaluated on a private circuit state, exactly as the window and
 * the command-line test runner do, so the project's own simulation is left as it was. The circuit
 * must be committed first.
 */
public final class TestVectors {
  /** One column that did not match on a failing row. Values are binary strings. */
  public record Mismatch(String column, String expected, String computed, boolean oscillating) {}

  /** A failing row; {@code row} counts the vector's data rows from 1, as the GUI shows them. */
  public record Failure(int row, List<Mismatch> mismatches) {}

  public record Result(int passed, int failed, List<Failure> failures) {}

  private final Space space;
  private final Project proj;

  private TestVectors(Space space) {
    this.space = space;
    this.proj = space.project();
  }

  public static TestVectors of(Space space) {
    return new TestVectors(space);
  }

  public Result run(String vectorText) {
    final File temp;
    try {
      temp = File.createTempFile("logisim-vector", ".txt");
      temp.deleteOnExit();
      Files.writeString(temp.toPath(), vectorText, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new InvalidTestVectorException(
          "cannot stage the test vector: " + e.getMessage(), Map.of(), "check the temp directory");
    }
    try {
      return run(temp, "the given text");
    } finally {
      temp.delete();
    }
  }

  public Result runFile(String path) {
    return run(new File(path), path);
  }

  private Result run(File file, String source) {
    if (space.isDirty()) throw new UncommittedChangesException(space.pendingCount());
    final TestVector vector;
    try {
      vector = new TestVector(file);
    } catch (IOException | RuntimeException e) {
      throw new InvalidTestVectorException(
          "cannot read test vector from " + source + ": " + e.getMessage(),
          Map.of("source", source),
          "see the Test window's format: a header of pin names, then rows of values");
    }
    final var circuit = space.circuit();
    final var state = CircuitState.createRootState(proj, circuit, Thread.currentThread());
    final TestVectorEvaluator evaluator;
    try {
      evaluator = new TestVectorEvaluator(state, vector);
    } catch (TestException e) {
      throw new InvalidTestVectorException(
          e.getMessage(),
          Map.of("circuit", circuit.getName()),
          "the header must name pins that exist in this circuit, with matching widths");
    }
    final var failures = new ArrayList<Failure>();
    final var counts =
        evaluator.evaluate(
            (row, report) -> {
              if (report == null || report.isEmpty()) return;
              final var mismatches = new ArrayList<Mismatch>();
              for (final var line : report) {
                mismatches.add(
                    new Mismatch(
                        line.columnName(),
                        line.expected().toDisplayString(2),
                        line.computed().toDisplayString(2),
                        line.oscillating()));
              }
              failures.add(new Failure(row + 1, mismatches));
            });
    return new Result(counts[0], counts[1], List.copyOf(failures));
  }
}
