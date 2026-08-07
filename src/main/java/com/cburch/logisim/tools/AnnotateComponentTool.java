/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.tools;

import static com.cburch.logisim.tools.Strings.S;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentDrawContext;
import com.cburch.logisim.data.AttributeOption;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.std.annotate.Annotation;
import com.cburch.logisim.std.annotate.AnnotationAnchorTracker;
import com.cburch.logisim.std.base.Text;
import com.cburch.logisim.util.StringGetter;
import java.awt.Graphics;

/**
 * Peler Edition Feature 5: click a component's body to attach a free-text annotation directly
 * above it. See {@link AbstractAnnotateTool} for the shared machinery and the split rationale,
 * and {@link AnnotateWireTool} for the wire-endpoint counterpart.
 */
public class AnnotateComponentTool extends AbstractAnnotateTool {
  /**
   * Unique identifier of the tool, used as reference in project files. Do NOT change as it will
   * prevent project files from loading.
   *
   * <p>Identifier value must MUST be unique string among all tools.
   */
  public static final String _ID = "Annotate Component Tool";

  // Vertical gap between a component's top edge and the note placed above it. Same one grid
  // square the wire tool uses, so a schematic mixing both kinds of note reads as one consistent
  // band of annotation rather than two heights.
  private static final int OFFSET_Y = -10;

  @Override
  public boolean equals(Object other) {
    return other instanceof AnnotateComponentTool;
  }

  @Override
  public int hashCode() {
    return AnnotateComponentTool.class.hashCode();
  }

  @Override
  public String getDescription() {
    return S.get("annotateComponentToolDesc");
  }

  @Override
  public String getDisplayName() {
    return S.get("annotateComponentTool");
  }

  @Override
  Component findAnchorTarget(Circuit circ, Location loc, Graphics g) {
    for (final var comp : circ.getAllContaining(loc, g)) {
      if (comp instanceof Wire) continue;
      if (comp.getFactory() instanceof Annotation) continue;
      return comp;
    }
    return null;
  }

  @Override
  Location anchorLocationOf(Component target, Location clickLoc) {
    // The click location doesn't matter here -- a component anchor is always its bounding box's
    // top-left corner, regardless of where within its body the user happened to click.
    return AnnotationAnchorTracker.componentAnchorPoint(target);
  }

  @Override
  AttributeOption horizontalAlignFor(Component anchor, Location anchorLoc) {
    // Left-aligned, so the note's first character lines up with the component's left edge rather
    // than being centred over its middle. Centring looked unsettled across a row of differently
    // sized components; a shared left edge gives the annotations a column to hang off.
    return Text.ATTR_HALIGN.parse("left");
  }

  @Override
  Location placementFor(Component anchor, Location anchorLoc) {
    // Deliberately NOT grid-snapped. A component's bounding-box centre is frequently off-grid --
    // a 2-input gate is 50px wide anchored at an on-grid pin, putting its centre on a half-square
    // -- so snapping shifted centre-aligned text up to half a square sideways and the note visibly
    // sat off-centre above its component. Annotations carry no connectivity, and the factory
    // already declares setShouldSnap(false), so there is nothing for the grid to buy here.
    return Location.create(anchorLoc.getX(), anchorLoc.getY() + OFFSET_Y, false);
  }

  @Override
  StringGetter noTargetHint() {
    return S.getter("annotateComponentNoTargetHint");
  }

  @Override
  void paintKindGlyph(ComponentDrawContext c, int x, int y) {
    // A small filled square, standing in for "a component" next to the note.
    final var g = c.getGraphics();
    g.fillRect(x + 4, y + 6, 5, 5);
  }
}
