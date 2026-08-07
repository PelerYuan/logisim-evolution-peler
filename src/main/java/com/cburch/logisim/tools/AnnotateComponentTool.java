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
import com.cburch.logisim.data.Location;
import com.cburch.logisim.gui.main.Canvas;
import com.cburch.logisim.std.annotate.Annotation;
import com.cburch.logisim.std.annotate.AnnotationAnchorTracker;
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

  // Grid-snapped vertical gap between a component's top edge and the note placed above it.
  private static final int DEFAULT_OFFSET_Y = -30;

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
    // top-center, regardless of where within its body the user happened to click.
    return AnnotationAnchorTracker.componentAnchorPoint(target);
  }

  @Override
  Location placementFor(Location anchorLoc) {
    final var x = Canvas.snapXToGrid(anchorLoc.getX());
    final var y = Canvas.snapYToGrid(anchorLoc.getY() + DEFAULT_OFFSET_Y);
    return Location.create(x, y, false);
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
