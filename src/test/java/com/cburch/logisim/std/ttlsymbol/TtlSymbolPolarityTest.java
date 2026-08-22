/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.ttlsymbol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.data.Value;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Peler Edition. The inversion circles.
 *
 * <p>Nothing else in this package looks at them. The equivalence test compares port index with
 * port index and so is blind to anything drawn; the layout test reads names and positions. A
 * circle is therefore the one part of a symbol that could be wrong on all sixty-one chips without
 * a single test noticing -- and four of them were: the 7413, 7418 and 7420 drew their NAND output
 * bare, and the 7442, 7443 and 7444 drew ten active-low decoder outputs with nothing to say so,
 * while the 74181 and 74381 carried upstream's "Pn" and "Gn" through as literal names. The full
 * suite passed before and after the fix.
 *
 * <p>The three checks here approach the circle from three sides, and none of them repeats a
 * decision the layout tables already make:
 *
 * <ul>
 *   <li>where upstream's own pinout marks a pin active low, the circle must be there;
 *   <li>where the chip is a plain gate, the truth table written out here says what the output is,
 *       and the circle has to agree with the same record field the truth table is taken from;
 *   <li>where the chip is a one-hot decoder, the asserted level is read off the chip itself -- the
 *       output that stands apart from its nine or seven neighbours -- and the circle has to agree
 *       with that.
 * </ul>
 *
 * <p>The first check is deliberately one-directional. A circle without an upstream marker is not
 * an error: upstream calls the 7442's outputs "O0" through "O9" and says nothing about polarity
 * anywhere in the pinout, so that chip's circles can only come from the layout table.
 */
public class TtlSymbolPolarityTest {

  @TempDir Path workDir;

  /** Upstream's own marker for an active-low pin: {@code nCLR}, {@code nOE1}, {@code 1nY0}. */
  private static final Pattern NEGATED_PREFIX = Pattern.compile("\\d*n[A-Z0-9].*");

  /** The other one, used on outputs: {@code Pn}, {@code Gn}, {@code Q7n}. */
  private static final Pattern NEGATED_SUFFIX = Pattern.compile("[A-Z][A-Za-z0-9]*n");

  /** Spelled out instead, inside a longer description. */
  private static final String NEGATED_WORDS = "active LOW";

  /**
   * The 74182's carry input. It ends in an "n" like {@code Pn} and {@code Gn} beside it on the same
   * chip, but the letter is the datasheet's subscript in C(n) rather than a negation, and the pin
   * is active high.
   */
  private static final Set<String> NOT_A_NEGATION = Set.of("Cn");

  static List<TtlSymbolSpec> symbols() {
    return TtlSymbolLayouts.SPECS;
  }

  @ParameterizedTest
  @MethodSource("symbols")
  public void pinsUpstreamMarksAsActiveLowCarryTheirCircle(TtlSymbolSpec spec) {
    final var gate = new TtlSymbolGate(spec);
    final var names = spec.delegate().get().getPortNames();
    if (names == null) return; // A gate array; upstream shows bare pin numbers.

    for (final var row : spec.ports()) {
      final var name = names[row.index()];
      if (name == null || NOT_A_NEGATION.contains(name)) continue;
      final var marked =
          NEGATED_PREFIX.matcher(name).matches()
              || NEGATED_SUFFIX.matcher(name).matches()
              || name.contains(NEGATED_WORDS);
      if (!marked) continue;
      assertTrue(
          gate.isInverted(row.index()),
          spec + " port " + row.index() + " is called \"" + name
              + "\" upstream, which marks it active low, but the symbol draws it without a circle");
    }
  }

  /**
   * A chip whose whole function fits in a truth table, written the way its datasheet writes it:
   * each output is the OR of a few ANDs of the names printed beside the pins, optionally inverted.
   */
  private record Gate(String chip, List<Output> outputs) {
    @Override
    public String toString() {
      return chip;
    }
  }

  private record Output(String label, boolean inverting, List<List<String>> andGroups) {}

  private static Output invert(String label, List<List<String>> andGroups) {
    return new Output(label, true, andGroups);
  }

  private static Output pass(String label, List<List<String>> andGroups) {
    return new Output(label, false, andGroups);
  }

  private static Gate dualFourInput(String chip, boolean inverting) {
    return new Gate(
        chip,
        List.of(
            new Output(
                "Y0", inverting, List.of(List.of("A0", "B0", "C0", "D0"))),
            new Output(
                "Y1", inverting, List.of(List.of("A1", "B1", "C1", "D1")))));
  }

  static List<Gate> gates() {
    return List.of(
        dualFourInput("7413", true),
        dualFourInput("7418", true),
        dualFourInput("7420", true),
        dualFourInput("7421", false),
        new Gate(
            "7430",
            List.of(invert("Y", List.of(List.of("A", "B", "C", "D", "E", "F", "G", "H"))))),
        new Gate(
            "7451",
            List.of(
                invert("Y1", List.of(List.of("A1", "B1"), List.of("C1", "D1"))),
                invert("Y2", List.of(List.of("A2", "B2"), List.of("C2", "D2"))))),
        new Gate(
            "7454",
            List.of(
                invert(
                    "Y",
                    List.of(
                        List.of("A", "B"),
                        List.of("C", "D"),
                        List.of("E", "F"),
                        List.of("G", "H"))))),
        new Gate(
            "7458",
            List.of(
                pass("Y0", List.of(List.of("A0", "B0", "C0"), List.of("D0", "E0", "F0"))),
                pass("Y1", List.of(List.of("A1", "B1"), List.of("C1", "D1"))))),
        new Gate(
            "7464",
            List.of(
                invert(
                    "Y",
                    List.of(
                        List.of("A", "B", "C", "D"),
                        List.of("E", "F"),
                        List.of("G", "H", "I"),
                        List.of("J", "K"))))));
  }

  @ParameterizedTest
  @MethodSource("gates")
  public void plainGatesComputeAndAnnounceTheirFunction(Gate gate) throws Exception {
    final var probe = TtlSymbolProbe.of(gate.chip(), workDir);

    final var inputs = new ArrayList<String>();
    for (final var row : probe.spec().left()) {
      if (!row.isGap()) inputs.add(probe.gate().label(row.index()));
    }

    for (final var output : gate.outputs()) {
      for (final var group : output.andGroups()) {
        assertTrue(
            inputs.containsAll(group),
            gate + " has no inputs named " + group + "; it offers " + inputs);
      }
      assertEquals(
          output.inverting(),
          probe.gate().isInverted(indexOf(probe, output.label())),
          gate + " output " + output.label() + " is drawn with the wrong polarity: the truth "
              + "table below says inverting=" + output.inverting());
    }

    for (var pattern = 0; pattern < (1 << inputs.size()); pattern++) {
      final var high = new LinkedHashMap<String, Boolean>();
      for (var bit = 0; bit < inputs.size(); bit++) {
        high.put(inputs.get(bit), ((pattern >> bit) & 1) == 1);
      }
      for (final var entry : high.entrySet()) probe.drive(entry.getKey(), entry.getValue());
      probe.settle();

      for (final var output : gate.outputs()) {
        var or = false;
        for (final var group : output.andGroups()) {
          var and = true;
          for (final var name : group) and &= high.get(name);
          or |= and;
        }
        probe.expect(output.label(), output.inverting() != or, "inputs " + high);
      }
    }
  }

  private static int indexOf(TtlSymbolProbe probe, String label) {
    for (final var row : probe.spec().right()) {
      if (!row.isGap() && probe.gate().label(row.index()).equals(label)) return row.index();
    }
    throw new IllegalArgumentException(probe.chip() + " has no output labelled " + label);
  }

  /**
   * A decoder, its code inputs and the outputs one of which it pulls away from the others. The
   * encoding is not written down: the test drives every code and watches which level stands alone,
   * so the excess-3 and excess-3-Gray parts need no table of their own.
   */
  private record Decoder(String chip, List<String> code, List<String> outputs, List<String> enables,
      List<Boolean> enabled) {
    @Override
    public String toString() {
      return chip + " " + outputs.get(0) + ".." + outputs.get(outputs.size() - 1);
    }
  }

  private static List<String> range(String prefix, int count) {
    final var names = new ArrayList<String>();
    for (var i = 0; i < count; i++) names.add(prefix + i);
    return names;
  }

  static List<Decoder> decoders() {
    return List.of(
        new Decoder("7442", List.of("A", "B", "C", "D"), range("O", 10), List.of(), List.of()),
        new Decoder("7443", List.of("A", "B", "C", "D"), range("O", 10), List.of(), List.of()),
        new Decoder("7444", List.of("A", "B", "C", "D"), range("O", 10), List.of(), List.of()),
        new Decoder("74138", List.of("A", "B", "C"), range("Y", 8),
            List.of("G1", "G2A", "G2B"), List.of(true, false, false)),
        new Decoder("74139", List.of("1A", "1B"), range("1Y", 4), List.of("1G"), List.of(false)),
        new Decoder("74139", List.of("2A", "2B"), range("2Y", 4), List.of("2G"), List.of(false)));
  }

  @ParameterizedTest
  @MethodSource("decoders")
  public void oneHotDecodersDrawTheLevelTheyActuallyAssert(Decoder decoder) throws Exception {
    final var probe = TtlSymbolProbe.of(decoder.chip(), workDir);

    Value asserted = null;
    var oneHotCodes = 0;

    for (var code = 0; code < (1 << decoder.code().size()); code++) {
      probe.driveOthersLow(List.of());
      for (var bit = 0; bit < decoder.code().size(); bit++) {
        probe.drive(decoder.code().get(bit), ((code >> bit) & 1) == 1);
      }
      for (var i = 0; i < decoder.enables().size(); i++) {
        probe.drive(decoder.enables().get(i), decoder.enabled().get(i));
      }
      probe.settle();

      final var levels = new ArrayList<Value>();
      for (final var label : decoder.outputs()) levels.add(probe.read(label));

      final var odd = onlyLevelAppearingOnce(levels);
      if (odd == null) continue;
      oneHotCodes++;
      if (asserted == null) {
        asserted = odd;
      } else {
        assertEquals(
            asserted, odd,
            decoder + ": code " + code + " singles out an output at " + odd
                + ", but an earlier code singled one out at " + asserted);
      }
    }

    assertTrue(
        oneHotCodes >= decoder.outputs().size(),
        decoder + ": only " + oneHotCodes + " of the codes selected a single output, so this "
            + "sweep never established which level the chip asserts");

    final var activeLow = Value.FALSE.equals(asserted);
    for (final var label : decoder.outputs()) {
      assertEquals(
          activeLow,
          probe.gate().isInverted(indexOf(probe, label)),
          decoder + " output " + label + ": the chip asserts " + asserted
              + ", so the symbol " + (activeLow ? "must" : "must not") + " draw an inversion circle");
    }
  }

  /** The level held by exactly one of the outputs, or null when none stands alone. */
  private static Value onlyLevelAppearingOnce(List<Value> levels) {
    Value odd = null;
    for (final var candidate : levels) {
      var count = 0;
      for (final var level : levels) if (level.equals(candidate)) count++;
      if (count != 1) continue;
      if (odd != null) return null;
      odd = candidate;
    }
    return odd;
  }
}
