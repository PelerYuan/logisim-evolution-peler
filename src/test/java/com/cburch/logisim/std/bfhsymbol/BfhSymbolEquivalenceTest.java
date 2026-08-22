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

import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.std.symbol.SymbolFixture;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Peler Edition. Each BFH symbol must answer exactly as the component it stands for.
 *
 * <p>The load-bearing test of the family, and it is load-bearing for the same reason the TTL one
 * is: a symbol reimplements nothing, {@code propagate} hands the state straight to the delegate,
 * and that is only sound because both factories address a port by index and never by where the
 * port sits. What could break it is the layout -- it decides which index goes where on the box, and
 * a transposed pair there gives a component that draws correctly, simulates without complaint, and
 * reports the wrong digit.
 *
 * <p>Both parts are combinational, so unlike the TTL sweep there is nothing to be gained from
 * carrying state across steps; instead every reachable input value is driven, which the 74xx chips
 * with their twenty-odd single-bit pins could not afford.
 */
public class BfhSymbolEquivalenceTest {

  /**
   * Beyond this many input values the sweep samples instead of counting. Thirteen bits is 8192
   * values and each is a full propagation, so the widest converters would otherwise dominate the
   * suite's runtime for no added coverage -- the conversion is digit-by-digit arithmetic, not a
   * lookup table with a rare wrong entry.
   */
  private static final int EXHAUSTIVE_LIMIT = 1024;

  @TempDir Path workDir;

  static List<BfhSymbolCases> cases() {
    return BfhSymbolCases.all();
  }

  @ParameterizedTest
  @MethodSource("cases")
  public void everySymbolAnswersAsTheComponentItRedraws(BfhSymbolCases testCase) throws Exception {
    final var delegateRuns =
        sweep(testCase.delegate(), BfhSymbolCases.DELEGATE_LIBRARY, testCase.delegateAttrs());
    final var symbolRuns =
        sweep(testCase.symbol(), BfhSymbolCases.SYMBOL_LIBRARY, testCase.symbolAttrs());

    // A sweep whose outputs never move is not evidence of anything, and is what a harness that has
    // stopped responding to its own inputs looks like from the outside.
    assertTrue(
        new HashSet<>(delegateRuns).size() > 1,
        testCase + ": the sweep never changed the component's outputs, so it compared nothing");

    assertEquals(
        delegateRuns.size(),
        symbolRuns.size(),
        testCase + ": the symbol and the component do not even have the same number of ports");
    for (var step = 0; step < delegateRuns.size(); step++) {
      assertEquals(
          delegateRuns.get(step),
          symbolRuns.get(step),
          testCase
              + " step "
              + step
              + ": the symbol disagrees with the component it redraws, so its layout names the "
              + "wrong port index somewhere.");
    }
  }

  /**
   * Drives the one input port through its whole range, or a sample of it, and records the outputs.
   *
   * <p>The unknown value is driven first and last. Both parts treat a not-fully-defined input as a
   * separate case -- the decoder blanks every segment, the converter divides minus one -- and that
   * branch is reached by nothing else here.
   */
  private List<String> sweep(ComponentFactory factory, String libraryId, AttributeSet attrs)
      throws Exception {
    final var fixture = SymbolFixture.open(factory, libraryId, workDir, attrs);
    final var inputs = fixture.inputPorts();
    assertEquals(1, inputs.size(), libraryId + " fixture should have exactly one input port");
    assertFalse(fixture.outputPorts().isEmpty(), libraryId + " fixture has no outputs");

    final var input = inputs.get(0);
    final var width = fixture.widthOf(input);
    final var span = 1 << width.getWidth();
    final var stride = span <= EXHAUSTIVE_LIMIT ? 1 : span / EXHAUSTIVE_LIMIT;

    final var rows = new ArrayList<String>();
    rows.add(step(fixture, input, Value.createUnknown(width)));
    for (var value = 0; value < span; value += stride) {
      rows.add(step(fixture, input, Value.createKnown(width, value)));
    }
    // The top of the range is where a digit count off by one shows, and a stride that does not
    // divide the span would otherwise step over it.
    rows.add(step(fixture, input, Value.createKnown(width, span - 1)));
    rows.add(step(fixture, input, Value.createUnknown(width)));
    return rows;
  }

  private static String step(SymbolFixture fixture, int port, Value value) {
    fixture.drive(port, value);
    fixture.settle();
    return fixture.outputRow();
  }
}
