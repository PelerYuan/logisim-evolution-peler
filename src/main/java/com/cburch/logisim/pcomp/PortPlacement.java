/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

/**
 * Peler Edition. One port of a custom component, and where the user put it.
 *
 * <p><b>A placement carries a name, not a port index.</b> The name is how the appearance finds the
 * pin it belongs to and how two versions of a component are compared, so it has to survive a pin
 * being added, removed or reordered inside the circuit -- which an index would not.
 *
 * <p><b>The position is absolute.</b> It is an offset from the box's top-left corner, in the same
 * units the drawing grid uses, and it is exactly where the port ends up in the saved file. Earlier
 * this was a slot number and the coordinate was computed from the port names; that made every
 * component regular and none of them adjustable, which is not what the layout window is for. The
 * automatic layout that a new component starts from still produces those regular coordinates -- see
 * {@link PortLayout#automatic} -- but from there the user is free to drag.
 *
 * <p>The side stays alongside the coordinate rather than being derived from it. It decides which
 * way the port's stub points and which way its name is written, and a port dragged into the middle
 * of the box has no nearest edge worth speaking of; the layout window sets it from the edge a drag
 * lands on, and a file may say otherwise.
 *
 * <p>Direction and bit width are deliberately absent. The {@code Pin} states both, and stating them
 * twice is how the two drift apart.
 *
 * @param name the pin's label, trimmed; unique within one component
 * @param side which edge of the box the port belongs to, for drawing purposes
 * @param x distance right of the box's left edge
 * @param y distance below the box's top edge
 */
public record PortPlacement(String name, PortSide side, int x, int y) {
  public PortPlacement {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("a port placement needs a name");
    }
    if (side == null) throw new IllegalArgumentException("port " + name + " has no side");
    if (x < 0 || y < 0) {
      throw new IllegalArgumentException(
          "port " + name + " is outside the box at " + x + "," + y);
    }
    name = name.trim();
  }

  /** The same port, moved to a coordinate. The side is left alone; see {@link #movedTo}. */
  public PortPlacement at(int newX, int newY) {
    return new PortPlacement(name, side, newX, newY);
  }

  /** The same port, moved to a coordinate and told which edge it now belongs to. */
  public PortPlacement movedTo(PortSide newSide, int newX, int newY) {
    return new PortPlacement(name, newSide, newX, newY);
  }

  public PortPlacement renamedTo(String newName) {
    return new PortPlacement(newName, side, x, y);
  }

  /**
   * How far along its own side this port sits: down the box for a stacked side, across it for a
   * laid-out one. What "in order" means on a side, and so how the ports in one side's list are
   * sorted.
   */
  public int along() {
    return side.stacked() ? y : x;
  }
}
