/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.ttlsymbol;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Peler Edition. A handful of symbols checked against their datasheets rather than against
 * upstream.
 *
 * <p>{@code TtlSymbolEquivalenceTest} compares a symbol with the DIP package it delegates to, and
 * would pass just as happily if both were wrong in the same way -- it cannot, by construction, tell
 * whether the names beside the pins mean what a datasheet says they mean. These vectors pin that
 * end down: they are read off published function tables and addressed by the names on the symbol,
 * so a layout table that puts the right index next to the wrong name fails here.
 *
 * <p>One part is taken from each family: a decoder, a display driver, a multiplexer, a comparator,
 * an adder, and a register, that last one so a clock port is exercised. The seventeen gate arrays
 * are covered exhaustively in {@code TtlSymbolGateArrayTest} instead, because they declare no pin
 * names and so have no other check on the names beside their pins.
 */
public class TtlSymbolDatasheetTest {

  @TempDir Path workDir;

  /** 74283 four-bit adder: 10 + 9 + 0 = 19, so the sum is 0011 with a carry out. */
  @Test
  public void theAdderAddsAsItsFunctionTableSays() throws Exception {
    final var chip = chip("74283");
    chip.drive("A1", false).drive("A2", true).drive("A3", false).drive("A4", true);
    chip.drive("B1", true).drive("B2", false).drive("B3", false).drive("B4", true);
    chip.drive("CIN", false);
    chip.settle();

    chip.expect("∑1", true, "sum bit 1");
    chip.expect("∑2", true, "sum bit 2");
    chip.expect("∑3", false, "sum bit 3");
    chip.expect("∑4", false, "sum bit 4");
    chip.expect("C4", true, "carry out");
  }

  /** 74138 three-to-eight decoder: enabled, with CBA = 101, only Y5 goes low. */
  @Test
  public void theDecoderPullsDownExactlyTheSelectedOutput() throws Exception {
    final var chip = chip("74138");
    chip.drive("G1", true).drive("G2A", false).drive("G2B", false);
    chip.drive("C", true).drive("B", false).drive("A", true);
    chip.settle();

    for (var line = 0; line < 8; line++) {
      chip.expect("Y" + line, line != 5, "output " + line + " with 5 selected");
    }
  }

  /** 74138 again, disabled: G2A high holds every output up whatever the address is. */
  @Test
  public void theDecoderIsSilentWhenDisabled() throws Exception {
    final var chip = chip("74138");
    chip.drive("G1", true).drive("G2A", true).drive("G2B", false);
    chip.drive("C", true).drive("B", false).drive("A", true);
    chip.settle();

    for (var line = 0; line < 8; line++) {
      chip.expect("Y" + line, true, "output " + line + " while disabled");
    }
  }

  /**
   * 7447 seven-segment driver showing a zero: every segment but the middle one is lit, and the
   * outputs are active low, so lit means the pin is pulled down.
   */
  @Test
  public void theDisplayDriverLightsTheSegmentsOfAZero() throws Exception {
    final var chip = chip("7447");
    chip.drive("A", false).drive("B", false).drive("C", false).drive("D", false);
    chip.drive("LT", true).drive("BI", true).drive("RBI", true);
    chip.settle();

    for (final var segment : List.of("a", "b", "c", "d", "e", "f")) {
      chip.expect(segment, false, "segment " + segment + " of a zero");
    }
    chip.expect("g", true, "the middle segment of a zero stays dark");
  }

  /** 74151 eight-to-one multiplexer: enabled with CBA = 011, Y follows D3 and W is its complement. */
  @Test
  public void theMultiplexerFollowsTheSelectedInput() throws Exception {
    final var chip = chip("74151");
    chip.drive("G", false);
    chip.drive("C", false).drive("B", true).drive("A", true);
    for (var line = 0; line < 8; line++) chip.drive("D" + line, line == 3);
    chip.settle();

    chip.expect("Y", true, "D3 is high and selected");
    chip.expect("W", false, "W is the complement of Y");

    chip.drive("D3", false).settle();
    chip.expect("Y", false, "D3 went low");
    chip.expect("W", true, "so W went high");
  }

  /** 7485 comparator: 9 against 6, with the cascade inputs set as a stand-alone stage. */
  @Test
  public void theComparatorRanksTwoWords() throws Exception {
    final var chip = chip("7485");
    // A = 1001, B = 0110, least significant bit first.
    chip.drive("A0", true).drive("A1", false).drive("A2", false).drive("A3", true);
    chip.drive("B0", false).drive("B1", true).drive("B2", true).drive("B3", false);
    chip.drive("A>B", true).drive("A=B", false).drive("A<B", false);
    chip.settle();

    chip.expect("A>B", true, "9 is greater than 6");
    chip.expect("A=B", false, "and not equal to it");
    chip.expect("A<B", false, "and not less than it");
  }

  /**
   * 74175 quad D flip-flop, the part the lecture slide draws. Data has to survive a rising edge and
   * appear on both the true and the complemented output, which is the only check here that the
   * clock port is the clock port.
   */
  @Test
  public void theRegisterLatchesOnTheRisingEdge() throws Exception {
    final var chip = chip("74175");
    chip.drive("CLR", true).drive("CLK", false);
    chip.drive("D1", true).drive("D2", false).drive("D3", true).drive("D4", true);
    chip.settle();
    chip.drive("CLK", true).settle();

    chip.expect("Q1", true, "Q1 after the edge");
    chip.expect("Q2", false, "Q2 after the edge");
    chip.expect("Q3", true, "Q3 after the edge");
    chip.expect("Q4", true, "Q4 after the edge");
    chip.expectInverted("Q1", false, "the complement of Q1");
    chip.expectInverted("Q2", true, "the complement of Q2");

    // Data changing between edges must not reach the outputs.
    chip.drive("D1", false).settle();
    chip.expect("Q1", true, "Q1 while the clock is still high");

    // And the clear is active low.
    chip.drive("CLR", false).settle();
    chip.expect("Q1", false, "Q1 after the clear");
    chip.expect("Q3", false, "Q3 after the clear");
  }

  private TtlSymbolProbe chip(String name) throws Exception {
    return TtlSymbolProbe.of(name, workDir);
  }
}
