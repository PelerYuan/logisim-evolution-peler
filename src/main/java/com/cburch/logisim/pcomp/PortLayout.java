/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import com.cburch.logisim.data.Location;
import com.cburch.draw.shapes.DrawAttr;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Peler Edition. A custom component's box, resolved: how big it is and where each port sits on it.
 *
 * <p>The input is what the layout window lets a user decide -- a name and a slot on one of four
 * edges per port, plus the component's own name. Everything geometric is computed from that here,
 * so the same component is the same size and has its ports in the same places on every machine that
 * opens it.
 *
 * <p><b>Nothing in this class may measure text.</b> A port's position ends up in every project file
 * that uses the component, as an absolute coordinate; a width that came out of the local
 * {@code FontMetrics} would put a circuit's wires somewhere else on a machine whose fonts differ.
 * {@code SymbolLayout} carries fixed character widths for the same reason. {@code PortLayoutTest}
 * reads this class's bytecode and fails if {@code java.awt.Font} ever appears in it.
 *
 * <p>Two properties the sizing rules below are built to guarantee, both checked by the tests:
 *
 * <ul>
 *   <li><b>Every port lands on the drawing grid, and never on a corner.</b> Each band is rounded up
 *       to the grid before anything is placed against it, the pitch is a whole number of grid
 *       squares, and the box is at least one pitch plus one band larger than the last port on each
 *       axis.
 *   <li><b>The caption never collides with a port's name.</b> The box is wide enough for the
 *       caption plus twice the wider of the two side bands, so a centred caption starts no earlier
 *       than the wider band ends; the height works the same way.
 * </ul>
 */
public final class PortLayout {

  /** The editor's drawing grid. Port coordinates are all multiples of this. */
  public static final int GRID = 10;

  /**
   * Distance between neighbouring ports on one side. A whole number of grid squares, necessarily --
   * ports are placed at multiples of it from a band that is itself grid-aligned, so a pitch off the
   * grid would take every port after the first off it too.
   */
  public static final int PITCH = GRID;

  /**
   * Width allowed per character, and the clear space between a name and the edge it is written
   * against.
   *
   * <p>Fixed numbers rather than measurements, for the reason given above, and this edition's own
   * rather than borrowed from {@code SymbolLayout}: a custom component is drawn by the appearance
   * model, not by {@code SymbolGate}, and the two use different fonts. {@link PcompAppearance}
   * writes port names in {@link DrawAttr#DEFAULT_FIXED_PICH_FONT}, whose advance is
   * {@link DrawAttr#FIXED_FONT_CHAR_WIDTH}, and the caption in the bold
   * {@link DrawAttr#DEFAULT_NAME_FONT}, two points larger. Measuring the box against the other
   * font's advance would leave it too narrow for its own labels, so these move if either font does.
   */
  public static final int LABEL_CHAR_WIDTH = DrawAttr.FIXED_FONT_CHAR_WIDTH;

  public static final int CAPTION_CHAR_WIDTH = 9;

  public static final int LABEL_INSET = 3;

  /**
   * Pitch on a laid-out side is a multiple of this, so half a pitch is still a whole number of grid
   * squares and a port can sit in the middle of its own cell.
   */
  public static final int ACROSS_STEP = 2 * GRID;

  /** Clear space kept between two neighbouring names on a laid-out side. */
  public static final int ACROSS_GUTTER = 6;

  /** Clear space on a side that carries no ports, so the box never ends flush against nothing. */
  public static final int MARGIN = 10;

  /** Height of one line of a port's name. */
  public static final int LINE_HEIGHT = 10;

  /** Smallest box drawn, on either axis, whatever the ports and the caption ask for. */
  public static final int MIN_SIZE = 60;

  /** Vertical room the caption needs, it being a single line. */
  public static final int CAPTION_HEIGHT = 10;

  private final String caption;
  private final List<PortPlacement> placements;
  private final Map<PortSide, List<PortPlacement>> bySide;
  private final Map<PortSide, Integer> bands;
  private final Map<PortSide, Integer> pitches;
  private final Map<String, Location> offsets;
  private final int width;
  private final int height;

  /**
   * @param caption the component's name, written across the middle of the box
   * @param ports every port of the component, in any order
   * @throws IllegalArgumentException if the caption is blank, there are no ports, two ports share a
   *     name, or the slots on some side do not run 0, 1, 2 ... with no holes
   */
  public PortLayout(String caption, Collection<PortPlacement> ports) {
    if (caption == null || caption.isBlank()) {
      throw new IllegalArgumentException("a custom component needs a name");
    }
    if (ports == null || ports.isEmpty()) {
      throw new IllegalArgumentException(caption + " has no ports");
    }
    this.caption = caption.trim();
    this.placements = List.copyOf(ports);
    this.bySide = groupBySide(this.placements);
    this.bands = measureBands(this.bySide);
    this.pitches = measurePitches(this.bySide);

    final var rows = Math.max(bySide.get(PortSide.LEFT).size(), bySide.get(PortSide.RIGHT).size());
    final var across =
        Math.max(
            bySide.get(PortSide.TOP).size() * pitches.get(PortSide.TOP),
            bySide.get(PortSide.BOTTOM).size() * pitches.get(PortSide.BOTTOM));
    final var left = bands.get(PortSide.LEFT);
    final var right = bands.get(PortSide.RIGHT);
    final var top = bands.get(PortSide.TOP);
    final var bottom = bands.get(PortSide.BOTTOM);
    final var captionWidth =
        this.caption.length() * CAPTION_CHAR_WIDTH + 2 * LABEL_INSET;

    this.width =
        toGrid(
            Math.max(
                Math.max(left + across + right, captionWidth + 2 * Math.max(left, right)),
                MIN_SIZE));
    this.height =
        toGrid(
            Math.max(
                Math.max(top + rows * PITCH + bottom, CAPTION_HEIGHT + 2 * Math.max(top, bottom)),
                MIN_SIZE));
    this.offsets = placePorts();
  }

  /**
   * Sorts the ports onto their sides and checks that each side's slots run 0, 1, 2 ... A hole or a
   * repeat is refused rather than tidied up: both mean the caller's model of the box and this one
   * have diverged, and quietly renumbering would hide that until the ports moved under a wire.
   */
  private static Map<PortSide, List<PortPlacement>> groupBySide(List<PortPlacement> ports) {
    final var seen = new HashMap<String, PortPlacement>();
    for (final var port : ports) {
      final var clash = seen.put(port.name(), port);
      if (clash != null) {
        throw new IllegalArgumentException("two ports are both named " + port.name());
      }
    }
    final var grouped = new EnumMap<PortSide, List<PortPlacement>>(PortSide.class);
    for (final var side : PortSide.values()) grouped.put(side, new ArrayList<>());
    for (final var port : ports) grouped.get(port.side()).add(port);

    final var result = new EnumMap<PortSide, List<PortPlacement>>(PortSide.class);
    for (final var side : PortSide.values()) {
      final var onSide = grouped.get(side);
      final var ordered = new PortPlacement[onSide.size()];
      for (final var port : onSide) {
        if (port.slot() >= ordered.length) {
          throw new IllegalArgumentException(
              "port "
                  + port.name()
                  + " asks for slot "
                  + port.slot()
                  + " on the "
                  + side.toXmlValue()
                  + " side, which holds "
                  + ordered.length);
        }
        if (ordered[port.slot()] != null) {
          throw new IllegalArgumentException(
              "ports "
                  + ordered[port.slot()].name()
                  + " and "
                  + port.name()
                  + " both ask for slot "
                  + port.slot()
                  + " on the "
                  + side.toXmlValue()
                  + " side");
        }
        ordered[port.slot()] = port;
      }
      result.put(side, List.of(ordered));
    }
    return result;
  }

  /**
   * How deep each side has to be for its ports' names to fit inside the box.
   *
   * <p>The two kinds of side measure differently, because every name is written the same way up.
   * {@code com.cburch.draw.shapes.Text} carries no angle, so a name on a laid-out side cannot be
   * turned a quarter turn to save width -- it is written along the box like any other, one line
   * deep whatever it says, and the room it needs is taken out of the pitch instead (see {@link
   * #measurePitches}). A name on a stacked side runs into the box, so there the depth is what the
   * name is long.
   */
  private static Map<PortSide, Integer> measureBands(Map<PortSide, List<PortPlacement>> bySide) {
    final var bands = new EnumMap<PortSide, Integer>(PortSide.class);
    for (final var side : PortSide.values()) {
      final var onSide = bySide.get(side);
      if (onSide.isEmpty()) {
        bands.put(side, MARGIN);
      } else if (side.stacked()) {
        bands.put(
            side, toGrid(LABEL_INSET + longestName(onSide) * LABEL_CHAR_WIDTH));
      } else {
        bands.put(side, toGrid(LABEL_INSET + LINE_HEIGHT));
      }
    }
    return bands;
  }

  /**
   * How far apart two neighbouring ports sit on each side.
   *
   * <p>A stacked side keeps the standard pitch: its names run into the box and cannot collide with
   * each other. A laid-out side's names sit side by side, so the pitch has to be at least as wide
   * as the longest of them. Rounded to {@link #ACROSS_STEP} rather than to the grid so that half a
   * pitch is still grid-aligned -- a port on a laid-out side sits in the middle of its own cell,
   * which is what keeps the first and last names inside the box without a special case.
   */
  private static Map<PortSide, Integer> measurePitches(Map<PortSide, List<PortPlacement>> bySide) {
    final var pitches = new EnumMap<PortSide, Integer>(PortSide.class);
    for (final var side : PortSide.values()) {
      final var onSide = bySide.get(side);
      if (side.stacked() || onSide.isEmpty()) {
        pitches.put(side, PITCH);
        continue;
      }
      final var needed = longestName(onSide) * LABEL_CHAR_WIDTH + ACROSS_GUTTER;
      pitches.put(side, Math.max(ACROSS_STEP, roundUpTo(ACROSS_STEP, needed)));
    }
    return pitches;
  }

  private static int longestName(List<PortPlacement> ports) {
    var longest = 0;
    for (final var port : ports) longest = Math.max(longest, port.name().length());
    return longest;
  }

  /**
   * Where each port sits, as an offset from the box's top-left corner facing east -- the same
   * anchor convention {@code SymbolGate} uses, so rotation can be left to the caller.
   */
  private Map<String, Location> placePorts() {
    final var result = new LinkedHashMap<String, Location>();
    for (final var side : PortSide.values()) {
      final var onSide = bySide.get(side);
      final var pitch = pitches.get(side);
      // A stacked side counts down from the top band, one port per grid row. A laid-out one counts
      // across from the left band, each port in the middle of its own cell so its name, written the
      // same way up as every other, stays inside the box at both ends.
      final var start =
          side.stacked() ? bands.get(PortSide.TOP) : bands.get(PortSide.LEFT) + pitch / 2;
      for (var slot = 0; slot < onSide.size(); slot++) {
        final var along = start + slot * pitch;
        final var x = side.stacked() ? (side == PortSide.LEFT ? 0 : width) : along;
        final var y = side.stacked() ? along : (side == PortSide.TOP ? 0 : height);
        // Not asking Location to snap: everything above is already a multiple of GRID, and letting
        // it round here would turn a sizing bug into a port silently sharing a coordinate.
        result.put(onSide.get(slot).name(), Location.create(x, y, false));
      }
    }
    return Map.copyOf(result);
  }

  private static int toGrid(int value) {
    return roundUpTo(GRID, value);
  }

  private static int roundUpTo(int step, int value) {
    return (value + step - 1) / step * step;
  }

  public String caption() {
    return caption;
  }

  public int width() {
    return width;
  }

  public int height() {
    return height;
  }

  /** Every port, in the order the caller gave them. */
  public List<PortPlacement> placements() {
    return placements;
  }

  /** The ports on one side, slot order, top first or left first. */
  public List<PortPlacement> side(PortSide side) {
    return bySide.get(side);
  }

  /** How deep one side's band of names is. */
  public int band(PortSide side) {
    return bands.get(side);
  }

  /** How far apart two neighbouring ports sit on one side. */
  public int pitch(PortSide side) {
    return pitches.get(side);
  }

  /** Where a port sits, or null if this layout has no port by that name. */
  public Location offsetOf(String portName) {
    return portName == null ? null : offsets.get(portName.trim());
  }

  /** Every port's offset, keyed by name. */
  public Map<String, Location> offsets() {
    return offsets;
  }

  public int portCount() {
    return placements.size();
  }
}
