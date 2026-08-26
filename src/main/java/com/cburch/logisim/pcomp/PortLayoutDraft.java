/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.std.wiring.Pin;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Peler Edition. A layout being edited, before it is any good.
 *
 * <p>{@link PortLayout} only accepts a layout that is already valid -- every port named, no name
 * used twice, no two ports in one place. The confirmation window is exactly where none of that
 * holds yet, so it edits this instead: a name may be blank, two ports may be sitting on top of each
 * other while one of them is being dragged past the other, and {@link #problem} says what still
 * stands between the user and a saveable component.
 *
 * <p>This is also where the box itself lives while it is being dragged. The window moves ports,
 * resizes the box and moves the caption through the methods below, all of which snap to the drawing
 * grid, and {@link #arrange} puts everything back the way {@link PortLayout#automatic} would have
 * it -- which is where a new component starts and what the "arrange for me" button does.
 *
 * <p>Deliberately free of Swing. Everything the window can do to a layout can be done here and
 * asked about here, so the rules are checked in tests rather than by clicking.
 */
public final class PortLayoutDraft {

  /** Smallest box the window will let a user drag out, on either axis. */
  public static final int MIN_SIZE = 3 * PortLayout.GRID;

  /** One port: the pin it belongs to, the name it will carry, and where it sits on the box. */
  public static final class Entry {
    private final Instance pin;
    private String name;
    private PortSide side;
    private int x;
    private int y;

    private Entry(Instance pin, String name, PortSide side) {
      this.pin = pin;
      this.name = name == null ? "" : name.trim();
      this.side = side;
    }

    public Instance pin() {
      return pin;
    }

    public String name() {
      return name;
    }

    public PortSide side() {
      return side;
    }

    /** How far right of the box's top-left corner this port sits. */
    public int atX() {
      return x;
    }

    /** How far below the box's top-left corner this port sits. */
    public int atY() {
      return y;
    }

    /** True when this pin drives the component from outside, false when the component drives it. */
    public boolean isInput() {
      return Pin.INPUT.equals(pin.getAttributeValue(Pin.ATTR_TYPE));
    }
  }

  /** What is still wrong with a draft. */
  public enum Problem {
    /** A component with no ports would be a box nothing can be attached to. */
    NO_PORTS,
    /** At least one port has no name. The user was told naming all of them is the price. */
    UNNAMED,
    /** Two ports carry one name, which the appearance could not bind to two different pins. */
    DUPLICATE_NAME,
    /** Two ports are in the same place, where one wire would attach to both. */
    OVERLAP
  }

  private static final Comparator<Entry> READING_ORDER =
      Comparator.comparingInt((Entry entry) -> entry.y)
          .thenComparingInt(entry -> entry.x)
          .thenComparing(entry -> entry.name);

  private String caption;
  private final List<Entry> entries = new ArrayList<>();
  private int width = PortLayout.MIN_SIZE;
  private int height = PortLayout.MIN_SIZE;
  private int captionX = PortLayout.MIN_SIZE / 2;
  private int captionY = PortLayout.MIN_SIZE / 2;

  /**
   * Whether the user has moved anything by hand yet.
   *
   * <p>Until they have, retyping the component's name arranges the box again, so that a name too
   * long for the box it opened with makes the box wider instead of hanging out of it. The first
   * drag ends that: from then on the box is the user's, and nothing moves it but them and the
   * arrange button.
   */
  private boolean touched;

  private PortLayoutDraft(String caption) {
    this.caption = caption == null ? "" : caption.trim();
  }

  /**
   * The layout a circuit starts out with: inputs down the left, outputs down the right, each in the
   * order the pins run down the canvas, spaced out by {@link PortLayout#automatic}.
   *
   * <p>The side comes from the pin's type, which is what {@code DefaultEvolutionAppearance} -- the
   * default subcircuit drawing -- already does. Facing would have been the other candidate, and is
   * what the older {@code DefaultClassicAppearance} uses, but a pin's facing does not follow its
   * type: an output pin placed the ordinary way still faces east, so facing would open every
   * circuit with all of its ports stacked on the left.
   */
  public static PortLayoutDraft of(Circuit circuit) {
    final var draft = new PortLayoutDraft(circuit.getName());
    for (final var pin : circuit.getAppearance().getCircuitPins().getPins()) {
      draft.entries.add(new Entry(pin, pin.getAttributeValue(StdAttr.LABEL), sideOf(pin)));
    }
    draft.entries.sort(
        (a, b) ->
            compare(
                a.pin.getLocation().getY(),
                b.pin.getLocation().getY(),
                a.pin.getLocation().getX(),
                b.pin.getLocation().getX()));
    draft.seedFromOrder();
    draft.arrange();
    draft.touched = false;
    return draft;
  }

  /**
   * The layout a component was published with, brought back for a new version to start from.
   *
   * <p>Not {@link #of(Circuit)} with the positions fixed up afterwards: a published component's
   * ports are where the user put them, and re-deriving them would silently propose undoing that
   * before the user had touched anything. A pin the layout does not mention -- one added since, in
   * the file the user opened to edit -- is parked on the default edge for its type, below whatever
   * is already there, because it has to go somewhere and that is where a new pin would have gone.
   */
  public static PortLayoutDraft of(Circuit circuit, String caption, PortLayout published) {
    final var draft = of(circuit);
    draft.width = published.width();
    draft.height = published.height();
    draft.captionX = published.captionX();
    draft.captionY = published.captionY();
    final var newcomers = new ArrayList<Entry>();
    for (final var entry : draft.entries) {
      final var placement = placementOf(published, entry.name);
      if (placement == null) {
        newcomers.add(entry);
        continue;
      }
      entry.side = placement.side();
      entry.x = placement.x();
      entry.y = placement.y();
    }
    for (final var entry : newcomers) draft.park(entry);
    // Before the caption, which would otherwise arrange the box and throw away what was published.
    draft.touched = true;
    draft.setCaption(caption);
    return draft;
  }

  private static PortPlacement placementOf(PortLayout published, String name) {
    for (final var placement : published.placements()) {
      if (placement.name().equals(name)) return placement;
    }
    return null;
  }

  /**
   * Puts a port that has nowhere yet on its own edge, at the first free spot below or right of
   * everything already on that edge.
   */
  private void park(Entry entry) {
    final var along = entry.side.stacked() ? height : width;
    var at = PortLayout.GRID;
    for (final var other : entries) {
      if (other == entry || other.side != entry.side) continue;
      at = Math.max(at, (other.side.stacked() ? other.y : other.x) + PortLayout.PITCH);
    }
    final var fixed = switch (entry.side) {
      case LEFT, TOP -> 0;
      case RIGHT -> width;
      case BOTTOM -> height;
    };
    if (entry.side.stacked()) {
      entry.x = fixed;
      entry.y = Math.min(at, along);
    } else {
      entry.x = Math.min(at, along);
      entry.y = fixed;
    }
  }

  private static int compare(int firstA, int firstB, int secondA, int secondB) {
    return firstA != firstB ? Integer.compare(firstA, firstB) : Integer.compare(secondA, secondB);
  }

  private static PortSide sideOf(Instance pin) {
    return Pin.OUTPUT.equals(pin.getAttributeValue(Pin.ATTR_TYPE)) ? PortSide.RIGHT : PortSide.LEFT;
  }

  /**
   * Gives each port a coordinate that merely puts it in the order the entry list is already in, for
   * {@link #arrange} to read back and turn into a real one.
   */
  private void seedFromOrder() {
    final var counts = new EnumMap<PortSide, Integer>(PortSide.class);
    for (final var entry : entries) {
      final var index = counts.merge(entry.side, 1, Integer::sum) - 1;
      entry.x = entry.side.stacked() ? 0 : index;
      entry.y = entry.side.stacked() ? index : 0;
    }
  }

  public String caption() {
    return caption;
  }

  /**
   * Renames the component. While nothing has been dragged yet this also arranges the box again, so
   * that the caption the user is typing is a caption the box is big enough for.
   */
  public void setCaption(String value) {
    caption = value == null ? "" : value.trim();
    if (!touched) arrange();
  }

  public int width() {
    return width;
  }

  public int height() {
    return height;
  }

  public int captionX() {
    return captionX;
  }

  public int captionY() {
    return captionY;
  }

  /** Every port, in the order they read off the box: down first, then across. */
  public List<Entry> all() {
    final var ordered = new ArrayList<>(entries);
    ordered.sort(READING_ORDER);
    return Collections.unmodifiableList(ordered);
  }

  /** The ports belonging to one edge, in the order they run along it. */
  public List<Entry> on(PortSide side) {
    final var onSide = new ArrayList<Entry>();
    for (final var entry : entries) {
      if (entry.side == side) onSide.add(entry);
    }
    onSide.sort(
        side.stacked()
            ? READING_ORDER
            : Comparator.comparingInt((Entry entry) -> entry.x)
                .thenComparingInt(entry -> entry.y)
                .thenComparing(entry -> entry.name));
    return Collections.unmodifiableList(onSide);
  }

  public int portCount() {
    return entries.size();
  }

  /**
   * Moves a port to a place on the box.
   *
   * <p>Snapped to the grid, because a port off it takes no wires, and clamped to the box, because a
   * port floating beside the drawing is a dot the user cannot tell from a mistake. The side is set
   * from the edge the port lands nearest, which is what turns its name round when it crosses the
   * box -- a file may still say otherwise, and dragging is simply not how you say it.
   */
  public void moveTo(Entry entry, int newX, int newY) {
    entry.x = snap(newX, width);
    entry.y = snap(newY, height);
    entry.side = PortSide.nearest(entry.x, entry.y, width, height);
    touched = true;
  }

  /**
   * Resizes the box.
   *
   * <p>A port sitting on an edge that moves goes with it, which is what makes dragging the right
   * edge feel like widening the component rather than leaving its outputs behind. Everything else
   * stays where it is, clamped into the new box, and the caption keeps the same place in it
   * proportionally -- a centred name stays centred.
   */
  public void resize(int newWidth, int newHeight) {
    final var wide = Math.max(MIN_SIZE, PortLayout.GRID * Math.round(newWidth / (float) PortLayout.GRID));
    final var tall = Math.max(MIN_SIZE, PortLayout.GRID * Math.round(newHeight / (float) PortLayout.GRID));
    for (final var entry : entries) {
      entry.x = entry.x == width ? wide : Math.min(entry.x, wide);
      entry.y = entry.y == height ? tall : Math.min(entry.y, tall);
    }
    captionX = width == 0 ? wide / 2 : captionX * wide / width;
    captionY = height == 0 ? tall / 2 : captionY * tall / height;
    width = wide;
    height = tall;
    touched = true;
  }

  /** Moves the caption. Not snapped: it is text, and nothing attaches to it. */
  public void moveCaption(int newX, int newY) {
    captionX = Math.max(0, Math.min(newX, width));
    captionY = Math.max(0, Math.min(newY, height));
    touched = true;
  }

  /**
   * Puts everything back the way {@link PortLayout#automatic} would have it.
   *
   * <p>The order the ports are already in along each edge is kept; only the spacing, the sides'
   * depths and the box's size are recomputed. That is what makes this useful as a button rather
   * than only as a starting point: a user who has arranged four inputs in the order they want and
   * then finds them crowded gets the crowding fixed, not the order.
   *
   * <p>Works whatever the names are. A blank or repeated name would stop {@link PortLayout} dead,
   * so each port is stood in for by a made-up one and the coordinates are read back by position --
   * the arithmetic reads names only for how long they are.
   */
  public void arrange() {
    if (entries.isEmpty()) return;
    final var stand = standInNames();
    final var provisional = new ArrayList<PortPlacement>(entries.size());
    for (var index = 0; index < entries.size(); index++) {
      final var entry = entries.get(index);
      provisional.add(new PortPlacement(stand.get(index), entry.side, entry.x, entry.y));
    }
    final var arranged = PortLayout.automatic(standInCaption(), provisional);
    for (var index = 0; index < entries.size(); index++) {
      final var at = arranged.offsetOf(stand.get(index));
      entries.get(index).x = at.getX();
      entries.get(index).y = at.getY();
    }
    width = arranged.width();
    height = arranged.height();
    captionX = arranged.captionX();
    captionY = arranged.captionY();
  }

  /** A usable name per port, in entry order, standing in for the blanks and the repeats. */
  private List<String> standInNames() {
    final var seen = new HashSet<String>();
    final var stand = new ArrayList<String>(entries.size());
    for (final var entry : entries) {
      final var name = entry.name.isBlank() ? "?" : entry.name;
      var unique = name;
      for (var n = 2; !seen.add(unique); n++) unique = name + "#" + n;
      stand.add(unique);
    }
    return stand;
  }

  private String standInCaption() {
    return caption.isBlank() ? "?" : caption;
  }

  private static int snap(int value, int limit) {
    final var onGrid = PortLayout.GRID * Math.round(value / (float) PortLayout.GRID);
    return Math.max(0, Math.min(onGrid, limit));
  }

  public void rename(Entry entry, String name) {
    entry.name = name == null ? "" : name.trim();
  }

  /**
   * What still stops this draft from being saved, or null when nothing does.
   *
   * <p>Returns the reason rather than a message: the window that shows it knows which locale it is
   * in, and this class deliberately does not.
   */
  public Problem problem() {
    if (entries.isEmpty()) return Problem.NO_PORTS;
    final var names = new HashSet<String>();
    for (final var entry : entries) {
      if (entry.name.isBlank()) return Problem.UNNAMED;
      if (!names.add(entry.name)) return Problem.DUPLICATE_NAME;
    }
    final var places = new HashSet<Long>();
    for (final var entry : entries) {
      if (!places.add((long) entry.x << 32 | (entry.y & 0xffffffffL))) return Problem.OVERLAP;
    }
    return null;
  }

  /** The ports this draft describes. Only meaningful once {@link #problem} says null. */
  public List<PortPlacement> placements() {
    final var placements = new ArrayList<PortPlacement>(entries.size());
    for (final var entry : all()) {
      placements.add(new PortPlacement(entry.name, entry.side, entry.x, entry.y));
    }
    return placements;
  }

  /** The box this draft describes. Only callable once {@link #problem} says null. */
  public PortLayout layout() {
    return new PortLayout(caption, width, height, captionX, captionY, placements());
  }

  /** The pins the ports name, ready for {@link PcompAppearance#build}. */
  public Map<String, Instance> pinsByName() {
    final var pins = new LinkedHashMap<String, Instance>();
    for (final var entry : all()) pins.put(entry.name, entry.pin);
    return pins;
  }
}
