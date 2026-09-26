/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.tools;

import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.pcomp.PcompLock;
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
   * Whether {@code tool} may be left armed after it places something.
   *
   * <p>A subcircuit and a VHDL entity may not: double-clicking one of those opens it for editing,
   * so the gesture that would arm continuous placement is already spoken for and there is nothing
   * left to mean "keep placing this".
   *
   * <p><b>A custom component is the exception.</b> It is a subcircuit underneath, but the door into
   * it is locked, so the explorer deliberately gives its double-click the meaning the gesture has
   * for a built-in component -- and if this method did not agree, that double-click would arm
   * nothing at all and the component would place exactly once. Which is what it did.
   *
   * <p>Locked rather than merely custom, because an unlocked component does open on a double-click,
   * and then the first paragraph applies to it like any other subcircuit.
   */
  public static boolean canStayArmed(Tool tool) {
    if (tool instanceof AbstractAnnotateTool) return true;
    if (!(tool instanceof AddTool addTool)) return false;
    final var source = addTool.getFactory();
    if (source instanceof VhdlEntity) return false;
    if (source instanceof SubcircuitFactory sub) {
      return PcompLock.blocksEntryInto(sub.getSubcircuit());
    }
    return true;
  }

  /**
   * Makes {@code tool} the project's current tool, and keeps it armed after each placement when
   * {@code continuous} is set and {@link #canStayArmed} allows it.
   *
   * @param continuous whether the tool should stay armed after it places something
   */
  public static void arm(Project proj, Tool tool, boolean continuous) {
    proj.setTool(tool);
    if (!continuous || !canStayArmed(tool)) return;
    if (tool instanceof AddTool addTool) {
      addTool.setStickyPlace(true);
    } else if (tool instanceof AbstractAnnotateTool annotateTool) {
      annotateTool.setStickyAnnotate(true);
    }
  }
}
