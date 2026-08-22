/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.ttlsymbol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.std.ttl.TtlLibrary;
import com.cburch.logisim.tools.AddTool;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Peler Edition. Structural checks on the per-chip layout tables, which are the one part of this
 * feature written by hand and therefore the one part that can be wrong.
 *
 * <p>{@code TtlSymbolEquivalenceTest} deliberately cannot catch what these catch. It compares the
 * symbol with its DIP package port index by port index, so swapping two indices inside a layout
 * table leaves both chips agreeing perfectly while the symbol on screen has A2 and A3 the wrong way
 * round. That was confirmed by making exactly that mutation and watching the equivalence test pass.
 * The ordering check below is what fails on it.
 */
public class TtlSymbolLayoutTest {

  /** Longest port name that still reads as a pin symbol rather than as a sentence. */
  private static final int LONGEST_LABEL = 8;

  /** A name that ends in a group number: A1, S3, Q2, 1A4, 1Y0. The prefix ends in a non-digit. */
  private static final Pattern TRAILING_NUMBER = Pattern.compile("(.*?\\D)(\\d+)");

  /** A name that ends in letters, optionally after a group number: A, QC, IOF, 1A, a. */
  private static final Pattern TRAILING_LETTERS = Pattern.compile("(\\d*)([A-Za-z]+)");

  /** A name that is one run of letters and then a number: A0, B0, D1. */
  private static final Pattern LETTER_THEN_NUMBER = Pattern.compile("([A-Za-z]+)(\\d+)");

  /**
   * Below this, a group ordered by letters carries no order worth checking and reading one into it
   * produces false alarms: SR and SL are a pair, not a sequence.
   */
  private static final int SHORTEST_LETTER_SEQUENCE = 3;

  static List<TtlSymbolSpec> specs() {
    return TtlSymbolLayouts.SPECS;
  }

  private static String id(TtlSymbolSpec spec) {
    return "Sym" + spec.delegate().get().getName();
  }

  /** Every chip upstream offers has a symbol, and no chip has two. */
  @Test
  public void everyChipInTheTtlLibraryHasASymbol() {
    final var dip = new ArrayList<String>();
    for (final var tool : new TtlLibrary().getTools()) {
      dip.add(((AddTool) tool).getFactory().getName());
    }
    final var symbols = new ArrayList<String>();
    for (final var spec : TtlSymbolLayouts.SPECS) symbols.add(spec.delegate().get().getName());

    assertEquals(
        new HashSet<>(symbols).size(),
        symbols.size(),
        "a chip appears twice in the symbol library: " + symbols);
    assertEquals(dip, symbols, "the symbol library does not cover the same chips, in the same order");
  }

  /**
   * Every port of the chip appears on the symbol exactly once.
   *
   * <p>A missing port would be a signal with nowhere to connect, and a duplicated one would put two
   * ports at different places on the same index, which {@code Instance.setPorts} would take as the
   * later one silently winning.
   */
  @ParameterizedTest
  @MethodSource("specs")
  public void everyPortIsPlacedExactlyOnce(TtlSymbolSpec spec) {
    final var dip = spec.delegate().get();
    final var probe = dip.createComponent(Location.create(0, 0, true), dip.createAttributeSet());
    final var expected = probe.getEnds().size();
    final var seen = new HashSet<Integer>();
    for (final var row : spec.ports()) {
      assertTrue(seen.add(row.index()), id(spec) + " places port " + row.index() + " twice");
    }
    assertEquals(expected, seen.size(), id(spec) + " does not place every port of its chip");
    for (var i = 0; i < expected; i++) {
      assertTrue(seen.contains(i), id(spec) + " never places port " + i);
    }
  }

  /**
   * A label written out in the layout table still belongs to the port index next to it.
   *
   * <p>Spelling a label out is what breaks the safety the rest of the design relies on -- normally
   * the name comes from the pinout, so a wrong index shows a wrong name. {@code Row.renamed} gets
   * that back by carrying upstream's name along with the short one, and this is what reads it.
   */
  @ParameterizedTest
  @MethodSource("specs")
  public void shortenedLabelsStillNameTheirUpstreamPin(TtlSymbolSpec spec) {
    final var names = spec.delegate().get().getPortNames();
    for (final var row : spec.ports()) {
      if (row.upstreamName() == null) continue;
      assertNotNull(names, id(spec) + " shortens a name on a chip that declares none");
      assertEquals(
          row.upstreamName(),
          names[row.index()],
          id(spec) + " port " + row.index() + " was shortened to " + row.label()
              + ", but that index is not the pin the layout table says it is");
    }
  }

  /**
   * A label is invented from nothing only where there is nothing to take one from.
   *
   * <p>{@code Row.named} skips the check above, so it must not be reachable on a chip that has a
   * pinout; that chip should be using the pinout, or {@code Row.renamed} if the pinout's name is a
   * sentence.
   */
  @ParameterizedTest
  @MethodSource("specs")
  public void inventedLabelsOnlyAppearOnChipsWithNoPinout(TtlSymbolSpec spec) {
    final var names = spec.delegate().get().getPortNames();
    for (final var row : spec.ports()) {
      if (row.label() == null || row.upstreamName() != null) continue;
      final var declared = names != null && row.index() < names.length ? names[row.index()] : null;
      assertNull(
          declared,
          id(spec) + " port " + row.index() + " is written out as " + row.label()
              + " although the chip calls it " + declared + "; use Row.of or Row.renamed");
    }
  }

  /** Names on the symbol are pin symbols, short enough to sit inside the box. */
  @ParameterizedTest
  @MethodSource("specs")
  public void labelsAreShortEnoughToBePinSymbols(TtlSymbolSpec spec) {
    final var gate = new TtlSymbolGate(spec);
    for (final var row : spec.ports()) {
      final var label = gate.label(row.index());
      assertTrue(
          label.length() <= LONGEST_LABEL,
          id(spec) + " port " + row.index() + " is labelled \"" + label
              + "\", which is a description rather than a pin symbol; shorten it with Row.renamed");
    }
  }

  /**
   * Inside one group -- a run of rows between blanks -- the names run in order as you go down.
   *
   * <p>This is the check that catches a transposed pair, which is the realistic way to get a layout
   * table wrong: the indices are bare numbers with no local meaning, so nothing about writing
   * {@code Row.of(12)} where {@code Row.of(2)} belongs looks unusual. It becomes visible only once
   * the name is resolved from the pinout, which is what this does.
   *
   * <p>A group is only checked when its names agree on how they are ordered -- all by a trailing
   * number, or all by a letter in the same position. A group that mixes a clock with an enable has
   * no order to be wrong about and is left alone.
   */
  @ParameterizedTest
  @MethodSource("specs")
  public void groupsRunInOrder(TtlSymbolSpec spec) {
    final var gate = new TtlSymbolGate(spec);
    for (final var column : List.of(spec.left(), spec.right())) {
      final var group = new ArrayList<String>();
      for (final var row : column) {
        if (row.isGap()) {
          assertAscending(spec, group);
          group.clear();
        } else {
          group.add(gate.label(row.index()));
        }
      }
      assertAscending(spec, group);
    }
  }

  private static void assertAscending(TtlSymbolSpec spec, List<String> group) {
    final var order = ordinals(group);
    if (order == null) return;
    for (var i = 1; i < order.size(); i++) {
      if (order.get(i) < order.get(i - 1)) {
        fail(
            id(spec) + " lists " + String.join(" ", group)
                + " down one side. Two rows of the layout table name each other's port index.");
      }
    }
  }

  /**
   * Reads a sequence out of a group of names, or nothing if they do not form one. Three shapes
   * count: a shared prefix and a trailing number (A1 A2 A3), a shared group number and a letter
   * (1A 1B, IOA IOB, a b c), and a shared trailing number after a letter (A0 B0 C0). The letter
   * forms need the letters all the same length, so that ENP next to PE is not read as a sequence
   * running backwards.
   */
  private static List<Integer> ordinals(List<String> group) {
    if (group.size() < 2) return null;

    final var byNumber = new ArrayList<Integer>();
    String prefix = null;
    for (final var name : group) {
      final var m = TRAILING_NUMBER.matcher(name);
      if (!m.matches() || (prefix != null && !prefix.equals(m.group(1)))) {
        byNumber.clear();
        break;
      }
      prefix = m.group(1);
      byNumber.add(Integer.parseInt(m.group(2)));
    }
    if (!byNumber.isEmpty()) return byNumber;

    if (group.size() < SHORTEST_LETTER_SEQUENCE) return null;

    final var byLetter = lettersInOrder(group, TRAILING_LETTERS, 1, 2);
    return byLetter != null ? byLetter : lettersInOrder(group, LETTER_THEN_NUMBER, 2, 1);
  }

  /** The letter half of every name in the group, as numbers, if they can be compared at all. */
  private static List<Integer> lettersInOrder(
      List<String> group, Pattern pattern, int sharedGroup, int letterGroup) {
    final var values = new ArrayList<Integer>();
    String shared = null;
    var length = -1;
    for (final var name : group) {
      final var m = pattern.matcher(name);
      if (!m.matches()) return null;
      final var letters = m.group(letterGroup).toUpperCase();
      if (shared != null && (!shared.equals(m.group(sharedGroup)) || length != letters.length())) {
        return null;
      }
      shared = m.group(sharedGroup);
      length = letters.length();
      var value = 0;
      for (final var c : letters.toCharArray()) value = value * 26 + (c - 'A');
      values.add(value);
    }
    return values;
  }

  /**
   * Every port sits on the edge of the component's bounds, whichever way the symbol is turned.
   *
   * <p>The bounds are turned by {@code Bounds.rotate} and the ports by a hand-written rotation next
   * to it; a sign wrong in one of the four cases leaves ports floating off the box, and only in
   * that one orientation.
   */
  @ParameterizedTest
  @MethodSource("specs")
  public void portsStayOnTheBoxWhenTurned(TtlSymbolSpec spec) {
    final var factory = new TtlSymbolGate(spec);
    for (final var dir : List.of(Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH)) {
      final var attrs = factory.createAttributeSet();
      attrs.setValue(StdAttr.FACING, dir);
      final var component = factory.createComponent(Location.create(300, 300, true), attrs);
      final var bounds = component.getBounds();
      for (final var end : component.getEnds()) {
        final var x = end.getLocation().getX();
        final var y = end.getLocation().getY();
        final var onVerticalEdge =
            (x == bounds.getX() || x == bounds.getX() + bounds.getWidth())
                && y >= bounds.getY()
                && y <= bounds.getY() + bounds.getHeight();
        final var onHorizontalEdge =
            (y == bounds.getY() || y == bounds.getY() + bounds.getHeight())
                && x >= bounds.getX()
                && x <= bounds.getX() + bounds.getWidth();
        assertTrue(
            onVerticalEdge || onHorizontalEdge,
            id(spec) + " facing " + dir + ": port at " + end.getLocation() + " is off " + bounds);
      }
    }
  }

  /**
   * Ports land on the grid, so a wire can actually reach them.
   *
   * <p>Logisim snaps a wire to a ten-pixel grid. A port half a step off it can be drawn and can be
   * wired to look connected, and simply is not.
   */
  @ParameterizedTest
  @MethodSource("specs")
  public void portsLandOnTheGrid(TtlSymbolSpec spec) {
    final var factory = new TtlSymbolGate(spec);
    final var component =
        factory.createComponent(Location.create(300, 300, true), factory.createAttributeSet());
    for (final var end : component.getEnds()) {
      assertEquals(
          0,
          end.getLocation().getX() % 10,
          id(spec) + ": port at " + end.getLocation() + " is off the grid horizontally");
      assertEquals(
          0,
          end.getLocation().getY() % 10,
          id(spec) + ": port at " + end.getLocation() + " is off the grid vertically");
    }
  }

  /**
   * Two ports never land on the same spot.
   *
   * <p>Two ports at one location are one node in Logisim, which is how the equivalence test's
   * fixture connects a pin to a chip without a wire. On a chip it would silently short two signals
   * together, and the simulation would go on working -- wrongly.
   */
  @ParameterizedTest
  @MethodSource("specs")
  public void noTwoPortsShareALocation(TtlSymbolSpec spec) {
    final var factory = new TtlSymbolGate(spec);
    final var component =
        factory.createComponent(Location.create(300, 300, true), factory.createAttributeSet());
    final var seen = new HashSet<Location>();
    for (final var end : component.getEnds()) {
      assertTrue(
          seen.add(end.getLocation()),
          id(spec) + " puts two ports at " + end.getLocation());
    }
  }
}
