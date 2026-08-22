/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.ttlsymbol;

import static com.cburch.logisim.std.ttlsymbol.TtlSymbolSpec.Row.clock;
import static com.cburch.logisim.std.ttlsymbol.TtlSymbolSpec.Row.inverted;
import static com.cburch.logisim.std.ttlsymbol.TtlSymbolSpec.Row.named;
import static com.cburch.logisim.std.ttlsymbol.TtlSymbolSpec.Row.namedInverted;
import static com.cburch.logisim.std.ttlsymbol.TtlSymbolSpec.Row.of;
import static com.cburch.logisim.std.ttlsymbol.TtlSymbolSpec.Row.renamed;
import static com.cburch.logisim.std.ttlsymbol.TtlSymbolSpec.Row.renamedClock;
import static com.cburch.logisim.std.ttlsymbol.TtlSymbolSpec.Row.renamedInverted;

import com.cburch.logisim.std.ttl.AbstractTtlGate;
import com.cburch.logisim.std.ttl.Ttl7400;
import com.cburch.logisim.std.ttl.Ttl7402;
import com.cburch.logisim.std.ttl.Ttl7404;
import com.cburch.logisim.std.ttl.Ttl7408;
import com.cburch.logisim.std.ttl.Ttl7410;
import com.cburch.logisim.std.ttl.Ttl7411;
import com.cburch.logisim.std.ttl.Ttl74125;
import com.cburch.logisim.std.ttl.Ttl74138;
import com.cburch.logisim.std.ttl.Ttl74139;
import com.cburch.logisim.std.ttl.Ttl7413;
import com.cburch.logisim.std.ttl.Ttl7414;
import com.cburch.logisim.std.ttl.Ttl74151;
import com.cburch.logisim.std.ttl.Ttl74153;
import com.cburch.logisim.std.ttl.Ttl74157;
import com.cburch.logisim.std.ttl.Ttl74158;
import com.cburch.logisim.std.ttl.Ttl74161;
import com.cburch.logisim.std.ttl.Ttl74163;
import com.cburch.logisim.std.ttl.Ttl74164;
import com.cburch.logisim.std.ttl.Ttl74165;
import com.cburch.logisim.std.ttl.Ttl74166;
import com.cburch.logisim.std.ttl.Ttl74175;
import com.cburch.logisim.std.ttl.Ttl74181;
import com.cburch.logisim.std.ttl.Ttl74182;
import com.cburch.logisim.std.ttl.Ttl7418;
import com.cburch.logisim.std.ttl.Ttl74192;
import com.cburch.logisim.std.ttl.Ttl74193;
import com.cburch.logisim.std.ttl.Ttl74194;
import com.cburch.logisim.std.ttl.Ttl7419;
import com.cburch.logisim.std.ttl.Ttl7420;
import com.cburch.logisim.std.ttl.Ttl7421;
import com.cburch.logisim.std.ttl.Ttl74240;
import com.cburch.logisim.std.ttl.Ttl74241;
import com.cburch.logisim.std.ttl.Ttl74244;
import com.cburch.logisim.std.ttl.Ttl74245;
import com.cburch.logisim.std.ttl.Ttl7424;
import com.cburch.logisim.std.ttl.Ttl74266;
import com.cburch.logisim.std.ttl.Ttl74273;
import com.cburch.logisim.std.ttl.Ttl7427;
import com.cburch.logisim.std.ttl.Ttl74283;
import com.cburch.logisim.std.ttl.Ttl74299;
import com.cburch.logisim.std.ttl.Ttl7430;
import com.cburch.logisim.std.ttl.Ttl7432;
import com.cburch.logisim.std.ttl.Ttl7434;
import com.cburch.logisim.std.ttl.Ttl7436;
import com.cburch.logisim.std.ttl.Ttl74377;
import com.cburch.logisim.std.ttl.Ttl74381;
import com.cburch.logisim.std.ttl.Ttl7442;
import com.cburch.logisim.std.ttl.Ttl7443;
import com.cburch.logisim.std.ttl.Ttl7444;
import com.cburch.logisim.std.ttl.Ttl7447;
import com.cburch.logisim.std.ttl.Ttl74541;
import com.cburch.logisim.std.ttl.Ttl7451;
import com.cburch.logisim.std.ttl.Ttl7454;
import com.cburch.logisim.std.ttl.Ttl7458;
import com.cburch.logisim.std.ttl.Ttl7464;
import com.cburch.logisim.std.ttl.Ttl74670;
import com.cburch.logisim.std.ttl.Ttl7474;
import com.cburch.logisim.std.ttl.Ttl747266;
import com.cburch.logisim.std.ttl.Ttl7485;
import com.cburch.logisim.std.ttl.Ttl7486;
import com.cburch.logisim.std.ttl.Ttl7487;
import com.cburch.logisim.std.ttlsymbol.TtlSymbolSpec.Row;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Peler Edition. Where every 74xx chip's pins go when it is drawn as a logic symbol instead of as a
 * DIP package. One entry per chip in upstream's {@code TtlLibrary} list, in the same order.
 *
 * <p>Each table says three things a program cannot work out for itself: which side of the box a pin
 * belongs on, what order the pins run down that side, and where the blank rows fall that separate
 * one group of pins from the next. Everything else -- the names, which pins are outputs, which are
 * active low, how the chip actually behaves -- is read from the DIP factory the symbol delegates
 * to, so this file cannot contradict it.
 *
 * <p>The arrangements follow each part's datasheet logic diagram: operands grouped as words with
 * the least significant bit at the top, control pins gathered below them, outputs in the matching
 * order on the right. Where upstream's model of a chip differs from the datasheet -- and it does,
 * a few times -- the model wins, because the model is what the symbol runs.
 *
 * <p>Comments give the port indices in pin order, which is how {@code AbstractTtlGate} numbers
 * them: pin 1 upwards, skipping the two supply pins and any the chip does not use.
 */
final class TtlSymbolLayouts {

  private TtlSymbolLayouts() {}

  /**
   * Builds one symbol a group at a time. Two kinds of chip need different treatment and this
   * offers both: a chip that is several independent gates wants each gate's output level with the
   * middle of its own inputs, which {@link #gate} does; a chip that is one function wants its
   * inputs and its outputs listed down their own sides independently, which {@link #in} and {@link
   * #out} do. {@link #blank} squares the two columns up again and leaves a blank row in each, so
   * the two styles can be mixed on one chip -- a quad multiplexer is four gates and then a pair of
   * shared control pins.
   */
  private static final class Layout {
    private final List<Row> left = new ArrayList<>();
    private final List<Row> right = new ArrayList<>();

    /** One gate: its inputs down the left, its output level with the middle of them. */
    Layout gate(Row out, Row... ins) {
      // An even number of rows has no middle one, so a blank is slipped in to make one.
      final var even = ins.length % 2 == 0;
      final var middle = ins.length / 2;
      final var slots = even ? ins.length + 1 : ins.length;
      var taken = 0;
      for (var row = 0; row < slots; row++) {
        left.add(even && row == middle ? Row.gap() : ins[taken++]);
        right.add(row == middle ? out : Row.gap());
      }
      return this;
    }

    /** Rows down the left of the box. */
    Layout in(Row... rows) {
      left.addAll(List.of(rows));
      return this;
    }

    /** Rows down the right of the box. */
    Layout out(Row... rows) {
      right.addAll(List.of(rows));
      return this;
    }

    /** A blank row in both columns, after squaring them up. */
    Layout blank() {
      while (left.size() < right.size()) left.add(Row.gap());
      while (right.size() < left.size()) right.add(Row.gap());
      left.add(Row.gap());
      right.add(Row.gap());
      return this;
    }

    TtlSymbolSpec of(Supplier<AbstractTtlGate> delegate) {
      return new TtlSymbolSpec(delegate, List.copyOf(left), List.copyOf(right));
    }
  }

  private static Layout symbol() {
    return new Layout();
  }

  // --------------------------------------------------------------------------------------------
  // Gate arrays. These chips declare no pin names at all, so the names here are the datasheets':
  // gates numbered from one, inputs A B C, output Y.
  // --------------------------------------------------------------------------------------------

  /** Four two-input gates, pinned 1A 1B 1Y, 2A 2B 2Y, 3Y 3A 3B, 4Y 4A 4B. */
  private static Layout quadTwoInput(boolean invertingOutput) {
    return symbol()
        .gate(output(2, "1Y", invertingOutput), named(0, "1A"), named(1, "1B"))
        .blank()
        .gate(output(5, "2Y", invertingOutput), named(3, "2A"), named(4, "2B"))
        .blank()
        .gate(output(6, "3Y", invertingOutput), named(7, "3A"), named(8, "3B"))
        .blank()
        .gate(output(9, "4Y", invertingOutput), named(10, "4A"), named(11, "4B"));
  }

  /** Six one-input gates, pinned 1A 1Y, 2A 2Y, 3A 3Y, 4Y 4A, 5Y 5A, 6Y 6A. */
  private static Layout hexOneInput(boolean invertingOutput) {
    return symbol()
        .gate(output(1, "1Y", invertingOutput), named(0, "1A"))
        .blank()
        .gate(output(3, "2Y", invertingOutput), named(2, "2A"))
        .blank()
        .gate(output(5, "3Y", invertingOutput), named(4, "3A"))
        .blank()
        .gate(output(6, "4Y", invertingOutput), named(7, "4A"))
        .blank()
        .gate(output(8, "5Y", invertingOutput), named(9, "5A"))
        .blank()
        .gate(output(10, "6Y", invertingOutput), named(11, "6A"));
  }

  /** Three three-input gates, pinned 1A 1B 2A 2B 2C 2Y, 3Y 3A 3B 3C 1Y 1C. */
  private static Layout tripleThreeInput(boolean invertingOutput) {
    return symbol()
        .gate(output(10, "1Y", invertingOutput), named(0, "1A"), named(1, "1B"), named(11, "1C"))
        .blank()
        .gate(output(5, "2Y", invertingOutput), named(2, "2A"), named(3, "2B"), named(4, "2C"))
        .blank()
        .gate(output(6, "3Y", invertingOutput), named(7, "3A"), named(8, "3B"), named(9, "3C"));
  }

  private static Row output(int index, String label, boolean inverting) {
    return inverting ? namedInverted(index, label) : named(index, label);
  }

  /** Two four-input gates. Upstream names these, numbering the gates from zero. */
  private static Layout dualFourInput(boolean invertingOutput) {
    return symbol()
        .gate(invertingOutput ? inverted(4) : of(4), of(0), of(1), of(2), of(3))
        .blank()
        .gate(invertingOutput ? inverted(5) : of(5), of(9), of(8), of(7), of(6));
  }

  // --------------------------------------------------------------------------------------------
  // The tables, in TtlLibrary order.
  // --------------------------------------------------------------------------------------------

  static final List<TtlSymbolSpec> SPECS =
      List.of(
          // 7400 quad 2-input NAND.
          quadTwoInput(true).of(Ttl7400::new),

          // 7402 quad 2-input NOR: the outputs come first on this one.
          // 0 1Y  1 1A  2 1B  3 2Y  4 2A  5 2B  6 3A  7 3B  8 3Y  9 4A  10 4B  11 4Y
          symbol()
              .gate(namedInverted(0, "1Y"), named(1, "1A"), named(2, "1B"))
              .blank()
              .gate(namedInverted(3, "2Y"), named(4, "2A"), named(5, "2B"))
              .blank()
              .gate(namedInverted(8, "3Y"), named(6, "3A"), named(7, "3B"))
              .blank()
              .gate(namedInverted(11, "4Y"), named(9, "4A"), named(10, "4B"))
              .of(Ttl7402::new),

          // 7404 hex inverter.
          hexOneInput(true).of(Ttl7404::new),

          // 7408 quad 2-input AND.
          quadTwoInput(false).of(Ttl7408::new),

          // 7410 triple 3-input NAND.
          tripleThreeInput(true).of(Ttl7410::new),

          // 7411 triple 3-input AND.
          tripleThreeInput(false).of(Ttl7411::new),

          // 7413 dual 4-input NAND with Schmitt trigger inputs.
          dualFourInput(true).of(Ttl7413::new),

          // 7414 hex inverter with Schmitt trigger inputs.
          hexOneInput(true).of(Ttl7414::new),

          // 7418 dual 4-input NAND with Schmitt trigger inputs.
          dualFourInput(true).of(Ttl7418::new),

          // 7419 hex inverter with Schmitt trigger inputs.
          hexOneInput(true).of(Ttl7419::new),

          // 7420 dual 4-input NAND.
          dualFourInput(true).of(Ttl7420::new),

          // 7421 dual 4-input AND.
          dualFourInput(false).of(Ttl7421::new),

          // 7424 quad 2-input NAND with Schmitt trigger inputs.
          quadTwoInput(true).of(Ttl7424::new),

          // 7427 triple 3-input NOR.
          tripleThreeInput(true).of(Ttl7427::new),

          // 7430 8-input NAND.
          // 0 A  1 B  2 C  3 D  4 E  5 F  6 Y  7 G  8 H
          symbol()
              .gate(inverted(6), of(0), of(1), of(2), of(3), of(4), of(5), of(7), of(8))
              .of(Ttl7430::new),

          // 7432 quad 2-input OR.
          quadTwoInput(false).of(Ttl7432::new),

          // 7434 hex buffer.
          hexOneInput(false).of(Ttl7434::new),

          // 7436 quad 2-input NOR, pinned like the 7400 rather than like the 7402.
          quadTwoInput(true).of(Ttl7436::new),

          // 7442 BCD to decimal decoder, active-low outputs.
          // 0..9 O0..O9  10 D  11 C  12 B  13 A
          decimalDecoder().of(Ttl7442::new),

          // 7443 excess-3 to decimal decoder.
          decimalDecoder().of(Ttl7443::new),

          // 7444 excess-3-Gray to decimal decoder.
          decimalDecoder().of(Ttl7444::new),

          // 7447 BCD to seven-segment decoder, active-low outputs.
          // 0 B  1 C  2 LT  3 BI  4 RBI  5 D  6 A  7 e  8 d  9 c  10 b  11 a  12 g  13 f
          symbol()
              .in(of(6), of(0), of(1), of(5))
              .blank()
              .in(inverted(2), inverted(3), inverted(4))
              .out(inverted(11), inverted(10), inverted(9), inverted(8))
              .out(inverted(7), inverted(13), inverted(12))
              .of(Ttl7447::new),

          // 7451 dual 2-wide 2-input AND-OR-invert.
          // 0 A1  1 A2  2 B2  3 C2  4 D2  5 Y2  6 Y1  7 C1  8 D1  9 B1
          symbol()
              .gate(inverted(6), of(0), of(9), Row.gap(), of(7), of(8))
              .blank()
              .gate(inverted(5), of(1), of(2), Row.gap(), of(3), of(4))
              .of(Ttl7451::new),

          // 7454 4-wide 2-input AND-OR-invert.
          // 0 A  1 C  2 D  3 E  4 F  5 Y  6 G  7 H  8 B
          symbol()
              .gate(
                  inverted(5),
                  of(0), of(8), Row.gap(),
                  of(1), of(2), Row.gap(),
                  of(3), of(4), Row.gap(),
                  of(6), of(7))
              .of(Ttl7454::new),

          // 7458 2-wide 3-input and 2-wide 2-input AND-OR. Upstream numbers the gates from zero.
          // 0 A0  1 A1  2 B1  3 C1  4 D1  5 Y1  6 Y0  7 D0  8 E0  9 F0  10 B0  11 C0
          symbol()
              .gate(of(6), of(0), of(10), of(11), Row.gap(), of(7), of(8), of(9))
              .blank()
              .gate(of(5), of(1), of(2), Row.gap(), of(3), of(4))
              .of(Ttl7458::new),

          // 7464 4-2-3-2-input AND-OR-invert.
          // 0 A  1 E  2 F  3 G  4 H  5 I  6 Y  7 J  8 K  9 B  10 C  11 D
          symbol()
              .gate(
                  inverted(6),
                  of(0), of(9), of(10), of(11), Row.gap(),
                  of(1), of(2), Row.gap(),
                  of(3), of(4), of(5), Row.gap(),
                  of(7), of(8))
              .of(Ttl7464::new),

          // 7474 dual D flip-flop with preset and clear.
          // 0 nCLR1  1 D1  2 CLK1  3 nPRE1  4 Q1  5 nQ1  6 nQ2  7 Q2  8 nPRE2  9 CLK2  10 D2
          // 11 nCLR2
          symbol()
              .in(of(1), clock(2), of(3), of(0))
              .out(of(4), of(5))
              .blank()
              .in(of(10), clock(9), of(8), of(11))
              .out(of(7), of(6))
              .of(Ttl7474::new),

          // 7485 4-bit magnitude comparator. The three cascade inputs sit level with the three
          // outputs they chain into.
          // 0 B3  1 A<B in  2 A=B in  3 A>B in  4 A>B out  5 A=B out  6 A<B out  7 B0  8 A0
          // 9 B1  10 A1  11 A2  12 B2  13 A3
          symbol()
              .in(of(8), of(10), of(11), of(13))
              .blank()
              .in(of(7), of(9), of(12), of(0))
              .blank()
              .in(of(3), of(2), of(1))
              .out(of(4), of(5), of(6))
              .of(Ttl7485::new),

          // 7486 quad 2-input XOR.
          quadTwoInput(false).of(Ttl7486::new),

          // 7487 4-bit true/complement, zero/one element.
          // 0 C  1 A1  2 Y1  3 A2  4 Y2  5 B  6 Y3  7 A3  8 Y4  9 A4
          symbol()
              .in(of(1), of(3), of(7), of(9))
              .out(of(2), of(4), of(6), of(8))
              .blank()
              .in(of(5), of(0))
              .of(Ttl7487::new),

          // 74125 quad bus buffer, each gate enabled by its own active-low pin.
          // 0 1nOE  1 1A  2 1Y  3 2nOE  4 2A  5 2Y  6 3Y  7 3A  8 3nOE  9 4Y  10 4A  11 4nOE
          symbol()
              .gate(named(2, "1Y"), named(1, "1A"), namedInverted(0, "1OE"))
              .blank()
              .gate(named(5, "2Y"), named(4, "2A"), namedInverted(3, "2OE"))
              .blank()
              .gate(named(6, "3Y"), named(7, "3A"), namedInverted(8, "3OE"))
              .blank()
              .gate(named(9, "4Y"), named(10, "4A"), namedInverted(11, "4OE"))
              .of(Ttl74125::new),

          // 74138 3-to-8 line decoder.
          // 0 A  1 B  2 C  3 nG2A  4 nG2B  5 G1  6 nY7  7 nY6  8 nY5  9 nY4  10 nY3  11 nY2
          // 12 nY1  13 nY0
          symbol()
              .in(of(0), of(1), of(2))
              .blank()
              .in(
                  renamed(5, "G1", "G1 Enable (active HIGH)"),
                  renamed(3, "G2A", "nG2A Enable (active LOW)"),
                  renamed(4, "G2B", "nG2B Enable (active LOW)"))
              .out(of(13), of(12), of(11), of(10), of(9), of(8), of(7), of(6))
              .of(Ttl74138::new),

          // 74139 dual 2-to-4 line decoder.
          // 0 1nG  1 1A  2 1B  3 1nY0  4 1nY1  5 1nY2  6 1nY3  7 2nY3  8 2nY2  9 2nY1  10 2nY0
          // 11 2B  12 2A  13 2nG
          symbol()
              .in(of(1), of(2), renamed(0, "1G", "1nG Enable (active LOW)"))
              .out(of(3), of(4), of(5), of(6))
              .blank()
              .in(of(12), of(11), renamed(13, "2G", "2nG Enable (active LOW)"))
              .out(of(10), of(9), of(8), of(7))
              .of(Ttl74139::new),

          // 74151 8-to-1 line multiplexer. W is the complement of Y.
          // 0 D3  1 D2  2 D1  3 D0  4 Y  5 W  6 nG  7 C  8 B  9 A  10 D7  11 D6  12 D5  13 D4
          symbol()
              .in(of(3), of(2), of(1), of(0), of(13), of(12), of(11), of(10))
              .out(of(4), inverted(5))
              .blank()
              .in(of(9), of(8), of(7))
              .blank()
              .in(of(6))
              .of(Ttl74151::new),

          // 74153 dual 4-to-1 line multiplexer with a shared pair of select pins.
          // 0 n1E  1 S1  2 1D3  3 1D2  4 1D1  5 1D0  6 1Y  7 2Y  8 2D0  9 2D1  10 2D2  11 2D3
          // 12 S0  13 n2E
          symbol()
              .gate(of(6), of(5), of(4), of(3), of(2), Row.gap(), of(0))
              .blank()
              .gate(of(7), of(8), of(9), of(10), of(11), Row.gap(), of(13))
              .blank()
              .in(of(12), of(1))
              .of(Ttl74153::new),

          // 74157 quad 2-to-1 line multiplexer.
          // 0 SELECT  1 1A  2 1B  3 1Y  4 2A  5 2B  6 2Y  7 3Y  8 3B  9 3A  10 4Y  11 4B  12 4A
          // 13 nSTROBE
          quadMultiplexer(false).of(Ttl74157::new),

          // 74158 quad 2-to-1 line multiplexer with inverting outputs.
          quadMultiplexer(true).of(Ttl74158::new),

          // 74161 4-bit binary counter with asynchronous clear.
          binaryCounter().of(Ttl74161::new),

          // 74163 4-bit binary counter with synchronous clear.
          binaryCounter().of(Ttl74163::new),

          // 74164 8-bit serial-in parallel-out shift register. The two serial inputs are ANDed.
          // 0 A  1 B  2 QA  3 QB  4 QC  5 QD  6 Clock  7 Clear  8 QE  9 QF  10 QG  11 QH
          symbol()
              .in(of(0), of(1))
              .blank()
              .in(
                  renamedClock(6, "CLK", "Clock"),
                  renamedInverted(7, "CLR", "Clear"))
              .out(of(2), of(3), of(4), of(5), of(8), of(9), of(10), of(11))
              .of(Ttl74164::new),

          // 74165 8-bit parallel-in serial-out shift register.
          // 0 Shift/Load  1 Clock  2 P4  3 P5  4 P6  5 P7  6 Q7n  7 Q7  8 Serial Input  9 P0
          // 10 P1  11 P2  12 P3  13 Clock Inhibit
          symbol()
              .in(of(9), of(10), of(11), of(12), of(2), of(3), of(4), of(5))
              .out(of(7), renamedInverted(6, "Q7", "Q7n"))
              .blank()
              .in(renamed(8, "SER", "Serial Input"))
              .blank()
              .in(
                  renamedInverted(0, "SH/LD", "Shift/Load"),
                  renamed(13, "CLKINH", "Clock Inhibit"),
                  renamedClock(1, "CLK", "Clock"))
              .of(Ttl74165::new),

          // 74166 8-bit parallel-in serial-out shift register with clear.
          // 0 Serial Input  1 P0  2 P1  3 P2  4 P3  5 Clock Inhibit  6 Clock  7 Clear  8 P4
          // 9 P5  10 P6  11 Q7  12 P7  13 Shift/Load
          symbol()
              .in(of(1), of(2), of(3), of(4), of(8), of(9), of(10), of(12))
              .out(of(11))
              .blank()
              .in(renamed(0, "SER", "Serial Input"))
              .blank()
              .in(
                  renamedInverted(13, "SH/LD", "Shift/Load"),
                  renamed(5, "CLKINH", "Clock Inhibit"),
                  renamedClock(6, "CLK", "Clock"),
                  renamedInverted(7, "CLR", "Clear"))
              .of(Ttl74166::new),

          // 74175 quad D flip-flop, the part the lecture slide draws.
          // 0 nCLR  1 Q1  2 nQ1  3 D1  4 D2  5 nQ2  6 Q2  7 CLK  8 Q3  9 nQ3  10 D3  11 D4
          // 12 nQ4  13 Q4
          symbol()
              .in(of(3), of(4), of(10), of(11))
              .out(of(1), of(6), of(8), of(13))
              .blank()
              .out(of(2), of(5), of(9), of(12))
              .blank()
              .in(clock(7), of(0))
              .of(Ttl74175::new),

          // 74181 arithmetic logic unit.
          // 0 B0  1 A0  2 S3  3 S2  4 S1  5 S0  6 nCi  7 M  8 F0  9 F1  10 F2  11 F3  12 A=B
          // 13 Pn  14 Co  15 Gn  16 B3  17 A3  18 B2  19 A2  20 B1  21 A1
          symbol()
              .in(of(1), of(21), of(19), of(17))
              .out(of(8), of(9), of(10), of(11))
              .blank()
              .in(of(0), of(20), of(18), of(16))
              .out(of(12), of(14), renamedInverted(13, "P", "Pn"), renamedInverted(15, "G", "Gn"))
              .blank()
              .in(of(5), of(4), of(3), of(2))
              .blank()
              .in(of(7), of(6))
              .of(Ttl74181::new),

          // 74182 carry lookahead generator. Every P and G, in and out, is active low; upstream's
          // own code says so where its pin names do not.
          // 0 G1  1 P1  2 G0  3 P0  4 G3  5 P3  6 P  7 Cnz  8 G  9 Cny  10 Cnx  11 Cn  12 G2
          // 13 P2
          symbol()
              .in(inverted(3), inverted(1), inverted(13), inverted(5))
              .out(of(10), of(9), of(7))
              .blank()
              .in(inverted(2), inverted(0), inverted(12), inverted(4))
              .out(inverted(6), inverted(8))
              .blank()
              .in(of(11))
              .of(Ttl74182::new),

          // 74192 decade up/down counter.
          upDownCounter().of(Ttl74192::new),

          // 74193 4-bit binary up/down counter.
          upDownCounter().of(Ttl74193::new),

          // 74194 4-bit bidirectional universal shift register.
          // 0 nCLR  1 SR  2 A  3 B  4 C  5 D  6 SL  7 S0  8 S1  9 CLK  10 QD  11 QC  12 QB
          // 13 QA
          symbol()
              .in(of(2), of(3), of(4), of(5))
              .out(of(13), of(12), of(11), of(10))
              .blank()
              .in(of(1), of(6))
              .blank()
              .in(of(7), of(8))
              .blank()
              .in(clock(9), of(0))
              .of(Ttl74194::new),

          // 74240 octal buffer with inverting outputs and two active-low enables.
          octalBuffer(2, 4, 6, 8, 10, 12, 14, 16).of(Ttl74240::new),

          // 74241 octal buffer, one enable active low and one active high.
          octalBuffer(2, 4, 6, 8, 10, 12, 14, 16).of(Ttl74241::new),

          // 74244 octal buffer with two active-low enables.
          octalBuffer(2, 4, 6, 8, 10, 12, 14, 16).of(Ttl74244::new),

          // 74245 octal bus transceiver. The A and B pins are bidirectional; DIR picks the way.
          // 0 DIR  1..8 A1..A8  9..16 B8..B1  17 nOE
          symbol()
              .in(of(1), of(2), of(3), of(4), of(5), of(6), of(7), of(8))
              .out(of(16), of(15), of(14), of(13), of(12), of(11), of(10), of(9))
              .blank()
              .in(of(0), of(17))
              .of(Ttl74245::new),

          // 74266 quad 2-input XNOR with open-drain outputs.
          quadTwoInput(true).of(Ttl74266::new),

          // 74273 octal D flip-flop with clear.
          octalRegister(0).of(Ttl74273::new),

          // 74283 4-bit binary full adder, the part the lecture slide draws.
          // 0 S2  1 B2  2 A2  3 S1  4 A1  5 B1  6 CIN  7 C4  8 S4  9 B4  10 A4  11 S3  12 A3
          // 13 B3
          symbol()
              .in(of(4), of(2), of(12), of(10))
              .out(of(3), of(0), of(11), of(8))
              .blank()
              .in(of(5), of(1), of(13), of(9))
              .blank()
              .in(of(6))
              .out(of(7))
              .of(Ttl74283::new),

          // 74299 8-bit universal shift/storage register with three-state outputs.
          // 0 S0  1 nOE1  2 nOE2  3 IOG  4 IOE  5 IOC  6 IOA  7 QA  8 nCLR  9 SR  10 CLK
          // 11 IOB  12 IOD  13 IOF  14 IOH  15 QH  16 SL  17 S1
          symbol()
              .in(of(0), of(17))
              .out(of(6), of(11), of(5), of(12), of(4), of(13), of(3), of(14))
              .blank()
              .in(of(9), of(16))
              .blank()
              .in(of(1), of(2))
              .out(of(7), of(15))
              .blank()
              .in(clock(10), of(8))
              .of(Ttl74299::new),

          // 74377 octal D flip-flop with clock enable.
          octalRegister(0).of(Ttl74377::new),

          // 74381 arithmetic logic unit / function generator.
          // 0 A1  1 B1  2 A0  3 B0  4 S0  5 S1  6 S2  7 F0  8 F1  9 F2  10 F3  11 Gn  12 Pn
          // 13 Ci  14 B3  15 A3  16 B2  17 A2
          symbol()
              .in(of(2), of(0), of(17), of(15))
              .out(of(7), of(8), of(9), of(10))
              .blank()
              .in(of(3), of(1), of(16), of(14))
              .out(renamedInverted(11, "G", "Gn"), renamedInverted(12, "P", "Pn"))
              .blank()
              .in(of(4), of(5), of(6))
              .blank()
              .in(of(13))
              .of(Ttl74381::new),

          // 74541 octal buffer with two active-low enables, inputs and outputs on opposite ends.
          // 0 nOE1  1..8 A1..A8  9 Y8  10 Y7  11 Y6  12 Y5  13 Y4  14 Y3  15 Y2  16 Y1  17 nOE2
          symbol()
              .in(of(1), of(2), of(3), of(4), of(5), of(6), of(7), of(8))
              .out(of(16), of(15), of(14), of(13), of(12), of(11), of(10), of(9))
              .blank()
              .in(of(0), of(17))
              .of(Ttl74541::new),

          // 74670 4-by-4 register file with separate read and write ports.
          // 0 D2  1 D3  2 D4  3 RA1  4 RA0  5 Q4  6 Q3  7 Q2  8 Q1  9 nOE  10 nWE  11 WA1
          // 12 WA0  13 D1
          symbol()
              .in(of(13), of(0), of(1), of(2))
              .out(of(8), of(7), of(6), of(5))
              .blank()
              .in(of(12), of(11))
              .blank()
              .in(of(4), of(3))
              .blank()
              .in(of(10), of(9))
              .of(Ttl74670::new),

          // 747266 quad 2-input XNOR.
          quadTwoInput(true).of(Ttl747266::new));

  /**
   * 7442, 7443 and 7444 share a pinout: the four code inputs at the top of the package and ten
   * active-low outputs, one per decoded value.
   */
  private static Layout decimalDecoder() {
    return symbol()
        .in(of(13), of(12), of(11), of(10))
        .out(
            inverted(0), inverted(1), inverted(2), inverted(3), inverted(4),
            inverted(5), inverted(6), inverted(7), inverted(8), inverted(9));
  }

  /** 74157 and 74158, which differ only in whether the outputs are inverted. */
  private static Layout quadMultiplexer(boolean invertingOutput) {
    return symbol()
        .gate(invertingOutput ? inverted(3) : of(3), of(1), of(2))
        .blank()
        .gate(invertingOutput ? inverted(6) : of(6), of(4), of(5))
        .blank()
        .gate(invertingOutput ? inverted(7) : of(7), of(9), of(8))
        .blank()
        .gate(invertingOutput ? inverted(10) : of(10), of(12), of(11))
        .blank()
        .in(of(0), renamed(13, "STROBE", "nSTROBE (active LOW)"));
  }

  /**
   * 74161 and 74163, which share a pinout and differ only in whether the clear is synchronous.
   * Upstream's pin names carry both the older and the newer symbol for each pin, separated by a
   * slash; the symbol keeps the newer one.
   */
  private static Layout binaryCounter() {
    return symbol()
        .in(
            renamed(2, "D0", "D0/A"),
            renamed(3, "D1", "D1/B"),
            renamed(4, "D2", "D2/C"),
            renamed(5, "D3", "D3/D"))
        .out(
            renamed(12, "Q0", "A0/QA"),
            renamed(11, "Q1", "A1/QB"),
            renamed(10, "Q2", "Q2/QC"),
            renamed(9, "Q3", "Q3/QD"))
        .blank()
        .in(
            renamed(6, "ENP", "CE/ENP (Count Enable)"),
            renamed(8, "ENT", "CET/ENT (Count Enable Carry)"),
            renamed(7, "PE", "PE/LOAD (Parallel Enable, active LOW)"))
        .out(renamed(13, "TC", "TC/RC0 (Terminal Count)"))
        .blank()
        .in(
            renamedClock(1, "CLK", "CP/CLK (Clock)"),
            renamed(0, "MR", "MR/CLR (Reset, active LOW)"));
  }

  /**
   * 74192 and 74193, which share a pinout and differ only in whether the count wraps at nine or at
   * fifteen. Upstream spells these pins out in words; the symbol uses the datasheet's letters.
   */
  private static Layout upDownCounter() {
    return symbol()
        .in(
            renamed(13, "A", "Data Input A"),
            renamed(0, "B", "Data Input B"),
            renamed(8, "C", "Data Input C"),
            renamed(7, "D", "Data Input D"))
        .out(
            renamed(2, "QA", "Data Output A"),
            renamed(1, "QB", "Data Output B"),
            renamed(5, "QC", "Data Output C"),
            renamed(6, "QD", "Data Output D"))
        .blank()
        .in(renamedClock(4, "UP", "Count Up"), renamedClock(3, "DOWN", "Count Down"))
        .out(renamedInverted(10, "CO", "Carry"), renamedInverted(11, "BO", "Borrow"))
        .blank()
        .in(renamedInverted(9, "LOAD", "Load"), renamed(12, "CLR", "Clear"));
  }

  /**
   * 74240, 74241 and 74244: two four-bit buffers in one package, each with its own enable. The
   * output indices differ between the parts only in whether upstream marks them active low, so
   * they are passed in.
   *
   * <p>Pin order is 0 1G, then the 1A inputs at odd indices 1..7, the 2A inputs at odd indices
   * 9..15, and 17 2G.
   */
  private static Layout octalBuffer(int y2d, int y2c, int y2b, int y2a, int y1d, int y1c, int y1b, int y1a) {
    return symbol()
        .in(of(1), of(3), of(5), of(7))
        .out(of(y1a), of(y1b), of(y1c), of(y1d))
        .blank()
        .in(of(9), of(11), of(13), of(15))
        .out(of(y2a), of(y2b), of(y2c), of(y2d))
        .blank()
        .in(of(0), of(17));
  }

  /**
   * 74273 and 74377: eight D flip-flops sharing a clock, plus one more control pin that is a clear
   * on the one and a clock enable on the other. Both sit at index 0, and both are active low, so
   * both take their name and their circle from upstream.
   *
   * <p>Pin order interleaves the D and Q pins: 0 control, 1 Q1, 2 D1, 3 D2, 4 Q2, 5 Q3, 6 D3,
   * 7 D4, 8 Q4, 9 CLK, 10 Q5, 11 D5, 12 D6, 13 Q6, 14 Q7, 15 D7, 16 D8, 17 Q8.
   */
  private static Layout octalRegister(int control) {
    return symbol()
        .in(of(2), of(3), of(6), of(7), of(11), of(12), of(15), of(16))
        .out(of(1), of(4), of(5), of(8), of(10), of(13), of(14), of(17))
        .blank()
        .in(clock(9), of(control));
  }
}
