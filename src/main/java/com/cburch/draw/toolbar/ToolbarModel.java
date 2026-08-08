/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.draw.toolbar;

import java.util.List;

public interface ToolbarModel {
  void addToolbarModelListener(ToolbarModelListener listener);

  List<ToolbarItem> getItems();

  boolean isSelected(ToolbarItem item);

  void itemSelected(ToolbarItem item);

  /**
   * Peler Edition: a toolbar item was double-clicked. Defaults to doing nothing, so the several
   * toolbars that have no use for the gesture (simulation, appearance, HDL, toolbox) need no
   * change; {@code LayoutToolbarModel} overrides it to arm continuous placement, matching what
   * double-clicking the same component in the toolbox tree already does.
   *
   * <p>The first click of the pair still arrives as an ordinary {@link #itemSelected}, so a
   * double-click both selects the tool and arms it.
   */
  default void itemDoubleClicked(ToolbarItem item) {
    // no-op implementation
  }

  void removeToolbarModelListener(ToolbarModelListener listener);
}
