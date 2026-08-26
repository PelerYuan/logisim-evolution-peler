/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import java.util.ArrayList;
import java.util.List;

/**
 * Peler Edition. Layouts for the tests to work from.
 *
 * <p>A port's position is an absolute coordinate, and writing dozens of them out by hand would put
 * the arithmetic under test into the tests themselves. What the fixtures here say instead is the
 * only thing most of them care about -- which edge a port is on and where in that edge's run it
 * sits -- and let {@link PortLayout#automatic} turn it into geometry, the same way a new component
 * gets its geometry in the first place.
 */
final class PcompLayouts {
  private PcompLayouts() {}

  /**
   * A port that is nth along its side, before anything has decided where that is.
   *
   * <p>The coordinate is a placeholder that sorts the way the order does, which is all {@link
   * PortLayout#automatic} reads back out of it.
   */
  static PortPlacement nth(String name, PortSide side, int order) {
    return new PortPlacement(name, side, side.stacked() ? 0 : order, side.stacked() ? order : 0);
  }

  /** The layout a set of ports gets by default, listed in the order they run down each side. */
  static PortLayout automatic(String caption, PortPlacement... ports) {
    return PortLayout.automatic(caption, List.of(ports));
  }

  /** The same, for a list that was built up rather than written out. */
  static PortLayout automatic(String caption, List<PortPlacement> ports) {
    return PortLayout.automatic(caption, ports);
  }

  /**
   * The same box with one port moved somewhere else and nothing else touched.
   *
   * <p>What a user does in the layout window, and what a test comparing two versions of a component
   * wants: arranging the whole thing again instead would move the other ports too, and the
   * comparison would report every one of them.
   */
  static PortLayout moving(PortLayout layout, String name, PortSide side, int x, int y) {
    final var moved = new ArrayList<PortPlacement>();
    for (final var port : layout.placements()) {
      moved.add(port.name().equals(name) ? port.movedTo(side, x, y) : port);
    }
    return new PortLayout(
        layout.caption(), layout.width(), layout.height(), layout.captionX(), layout.captionY(),
        moved);
  }
}
