/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.ttlsymbol;

import com.cburch.logisim.data.Value;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Peler Edition. The seventeen gate arrays, each gate driven on its own and read through its own
 * output.
 *
 * <p>These chips are the ones with no safety net anywhere else. Every other chip takes the names
 * beside its pins from upstream's pinout, so a layout table that names the wrong port index shows a
 * wrong name and the ordering check in {@code TtlSymbolLayoutTest} catches it. A gate array
 * declares no pin names at all -- upstream's tooltips show bare pin numbers -- so the names here
 * are written out by hand and nothing but behaviour can confirm them.
 *
 * <p>{@code TtlSymbolEquivalenceTest} cannot: it compares port index with port index, so moving
 * gate 3's output up to where gate 2's belongs leaves both chips agreeing exactly. That mutation
 * was made and did pass everything, which is why this exists. Every gate is walked through its
 * whole truth table with the other gates held low, so an output that answers to the wrong gate
 * reads a constant where it should be following its inputs.
 */
public class TtlSymbolGateArrayTest {

  @TempDir Path workDir;

  /**
   * One chip: how many gates, what each gate's inputs are called after its number, and what the
   * gate does. {@code 7400} is four gates of {@code 1A 1B -> 1Y}, and so on.
   */
  private record Family(String chip, int gates, List<String> inputs, Function<List<Boolean>, Value> fn) {
    @Override
    public String toString() {
      return chip;
    }
  }

  private static Value bit(boolean high) {
    return high ? Value.TRUE : Value.FALSE;
  }

  private static boolean all(List<Boolean> ins) {
    for (final var b : ins) if (!b) return false;
    return true;
  }

  private static boolean any(List<Boolean> ins) {
    for (final var b : ins) if (b) return true;
    return false;
  }

  static List<Family> families() {
    final var two = List.of("A", "B");
    final var three = List.of("A", "B", "C");
    final var one = List.of("A");
    return List.of(
        new Family("7400", 4, two, ins -> bit(!all(ins))),
        new Family("7402", 4, two, ins -> bit(!any(ins))),
        new Family("7404", 6, one, ins -> bit(!ins.get(0))),
        new Family("7408", 4, two, ins -> bit(all(ins))),
        new Family("7410", 3, three, ins -> bit(!all(ins))),
        new Family("7411", 3, three, ins -> bit(all(ins))),
        new Family("7414", 6, one, ins -> bit(!ins.get(0))),
        new Family("7419", 6, one, ins -> bit(!ins.get(0))),
        new Family("7424", 4, two, ins -> bit(!all(ins))),
        new Family("7427", 3, three, ins -> bit(!any(ins))),
        new Family("7432", 4, two, ins -> bit(any(ins))),
        new Family("7434", 6, one, ins -> bit(ins.get(0))),
        new Family("7436", 4, two, ins -> bit(!any(ins))),
        new Family("7486", 4, two, ins -> bit(ins.get(0) ^ ins.get(1))),
        // Open drain: the high side is not driven, so it reads as floating rather than as one.
        new Family("74266", 4, two, ins -> ins.get(0) == ins.get(1) ? Value.UNKNOWN : Value.FALSE),
        new Family("747266", 4, two, ins -> bit(ins.get(0) == ins.get(1))),
        // A three-state buffer: the enable is active low, and a disabled gate floats.
        new Family("74125", 4, List.of("A", "OE"), ins -> ins.get(1) ? Value.UNKNOWN : bit(ins.get(0))));
  }

  @ParameterizedTest
  @MethodSource("families")
  public void everyGateAnswersOnlyToItsOwnInputs(Family family) throws Exception {
    final var probe = TtlSymbolProbe.of(family.chip(), workDir);

    for (var gate = 1; gate <= family.gates(); gate++) {
      final var names = new ArrayList<String>();
      for (final var suffix : family.inputs()) names.add(gate + suffix);
      final var output = gate + "Y";

      for (var pattern = 0; pattern < (1 << names.size()); pattern++) {
        final var values = new ArrayList<Boolean>();
        for (var bit = 0; bit < names.size(); bit++) values.add(((pattern >> bit) & 1) == 1);

        // Every other gate is held low, so an output wired to the wrong gate sits at a constant
        // while this gate's inputs move underneath it.
        probe.driveOthersLow(names);
        for (var bit = 0; bit < names.size(); bit++) probe.drive(names.get(bit), values.get(bit));
        probe.settle();

        probe.expect(
            output,
            family.fn().apply(values),
            "gate " + gate + " with " + names + " = " + values);
      }
    }
  }
}
