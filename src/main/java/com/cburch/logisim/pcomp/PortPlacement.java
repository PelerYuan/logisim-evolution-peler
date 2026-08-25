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
 * <p><b>A placement carries a name, not a port index.</b> The name is what binds it to a {@code
 * Pin} in the component's circuit, and it is the only binding there is: the pins' order inside a
 * circuit is not stable across an edit, so an index would silently re-point at a different pin the
 * first time somebody moved one. It is also why the layout window refuses to save while any port is
 * unnamed -- an unnamed port cannot be bound to, now or after a version bump.
 *
 * <p>Direction and bit width are deliberately absent. The {@code Pin} states both, and stating them
 * twice is how the two drift apart.
 *
 * @param name the pin's label, trimmed; unique within one component
 * @param side which edge of the box the port sits on
 * @param slot position along that edge, counting from the top for a stacked side and from the left
 *     for a laid-out one; the slots on one side must run 0, 1, 2 ... with no holes
 */
public record PortPlacement(String name, PortSide side, int slot) {

  public PortPlacement {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("a port placement needs a name");
    }
    if (side == null) {
      throw new IllegalArgumentException("port " + name + " has no side");
    }
    if (slot < 0) {
      throw new IllegalArgumentException("port " + name + " has a negative slot: " + slot);
    }
    name = name.trim();
  }

  /** The same port moved elsewhere. */
  public PortPlacement movedTo(PortSide newSide, int newSlot) {
    return new PortPlacement(name, newSide, newSlot);
  }

  /** The same port renamed, keeping its position. */
  public PortPlacement renamedTo(String newName) {
    return new PortPlacement(newName, side, slot);
  }
}
