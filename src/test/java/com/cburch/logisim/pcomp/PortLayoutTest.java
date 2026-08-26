/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import static com.cburch.logisim.pcomp.PcompLayouts.automatic;
import static com.cburch.logisim.pcomp.PcompLayouts.nth;
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
 * <p>Two things are being tested, and they are not the same thing. A layout the user dragged is
 * carried as given, and almost nothing about it is guaranteed -- that is what the layout window is
 * for. {@link PortLayout#automatic} is the tidy default a component starts from, and it does
 * guarantee things: ports on the grid and off the corners, names that clear each other, a caption
 * that clears the names. Everything below guards a property that only shows up much later if it
 * breaks. A port half a grid square off its edge draws fine and refuses to take a wire; a box
 * measured from the local font draws fine here and puts every wire in the wrong place on somebody
 * else's machine; a caption over a port name looks like a drawing bug rather than a sizing one.
 */
public class PortLayoutTest {

  private static PortLayout layoutOf(String caption, int left, int right, int top, int bottom) {
    final var ports = new ArrayList<PortPlacement>();
    for (var i = 0; i < left; i++) ports.add(nth("L" + i, PortSide.LEFT, i));
    for (var i = 0; i < right; i++) ports.add(nth("R" + i, PortSide.RIGHT, i));
    for (var i = 0; i < top; i++) ports.add(nth("T" + i, PortSide.TOP, i));
    for (var i = 0; i < bottom; i++) ports.add(nth("B" + i, PortSide.BOTTOM, i));
    return automatic(caption, ports);
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
   * one-character inputs down the left, one output on the right, a five-character caption. Each
   * side band comes to 5 + 8 = 13, rounded up to 20; the top and bottom carry no ports so theirs
   * stay at the 10 margin. The caption asks for 5 * 9 = 45, which is more than the nothing the
   * empty top and bottom sides ask for, so the box is 20 + 45 + 20 = 85 wide, rounded to 90. Two
   * ports at a pitch of 20 between two 10 margins is 60 tall, which is also the minimum. The two
   * left ports are centred in the 40 between the margins, so they land 20 apart with 20 clear above
   * and below; the single right port ends up in the middle on its own.
   */
  @Test
  public void theDefaultShapeIsTheOneTheArithmeticSaysItIs() {
    final var layout =
        automatic(
            "Adder",
            nth("A", PortSide.LEFT, 0),
            nth("B", PortSide.LEFT, 1),
            nth("S", PortSide.RIGHT, 0));

    assertEquals(90, layout.width());
    assertEquals(60, layout.height());
    assertEquals(Location.create(0, 20, false), layout.offsetOf("A"));
    assertEquals(Location.create(0, 40, false), layout.offsetOf("B"));
    assertEquals(Location.create(90, 30, false), layout.offsetOf("S"));
    assertEquals(45, layout.captionX());
    assertEquals(30, layout.captionY());
  }

  /**
   * A layout that came out of the layout window is carried, not recomputed. This is the whole
   * difference between the two constructors, and the reason the window is worth having.
   */
  @Test
  public void theDraggedLayoutIsKeptExactlyAsItWasGiven() {
    final var layout =
        new PortLayout(
            "Odd",
            200,
            40,
            13,
            7,
            List.of(
                new PortPlacement("A", PortSide.LEFT, 0, 30),
                new PortPlacement("B", PortSide.TOP, 170, 0)));

    assertEquals(200, layout.width());
    assertEquals(40, layout.height());
    assertEquals(13, layout.captionX());
    assertEquals(7, layout.captionY());
    assertEquals(Location.create(0, 30, false), layout.offsetOf("A"));
    assertEquals(Location.create(170, 0, false), layout.offsetOf("B"));
  }

  /**
   * Ports are snapped to the grid on the way in rather than refused. A port off the grid draws
   * perfectly and then silently takes no wire, which is the worst way for this to go wrong; the box
   * is rounded up for the same reason, so its far edge stays somewhere a port can sit.
   */
  @Test
  public void coordinatesOffTheGridAreSnappedOntoIt() {
    final var layout =
        new PortLayout(
            "Odd",
            93,
            57,
            40,
            20,
            List.of(new PortPlacement("A", PortSide.LEFT, 2, 34)));

    assertEquals(100, layout.width());
    assertEquals(60, layout.height());
    assertEquals(Location.create(0, 30, false), layout.offsetOf("A"));
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
   * Each side's run of ports is centred between the bands that bound it, rather than starting hard
   * against one of them. The old arithmetic anchored every run at the top band and let all the
   * slack collect at the bottom, which is what made a component with two ports and a roomy box look
   * like a mistake.
   */
  @ParameterizedTest
  @MethodSource("portCounts")
  public void eachSidesPortsAreCentredOnIt(int left, int right, int top, int bottom) {
    final var layout = layoutOf("Box", left, right, top, bottom);

    for (final var side : PortSide.values()) {
      final var onSide = layout.side(side);
      if (onSide.isEmpty()) continue;
      final var first = layout.offsetOf(onSide.get(0).name());
      final var last = layout.offsetOf(onSide.get(onSide.size() - 1).name());
      // Centred between the bands rather than between the box's own edges: the bands are where the
      // other two sides write their names, and a run centred through them would cross the writing.
      final var startBand = layout.band(side.stacked() ? PortSide.TOP : PortSide.LEFT);
      final var endBand = layout.band(side.stacked() ? PortSide.BOTTOM : PortSide.RIGHT);
      final var extent = side.stacked() ? layout.height() : layout.width();
      final var before = (side.stacked() ? first.getY() : first.getX()) - startBand;
      final var beyond = extent - endBand - (side.stacked() ? last.getY() : last.getX());
      assertTrue(
          Math.abs(before - beyond) <= PortLayout.GRID,
          "the " + side + " ports sit " + before + " from one end and " + beyond + " from the other");
    }
  }

  /**
   * Two names on one side never touch. A name is {@link PortLayout#LINE_HEIGHT} tall and the pitch
   * on a stacked side is a whole {@link PortLayout#PITCH}, which is what the first version of this
   * got wrong -- the two were both ten, so every pair of neighbouring names met.
   */
  @ParameterizedTest
  @MethodSource("portCounts")
  public void namesOnAStackedSideDoNotTouch(int left, int right, int top, int bottom) {
    final var layout = layoutOf("Box", left, right, top, bottom);

    for (final var side : List.of(PortSide.LEFT, PortSide.RIGHT)) {
      final var onSide = layout.side(side);
      for (var index = 1; index < onSide.size(); index++) {
        final var gap =
            layout.offsetOf(onSide.get(index).name()).getY()
                - layout.offsetOf(onSide.get(index - 1).name()).getY();
        assertEquals(PortLayout.PITCH, gap, "the ports on the " + side + " side are not one pitch apart");
        assertTrue(gap > PortLayout.LINE_HEIGHT, "two names on the " + side + " side overlap");
      }
    }
  }

  /**
   * The caption is centred in the space the four bands leave, and the box is made big enough for it
   * to fit there -- otherwise a long component name is written across its own port names.
   *
   * <p>Only as wide as it needs to be, though. The rule this replaced demanded the wider of the two
   * side bands' worth of clearance on <em>both</em> sides of the caption, which on a component with
   * one long output name and one short input name made the box half as wide again as anything in it
   * needed.
   */
  @ParameterizedTest
  @MethodSource("portCounts")
  public void theCaptionCannotRunIntoAPortName(int left, int right, int top, int bottom) {
    final var caption = "A rather long component name";
    final var layout = layoutOf(caption, left, right, top, bottom);
    final var half = caption.length() * PortLayout.CAPTION_CHAR_WIDTH / 2;

    assertTrue(
        layout.captionX() - half >= layout.band(PortSide.LEFT),
        "the caption starts inside the left band");
    assertTrue(
        layout.captionX() + half <= layout.width() - layout.band(PortSide.RIGHT),
        "the caption ends inside the right band");
    assertTrue(
        layout.captionY() - PortLayout.CAPTION_HEIGHT / 2 >= layout.band(PortSide.TOP),
        "the caption starts inside the top band");
    assertTrue(
        layout.captionY() + PortLayout.CAPTION_HEIGHT / 2
            <= layout.height() - layout.band(PortSide.BOTTOM),
        "the caption ends inside the bottom band");
  }

  /** A long name on one side deepens that side's band and nothing else's. */
  @Test
  public void longNamesWidenOnlyTheSideTheyAreOn() {
    final var plain = layoutOf("Box", 1, 1, 0, 0);
    final var wide =
        automatic("Box", nth("CARRY_IN", PortSide.LEFT, 0), nth("R0", PortSide.RIGHT, 0));

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
        automatic("Box", nth("CARRY_IN", PortSide.TOP, 0), nth("CARRY_OUT", PortSide.TOP, 1));

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
   * Both follow from the pitch being at least as wide as the longest name and the run being centred
   * in a box wide enough for one whole pitch per port.
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
              gap >= half + previous.length() * PortLayout.LABEL_CHAR_WIDTH / 2,
              name + " overlaps " + previous);
        }
      }
    }
  }

  /**
   * Ports keep the order they were already in along their side, whatever order the list arrives in.
   * That is what makes the arrange button usable on a layout the user has already thought about: it
   * fixes the spacing without shuffling the ports.
   */
  @Test
  public void theOrderAlongASideSurvivesTheOrderThePortsArriveIn() {
    final var layout =
        automatic(
            "Box",
            nth("third", PortSide.LEFT, 2),
            nth("first", PortSide.LEFT, 0),
            nth("second", PortSide.LEFT, 1));

    assertEquals(
        List.of("first", "second", "third"),
        layout.side(PortSide.LEFT).stream().map(PortPlacement::name).toList());
    assertTrue(
        layout.offsetOf("first").getY() < layout.offsetOf("second").getY(),
        "the first port should sit above the second");
  }

  @Test
  public void blankOrMissingPortNamesAreRefused() {
    assertThrows(
        IllegalArgumentException.class, () -> new PortPlacement("   ", PortSide.LEFT, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new PortPlacement(null, PortSide.LEFT, 0, 0));
  }

  /** A port outside the box to the left or above it has no anchor to be measured from. */
  @Test
  public void portsBeforeTheBoxsOwnCornerAreRefused() {
    assertThrows(
        IllegalArgumentException.class, () -> new PortPlacement("A", PortSide.LEFT, -10, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new PortPlacement("A", PortSide.TOP, 0, -10));
  }

  @Test
  public void componentsWithNoNameOrNoPortsAreRefused() {
    assertThrows(
        IllegalArgumentException.class, () -> automatic("  ", nth("A", PortSide.LEFT, 0)));
    assertThrows(IllegalArgumentException.class, () -> automatic("Box", List.of()));
  }

  @Test
  public void twoPortsCannotShareAName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> automatic("Box", nth("A", PortSide.LEFT, 0), nth("A", PortSide.RIGHT, 0)));
  }

  /**
   * Two ports in one place are refused, which is the one thing a free layout still may not say. A
   * wire drawn there would attach to both, and nothing about the drawing would show why.
   */
  @Test
  public void twoPortsCannotShareOnePlace() {
    final var failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new PortLayout(
                    "Box",
                    100,
                    60,
                    50,
                    30,
                    List.of(
                        new PortPlacement("A", PortSide.LEFT, 0, 20),
                        new PortPlacement("B", PortSide.TOP, 0, 20))));
    assertTrue(failure.getMessage().contains("A"), "the message should name the ports");
    assertTrue(failure.getMessage().contains("B"), "the message should name the ports");
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
