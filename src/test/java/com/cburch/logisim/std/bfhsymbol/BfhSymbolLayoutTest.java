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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.std.bfh.BfhLibrary;
import com.cburch.logisim.std.symbol.SymbolLayout;
import com.cburch.logisim.tools.AddTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Peler Edition. Structural checks on the BFH symbols.
 *
 * <p>These sit where the equivalence test cannot see. That one compares port index with port index,
 * so a layout that placed two rows on one index, left a port off the box, or drew a four-bit bus as
 * a single wire would either agree anyway or fail with nothing to point at. Everything here is a
 * property the picture must have before behaviour is worth measuring.
 */
public class BfhSymbolLayoutTest {

  static List<BfhSymbolCases> cases() {
    return BfhSymbolCases.all();
  }

  /** Both BFH components have a symbol, and each symbol says which one it stands for. */
  @Test
  public void theLibraryCoversEveryBfhComponent() {
    final var originals = new HashSet<String>();
    for (final var tool : new BfhLibrary().getTools()) {
      originals.add(((AddTool) tool).getFactory().getName());
    }
    final var covered = new HashSet<String>();
    for (final var tool : new BfhSymbolLibrary().getTools()) {
      final var name = ((AddTool) tool).getFactory().getName();
      assertTrue(name.startsWith("Sym"), name + " should be named after the component it redraws");
      covered.add(name.substring(3));
    }
    assertEquals(originals, covered, "the symbol library and the BFH library do not line up");
  }

  /**
   * Every port of the component appears on the symbol exactly once.
   *
   * <p>A missing port is a signal with nowhere to connect; a duplicated one puts two ports on the
   * same index, which {@code Instance.setPorts} takes as the later one silently winning. The base
   * class throws on the first case, so this is really a check that the layout reaches it.
   */
  @ParameterizedTest
  @MethodSource("cases")
  public void everyPortIsPlacedExactlyOnce(BfhSymbolCases testCase) {
    final var layout = testCase.symbol().layoutFor(testCase.symbolAttrs());
    final var seen = new HashSet<Integer>();
    for (final var leftSide : List.of(true, false)) {
      for (final var row : layout.side(leftSide)) {
        if (row.isGap()) continue;
        assertTrue(seen.add(row.index()), testCase + " places port " + row.index() + " twice");
      }
    }

    final var original =
        testCase
            .delegate()
            .createComponent(Location.create(0, 0, true), testCase.delegateAttrs());
    assertEquals(
        original.getEnds().size(),
        seen.size(),
        testCase + " does not place every port of the component it redraws");
    for (var i = 0; i < seen.size(); i++) {
      assertTrue(seen.contains(i), testCase + " never places port " + i);
    }
  }

  /** Inputs down the left, outputs down the right. That is what makes it a logic symbol. */
  @ParameterizedTest
  @MethodSource("cases")
  public void inputsAreOnTheLeftAndOutputsOnTheRight(BfhSymbolCases testCase) {
    final var layout = testCase.symbol().layoutFor(testCase.symbolAttrs());
    for (final var row : layout.side(true)) {
      if (row.isGap()) continue;
      assertEquals(
          SymbolLayout.INPUT,
          layout.kind(row.index()),
          testCase + ": port " + row.index() + " is not an input but sits on the left");
    }
    for (final var row : layout.side(false)) {
      if (row.isGap()) continue;
      assertEquals(
          SymbolLayout.OUTPUT,
          layout.kind(row.index()),
          testCase + ": port " + row.index() + " is not an output but sits on the right");
    }
  }

  /** Every port carries a short, distinct name, since the vectors elsewhere address them by it. */
  @ParameterizedTest
  @MethodSource("cases")
  public void labelsAreUniqueAndShortEnoughToRead(BfhSymbolCases testCase) {
    final var layout = testCase.symbol().layoutFor(testCase.symbolAttrs());
    final var seen = new HashSet<String>();
    for (var i = 0; i < layout.portCount(); i++) {
      final var label = layout.label(i);
      assertNotNull(label, testCase + ": port " + i + " has no label");
      assertFalse(label.isBlank(), testCase + ": port " + i + " has a blank label");
      assertTrue(label.length() <= 6, testCase + ": the label " + label + " is too long to fit");
      assertTrue(seen.add(label), testCase + " uses the label " + label + " twice");
    }
  }

  /**
   * A port on the symbol is as wide as the same port on the component.
   *
   * <p>The base class defaults every port to one bit, which is right for a 74xx pin and wrong for
   * everything here. A four-bit output drawn as a single wire simulates without complaint and
   * carries the bottom bit of the digit.
   */
  @ParameterizedTest
  @MethodSource("cases")
  public void portsAreAsWideAsTheComponents(BfhSymbolCases testCase) {
    final var at = Location.create(300, 300, true);
    final var symbol = testCase.symbol().createComponent(at, testCase.symbolAttrs());
    final var original = testCase.delegate().createComponent(at, testCase.delegateAttrs());
    for (var i = 0; i < original.getEnds().size(); i++) {
      assertEquals(
          original.getEnds().get(i).getWidth(),
          symbol.getEnds().get(i).getWidth(),
          testCase + ": port " + i + " is not the width the component gives it");
    }
  }

  /**
   * The converter has one output per power of ten the input can reach, most significant first.
   *
   * <p>Reading order, and the same order the original box uses along its top edge. It is also the
   * check on the digit count: the widest value the input holds must have exactly as many digits as
   * there are output ports, so a symbol one row short would drop the leading digit and one row long
   * would carry a port that is always zero.
   */
  @ParameterizedTest
  @MethodSource("widths")
  public void theConverterHasOneOutputPerDecimalDigit(int bits) {
    final var testCase = BfhSymbolCases.binToBcd(bits);
    final var layout = testCase.symbol().layoutFor(testCase.symbolAttrs());
    final var largest = (1L << bits) - 1;
    final var expected = Long.toString(largest).length();

    final var weights = new ArrayList<String>();
    for (final var row : layout.side(false)) {
      if (!row.isGap()) weights.add(layout.label(row.index()));
    }
    assertEquals(
        expected,
        weights.size(),
        bits + " bits reaches " + largest + ", which has " + expected + " digits");

    for (var i = 0; i < weights.size(); i++) {
      final var weight = Long.toString((long) Math.pow(10.0, weights.size() - 1 - i));
      assertEquals(
          weight, weights.get(i), bits + " bits: row " + i + " on the right is the wrong weight");
    }
    assertEquals(1, layout.side(true).size(), bits + " bits: the input side should be one row");
  }

  /**
   * A wider input really does give a different symbol.
   *
   * <p>The base class caches a layout per {@link com.cburch.logisim.std.symbol.SymbolGate#layoutKey
   * layoutKey}, and a key that ignored the width would hand every instance whichever symbol was
   * built first -- an eight-bit converter drawn, and wired, with four digit ports.
   */
  @Test
  public void eachInputWidthGetsItsOwnSymbol() {
    final var symbol = new BinToBcdSymbol();
    final var seen = new HashSet<Integer>();
    for (var bits = BfhSymbolCases.MIN_BITS; bits <= BfhSymbolCases.MAX_BITS; bits++) {
      final var attrs = BfhSymbolCases.binToBcd(bits).symbolAttrs();
      seen.add(symbol.layoutFor(attrs).portCount());
    }
    assertEquals(
        3,
        seen.size(),
        "four to thirteen bits spans two, three and four digits, so the cache is keyed wrong");
  }

  /** Every port sits on the edge of the bounds, whichever way the symbol is turned. */
  @ParameterizedTest
  @MethodSource("cases")
  public void portsStayOnTheBoxWhenTurned(BfhSymbolCases testCase) {
    for (final var dir :
        List.of(Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH)) {
      final var attrs = (AttributeSet) testCase.symbolAttrs().clone();
      attrs.setValue(StdAttr.FACING, dir);
      final var component =
          testCase.symbol().createComponent(Location.create(300, 300, true), attrs);
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
            testCase + " facing " + dir + ": port at " + end.getLocation() + " is off " + bounds);
      }
    }
  }

  /** Ports land on the grid, and no two land on the same spot. */
  @ParameterizedTest
  @MethodSource("cases")
  public void portsLandOnTheGridAndNeverOnEachOther(BfhSymbolCases testCase) {
    final var component =
        testCase.symbol().createComponent(Location.create(300, 300, true), testCase.symbolAttrs());
    final var seen = new HashSet<Location>();
    for (final var end : component.getEnds()) {
      final var at = end.getLocation();
      assertEquals(0, at.getX() % 10, testCase + ": port at " + at + " is off the grid");
      assertEquals(0, at.getY() % 10, testCase + ": port at " + at + " is off the grid");
      assertTrue(seen.add(at), testCase + " puts two ports at " + at);
    }
  }

  /**
   * The library's name is translated everywhere, not just in English.
   *
   * <p>Read from the files rather than through {@code ResourceBundle}, which falls back to the base
   * bundle for a missing key and would pass this for a language with no translation at all.
   */
  @Test
  public void everyLocaleNamesTheLibrary() throws Exception {
    final var bundles = Path.of("src/main/resources/resources/logisim/strings/std");
    try (final var files = Files.list(bundles)) {
      final var checked = new ArrayList<String>();
      for (final var file : files.sorted().toList()) {
        if (!file.getFileName().toString().endsWith(".properties")) continue;
        final var properties = new Properties();
        try (final var in = Files.newBufferedReader(file)) {
          properties.load(in);
        }
        final var name = properties.getProperty("bfhSymbolLibrary");
        assertNotNull(name, file.getFileName() + " has no name for the BFH symbol library");
        assertFalse(name.isBlank(), file.getFileName() + " leaves the library name blank");
        checked.add(file.getFileName().toString());
      }
      assertEquals(12, checked.size(), "expected twelve locales, found " + checked);
    }
  }

  static List<Integer> widths() {
    final var widths = new ArrayList<Integer>();
    for (var bits = BfhSymbolCases.MIN_BITS; bits <= BfhSymbolCases.MAX_BITS; bits++) {
      widths.add(bits);
    }
    return widths;
  }
}
