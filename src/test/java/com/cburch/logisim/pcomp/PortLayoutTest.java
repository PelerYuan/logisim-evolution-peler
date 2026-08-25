/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.data.Location;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Peler Edition. The geometry of a custom component's box.
 *
 * <p>Everything here guards a property that only shows up much later if it breaks. A port half a
 * grid square off its edge draws fine and refuses to take a wire; a box measured from the local
 * font draws fine here and puts every wire in the wrong place on somebody else's machine; a caption
 * that overlaps a port name looks like a drawing bug rather than a sizing one. All three are cheap
 * to state as arithmetic and expensive to find by eye.
 */
public class PortLayoutTest {

  private static PortLayout layoutOf(String caption, int left, int right, int top, int bottom) {
    final var ports = new ArrayList<PortPlacement>();
    for (var i = 0; i < left; i++) ports.add(new PortPlacement("L" + i, PortSide.LEFT, i));
    for (var i = 0; i < right; i++) ports.add(new PortPlacement("R" + i, PortSide.RIGHT, i));
    for (var i = 0; i < top; i++) ports.add(new PortPlacement("T" + i, PortSide.TOP, i));
    for (var i = 0; i < bottom; i++) ports.add(new PortPlacement("B" + i, PortSide.BOTTOM, i));
    return new PortLayout(caption, ports);
  }

  static Stream<Arguments> portCounts() {
    final var counts = new ArrayList<Arguments>();
    for (final var left : List.of(0, 1, 4, 9)) {
      for (final var right : List.of(0, 1, 4)) {
        for (final var top : List.of(0, 1, 3)) {
          for (final var bottom : List.of(0, 2)) {
            if (left + right + top + bottom == 0) continue;
            counts.add(Arguments.of(left, right, top, bottom));
          }
        }
      }
    }
    return counts.stream();
  }

  /**
   * The shape the layout window opens with, worked out by hand rather than recorded from a run: two
   * one-character inputs down the left, one output on the right, a five-character caption. Bands
   * on those two sides come to 3 + 8 = 11, rounded to 20, and being horizontal they set the width
   * and nothing else; the top and bottom carry no ports so their bands stay at the 10 margin. The
   * caption asks for 5 * 9 + 6 = 51, so the box is 51 + 2 * 20 = 91 wide, rounded to 100. Two rows
   * at a 10 pitch between two 10 margins is only 40 tall, so the 60 minimum wins.
   */
  @Test
  public void theDefaultShapeIsTheOneTheArithmeticSaysItIs() {
    final var layout =
        new PortLayout(
            "Adder",
            List.of(
                new PortPlacement("A", PortSide.LEFT, 0),
                new PortPlacement("B", PortSide.LEFT, 1),
                new PortPlacement("S", PortSide.RIGHT, 0)));

    assertEquals(100, layout.width());
    assertEquals(60, layout.height());
    assertEquals(Location.create(0, 10, false), layout.offsetOf("A"));
    assertEquals(Location.create(0, 20, false), layout.offsetOf("B"));
    assertEquals(Location.create(100, 10, false), layout.offsetOf("S"));
  }

  /** Every port coordinate, and the box itself, is a whole number of grid squares. */
  @ParameterizedTest
  @MethodSource("portCounts")
  public void everythingLandsOnTheDrawingGrid(int left, int right, int top, int bottom) {
    final var layout = layoutOf("Box", left, right, top, bottom);

    assertEquals(0, layout.width() % PortLayout.GRID, "box width is off the grid");
    assertEquals(0, layout.height() % PortLayout.GRID, "box height is off the grid");
    for (final var port : layout.placements()) {
      final var at = layout.offsetOf(port.name());
      assertNotNull(at, port.name() + " was not placed");
      assertEquals(0, at.getX() % PortLayout.GRID, port.name() + " is off the grid in x");
      assertEquals(0, at.getY() % PortLayout.GRID, port.name() + " is off the grid in y");
    }
  }

  /**
   * A port sits on exactly one edge. One on a corner belongs to two sides at once, and a wire
   * reaching it from either direction would be ambiguous.
   */
  @ParameterizedTest
  @MethodSource("portCounts")
  public void noPortSitsOnACorner(int left, int right, int top, int bottom) {
    final var layout = layoutOf("Box", left, right, top, bottom);

    for (final var port : layout.placements()) {
      final var at = layout.offsetOf(port.name());
      final var onVerticalEdge = at.getX() == 0 || at.getX() == layout.width();
      final var onHorizontalEdge = at.getY() == 0 || at.getY() == layout.height();
      assertTrue(onVerticalEdge || onHorizontalEdge, port.name() + " is not on an edge at all");
      assertFalse(onVerticalEdge && onHorizontalEdge, port.name() + " sits on a corner");
    }
  }

  /**
   * The caption is drawn centred, so the box has to be wide enough that it starts after the wider
   * of the two side bands ends -- otherwise a long component name is written across its own port
   * names. Same argument vertically.
   */
  @ParameterizedTest
  @MethodSource("portCounts")
  public void theCaptionCannotRunIntoAPortName(int left, int right, int top, int bottom) {
    final var caption = "A rather long component name";
    final var layout = layoutOf(caption, left, right, top, bottom);

    final var captionWidth =
        caption.length() * PortLayout.CAPTION_CHAR_WIDTH + 2 * PortLayout.LABEL_INSET;
    final var widestSideBand =
        Math.max(layout.band(PortSide.LEFT), layout.band(PortSide.RIGHT));
    final var deepestEndBand = Math.max(layout.band(PortSide.TOP), layout.band(PortSide.BOTTOM));

    assertTrue(
        (layout.width() - captionWidth) / 2 >= widestSideBand,
        "the caption starts inside the left or right band");
    assertTrue(
        (layout.height() - PortLayout.CAPTION_HEIGHT) / 2 >= deepestEndBand,
        "the caption starts inside the top or bottom band");
  }

  /** A long name on one side deepens that side's band and nothing else's. */
  @Test
  public void longNamesWidenOnlyTheSideTheyAreOn() {
    final var plain = layoutOf("Box", 1, 1, 0, 0);
    final var wide =
        new PortLayout(
            "Box",
            List.of(
                new PortPlacement("CARRY_IN", PortSide.LEFT, 0),
                new PortPlacement("R0", PortSide.RIGHT, 0)));

    assertTrue(
        wide.band(PortSide.LEFT) > plain.band(PortSide.LEFT), "the long name did not widen its band");
    assertEquals(
        plain.band(PortSide.RIGHT), wide.band(PortSide.RIGHT), "it widened the other side too");
    assertTrue(wide.width() > plain.width(), "the box did not grow to hold it");
  }

  /**
   * A name on a laid-out side is written the same way up as every other -- the appearance model's
   * text carries no angle -- so two of them sit side by side and the room they need comes out of
   * the pitch, not out of the band. The band there is one line deep whatever the names say.
   */
  @Test
  public void namesOnALaidOutSideWidenThePitchRatherThanTheBand() {
    final var shortNames = layoutOf("Box", 0, 0, 2, 0);
    final var longNames =
        new PortLayout(
            "Box",
            List.of(
                new PortPlacement("CARRY_IN", PortSide.TOP, 0),
                new PortPlacement("CARRY_OUT", PortSide.TOP, 1)));

    assertEquals(
        shortNames.band(PortSide.TOP),
        longNames.band(PortSide.TOP),
        "a laid-out side's band is one line deep whatever the names are");
    assertTrue(
        longNames.pitch(PortSide.TOP) > shortNames.pitch(PortSide.TOP),
        "the long names did not widen the pitch");
    assertTrue(longNames.width() > shortNames.width(), "the box did not grow wider for them");
  }

  /**
   * Names on a laid-out side never run into each other, and the first and last stay inside the box.
   * A port there sits in the middle of its own cell, so both follow from the pitch being at least
   * as wide as the longest name.
   */
  @ParameterizedTest
  @MethodSource("portCounts")
  public void namesOnALaidOutSideNeitherCollideNorOverhang(int left, int right, int top, int bottom) {
    final var layout = layoutOf("Box", left, right, top, bottom);

    for (final var side : List.of(PortSide.TOP, PortSide.BOTTOM)) {
      final var onSide = layout.side(side);
      final var pitch = layout.pitch(side);
      for (var slot = 0; slot < onSide.size(); slot++) {
        final var name = onSide.get(slot).name();
        final var half = name.length() * PortLayout.LABEL_CHAR_WIDTH / 2;
        final var centre = layout.offsetOf(name).getX();
        assertTrue(centre - half >= 0, name + " hangs off the left edge");
        assertTrue(centre + half <= layout.width(), name + " hangs off the right edge");
        if (slot > 0) {
          final var previous = onSide.get(slot - 1).name();
          final var gap = centre - layout.offsetOf(previous).getX();
          assertEquals(pitch, gap, name + " is not one pitch from " + previous);
          assertTrue(
              gap >= half + previous.length() * PortLayout.LABEL_CHAR_WIDTH / 2, name + " overlaps " + previous);
        }
      }
    }
  }

  /** Ports keep the slot order they were given, whatever order they arrive in. */
  @Test
  public void slotOrderSurvivesTheOrderThePortsArriveIn() {
    final var layout =
        new PortLayout(
            "Box",
            List.of(
                new PortPlacement("third", PortSide.LEFT, 2),
                new PortPlacement("first", PortSide.LEFT, 0),
                new PortPlacement("second", PortSide.LEFT, 1)));

    assertEquals(
        List.of("first", "second", "third"),
        layout.side(PortSide.LEFT).stream().map(PortPlacement::name).toList());
    assertTrue(
        layout.offsetOf("first").getY() < layout.offsetOf("second").getY(),
        "slot 0 should sit above slot 1");
  }

  @Test
  public void blankOrMissingPortNamesAreRefused() {
    assertThrows(
        IllegalArgumentException.class, () -> new PortPlacement("   ", PortSide.LEFT, 0));
    assertThrows(IllegalArgumentException.class, () -> new PortPlacement(null, PortSide.LEFT, 0));
  }

  @Test
  public void componentsWithNoNameOrNoPortsAreRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new PortLayout("  ", List.of(new PortPlacement("A", PortSide.LEFT, 0))));
    assertThrows(IllegalArgumentException.class, () -> new PortLayout("Box", List.of()));
  }

  @Test
  public void twoPortsCannotShareAName() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PortLayout(
                "Box",
                List.of(
                    new PortPlacement("A", PortSide.LEFT, 0),
                    new PortPlacement("A", PortSide.RIGHT, 0))));
  }

  /**
   * A hole or a repeat in one side's slots is refused rather than renumbered. Either means the
   * caller's picture of the box and this one have come apart, and tidying it up here would move
   * ports out from under wires that were already drawn to them.
   */
  @Test
  public void slotsOnOneSideMustRunWithoutHolesOrRepeats() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PortLayout(
                "Box",
                List.of(
                    new PortPlacement("A", PortSide.LEFT, 0),
                    new PortPlacement("B", PortSide.LEFT, 2))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PortLayout(
                "Box",
                List.of(
                    new PortPlacement("A", PortSide.LEFT, 1),
                    new PortPlacement("B", PortSide.LEFT, 1))));
  }

  /**
   * The rule this whole class is built around, checked where it cannot be argued with: the compiled
   * class must not reference anything that measures text. A port's position is written into every
   * project file that uses the component as an absolute coordinate, so a box sized from the local
   * font would move every wire on a machine whose fonts differ.
   */
  @Test
  public void theLayoutNeverMeasuresText() throws IOException {
    try (final var bytecode = PortLayout.class.getResourceAsStream("PortLayout.class")) {
      assertNotNull(bytecode, "could not read PortLayout's own bytecode");
      final var constants = new String(bytecode.readAllBytes(), StandardCharsets.ISO_8859_1);
      for (final var forbidden :
          List.of("FontMetrics", "java/awt/Font", "getFontMetrics", "stringWidth", "getStringBounds")) {
        assertFalse(
            constants.contains(forbidden),
            "PortLayout references " + forbidden + "; box sizes must not depend on the local fonts");
      }
    }
  }
}
