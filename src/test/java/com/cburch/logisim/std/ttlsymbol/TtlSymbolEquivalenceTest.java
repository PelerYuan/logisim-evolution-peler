/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.ttlsymbol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.Value;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Peler Edition. Every logic symbol must answer exactly as the DIP package it stands for.
 *
 * <p>This is the load-bearing test of the whole feature. A symbol does not reimplement anything:
 * {@code TtlSymbolGate.propagate} hands the state to the DIP factory's {@code propagateTtl}, which
 * is only sound because {@code AbstractTtlGate} numbers a port by its pin and never by its
 * position. The layout tables in {@code TtlSymbolLayouts} are what could break that -- each names a
 * port index per row, and a transposed pair there produces a chip that looks right, simulates
 * without complaint, and adds the wrong numbers.
 *
 * <p>The comparison drives one long sequence rather than a fresh state per row, so a chip that
 * holds state is exercised too: the clock and the data ports have to line up over many edges, not
 * just settle to the same answer once.
 */
public class TtlSymbolEquivalenceTest {

  /**
   * Length of the random phase, which every chip gets whether or not it also gets an exhaustive
   * one. A fixed seed keeps it repeatable.
   *
   * <p>Counting through the input combinations, on its own, is close to useless on a clocked part:
   * counting makes the clock a bit like any other, so it rises exactly once in the whole sweep, and
   * on the parts here that one rising edge lands on the step where the asynchronous clear is also
   * asserted. Every register in the library sat cleared through its entire sweep. Random inputs
   * give a clock edge every few steps with the clear inactive, which is what actually walks a
   * register through its states.
   */
  private static final int STEPS = 1200;

  /** Up to this many inputs the sweep also counts through every combination first. */
  private static final int EXHAUSTIVE_INPUT_LIMIT = 10;

  @TempDir Path workDir;

  static List<TtlSymbolSpec> specs() {
    return TtlSymbolLayouts.SPECS;
  }

  @ParameterizedTest
  @MethodSource("specs")
  public void everySymbolAnswersAsItsDipPackage(TtlSymbolSpec spec) throws Exception {
    final var symbol = new TtlSymbolGate(spec);
    final var dip = spec.delegate().get();
    final var id = "Sym" + dip.getName();

    final var dipRuns = sweep(dip, "TTL");
    final var symbolRuns = sweep(symbol, "TTL Symbols");

    // A sweep whose outputs never move is not evidence of anything, and is what a harness that has
    // stopped responding to its own inputs looks like from the outside. This test ran that way once
    // already, before the pins were marked changed after being driven.
    assertTrue(
        new HashSet<>(dipRuns).size() > 1,
        id + ": the sweep never changed the chip's outputs, so it compared nothing");

    assertEquals(
        dipRuns.size(),
        symbolRuns.size(),
        id + ": the symbol and the DIP package do not even have the same number of ports");
    for (var step = 0; step < dipRuns.size(); step++) {
      assertEquals(
          dipRuns.get(step),
          symbolRuns.get(step),
          id + " step " + step
              + ": the symbol disagrees with the DIP package. A row in its layout table names the "
              + "wrong port index.");
    }
  }

  /**
   * Drives the chip through a repeatable sequence and records what the outputs did at every step.
   * The state is kept across steps on purpose: a register that latched the wrong port would still
   * settle correctly if every step started from scratch.
   */
  private List<String> sweep(ComponentFactory factory, String libraryId) throws Exception {
    final var fixture = TtlFixture.open(factory, libraryId, workDir);
    final var inputs = fixture.inputPorts();
    assertFalse(inputs.isEmpty(), libraryId + " fixture has no inputs");
    assertFalse(fixture.outputPorts().isEmpty(), libraryId + " fixture has no outputs");

    final var random = new Random(20260822L);
    final var counted = inputs.size() <= EXHAUSTIVE_INPUT_LIMIT ? (1 << inputs.size()) : 0;

    final var rows = new ArrayList<String>();
    for (var step = 0; step < counted + STEPS; step++) {
      final var combination = step < counted ? step : random.nextInt();
      for (var bit = 0; bit < inputs.size(); bit++) {
        fixture.drive(inputs.get(bit), ((combination >> bit) & 1) == 1 ? Value.TRUE : Value.FALSE);
      }
      fixture.settle();
      rows.add(fixture.outputRow());
    }
    return rows;
  }
}
