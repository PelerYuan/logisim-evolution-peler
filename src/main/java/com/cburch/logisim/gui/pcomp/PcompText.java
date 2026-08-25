/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.pcomp;

import static com.cburch.logisim.gui.Strings.S;

import com.cburch.logisim.pcomp.PortSide;
import com.cburch.logisim.pcomp.PortSignature;

/**
 * Peler Edition. The words the component windows put on the things {@code com.cburch.logisim.pcomp}
 * describes.
 *
 * <p>That package works out what a component's ports are and how two versions of one differ; it
 * deliberately says none of it in a language. Keeping the phrasing here is what lets the comparison
 * be tested without a locale, and what stops the same four side names from being spelled out in
 * each of the two windows that show them.
 */
final class PcompText {
  private PcompText() {}

  /** A side, named the way the layout window's table names it. */
  static String sideName(PortSide side) {
    if (side == null) return "";
    return switch (side) {
      case LEFT -> S.get("pcompSideLeft");
      case RIGHT -> S.get("pcompSideRight");
      case TOP -> S.get("pcompSideTop");
      case BOTTOM -> S.get("pcompSideBottom");
    };
  }

  /** Where a port sits, as "left 3" rather than "LEFT 2" -- slots are counted from one out here. */
  static String placeOf(PortSignature port) {
    return S.get("pcompPlaceAt", sideName(port.side()), Integer.toString(port.slot() + 1));
  }

  /** One difference between two versions of a component, in a line a user can act on. */
  static String describe(PortSignature.Difference difference) {
    return switch (difference.kind()) {
      case ADDED -> S.get("pcompDiffAdded", difference.name(), placeOf(difference.now()));
      case REMOVED -> S.get("pcompDiffRemoved", difference.name());
      case MOVED -> S.get("pcompDiffMoved", difference.name(),
          placeOf(difference.was()), placeOf(difference.now()));
      case DIRECTION -> S.get("pcompDiffDirection", difference.name(),
          S.get(difference.now().input() ? "pcompDirectionInput" : "pcompDirectionOutput"));
      case WIDTH -> S.get("pcompDiffWidth", difference.name(),
          Integer.toString(difference.was().width()), Integer.toString(difference.now().width()));
    };
  }
}
