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

import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentDrawContext;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.gui.main.Canvas;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.proj.Action;
import com.cburch.logisim.util.GraphicsUtil;
import com.cburch.logisim.util.StringGetter;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

public class WiringTool extends Tool {
  /**
   * Unique identifier of the tool, used as reference in project files. Do NOT change as it will
   * prevent project files from loading.
   *
   * <p>Identifier value must MUST be unique string among all tools.
   */
  public static final String _ID = "Wiring Tool";

  private static final Cursor cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);

  private static final int HORIZONTAL = 1;
  private static final int VERTICAL = 2;

  // Peler Edition Feature 3: max distance (pixels) from a component pin at which a wire endpoint
  // snaps to that pin instead of the drawing grid. Two grid units: verified hands-on (2026-08-07)
  // that one grid unit (10px) technically worked but felt too tight to be noticeable/useful in
  // practice -- widened so the snap actually feels "magnetic" rather than requiring the cursor to
  // already be almost exactly on the pin.
  private static final int PIN_SNAP_RADIUS = 20;

  private boolean exists = false;
  private boolean inCanvas = false;
  private Location start = Location.create(0, 0, true);
  private Location cur = Location.create(0, 0, true);
  private boolean hasDragged = false;
  private boolean startShortening = false;
  private Wire shortening = null;
  private Action lastAction = null;
  private int direction = 0;
  // Peler Edition Feature 3: the pin the cursor is currently snapped to, if any -- drawn as a
  // hover highlight in draw() so snapping to a pin is never silent/surprising.
  private Location snappedPin = null;

  public WiringTool() {
    super.select(null);
  }

  private Wire checkForRepairs(Canvas canvas, Wire w, Location end) {
    // don't repair a short wire to nothing
    if (w.getLength() <= 10) return w;
    if (!canvas.getCircuit().getNonWires(end).isEmpty()) return w;

    int delta = (end.equals(w.getEnd0()) ? 10 : -10);
    Location cand;
    if (w.isVertical()) {
      cand = Location.create(end.getX(), end.getY() + delta, true);
    } else {
      cand = Location.create(end.getX() + delta, end.getY(), true);
    }

    for (final var comp : canvas.getCircuit().getNonWires(cand)) {
      if (comp.getBounds().contains(end, 2)) {
        final var repair = (WireRepair) comp.getFeature(WireRepair.class);
        if (repair != null && repair.shouldRepairWire(new WireRepairData(w, cand))) {
          w = Wire.create(w.getOtherEnd(end), cand);
          canvas.repaint(end.getX() - 13, end.getY() - 13, 26, 26);
          return w;
        }
      }
    }
    return w;
  }

  private boolean computeMove(int newX, int newY) {
    if (cur.getX() == newX && cur.getY() == newY) return false;
    final var start = this.start;
    if (direction == 0) {
      if (newX != start.getX()) direction = HORIZONTAL;
      else if (newY != start.getY()) direction = VERTICAL;
    } else if (direction == HORIZONTAL && newX == start.getX()) {
      if (newY == start.getY()) direction = 0;
      else direction = VERTICAL;
    } else if (direction == VERTICAL && newY == start.getY()) {
      if (newX == start.getX()) direction = 0;
      else direction = HORIZONTAL;
    }
    return true;
  }

  @Override
  public void draw(Canvas canvas, ComponentDrawContext context) {
    final var g = context.getGraphics();
    if (exists) {
      var e0 = start;
      var e1 = cur;
      final var shortenBefore = willShorten(start, cur);
      if (shortenBefore != null) {
        final var shorten = getShortenResult(shortenBefore, start, cur);
        if (shorten == null) {
          return;
        } else {
          e0 = shorten.getEnd0();
          e1 = shorten.getEnd1();
        }
      }
      final var x0 = e0.getX();
      final var y0 = e0.getY();
      final var x1 = e1.getX();
      final var y1 = e1.getY();

      g.setColor(Color.BLACK);
      GraphicsUtil.switchToWidth(g, 3);
      if (direction == HORIZONTAL) {
        if (x0 != x1) g.drawLine(x0, y0, x1, y0);
        if (y0 != y1) g.drawLine(x1, y0, x1, y1);
      } else if (direction == VERTICAL) {
        if (y0 != y1) g.drawLine(x0, y0, x0, y1);
        if (x0 != x1) g.drawLine(x0, y1, x1, y1);
      }
    } else if (AppPreferences.ADD_SHOW_GHOSTS.getBoolean() && inCanvas) {
      g.setColor(Color.GRAY);
      g.fillOval(cur.getX() - 2, cur.getY() - 2, 5, 5);
    }
    // Peler Edition Feature 3: highlight the pin a wire endpoint is currently snapped to, drawn
    // regardless of whether a wire is mid-drag, so snapping is always visible before you commit.
    // Sized/weighted (2026-08-07, after hands-on testing) to actually catch the eye rather than
    // blend into the grid dots -- a soft outer halo plus a bold ring, not just a thin 8px circle.
    if (snappedPin != null && inCanvas) {
      final var oldColor = g.getColor();
      final var x = snappedPin.getX();
      final var y = snappedPin.getY();
      g.setColor(new Color(0, 200, 0, 60));
      g.fillOval(x - 9, y - 9, 18, 18);
      g.setColor(new Color(0, 170, 0));
      GraphicsUtil.switchToWidth(g, 3);
      g.drawOval(x - 6, y - 6, 12, 12);
      g.setColor(oldColor);
      GraphicsUtil.switchToWidth(g, 1);
    }
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof WiringTool;
  }

  @Override
  public Cursor getCursor() {
    return cursor;
  }

  @Override
  public String getDescription() {
    return S.get("wiringToolDesc");
  }

  @Override
  public String getDisplayName() {
    return S.get("wiringTool");
  }

  @Override
  public Set<Component> getHiddenComponents(Canvas canvas) {
    final var shorten = willShorten(start, cur);
    return (shorten != null) ? Collections.singleton(shorten) : null;
  }

  private Wire getShortenResult(Wire shorten, Location drag0, Location drag1) {
    if (shorten == null) return null;

    Location e0;
    Location e1;
    if (shorten.endsAt(drag0)) {
      e0 = drag1;
      e1 = shorten.getOtherEnd(drag0);
    } else if (shorten.endsAt(drag1)) {
      e0 = drag0;
      e1 = shorten.getOtherEnd(drag1);
    } else {
      return null;
    }
    return e0.equals(e1) ? null : Wire.create(e0, e1);
  }

  @Override
  public int hashCode() {
    return WiringTool.class.hashCode();
  }

  @Override
  public void keyPressed(Canvas canvas, KeyEvent event) {
    if (event.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
      if (lastAction != null && canvas.getProject().getLastAction() == lastAction) {
        canvas.getProject().undoAction();
        lastAction = null;
      }
    }
  }

  @Override
  public void mouseDragged(Canvas canvas, Graphics g, MouseEvent e) {
    if (exists) {
      snapToPinOrGrid(canvas, e);
      int curX = e.getX();
      int curY = e.getY();
      if (!computeMove(curX, curY)) return;
      hasDragged = true;

      final var rect = new Rectangle();
      rect.add(start.getX(), start.getY());
      rect.add(cur.getX(), cur.getY());
      rect.add(curX, curY);
      rect.grow(3, 3);

      cur = Location.create(curX, curY, true);
      super.mouseDragged(canvas, g, e);

      Wire shorten = null;
      if (startShortening) {
        for (final var w : canvas.getCircuit().getWires(start)) {
          if (w.contains(cur)) {
            shorten = w;
            break;
          }
        }
      }
      if (shorten == null) {
        for (final var w : canvas.getCircuit().getWires(cur)) {
          if (w.contains(start)) {
            shorten = w;
            break;
          }
        }
      }
      shortening = shorten;

      canvas.repaint(rect);
    }
  }

  @Override
  public void mouseEntered(Canvas canvas, Graphics g, MouseEvent e) {
    inCanvas = true;
    canvas.getProject().repaintCanvas();
  }

  @Override
  public void mouseExited(Canvas canvas, Graphics g, MouseEvent e) {
    inCanvas = false;
    snappedPin = null;
    canvas.getProject().repaintCanvas();
  }

  @Override
  public void mouseMoved(Canvas canvas, Graphics g, MouseEvent e) {
    if (exists) {
      mouseDragged(canvas, g, e);
    } else {
      snapToPinOrGrid(canvas, e);
      inCanvas = true;
      final var curX = e.getX();
      final var curY = e.getY();
      if (cur.getX() != curX || cur.getY() != curY) {
        cur = Location.create(curX, curY, true);
      }
      canvas.getProject().repaintCanvas();
    }
  }

  @Override
  public void mousePressed(Canvas canvas, Graphics g, MouseEvent e) {
    if (!canvas.getProject().getLogisimFile().contains(canvas.getCircuit())) {
      exists = false;
      canvas.setErrorMessage(S.getter("cannotModifyError"));
      return;
    }
    snapToPinOrGrid(canvas, e);
    start = Location.create(e.getX(), e.getY(), true);
    cur = start;
    exists = true;
    hasDragged = false;

    startShortening = !canvas.getCircuit().getWires(start).isEmpty();
    shortening = null;

    super.mousePressed(canvas, g, e);
    canvas.getProject().repaintCanvas();
  }

  @Override
  public void mouseReleased(Canvas canvas, Graphics g, MouseEvent e) {
    if (!exists) return;

    snapToPinOrGrid(canvas, e);
    final var curX = e.getX();
    final var curY = e.getY();
    if (computeMove(curX, curY)) {
      cur = Location.create(curX, curY, true);
    }
    if (hasDragged) {
      exists = false;
      super.mouseReleased(canvas, g, e);

      final var wires = new ArrayList<Wire>(2);
      if (cur.getY() == start.getY() || cur.getX() == start.getX()) {
        var wire = Wire.create(cur, start);
        wire = checkForRepairs(canvas, wire, wire.getEnd0());
        wire = checkForRepairs(canvas, wire, wire.getEnd1());
        if (performShortening(canvas, start, cur)) return;
        if (wire.getLength() > 0) wires.add(wire);
      } else {
        Location m;
        if (direction == HORIZONTAL) {
          m = Location.create(cur.getX(), start.getY(), true);
        } else {
          m = Location.create(start.getX(), cur.getY(), true);
        }
        var wire0 = Wire.create(start, m);
        var wire1 = Wire.create(m, cur);
        wire0 = checkForRepairs(canvas, wire0, start);
        wire1 = checkForRepairs(canvas, wire1, cur);
        if (wire0.getLength() > 0) wires.add(wire0);
        if (wire1.getLength() > 0) wires.add(wire1);
      }
      if (!wires.isEmpty()) {
        final var mutation = new CircuitMutation(canvas.getCircuit());
        mutation.addAll(wires);
        final var desc =
            (wires.size() == 1) ? S.getter("addWireAction") : S.getter("addWiresAction");
        final var act = mutation.toAction(desc);
        canvas.getProject().doAction(act);
        lastAction = act;
      }
    }
  }

  @Override
  public void paintIcon(ComponentDrawContext c, int x, int y) {
    final var g2 = (Graphics2D) c.getGraphics().create();
    g2.translate(x, y);
    final int[] points = {3, 13, 8, 13, 8, 3, 13, 3};
    g2.setStroke(new BasicStroke(AppPreferences.getScaled(2)));
    for (var i = 0; i < points.length - 2; i += 2)
      g2.drawLine(
          AppPreferences.getScaled(points[i]),
          AppPreferences.getScaled(points[i + 1]),
          AppPreferences.getScaled(points[i + 2]),
          AppPreferences.getScaled(points[i + 3]));
    g2.setColor(Value.trueColor);
    final var wh = AppPreferences.getScaled(5);
    g2.fillOval(AppPreferences.getScaled(1), AppPreferences.getScaled(11), wh, wh);
    g2.setColor(Value.unknownColor);
    g2.fillOval(AppPreferences.getScaled(11), AppPreferences.getScaled(1), wh, wh);
    g2.dispose();
  }

  private boolean performShortening(Canvas canvas, Location drag0, Location drag1) {
    final var shorten = willShorten(drag0, drag1);
    if (shorten == null) return false;
    final var xn = new CircuitMutation(canvas.getCircuit());
    StringGetter actName;
    final var result = getShortenResult(shorten, drag0, drag1);
    if (result == null) {
      xn.remove(shorten);
      actName = S.getter("removeComponentAction", shorten.getFactory().getDisplayGetter());
    } else {
      xn.replace(shorten, result);
      actName = S.getter("shortenWireAction");
    }
    canvas.getProject().doAction(xn.toAction(actName));
    return true;
  }

  private void reset() {
    exists = false;
    inCanvas = false;
    start = Location.create(0, 0, true);
    cur = Location.create(0, 0, true);
    startShortening = false;
    shortening = null;
    direction = 0;
    snappedPin = null;
  }

  /**
   * Peler Edition Feature 3: snaps {@code e} to the nearest component pin within
   * {@link #PIN_SNAP_RADIUS} pixels, if {@link AppPreferences#WIRE_AUTO_SNAP} is on and one
   * exists; otherwise falls back to the normal drawing-grid snap. Updates {@link #snappedPin} for
   * the hover highlight either way.
   *
   * <p>Pin-snap is skipped whenever the plain grid-snapped cursor position already coincides with
   * an existing wire endpoint ({@link #nearsExistingWireEndpoint}) -- otherwise, in a dense
   * circuit, pin-snap could pull the click toward an unrelated component's pin instead of the
   * dangling wire stub the user is actually trying to grab, silently defeating the pre-existing
   * wire-shortening gesture ({@link #startShortening}/{@link #willShorten}). Grabbing/shortening
   * an existing wire always takes priority over snapping to a nearby pin.
   */
  private void snapToPinOrGrid(Canvas canvas, MouseEvent e) {
    if (AppPreferences.WIRE_AUTO_SNAP.getBoolean() && !nearsExistingWireEndpoint(canvas, e)) {
      final var pin = findNearestPin(canvas, e.getX(), e.getY());
      if (pin != null) {
        e.translatePoint(pin.getX() - e.getX(), pin.getY() - e.getY());
        snappedPin = pin;
        return;
      }
    }
    snappedPin = null;
    Canvas.snapToGrid(e);
  }

  /**
   * True if the raw cursor position in {@code e}, once grid-snapped, already lands exactly on an
   * existing wire's endpoint. Computed without mutating {@code e} (unlike {@link
   * Canvas#snapToGrid}), using the same {@link Canvas#snapXToGrid}/{@link Canvas#snapYToGrid}
   * math every other grid-snap in this class already relies on.
   */
  private boolean nearsExistingWireEndpoint(Canvas canvas, MouseEvent e) {
    final var gridLoc = Location.create(
        Canvas.snapXToGrid(e.getX()), Canvas.snapYToGrid(e.getY()), false);
    return !canvas.getCircuit().getWires(gridLoc).isEmpty();
  }

  /**
   * Finds the closest component pin to raw cursor position {@code (x, y)}, considering only pins
   * belonging to components whose bounds (expanded by {@link #PIN_SNAP_RADIUS}, a cheap
   * broad-phase filter) contain the cursor, then only returning it if it's genuinely within
   * {@link #PIN_SNAP_RADIUS} pixels. Returns {@code null} if nothing qualifies. Wires themselves
   * are deliberately excluded -- this snaps to component pins specifically, per the original
   * request (see docs/peler-edition/ROADMAP.md, Feature 3).
   */
  private Location findNearestPin(Canvas canvas, int x, int y) {
    Location best = null;
    var bestDistSq = Long.MAX_VALUE;
    for (final var comp : canvas.getCircuit().getNonWires()) {
      final var bds = comp.getBounds().expand(PIN_SNAP_RADIUS);
      if (!bds.contains(x, y)) continue;
      for (final var end : comp.getEnds()) {
        final var loc = end.getLocation();
        final long dx = loc.getX() - x;
        final long dy = loc.getY() - y;
        final var distSq = dx * dx + dy * dy;
        if (distSq < bestDistSq) {
          bestDistSq = distSq;
          best = loc;
        }
      }
    }
    final var radiusSq = (long) PIN_SNAP_RADIUS * PIN_SNAP_RADIUS;
    return (best != null && bestDistSq <= radiusSq) ? best : null;
  }

  void resetClick() {
    exists = false;
  }

  @Override
  public void select(Canvas canvas) {
    super.select(canvas);
    lastAction = null;
    reset();
  }

  private Wire willShorten(Location drag0, Location drag1) {
    final var shorten = shortening;
    if (shorten == null) {
      return null;
    } else if (shorten.endsAt(drag0) || shorten.endsAt(drag1)) {
      return shorten;
    }
    return null;
  }
}
