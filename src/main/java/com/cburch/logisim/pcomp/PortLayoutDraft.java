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
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Peler Edition. A layout being edited, before it is any good.
 *
 * <p>{@link PortLayout} only accepts a layout that is already valid -- every port named, no name
 * used twice, slots running without holes. The confirmation window is exactly where none of that
 * holds yet, so it edits this instead: sides hold ordered lists, a name may be blank, and {@link
 * #problem} says what still stands between the user and a saveable component.
 *
 * <p>Deliberately free of Swing. Everything the window can do to a layout can be done here and
 * asked about here, so the rules are checked in tests rather than by clicking.
 */
public final class PortLayoutDraft {

  /** One port: the pin it belongs to, the name it will carry, and the side it sits on. */
  public static final class Entry {
    private final Instance pin;
    private String name;

    private Entry(Instance pin, String name) {
      this.pin = pin;
      this.name = name == null ? "" : name.trim();
    }

    public Instance pin() {
      return pin;
    }

    public String name() {
      return name;
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
    DUPLICATE_NAME
  }

  private String caption;
  private final EnumMap<PortSide, List<Entry>> sides = new EnumMap<>(PortSide.class);

  private PortLayoutDraft(String caption) {
    this.caption = caption == null ? "" : caption.trim();
    for (final var side : PortSide.values()) sides.put(side, new ArrayList<>());
  }

  /**
   * The layout a circuit starts out with: inputs down the left, outputs down the right, each in the
   * order the pins run down the canvas.
   *
   * <p>The side comes from the pin's type, which is what {@code DefaultEvolutionAppearance} -- the
   * default subcircuit drawing -- already does. Facing would have been the other candidate, and is
   * what the older {@code DefaultClassicAppearance} uses, but a pin's facing does not follow its
   * type: an output pin placed the ordinary way still faces east, so facing would open every
   * circuit with all of its ports stacked on the left.
   */
  public static PortLayoutDraft of(Circuit circuit) {
    final var draft = new PortLayoutDraft(circuit.getName());
    final var pins = new ArrayList<>(circuit.getAppearance().getCircuitPins().getPins());
    for (final var pin : pins) {
      draft.sides.get(sideOf(pin)).add(new Entry(pin, pin.getAttributeValue(StdAttr.LABEL)));
    }
    for (final var side : PortSide.values()) {
      final var onSide = draft.sides.get(side);
      onSide.sort(
          side.stacked()
              ? (a, b) -> compare(a.pin.getLocation().getY(), b.pin.getLocation().getY(),
                  a.pin.getLocation().getX(), b.pin.getLocation().getX())
              : (a, b) -> compare(a.pin.getLocation().getX(), b.pin.getLocation().getX(),
                  a.pin.getLocation().getY(), b.pin.getLocation().getY()));
    }
    return draft;
  }

  private static int compare(int firstA, int firstB, int secondA, int secondB) {
    return firstA != firstB ? Integer.compare(firstA, firstB) : Integer.compare(secondA, secondB);
  }

  private static PortSide sideOf(Instance pin) {
    return Pin.OUTPUT.equals(pin.getAttributeValue(Pin.ATTR_TYPE)) ? PortSide.RIGHT : PortSide.LEFT;
  }

  public String caption() {
    return caption;
  }

  /** Renames the component. The caption is drawn in the box, so it also changes the box's width. */
  public void setCaption(String value) {
    caption = value == null ? "" : value.trim();
  }

  /**
   * The layout a component was published with, brought back for a new version to start from.
   *
   * <p>Not {@link #of(Circuit)} with the sides fixed up afterwards: a published component's ports
   * are where the user put them, and re-deriving them from pin types would silently propose undoing
   * that before the user had touched anything. A pin the placements do not mention -- one added
   * since, in the file the user opened to edit -- lands on the default side for its type, because
   * it has to land somewhere and that is where a new pin would have gone.
   */
  public static PortLayoutDraft of(Circuit circuit, String caption, List<PortPlacement> published) {
    final var draft = of(circuit);
    final var byName = new LinkedHashMap<String, PortPlacement>();
    for (final var placement : published) byName.put(placement.name(), placement);
    final var known = new ArrayList<Entry>();
    for (final var side : PortSide.values()) {
      for (final var entry : draft.sides.get(side)) {
        if (byName.containsKey(entry.name)) known.add(entry);
      }
    }
    for (final var entry : known) {
      final var placement = byName.get(entry.name);
      draft.moveTo(entry, placement.side(), Integer.MAX_VALUE);
    }
    for (final var side : PortSide.values()) {
      draft.sides.get(side).sort((a, b) -> slotOf(byName, a) - slotOf(byName, b));
    }
    draft.setCaption(caption);
    return draft;
  }

  /**
   * Where a port sits on its side, with anything the published layout never mentioned going last.
   */
  private static int slotOf(Map<String, PortPlacement> published, Entry entry) {
    final var placement = published.get(entry.name);
    return placement == null ? Integer.MAX_VALUE : placement.slot();
  }

  /** The ports on one side, in the order they will be drawn. */
  public List<Entry> on(PortSide side) {
    return Collections.unmodifiableList(sides.get(side));
  }

  public int portCount() {
    var count = 0;
    for (final var side : PortSide.values()) count += sides.get(side).size();
    return count;
  }

  /** Moves a port to a side and a position on it, clamping a position past the end. */
  public void moveTo(Entry entry, PortSide side, int slot) {
    for (final var list : sides.values()) list.remove(entry);
    final var target = sides.get(side);
    target.add(Math.max(0, Math.min(slot, target.size())), entry);
  }

  public PortSide sideOf(Entry entry) {
    for (final var side : PortSide.values()) {
      if (sides.get(side).contains(entry)) return side;
    }
    return null;
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
    if (portCount() == 0) return Problem.NO_PORTS;
    final var seen = new HashSet<String>();
    for (final var side : PortSide.values()) {
      for (final var entry : sides.get(side)) {
        if (entry.name.isBlank()) return Problem.UNNAMED;
        if (!seen.add(entry.name)) return Problem.DUPLICATE_NAME;
      }
    }
    return null;
  }

  /** The ports this draft describes. Only meaningful once {@link #problem} says null. */
  public List<PortPlacement> placements() {
    final var placements = new ArrayList<PortPlacement>();
    for (final var side : PortSide.values()) {
      final var onSide = sides.get(side);
      for (var slot = 0; slot < onSide.size(); slot++) {
        placements.add(new PortPlacement(onSide.get(slot).name, side, slot));
      }
    }
    return placements;
  }

  /**
   * The drawing to show while editing, whether or not the draft is saveable yet.
   *
   * <p>{@link PortLayout} refuses a blank or repeated name, and the window is where both are
   * normal. Rather than a second copy of the sizing arithmetic for the preview to use, the names
   * are stood in for: a blank one becomes a question mark, and a repeat gets a number. Only the
   * picture ever sees them -- {@link #placements} hands out the real names, and refuses to be
   * called while {@link #problem} has an answer.
   */
  public PortLayout previewLayout() {
    final var seen = new HashSet<String>();
    final var stood = new ArrayList<PortPlacement>();
    for (final var side : PortSide.values()) {
      final var onSide = sides.get(side);
      for (var slot = 0; slot < onSide.size(); slot++) {
        var name = onSide.get(slot).name;
        if (name.isBlank()) name = "?";
        var unique = name;
        for (var n = 2; !seen.add(unique); n++) unique = name + "#" + n;
        stood.add(new PortPlacement(unique, side, slot));
      }
    }
    if (stood.isEmpty()) return new PortLayout(caption, List.of(new PortPlacement("?",
        PortSide.LEFT, 0)));
    return new PortLayout(caption, stood);
  }

  /** The name the preview shows for one port, which is not always the name it carries. */
  public String previewNameOf(Entry entry) {
    final var layout = previewLayout();
    var index = 0;
    for (final var side : PortSide.values()) {
      for (final var other : sides.get(side)) {
        if (other == entry) return layout.placements().get(index).name();
        index++;
      }
    }
    return entry.name;
  }

  /** The pins the ports name, ready for {@link PcompAppearance#build}. */
  public Map<String, Instance> pinsByName() {
    final var pins = new LinkedHashMap<String, Instance>();
    for (final var side : PortSide.values()) {
      for (final var entry : sides.get(side)) pins.put(entry.name, entry.pin);
    }
    return pins;
  }
}
