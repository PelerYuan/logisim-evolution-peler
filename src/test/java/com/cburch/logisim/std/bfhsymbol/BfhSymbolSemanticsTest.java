/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.bfhsymbol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.std.symbol.SymbolFixture;
import com.cburch.logisim.std.symbol.SymbolGate;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Peler Edition. The symbols read through the names written on them, rather than through port
 * indices.
 *
 * <p>{@code BfhSymbolEquivalenceTest} compares a symbol with its component index by index, and so
 * is blind by construction to what the labels say: move the label "c" next to the port that carries
 * segment e and both halves still agree perfectly, while the picture on screen is a lie. The
 * vectors here are stated the way a datasheet states them -- drive the input, name the outputs --
 * so a label beside the wrong index gets the wrong answer.
 *
 * <p>The expected values are not taken from the components. The seven-segment patterns are the
 * standard ones for a numeral display, and a decimal digit is a decimal digit; both are outside
 * knowledge, which is what makes them a check rather than a restatement.
 */
public class BfhSymbolSemanticsTest {

  @TempDir Path workDir;

  /** Which segments a numeral lights, in the usual arrangement with a at the top and g the bar. */
  private static final List<Set<String>> NUMERAL_SEGMENTS =
      List.of(
          Set.of("a", "b", "c", "d", "e", "f"),
          Set.of("b", "c"),
          Set.of("a", "b", "d", "e", "g"),
          Set.of("a", "b", "c", "d", "g"),
          Set.of("b", "c", "f", "g"),
          Set.of("a", "c", "d", "f", "g"),
          Set.of("a", "c", "d", "e", "f", "g"),
          Set.of("a", "b", "c"),
          Set.of("a", "b", "c", "d", "e", "f", "g"),
          Set.of("a", "b", "c", "d", "f", "g"));

  static List<Integer> numerals() {
    return List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
  }

  @ParameterizedTest
  @MethodSource("numerals")
  public void theDecoderLightsTheSegmentsTheNumeralNeeds(int numeral) throws Exception {
    final var testCase = BfhSymbolCases.sevenSegment();
    final var probe = open(testCase);
    probe.drive("BCD", numeral);

    for (final var segment : NUMERAL_SEGMENTS.get(8)) {
      final var lit = NUMERAL_SEGMENTS.get(numeral).contains(segment);
      assertEquals(
          lit ? Value.TRUE : Value.FALSE,
          probe.read(segment),
          "displaying " + numeral + ": segment " + segment + " is the wrong way round");
    }
  }

  /** Above nine there is no numeral, and the component blanks the display rather than guessing. */
  @Test
  public void theDecoderBlanksEverySegmentForAnInputAboveNine() throws Exception {
    final var probe = open(BfhSymbolCases.sevenSegment());
    for (var value = 10; value < 16; value++) {
      probe.drive("BCD", value);
      for (final var segment : NUMERAL_SEGMENTS.get(8)) {
        assertFalse(
            probe.read(segment).isFullyDefined(),
            "input " + value + " is not a numeral, so segment " + segment + " should be blank");
      }
    }
  }

  /**
   * The converter puts each decimal digit on the port its label claims.
   *
   * <p>Three widths, chosen for where an off-by-one would hide: the narrowest the attribute allows,
   * the default, and the widest. The values are the largest each holds, plus one that puts a zero
   * in the middle -- a digit reading as its neighbour is invisible when every digit differs.
   */
  @Test
  public void theConverterSplitsANumberIntoTheDigitsItsLabelsName() throws Exception {
    assertDigits(4, 15, Map.of("10", 1, "1", 5));
    assertDigits(9, 407, Map.of("100", 4, "10", 0, "1", 7));
    assertDigits(9, 511, Map.of("100", 5, "10", 1, "1", 1));
    assertDigits(13, 8191, Map.of("1000", 8, "100", 1, "10", 9, "1", 1));
    assertDigits(13, 1024, Map.of("1000", 1, "100", 0, "10", 2, "1", 4));
  }

  private void assertDigits(int bits, int value, Map<String, Integer> expected) throws Exception {
    final var probe = open(BfhSymbolCases.binToBcd(bits));
    probe.drive("Bin", value);
    for (final var entry : expected.entrySet()) {
      assertEquals(
          Value.createKnown(BitWidth.create(4), entry.getValue()),
          probe.read(entry.getKey()),
          bits + " bits, " + value + ": the port labelled " + entry.getKey() + " has the wrong digit");
    }
  }

  private Probe open(BfhSymbolCases testCase) throws Exception {
    return new Probe(
        SymbolFixture.open(
            testCase.symbol(),
            BfhSymbolCases.SYMBOL_LIBRARY,
            workDir,
            testCase.symbolAttrs()),
        testCase.symbol(),
        testCase.symbolAttrs());
  }

  /** One symbol addressed by the labels drawn on it. */
  private static final class Probe {
    private final SymbolFixture fixture;
    private final Map<String, Integer> byLabel = new LinkedHashMap<>();

    private Probe(SymbolFixture fixture, SymbolGate symbol, AttributeSet attrs) {
      this.fixture = fixture;
      final var layout = symbol.layoutFor(attrs);
      for (var i = 0; i < layout.portCount(); i++) {
        assertTrue(
            byLabel.put(layout.label(i), i) == null,
            "two ports are labelled " + layout.label(i) + ", so a vector cannot name either");
      }
    }

    void drive(String label, int value) {
      final var port = byLabel.get(label);
      assertTrue(port != null, "no port is labelled " + label);
      fixture.drive(port, Value.createKnown(fixture.widthOf(port), value));
      fixture.settle();
    }

    Value read(String label) {
      final var port = byLabel.get(label);
      assertTrue(port != null, "no port is labelled " + label);
      return fixture.read(port);
    }
  }
}
