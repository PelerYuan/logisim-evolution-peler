/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import java.util.Locale;

/**
 * Peler Edition. Which edge of a custom component's box a port sits on.
 *
 * <p>The four sides are the whole of what the layout window lets a user decide about a port's
 * position: which edge, and which slot down (or across) that edge. Everything else about where the
 * port lands -- the box's size, the distance from the corner, the pixel coordinate -- is computed
 * from the port names by {@link PortLayout}, because a coordinate a human typed is a coordinate
 * that can be off the drawing grid.
 */
public enum PortSide {
  LEFT,
  RIGHT,
  TOP,
  BOTTOM;

  /**
   * True when ports on this side are stacked one below the next, false when they are laid out one
   * beside the next. Also says which way the port's name is written: along the box for a stacked
   * side, turned a quarter turn for a laid-out one.
   */
  public boolean stacked() {
    return this == LEFT || this == RIGHT;
  }

  /** The side facing this one across the box. */
  public PortSide opposite() {
    return switch (this) {
      case LEFT -> RIGHT;
      case RIGHT -> LEFT;
      case TOP -> BOTTOM;
      case BOTTOM -> TOP;
    };
  }

  /** How this side is spelled in a {@code .pcomp} file. Lower case, and fixed for good. */
  public String toXmlValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Reads back {@link #toXmlValue}. Returns null rather than throwing on an unknown word. */
  public static PortSide fromXmlValue(String value) {
    if (value == null) return null;
    for (final var side : values()) {
      if (side.toXmlValue().equals(value.trim().toLowerCase(Locale.ROOT))) return side;
    }
    return null;
  }
}
