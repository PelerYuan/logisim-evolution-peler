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
import com.cburch.logisim.std.annotate.AnnotationAnchorTracker;
import com.cburch.logisim.std.annotate.AnnotationAttributes;
import com.cburch.logisim.std.base.Text;
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

  // Grid-snapped offsets from the endpoint to the note: one grid square up, one to the side.
  // Deliberately much tighter than the component tool's gap -- a wire endpoint is a point, not a
  // body, so there's nothing to clear, and a far-flung note reads as belonging to nothing in
  // particular. Diagonal rather than straight up so the note never sits on the wire itself.
  private static final int OFFSET_Y = -10;
  private static final int OFFSET_X = 10;

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
    Location nearestEnd = null;
    var best = (long) ANCHOR_SNAP_RADIUS * ANCHOR_SNAP_RADIUS;
    for (final var wire : circ.getWires()) {
      for (final var end : new Location[] {wire.getEnd0(), wire.getEnd1()}) {
        final var dx = (long) end.getX() - loc.getX();
        final var dy = (long) end.getY() - loc.getY();
        final var distSq = dx * dx + dy * dy;
        if (distSq <= best) {
          best = distSq;
          nearestWire = wire;
          nearestEnd = end;
        }
      }
    }
    if (nearestWire == null) return null;

    // If that endpoint sits on a component's pin, anchor to the COMPONENT rather than the wire.
    // A component survives a move as one identifiable replacement whose pins get recomputed; a
    // wire does not -- dragging a component re-routes its wires into fresh segments, and a note
    // chasing "the wire it was on" is how notes ended up stranded mid-canvas. See
    // AnnotationAnchorTracker.pinAnchorPoint.
    final var owner = pinOwnerAt(circ, nearestEnd);
    return (owner != null) ? owner : nearestWire;
  }

  private static Component pinOwnerAt(Circuit circ, Location end) {
    for (final var comp : circ.getNonWires()) {
      for (final var data : comp.getEnds()) {
        if (data.getLocation().equals(end)) return comp;
      }
    }
    return null;
  }

  @Override
  Location anchorLocationOf(Component target, Location clickLoc) {
    return (target instanceof Wire w)
        ? AnnotationAnchorTracker.wireAnchorPoint(w, clickLoc)
        : AnnotationAnchorTracker.pinAnchorPoint(target, clickLoc);
  }

  @Override
  String anchorKind() {
    return AnnotationAttributes.KIND_POINT;
  }

  @Override
  Location placementFor(Component anchor, Location anchorLoc) {
    // Not grid-snapped, matching AnnotateComponentTool -- wire endpoints are already on-grid, so
    // snapping would be a no-op here anyway, and keeping both tools on the same rule means the
    // offsets stay exactly as written instead of quietly rounding.
    final var x = anchorLoc.getX() + OFFSET_X * sideOf(anchor, anchorLoc);
    final var y = anchorLoc.getY() + OFFSET_Y;
    return Location.create(x, y, false);
  }

  @Override
  AttributeOption horizontalAlignFor(Component anchor, Location anchorLoc) {
    // Text runs outward from the endpoint: placed to its right it's left-aligned, and vice versa.
    // Without this the note would be centered on the offset point and half of it would fall back
    // across the endpoint (and the wire) it's offset away from.
    return Text.ATTR_HALIGN.parse(sideOf(anchor, anchorLoc) > 0 ? "left" : "right");
  }

  /**
   * Which side of the endpoint to put the note on: {@code +1} for up-and-right, {@code -1} for
   * up-and-left. Follows the direction the wire runs -- a wire heading right gets its note to the
   * upper right, a wire heading left to the upper left -- so the note reads as belonging to that
   * run of wire and trails off in the same direction the eye is already travelling.
   */
  private static int sideOf(Component anchor, Location anchorLoc) {
    if (anchor instanceof Wire w) {
      final var other = w.getEnd0().equals(anchorLoc) ? w.getEnd1() : w.getEnd0();
      if (other.getX() > anchorLoc.getX()) return 1;
      if (other.getX() < anchorLoc.getX()) return -1;
    }
    return 1; // vertical wire (or a non-wire anchor): neither side is implied, pick the right.
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
