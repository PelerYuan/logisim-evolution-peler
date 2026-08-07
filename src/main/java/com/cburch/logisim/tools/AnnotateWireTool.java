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
import com.cburch.logisim.std.annotate.AnnotationAnchorTracker;
import com.cburch.logisim.util.StringGetter;
import java.awt.Graphics;

/**
 * Peler Edition Feature 5: click near a wire endpoint to attach a free-text annotation to it. See
 * {@link AbstractAnnotateTool} for the shared machinery and the split rationale, and {@link
 * AnnotateComponentTool} for the component counterpart.
 */
public class AnnotateWireTool extends AbstractAnnotateTool {
  /**
   * Unique identifier of the tool, used as reference in project files. Do NOT change as it will
   * prevent project files from loading.
   *
   * <p>Identifier value must MUST be unique string among all tools.
   */
  public static final String _ID = "Annotate Wire Tool";

  // How close a click needs to land to a wire's endpoint to count as "clicked that endpoint",
  // matching WiringTool's own PIN_SNAP_RADIUS (Feature 3) -- kept as an independent constant
  // rather than reusing that one directly since the two tools have no other coupling.
  private static final int ANCHOR_SNAP_RADIUS = 20;

  // Grid-snapped vertical gap between a wire endpoint and the note placed above it.
  private static final int DEFAULT_OFFSET_Y = -30;

  @Override
  public boolean equals(Object other) {
    return other instanceof AnnotateWireTool;
  }

  @Override
  public int hashCode() {
    return AnnotateWireTool.class.hashCode();
  }

  @Override
  public String getDescription() {
    return S.get("annotateWireToolDesc");
  }

  @Override
  public String getDisplayName() {
    return S.get("annotateWireTool");
  }

  @Override
  Component findAnchorTarget(Circuit circ, Location loc, Graphics g) {
    Wire nearestWire = null;
    var best = (long) ANCHOR_SNAP_RADIUS * ANCHOR_SNAP_RADIUS;
    for (final var wire : circ.getWires()) {
      for (final var end : new Location[] {wire.getEnd0(), wire.getEnd1()}) {
        final var dx = (long) end.getX() - loc.getX();
        final var dy = (long) end.getY() - loc.getY();
        final var distSq = dx * dx + dy * dy;
        if (distSq <= best) {
          best = distSq;
          nearestWire = wire;
        }
      }
    }
    return nearestWire;
  }

  @Override
  Location anchorLocationOf(Component target, Location clickLoc) {
    return AnnotationAnchorTracker.wireAnchorPoint((Wire) target, clickLoc);
  }

  @Override
  Location placementFor(Location anchorLoc) {
    final var x = Canvas.snapXToGrid(anchorLoc.getX());
    final var y = Canvas.snapYToGrid(anchorLoc.getY() + DEFAULT_OFFSET_Y);
    return Location.create(x, y, false);
  }

  @Override
  StringGetter noTargetHint() {
    return S.getter("annotateWireNoTargetHint");
  }

  @Override
  void paintKindGlyph(ComponentDrawContext c, int x, int y) {
    // A little zigzag, standing in for "a wire" next to the note.
    final var g = c.getGraphics();
    g.drawLine(x + 4, y + 11, x + 7, y + 8);
    g.drawLine(x + 7, y + 8, x + 9, y + 10);
  }
}
