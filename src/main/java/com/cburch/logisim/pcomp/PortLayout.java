/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import com.cburch.draw.shapes.DrawAttr;
import com.cburch.logisim.data.Location;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Peler Edition. A custom component's box: how big it is, where the caption goes, and where each
 * port sits on it.
 *
 * <p><b>A layout is carried, not computed.</b> Every number here comes from the file, which is to
 * say from whatever the user dragged the box and its ports into in the layout window. What the
 * class still does is check the handful of things that would produce a component nobody could wire
 * up -- ports on the grid, no two of them in the same place -- and offer {@link #automatic}, the
 * tidy default a new component starts from and the one a user gets back by asking for it.
 *
 * <p>This is a deliberate reversal. The first version of this feature let the user choose only a
 * side and an order per port and worked the geometry out itself, so that every component came out
 * regular. It did, and they were also all the same shape, too wide, and impossible to adjust; the
 * point of a layout window is to lay things out. The regularity now lives in {@link #automatic},
 * where it is a starting point rather than a cage.
 *
 * <p><b>Nothing in this class may measure text.</b> A port's position ends up in every project file
 * that uses the component, as an absolute coordinate; a width that came out of the local
 * {@code FontMetrics} would put a circuit's wires somewhere else on a machine whose fonts differ.
 * {@code SymbolLayout} carries fixed character widths for the same reason. {@code PortLayoutTest}
 * reads this class's bytecode and fails if {@code java.awt.Font} ever appears in it.
 *
 * <p>What {@link #automatic} guarantees, all checked by the tests:
 *
 * <ul>
 *   <li><b>Every port lands on the drawing grid, and never on a corner.</b> Each band is rounded up
 *       to the grid, the pitch is a whole number of grid squares, and each side's run of ports is
 *       centred between the two bands that bound it, which leaves at least one whole pitch of clear
 *       box beyond the last port on each axis.
 *   <li><b>Neighbouring port names never touch.</b> The pitch on a stacked side is
 *       {@link #PITCH}, taller than a line of text; on a laid-out side it is widened to hold the
 *       longest name on it.
 *   <li><b>The caption never collides with a port name.</b> It is centred in the space the four
 *       bands leave, and the box is made wide and tall enough for it to fit there.
 * </ul>
 *
 * <p>A layout the user has dragged guarantees none of that, on purpose. Ports may be crowded,
 * overlapping the caption, or left outside a box that was shrunk under them. The one thing still
 * refused is two ports in the same place, because a wire drawn there would attach to both.
 */
public final class PortLayout {

  /** The editor's drawing grid. Port coordinates are all multiples of this. */
  public static final int GRID = 10;

  /**
   * Distance between neighbouring ports on a stacked side, in {@link #automatic}.
   *
   * <p>Two grid squares, which is what {@code DefaultEvolutionAppearance} works out for the same
   * job from the same font. One square is not enough: a line of {@link
   * DrawAttr#DEFAULT_FIXED_PICH_FONT} is {@link #LINE_HEIGHT} tall, so ports a single square apart
   * have their names overlapping before the box is even drawn.
   */
  public static final int PITCH = 2 * GRID;

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

  /**
   * Clear space between a name and the edge it is written against. Five, which is where
   * {@code DefaultEvolutionAppearance} starts its own pin labels inside the box.
   */
  public static final int LABEL_INSET = 5;

  /** Pitch on a laid-out side is a multiple of this, so a centred run stays on the grid. */
  public static final int ACROSS_STEP = 2 * GRID;

  /** Clear space kept between two neighbouring names on a laid-out side. */
  public static final int ACROSS_GUTTER = 6;

  /** Clear space on a side that carries no ports, so the box never ends flush against nothing. */
  public static final int MARGIN = 10;

  /** Height of one line of a port's name. */
  public static final int LINE_HEIGHT = DrawAttr.FIXED_FONT_HEIGHT;

  /** Smallest box {@link #automatic} draws, on either axis, whatever the ports ask for. */
  public static final int MIN_SIZE = 60;

  /** Vertical room the caption needs, it being a single line of the larger font. */
  public static final int CAPTION_HEIGHT = 16;

  private final String caption;
  private final List<PortPlacement> placements;
  private final Map<PortSide, List<PortPlacement>> bySide;
  private final Map<PortSide, Integer> bands;
  private final Map<PortSide, Integer> pitches;
  private final Map<String, Location> offsets;
  private final int width;
  private final int height;
  private final int captionX;
  private final int captionY;

  /**
   * @param caption the component's name, written in the box
   * @param width box width, rounded up to the grid
   * @param height box height, rounded up to the grid
   * @param captionX where the middle of the caption sits, measured from the box's left edge
   * @param captionY where the middle of the caption sits, measured from the box's top edge
   * @param ports every port of the component, in any order; coordinates are snapped to the grid
   * @throws IllegalArgumentException if the caption is blank, there are no ports, two ports share a
   *     name, or two ports end up in the same place
   */
  public PortLayout(
      String caption,
      int width,
      int height,
      int captionX,
      int captionY,
      Collection<PortPlacement> ports) {
    if (caption == null || caption.isBlank()) {
      throw new IllegalArgumentException("a custom component needs a name");
    }
    if (ports == null || ports.isEmpty()) {
      throw new IllegalArgumentException(caption + " has no ports");
    }
    this.caption = caption.trim();
    this.width = Math.max(GRID, toGrid(width));
    this.height = Math.max(GRID, toGrid(height));
    this.captionX = captionX;
    this.captionY = captionY;

    // Snapping rather than refusing. A port half a square off its edge draws perfectly and then
    // silently refuses every wire, which is the worst way for this to go wrong; rounding it is the
    // one repair that is always what was meant.
    final var snapped = new ArrayList<PortPlacement>(ports.size());
    for (final var port : ports) {
      snapped.add(port.at(toNearestGrid(port.x()), toNearestGrid(port.y())));
    }
    this.placements = List.copyOf(snapped);
    this.bySide = groupBySide(this.placements);
    this.bands = measureBands(this.bySide);
    this.pitches = measurePitches(this.bySide);
    this.offsets = collectOffsets(this.placements);
  }

  /**
   * The tidy default: inputs and outputs spread evenly down the sides they are on, in a box big
   * enough for their names and the caption.
   *
   * <p>The coordinates the ports arrive with are read only for their order along their own side --
   * a port higher up the left edge stays higher up it. That is what makes this usable both for a
   * component that has never had a layout and for the "arrange this for me" button, where the user
   * has already put things in an order they meant and wants the spacing fixed rather than their
   * work thrown away.
   */
  public static PortLayout automatic(String caption, Collection<PortPlacement> ports) {
    if (caption == null || caption.isBlank()) {
      throw new IllegalArgumentException("a custom component needs a name");
    }
    if (ports == null || ports.isEmpty()) {
      throw new IllegalArgumentException(caption + " has no ports");
    }
    final var ordered = new EnumMap<PortSide, List<PortPlacement>>(PortSide.class);
    for (final var side : PortSide.values()) ordered.put(side, new ArrayList<>());
    for (final var port : ports) ordered.get(port.side()).add(port);
    final var alongThenName =
        Comparator.comparingInt(PortPlacement::along).thenComparing(PortPlacement::name);
    for (final var side : PortSide.values()) ordered.get(side).sort(alongThenName);

    final var bands = measureBands(ordered);
    final var pitches = measurePitches(ordered);
    final var left = bands.get(PortSide.LEFT);
    final var right = bands.get(PortSide.RIGHT);
    final var top = bands.get(PortSide.TOP);
    final var bottom = bands.get(PortSide.BOTTOM);

    // One whole cell per port, as DefaultEvolutionAppearance does, so the outermost port keeps half
    // a pitch of clear box beyond it once the run is centred.
    final var down =
        Math.max(ordered.get(PortSide.LEFT).size(), ordered.get(PortSide.RIGHT).size()) * PITCH;
    final var across =
        Math.max(
            ordered.get(PortSide.TOP).size() * pitches.get(PortSide.TOP),
            ordered.get(PortSide.BOTTOM).size() * pitches.get(PortSide.BOTTOM));
    final var captionWidth = caption.trim().length() * CAPTION_CHAR_WIDTH;

    final var width =
        toGrid(Math.max(left + Math.max(across, captionWidth) + right, MIN_SIZE));
    final var height =
        toGrid(Math.max(top + Math.max(down, CAPTION_HEIGHT) + bottom, MIN_SIZE));

    final var placed = new ArrayList<PortPlacement>(ports.size());
    for (final var side : PortSide.values()) {
      final var onSide = ordered.get(side);
      if (onSide.isEmpty()) continue;
      final var pitch = side.stacked() ? PITCH : pitches.get(side);
      final var from = side.stacked() ? top : left;
      final var span = side.stacked() ? height - top - bottom : width - left - right;
      final var run = (onSide.size() - 1) * pitch;
      final var start = from + floorToGrid((span - run) / 2);
      for (var index = 0; index < onSide.size(); index++) {
        final var along = start + index * pitch;
        final var x = side.stacked() ? (side == PortSide.LEFT ? 0 : width) : along;
        final var y = side.stacked() ? along : (side == PortSide.TOP ? 0 : height);
        placed.add(onSide.get(index).at(x, y));
      }
    }
    return new PortLayout(
        caption,
        width,
        height,
        left + (width - left - right) / 2,
        top + (height - top - bottom) / 2,
        placed);
  }

  /**
   * Sorts the ports onto their sides, in the order they run along each, and refuses the two things
   * a layout may not say: one name twice, or two ports in one place. Neither is a shape the user
   * could have meant, and both produce a component that draws correctly and then misbehaves -- the
   * first has the appearance binding one pin to two ports, the second has a wire attaching to two.
   */
  private static Map<PortSide, List<PortPlacement>> groupBySide(List<PortPlacement> ports) {
    final var byName = new HashMap<String, PortPlacement>();
    final var byPlace = new HashMap<Location, PortPlacement>();
    for (final var port : ports) {
      if (byName.put(port.name(), port) != null) {
        throw new IllegalArgumentException("two ports are both named " + port.name());
      }
      final var clash = byPlace.put(Location.create(port.x(), port.y(), false), port);
      if (clash != null) {
        throw new IllegalArgumentException(
            "ports "
                + clash.name()
                + " and "
                + port.name()
                + " are both at "
                + port.x()
                + ","
                + port.y());
      }
    }
    final var grouped = new EnumMap<PortSide, List<PortPlacement>>(PortSide.class);
    for (final var side : PortSide.values()) grouped.put(side, new ArrayList<>());
    for (final var port : ports) grouped.get(port.side()).add(port);
    final var alongThenName =
        Comparator.comparingInt(PortPlacement::along).thenComparing(PortPlacement::name);
    final var result = new EnumMap<PortSide, List<PortPlacement>>(PortSide.class);
    for (final var side : PortSide.values()) {
      final var onSide = grouped.get(side);
      onSide.sort(alongThenName);
      result.put(side, List.copyOf(onSide));
    }
    return result;
  }

  private static Map<String, Location> collectOffsets(List<PortPlacement> ports) {
    final var offsets = new LinkedHashMap<String, Location>();
    for (final var port : ports) {
      offsets.put(port.name(), Location.create(port.x(), port.y(), false));
    }
    return Map.copyOf(offsets);
  }

  /**
   * How deep each side's names run into the box.
   *
   * <p>A property of the names, not of where the ports happen to be: every name is written the same
   * way up, because {@code com.cburch.draw.shapes.Text} carries no angle. A name on a stacked side
   * runs into the box, so its band is what the name is long; one on a laid-out side is written
   * along the box like any other and is a single line deep whatever it says, with the room it needs
   * taken out of the pitch instead (see {@link #measurePitches}).
   */
  private static Map<PortSide, Integer> measureBands(Map<PortSide, List<PortPlacement>> bySide) {
    final var bands = new EnumMap<PortSide, Integer>(PortSide.class);
    for (final var side : PortSide.values()) {
      final var onSide = bySide.get(side);
      if (onSide.isEmpty()) {
        bands.put(side, MARGIN);
      } else if (side.stacked()) {
        bands.put(side, toGrid(LABEL_INSET + longestName(onSide) * LABEL_CHAR_WIDTH));
      } else {
        bands.put(side, toGrid(LABEL_INSET + LINE_HEIGHT));
      }
    }
    return bands;
  }

  /**
   * How far apart two neighbouring ports have to sit on each side for their names to clear each
   * other.
   *
   * <p>A stacked side keeps {@link #PITCH}: its names run into the box, so what has to clear is one
   * line of text above the next. A laid-out side's names sit side by side, so the pitch there has
   * to be at least as wide as the longest of them. Rounded to {@link #ACROSS_STEP} so that a run of
   * them centred in the box still lands on the grid.
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

  private static int toGrid(int value) {
    return roundUpTo(GRID, value);
  }

  private static int floorToGrid(int value) {
    return Math.max(0, value) / GRID * GRID;
  }

  private static int toNearestGrid(int value) {
    return (value + GRID / 2) / GRID * GRID;
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

  /** Where the middle of the caption sits, measured from the box's top-left corner. */
  public int captionX() {
    return captionX;
  }

  public int captionY() {
    return captionY;
  }

  /** Every port, snapped to the grid, in the order the caller gave them. */
  public List<PortPlacement> placements() {
    return placements;
  }

  /** The ports on one side, in the order they run along it: top first, or left first. */
  public List<PortPlacement> side(PortSide side) {
    return bySide.get(side);
  }

  /** How deep one side's band of names is. */
  public int band(PortSide side) {
    return bands.get(side);
  }

  /** How far apart two neighbouring ports on one side have to be for their names to clear. */
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

  /**
   * Two layouts are the same layout when they draw the same picture.
   *
   * <p>Compared side by side rather than list by list, so that the order the ports were handed over
   * in is not part of the answer. It is not part of the drawing either, and a file read back is a
   * file whose ports arrive in whatever order they were written.
   */
  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof PortLayout that)) return false;
    return width == that.width
        && height == that.height
        && captionX == that.captionX
        && captionY == that.captionY
        && caption.equals(that.caption)
        && bySide.equals(that.bySide);
  }

  @Override
  public int hashCode() {
    return Objects.hash(caption, width, height, captionX, captionY, bySide);
  }

  @Override
  public String toString() {
    return caption + " " + width + "x" + height + " " + placements;
  }
}
