/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.tools.AddTool;

/**
 * Peler Edition. The toolbox entry for one installed component, labelled the way the user named it.
 *
 * <p>An ordinary {@code AddTool} is labelled by its factory, and a {@code SubcircuitFactory} is
 * labelled by its circuit's name -- which for a component is the derived {@code Name_vN}, spaces
 * folded to underscores so it survives {@code SyntaxChecker}. That is the right identity for a
 * project file to record and the wrong one to show a person, so the label is overridden here and
 * nothing else is.
 *
 * <p>{@code getName} is deliberately left alone. It is what {@code PcompCatalogLibrary} dedupes on
 * and what {@code <comp lib name>} resolves against, so it has to stay the circuit's name.
 *
 * <p>{@code cloneTool} is left alone too, so a copy dragged onto the toolbar is a plain {@code
 * AddTool} carrying the full attribute state the copy constructor preserves. It loses the pretty
 * label, which is a tooltip on an icon; keeping the label instead would mean rebuilding the tool
 * from its factory and dropping whatever the user had configured on it.
 */
public final class PcompTool extends AddTool {

  private final String displayName;

  public PcompTool(SubcircuitFactory factory, String displayName) {
    super(factory);
    this.displayName = displayName;
  }

  @Override
  public String getDisplayName() {
    return displayName;
  }
}
