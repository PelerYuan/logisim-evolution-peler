/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.ttlsymbol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cburch.logisim.data.Value;
import com.cburch.logisim.std.ttlsymbol.TtlSymbolSpec.Row;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Peler Edition. One symbol under test, addressed by the names written on it rather than by port
 * index.
 *
 * <p>Going through the names is the point. The equivalence test already compares a symbol with its
 * DIP package index by index and so can say nothing about whether the name beside an index is the
 * right one; here a vector is written the way a datasheet writes it -- drive A and B, read Y -- and
 * a layout table that put the right index beside the wrong name gets the wrong answer.
 */
final class TtlSymbolProbe {

  private final TtlSymbolSpec spec;
  private final TtlSymbolGate gate;
  private final TtlFixture fixture;

  private TtlSymbolProbe(TtlSymbolSpec spec, TtlSymbolGate gate, TtlFixture fixture) {
    this.spec = spec;
    this.gate = gate;
    this.fixture = fixture;
  }

  /** Opens the symbol for the named chip, e.g. {@code "74283"}. */
  static TtlSymbolProbe of(String chip, Path workDir) throws Exception {
    final var spec =
        TtlSymbolLayouts.SPECS.stream()
            .filter(s -> s.delegate().get().getName().equals(chip))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("no symbol for " + chip));
    final var gate = new TtlSymbolGate(spec);
    return new TtlSymbolProbe(spec, gate, TtlFixture.open(gate, "TTL Symbols", workDir));
  }

  String chip() {
    return spec.delegate().get().getName();
  }

  TtlSymbolSpec spec() {
    return spec;
  }

  TtlSymbolGate gate() {
    return gate;
  }

  TtlSymbolProbe drive(String label, boolean high) {
    fixture.drive(find(spec.left(), label, false), high ? Value.TRUE : Value.FALSE);
    return this;
  }

  /** Holds every input the caller has not named at a low level. */
  TtlSymbolProbe driveOthersLow(List<String> named) {
    for (final var row : spec.left()) {
      if (row.isGap()) continue;
      final var label = gate.label(row.index());
      if (!named.contains(label) && fixture.inputPorts().contains(row.index())) {
        fixture.drive(row.index(), Value.FALSE);
      }
    }
    return this;
  }

  TtlSymbolProbe settle() {
    fixture.settle();
    return this;
  }

  Value read(String label) {
    return fixture.read(find(spec.right(), label, false));
  }

  void expect(String label, boolean high, String what) {
    expect(label, high ? Value.TRUE : Value.FALSE, what);
  }

  void expect(String label, Value expected, String what) {
    assertEquals(expected, read(label), chip() + ": " + what);
  }

  void expectInverted(String label, boolean high, String what) {
    assertEquals(
        high ? Value.TRUE : Value.FALSE,
        fixture.read(find(spec.right(), label, true)),
        chip() + ": " + what);
  }

  /**
   * The port index of the row in this column labelled {@code label}. The inversion circle is only
   * consulted when the name alone is ambiguous, which happens on a flip-flop: it writes Q1 twice
   * down its right-hand side, once with the circle that says it is the complement.
   */
  private int find(List<Row> column, String label, boolean inverted) {
    final var matches = new ArrayList<Integer>();
    for (final var row : column) {
      if (!row.isGap() && gate.label(row.index()).equals(label)) matches.add(row.index());
    }
    if (matches.size() == 1) return matches.get(0);
    for (final var index : matches) {
      if (gate.isInverted(index) == inverted) return index;
    }
    throw new IllegalArgumentException(
        chip() + " has no port labelled " + label
            + (inverted ? " with an inversion circle" : "") + " on that side");
  }
}
