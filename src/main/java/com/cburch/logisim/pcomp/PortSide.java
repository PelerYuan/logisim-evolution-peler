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
 * Peler Edition. Which edge of a custom component's box a port belongs to.
 *
 * <p>Not where the port is -- {@link PortPlacement} carries that as a coordinate the user drags.
 * This is what the port is drawn <em>like</em>: which way its stub sticks out, which way its name
 * is written, and which end of the name is anchored to it. A port on the left has its name written
 * rightwards into the box; the same port dragged to the right edge has it written leftwards, and
 * nothing but this enum knows the difference.
 *
 * <p>The two are kept apart because a coordinate cannot always answer the question. A port dragged
 * into the middle of the box is near no edge in particular, and one sitting exactly on a corner is
 * equally near two. The layout window sets the side from the edge a drag lands on, which is right
 * nearly always and overridable by a file when it is not.
 */
public enum PortSide {
  LEFT,
  RIGHT,
  TOP,
  BOTTOM;

  /**
   * True when ports on this side are stacked one below the next, false when they are laid out one
   * beside the next. Says which axis {@link PortLayout#automatic} runs them along, and which
   * coordinate counts as "along this side" when they are put in order.
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

  /**
   * The edge of a {@code width} by {@code height} box that a point belongs to.
   *
   * <p>Compared as fractions of the box rather than in pixels, so a wide flat component does not
   * hand its whole area to the left and right edges.
   */
  public static PortSide nearest(int x, int y, int width, int height) {
    final var across = (x - Math.max(1, width) / 2.0) / Math.max(1, width);
    final var down = (y - Math.max(1, height) / 2.0) / Math.max(1, height);
    if (Math.abs(across) >= Math.abs(down)) return across < 0 ? LEFT : RIGHT;
    return down < 0 ? TOP : BOTTOM;
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
