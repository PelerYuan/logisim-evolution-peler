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
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentDrawContext;
import com.cburch.logisim.comp.ComponentUserEvent;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.gui.main.Canvas;
import com.cburch.logisim.gui.main.SelectionActions;
import com.cburch.logisim.proj.Action;
import com.cburch.logisim.std.annotate.Annotation;
import com.cburch.logisim.std.annotate.AnnotationAnchorTracker;
import com.cburch.logisim.std.annotate.AnnotationAttributes;
import com.cburch.logisim.util.StringUtil;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

/**
 * Peler Edition Feature 5: click a component or wire endpoint to attach a free-text annotation to
 * it, anchored so it moves/deletes along with its target (see {@link AnnotationAnchorTracker}).
 * Structurally a close cousin of {@link TextTool} -- same caret-editing machinery via {@link
 * TextEditable} -- but placement requires clicking an existing target rather than anywhere on the
 * canvas, and every placement is registered with the target circuit's anchor tracker.
 *
 * <p>See docs/peler-edition/ROADMAP.md, Feature 5.
 */
public class AnnotateTool extends Tool {
  /**
   * Unique identifier of the tool, used as reference in project files. Do NOT change as it will
   * prevent project files from loading.
   *
   * <p>Identifier value must MUST be unique string among all tools.
   */
  public static final String _ID = "Annotate Tool";

  // How close a click needs to land to a wire's endpoint to count as "clicked that endpoint",
  // matching WiringTool's own PIN_SNAP_RADIUS (Feature 3) -- kept as an independent constant
  // rather than reusing that one directly since the two tools have no other coupling.
  private static final int ANCHOR_SNAP_RADIUS = 20;

  // Default placement offset from the anchor point: straight up, so the note floats above its
  // target per the original request, then grid-snapped like everything else placed on canvas.
  private static final int DEFAULT_OFFSET_Y = -30;

  private static final Cursor cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR);

  private final MyListener listener = new MyListener();
  private Caret caret;
  private boolean caretCreatingText;
  private Canvas caretCanvas;
  private Circuit caretCircuit;
  private Component caretComponent;
  private Component pendingAnchor; // only meaningful while caretCreatingText

  @Override
  public void deselect(Canvas canvas) {
    if (caret != null) {
      caret.stopEditing();
      caret = null;
    }
  }

  @Override
  public void draw(Canvas canvas, ComponentDrawContext context) {
    if (caret != null) caret.draw(context.getGraphics());
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof AnnotateTool;
  }

  @Override
  public int hashCode() {
    return AnnotateTool.class.hashCode();
  }

  @Override
  public Cursor getCursor() {
    return cursor;
  }

  @Override
  public String getDescription() {
    return S.get("annotateToolDesc");
  }

  @Override
  public String getDisplayName() {
    return S.get("annotateTool");
  }

  @Override
  public void keyPressed(Canvas canvas, KeyEvent e) {
    if (caret != null) {
      caret.keyPressed(e);
      canvas.getProject().repaintCanvas();
    }
  }

  @Override
  public void keyReleased(Canvas canvas, KeyEvent e) {
    if (caret != null) {
      caret.keyReleased(e);
      canvas.getProject().repaintCanvas();
    }
  }

  @Override
  public void keyTyped(Canvas canvas, KeyEvent e) {
    if (caret != null) {
      caret.keyTyped(e);
      canvas.getProject().repaintCanvas();
    }
  }

  @Override
  public void mouseDragged(Canvas canvas, Graphics g, MouseEvent e) {
    if (caret != null) {
      caret.mouseDragged(e);
      canvas.getProject().repaintCanvas();
    }
  }

  @Override
  public void mousePressed(Canvas canvas, Graphics g, MouseEvent e) {
    final var proj = canvas.getProject();
    final var circ = canvas.getCircuit();
    AnnotationAnchorTracker.getOrAttach(proj, circ);

    final var act = SelectionActions.dropAll(canvas.getSelection());
    proj.doAction(act);

    if (!proj.getLogisimFile().contains(circ)) {
      if (caret != null) caret.cancelEditing();
      canvas.setErrorMessage(S.getter("cannotModifyError"));
      return;
    }

    if (caret != null) {
      if (caret.getBounds(g).contains(e.getX(), e.getY())) {
        caret.mousePressed(e);
        proj.repaintCanvas();
        return;
      }
      caret.stopEditing();
    }
    // caret is null at this point

    final var x = e.getX();
    final var y = e.getY();
    final var loc = Location.create(x, y, false);
    final var event = new ComponentUserEvent(canvas, x, y);

    // Clicking an existing Annotation re-opens it for editing, same as TextTool re-opening Text.
    for (final var comp : circ.getAllContaining(loc, g)) {
      if (!(comp.getFactory() instanceof Annotation)) continue;
      final var editable = (TextEditable) comp.getFeature(TextEditable.class);
      if (editable == null) continue;
      caret = editable.getTextCaret(event);
      if (caret != null) {
        proj.getFrame().viewComponentAttributes(circ, comp);
        caretComponent = comp;
        caretCreatingText = false;
        pendingAnchor = null;
        break;
      }
    }

    // Otherwise, look for something to anchor a brand new annotation to.
    if (caret == null) {
      final var anchor = findAnchorTarget(circ, loc, g);
      if (anchor == null) {
        canvas.setErrorMessage(S.getter("annotateNoTargetHint"));
      } else {
        final var anchorLoc = anchorLocationOf(anchor, loc);
        final var placeX = Canvas.snapXToGrid(anchorLoc.getX());
        final var placeY = Canvas.snapYToGrid(anchorLoc.getY() + DEFAULT_OFFSET_Y);
        final var placeLoc = Location.create(placeX, placeY, false);

        final var attrs = (AnnotationAttributes) Annotation.FACTORY.createAttributeSet();
        attrs.setValue(AnnotationAttributes.ANCHOR_LOC, anchorLoc);
        caretComponent = Annotation.FACTORY.createComponent(placeLoc, attrs);
        caretCreatingText = true;
        pendingAnchor = anchor;

        final var editable = (TextEditable) caretComponent.getFeature(TextEditable.class);
        if (editable != null) {
          caret = editable.getTextCaret(event);
          proj.getFrame().viewComponentAttributes(circ, caretComponent);
        }
      }
    }

    if (caret != null) {
      caretCanvas = canvas;
      caretCircuit = circ;
      caret.addCaretListener(listener);
    }
    proj.repaintCanvas();
  }

  @Override
  public void mouseReleased(Canvas canvas, Graphics g, MouseEvent e) {
    if (caret != null) {
      caret.mouseReleased(e);
      canvas.getProject().repaintCanvas();
    }
  }

  @Override
  public void paintIcon(ComponentDrawContext c, int x, int y) {
    // Minimal placeholder icon, same style/effort as QuickRotateTool.paintIcon and
    // Annotation.paintIcon: a little sticky-note shape, plain line primitives.
    final var g = (Graphics2D) c.getGraphics();
    final var oldColor = g.getColor();
    g.drawRect(x + 2, y + 2, 12, 12);
    g.fillPolygon(new int[] {x + 10, x + 14, x + 14}, new int[] {y + 2, y + 2, y + 6}, 3);
    g.drawLine(x + 4, y + 7, x + 10, y + 7);
    g.drawLine(x + 4, y + 10, x + 10, y + 10);
    g.setColor(oldColor);
  }

  /**
   * Finds a click target to anchor a new annotation to: a wire endpoint within {@link
   * #ANCHOR_SNAP_RADIUS} pixels (checked first, since it's a small precise target easily missed
   * otherwise), else any non-wire, non-Annotation component whose painted shape contains the
   * click. Returns {@code null} if neither is found -- empty canvas is not a valid target, per the
   * original request ("click a component or wire endpoint").
   */
  private Component findAnchorTarget(Circuit circ, Location loc, Graphics g) {
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
    if (nearestWire != null) return nearestWire;

    for (final var comp : circ.getAllContaining(loc, g)) {
      if (comp.getFactory() instanceof Annotation) continue;
      return comp;
    }
    return null;
  }

  private static Location anchorLocationOf(Component target, Location clickLoc) {
    if (target instanceof Wire w) {
      final var d0 = sqDist(w.getEnd0(), clickLoc);
      final var d1 = sqDist(w.getEnd1(), clickLoc);
      return d0 <= d1 ? w.getEnd0() : w.getEnd1();
    }
    return target.getLocation();
  }

  private static long sqDist(Location a, Location b) {
    final var dx = (long) a.getX() - b.getX();
    final var dy = (long) a.getY() - b.getY();
    return dx * dx + dy * dy;
  }

  private class MyListener implements CaretListener {
    @Override
    public void editingCanceled(CaretEvent e) {
      if (e.getCaret() != caret) {
        e.getCaret().removeCaretListener(this);
        return;
      }
      caret.removeCaretListener(this);
      reset();
    }

    @Override
    public void editingStopped(CaretEvent e) {
      if (e.getCaret() != caret) {
        e.getCaret().removeCaretListener(this);
        return;
      }
      caret.removeCaretListener(this);

      final var circ = caretCircuit;
      final var comp = caretComponent;
      final var wasCreating = caretCreatingText;
      final var anchor = pendingAnchor;
      final var proj = caretCanvas.getProject();

      final var val = caret.getText();
      final var isEmpty = StringUtil.isNullOrEmpty(val);
      Action a = null;
      if (wasCreating) {
        if (!isEmpty) {
          final var xn = new CircuitMutation(circ);
          xn.add(comp);
          a = xn.toAction(S.getter("addComponentAction", Annotation.FACTORY.getDisplayGetter()));
        }
        // empty text on a brand-new annotation -> just don't add it, matching TextTool.
      } else {
        if (isEmpty) {
          final var xn = new CircuitMutation(circ);
          xn.remove(comp);
          a = xn.toAction(S.getter("removeComponentAction", Annotation.FACTORY.getDisplayGetter()));
          AnnotationAnchorTracker.getOrAttach(proj, circ).forget(comp);
        } else {
          final var editable = (TextEditable) comp.getFeature(TextEditable.class);
          if (editable != null) a = editable.getCommitAction(circ, e.getOldText(), val);
        }
      }

      reset();
      if (a != null) proj.doAction(a);
      if (wasCreating && !isEmpty && anchor != null) {
        AnnotationAnchorTracker.getOrAttach(proj, circ).registerAnchor(comp, anchor);
      }
    }

    private void reset() {
      caretCircuit = null;
      caretComponent = null;
      caretCreatingText = false;
      pendingAnchor = null;
      caret = null;
      caretCanvas = null;
    }
  }
}
