/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.std.wiring.Pin;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Peler Edition. Everything about a component's ports that a wire already drawn to one depends on.
 *
 * <p>Five things per port: the name, which side it is on, where in that side's order it sits, which
 * way it points and how wide it is. Change any of them and something in somebody's project is now
 * wrong -- the first three move the port out from under its wire, the last two leave the wire where
 * it is and change what flows through it, which is worse for being invisible.
 *
 * <p><b>This is why a published component cannot be edited in place.</b> Comparing signatures is
 * what turns "the layout is fixed once published" from a rule people are asked to follow into one
 * the program can check: an unchanged signature may overwrite its own version, a changed one has to
 * arrive as a new version that nothing is using yet.
 *
 * <p>Placement comes from the layout and direction and width come from the {@code Pin}, because
 * that is where each of them is actually true. {@link PcompMetadata} deliberately stores only the
 * placement half; a file that also recorded the width would be a second copy of something the
 * circuit already says, free to disagree with it.
 *
 * @param name the port's name, which is also its pin's label
 * @param side which side of the box it is on
 * @param slot its position in that side's order, counting from zero
 * @param input true for an input, false for an output
 * @param width bits, from the pin's {@code StdAttr.WIDTH}
 */
public record PortSignature(String name, PortSide side, int slot, boolean input, int width) {

  /**
   * The signature of a set of placements, taking direction and width from the matching pins.
   *
   * <p>A placement whose pin is missing is left out rather than guessed at. The caller that cares
   * -- loading a component -- already refuses a file whose ports name no pin, and the caller that
   * does not -- comparing two versions -- would rather see the port as removed than as a port with
   * invented properties.
   */
  public static List<PortSignature> of(List<PortPlacement> ports, Map<String, Instance> pins) {
    final var signature = new ArrayList<PortSignature>();
    for (final var port : ports) {
      final var pin = pins.get(port.name());
      if (pin == null) continue;
      signature.add(
          new PortSignature(
              port.name(),
              port.side(),
              port.slot(),
              Pin.INPUT.equals(pin.getAttributeValue(Pin.ATTR_TYPE)),
              pin.getAttributeValue(StdAttr.WIDTH).getWidth()));
    }
    signature.sort(PortSignature::inReadingOrder);
    return List.copyOf(signature);
  }

  /** The signature of an installed component, read off the circuit it was published with. */
  public static List<PortSignature> of(PcompLibrary component) {
    final var pins = new LinkedHashMap<String, Instance>();
    for (final var pin : component.getCircuit().getAppearance().getCircuitPins().getPins()) {
      final var label = pin.getAttributeValue(StdAttr.LABEL);
      if (label != null && !label.isBlank()) pins.put(label.trim(), pin);
    }
    return of(component.getMetadata().ports(), pins);
  }

  /** The signature the layout window would publish if the user saved right now. */
  public static List<PortSignature> of(PortLayoutDraft draft) {
    return of(draft.placements(), draft.pinsByName());
  }

  private static int inReadingOrder(PortSignature a, PortSignature b) {
    if (a.side != b.side) return a.side.compareTo(b.side);
    return a.slot - b.slot;
  }

  /**
   * What changed between two signatures, one entry per port that is not the same in both.
   *
   * <p>Matched <b>by name</b>, which is why every port having one is a hard requirement rather than
   * a nicety: side and slot are the things being compared, so they cannot also be the identity, and
   * a port has no other stable handle. A port whose name changed therefore reads as one removed and
   * one added, which is the honest description -- nothing can tell that apart from a rename, and
   * treating it as a rename would quietly keep a wire attached across a change the user might have
   * meant as a replacement.
   *
   * <p>Structured rather than a list of sentences. The window that shows these has to phrase them
   * in the user's language, and a comparison that returned finished text would either drag the
   * string table into this package or fix the wording in English on the way past.
   *
   * @return an empty list when the two are the same signature
   */
  public static List<Difference> differences(
      List<PortSignature> before, List<PortSignature> after) {
    final var was = index(before);
    final var now = index(after);
    final var names = new LinkedHashSet<String>();
    names.addAll(was.keySet());
    names.addAll(now.keySet());

    final var found = new ArrayList<Difference>();
    for (final var name : names) {
      final var older = was.get(name);
      final var newer = now.get(name);
      final var kind =
          older == null ? Kind.ADDED
              : newer == null ? Kind.REMOVED
                  : older.side != newer.side || older.slot != newer.slot ? Kind.MOVED
                      : older.input != newer.input ? Kind.DIRECTION
                          : older.width != newer.width ? Kind.WIDTH : null;
      if (kind != null) found.add(new Difference(kind, name, older, newer));
    }
    return List.copyOf(found);
  }

  private static Map<String, PortSignature> index(List<PortSignature> signature) {
    final var byName = new LinkedHashMap<String, PortSignature>();
    for (final var port : signature) byName.put(port.name(), port);
    return byName;
  }

  /** What sort of change one port underwent. */
  public enum Kind {
    ADDED,
    REMOVED,
    MOVED,
    DIRECTION,
    WIDTH
  }

  /**
   * One port that is not the same in both signatures.
   *
   * @param was the port as it was published, null when the port is new
   * @param now the port as it stands, null when the port is gone
   */
  public record Difference(Kind kind, String name, PortSignature was, PortSignature now) {}
}
