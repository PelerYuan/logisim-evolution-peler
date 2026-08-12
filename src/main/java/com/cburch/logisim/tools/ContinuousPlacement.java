/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.tools;

import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.vhdl.base.VhdlEntity;

/**
 * Peler Edition Feature 10: the one place that decides whether picking a component keeps it armed.
 *
 * <p>A component can be picked from the toolbox tree, from the layout toolbar or from the component
 * finder, and before this existed each of the three decided for itself what a click meant. They had
 * drifted apart once already -- the toolbar had no continuous-placement gesture at all until
 * Feature 1 was revisited -- so the rule lives here and the three call it.
 */
public final class ContinuousPlacement {

  private ContinuousPlacement() {}

  /** True when a plain click on a toolbox entry or toolbar button should already keep placing. */
  public static boolean armedByClick() {
    return AppPreferences.PLACE_SINGLE_STICKY.equals(AppPreferences.PLACEMENT_MODE.get());
  }

  /** True when a double-click is what arms continuous placement, which is the default. */
  public static boolean armedByDoubleClick() {
    return AppPreferences.PLACE_DOUBLE_STICKY.equals(AppPreferences.PLACEMENT_MODE.get());
  }

  /**
   * Makes {@code tool} the project's current tool, and keeps it armed after each placement when
   * {@code continuous} is set.
   *
   * <p>Subcircuits and VHDL entities never go continuous whatever the setting says: double-clicking
   * one of those opens it for editing rather than arming it, so there is no gesture that could mean
   * "keep placing this" in the first place.
   *
   * @param continuous whether the tool should stay armed after it places something
   */
  public static void arm(Project proj, Tool tool, boolean continuous) {
    proj.setTool(tool);
    if (!continuous) return;
    if (tool instanceof AddTool addTool) {
      final var source = addTool.getFactory();
      if (source instanceof SubcircuitFactory || source instanceof VhdlEntity) return;
      addTool.setStickyPlace(true);
    } else if (tool instanceof AbstractAnnotateTool annotateTool) {
      annotateTool.setStickyAnnotate(true);
    }
  }
}
