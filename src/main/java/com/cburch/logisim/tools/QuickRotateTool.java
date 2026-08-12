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
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentDrawContext;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.gui.main.Canvas;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.std.base.BaseLibrary;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.event.MouseEvent;

/**
 * Peler Edition Feature 2: rotates the component under the cursor 90&deg; clockwise on a single
 * press, with no popup menu. Modeled closely on {@link MenuTool.MenuComponent}'s component
 * lookup (selection first, then circuit hit-test) and its {@code rotateRight} handler.
 *
 * <p>See {@code docs/peler-edition/ROADMAP.md}, Feature 2.
 */
public class QuickRotateTool extends Tool {
  /**
   * Unique identifier of the tool, used as reference in project files. Do NOT change as it will
   * prevent project files from loading.
   *
   * <p>Identifier value must MUST be unique string among all tools.
   */
  public static final String _ID = "Quick Rotate Tool";

  /**
   * Color for the one-time hint message (Task 6). {@code canvas.setErrorMessage} is not
   * exclusively for errors despite its name -- {@code SelectTool} already uses it for a
   * non-error transient "computing..." status message with a custom (non-red) color, which is
   * the same mechanism reused here; passing a non-default color is what keeps it from reading as
   * an error.
   */
  private static final Color HINT_COLOR = new Color(64, 96, 192);

  public QuickRotateTool() {}

  @Override
  public boolean equals(Object other) {
    return other instanceof QuickRotateTool;
  }

  @Override
  public int hashCode() {
    return QuickRotateTool.class.hashCode();
  }

  @Override
  public String getDescription() {
    return S.get("quickRotateToolDesc");
  }

  @Override
  public String getDisplayName() {
    return S.get("quickRotateTool");
  }

  /**
   * Peler Edition Feature 10: hands the press back to the ordinary component menu when the user has
   * turned quick rotation off.
   *
   * <p>Done here rather than by rewriting the mouse mapping because the mapping is not ours to
   * rewrite: it comes from {@code default.templ} and is then stored in each project file, so a
   * preference could only ever affect new projects, and every file made before the setting existed
   * would keep right-clicking to rotate with no way to say otherwise.
   *
   * @return true if the press was dealt with as a menu request and this tool should stop
   */
  private boolean deferToMenu(Canvas canvas, Graphics g, MouseEvent e) {
    if (!AppPreferences.QUICK_ROTATE_OFF.equals(AppPreferences.QUICK_ROTATE_MODE.get())) {
      return false;
    }
    final var base = canvas.getProject().getLogisimFile().getLibrary(BaseLibrary._ID);
    final var menu = (base == null) ? null : base.getTool(MenuTool._ID);
    if (menu != null) menu.mousePressed(canvas, g, e);
    // True either way: with rotation off, a right-click must never rotate, even if the menu tool
    // could not be found for some reason.
    return true;
  }

  @Override
  public void mousePressed(Canvas canvas, Graphics g, MouseEvent e) {
    final var proj = canvas.getProject();

    // A right-click while a component is armed for placement means "stop placing", not "rotate" --
    // for ordinary single-click placement just as much as for continuous (sticky) placement.
    // Canvas dispatches this mousePressed while the AddTool is still proj.getTool() (its own
    // temp-swap to this tool happens right after this call returns), so simply being an AddTool
    // here means something is armed. See AddTool.stopPlacement's Javadoc for why this can't be
    // done implicitly via the swap-and-restore mechanism alone.
    final var curTool = proj.getTool();
    if (curTool instanceof AddTool addTool && addTool.stopPlacement(canvas)) {
      return;
    }
    // Same deal for the annotate tools' continuous mode: right-click means "stop annotating".
    if (curTool instanceof AbstractAnnotateTool annotateTool && annotateTool.stopAnnotating(canvas)) {
      return;
    }

    // Checked after those two, not before: turning quick rotation off restores the component menu,
    // but it does not take away the escape hatch out of placement. "Esc, Enter or a right-click
    // stops" is what this edition documents, and it has to keep being true in every mode -- the
    // setting is about what a right-click does when nothing is armed.
    if (deferToMenu(canvas, g, e)) return;

    final Circuit circ = canvas.getCircuit();
    if (!proj.getLogisimFile().contains(circ)) {
      // Same "can this circuit be modified" guard used by AddTool/TextTool/WiringTool.
      canvas.setErrorMessage(S.getter("cannotModifyError"));
      return;
    }

    final var pt = Location.create(e.getX(), e.getY(), false);
    final var sel = proj.getSelection();
    final var inSel = sel.getComponentsContaining(pt, g);
    Component comp = null;
    if (!inSel.isEmpty()) {
      comp = inSel.iterator().next();
    } else {
      final var cl = circ.getAllContaining(pt, g);
      if (!cl.isEmpty()) {
        comp = cl.iterator().next();
      }
    }

    // No component under the cursor, or it has no facing to rotate: silently do nothing.
    if (comp == null || !comp.getAttributeSet().containsAttribute(StdAttr.FACING)) {
      return;
    }

    final var factory = comp.getFactory();
    final var oldAttrs = comp.getAttributeSet();
    final var newAttrs = (AttributeSet) oldAttrs.clone();
    final var facing = oldAttrs.getValue(StdAttr.FACING);
    // Feature 10: which way a right-click turns things. Everything below -- the re-pivot, the
    // replace, the undo entry -- is direction-agnostic, so this is the whole of the difference.
    final var anticlockwise =
        AppPreferences.QUICK_ROTATE_CCW.equals(AppPreferences.QUICK_ROTATE_MODE.get());
    newAttrs.setValue(StdAttr.FACING, anticlockwise ? facing.getLeft() : facing.getRight());

    // Pivot around the component's visual center rather than upstream's default of leaving the
    // anchor location untouched (which, for most components, sits at/near a pin rather than the
    // geometric center -- see docs/peler-edition/ROADMAP.md, Feature 2 follow-up). Both bounds
    // below are anchor-relative "offset" bounds, so the center delta is anchor-independent; it's
    // then rounded to the nearest grid point so the component (and its pins) stay grid-aligned.
    final var oldBounds = factory.getOffsetBounds(oldAttrs);
    final var newBounds = factory.getOffsetBounds(newAttrs);
    final var dx = Canvas.snapXToGrid(
        (oldBounds.getX() + oldBounds.getWidth() / 2)
            - (newBounds.getX() + newBounds.getWidth() / 2));
    final var dy = Canvas.snapYToGrid(
        (oldBounds.getY() + oldBounds.getHeight() / 2)
            - (newBounds.getY() + newBounds.getHeight() / 2));
    final var newLoc = comp.getLocation().translate(dx, dy);
    final var newComp = factory.createComponent(newLoc, newAttrs);

    // A component's location is fixed at construction (no in-place move), so re-pivoting means
    // replacing it outright -- same pattern WiringTool uses to shorten/replace a Wire in place.
    // Selection.java listens for CircuitEvent.TRANSACTION_DONE and walks the ReplacementMap, so a
    // component that was selected before rotating stays correctly selected (as the new instance)
    // afterward, and CircuitState transfers componentData (RAM/register contents etc.) across the
    // replace when the factory matches, so simulation state survives a rotate too.
    final var xn = new CircuitMutation(circ);
    xn.replace(comp, newComp);
    proj.doAction(xn.toAction(S.getter("rotateComponentAction", factory.getDisplayGetter())));

    if (!AppPreferences.SHOWN_QUICK_ROTATE_HINT.getBoolean()) {
      // Fire-and-forget: the rotate above has already happened, so this never blocks or delays
      // it. Shown at most once ever, persisted via the preference.
      canvas.setErrorMessage(S.getter("quickRotateHint"), HINT_COLOR);
      AppPreferences.SHOWN_QUICK_ROTATE_HINT.setBoolean(true);
    }
  }

  @Override
  public void paintIcon(ComponentDrawContext c, int x, int y) {
    // Minimal placeholder icon: a curved arrow suggesting the direction of rotation, drawn with
    // simple line segments in the style of WiringTool.paintIcon (no image resources needed).
    // Mirrored horizontally about x + 8 when the setting turns rotation the other way, so the
    // icon in the mouse-mapping list is not quietly telling the opposite of what a click does.
    final var g = c.getGraphics();
    final var oldColor = g.getColor();
    final var anticlockwise =
        AppPreferences.QUICK_ROTATE_CCW.equals(AppPreferences.QUICK_ROTATE_MODE.get());
    g.setColor(Color.BLACK);
    if (anticlockwise) {
      g.drawArc(x + 2, y + 2, 12, 12, 135, -270);
      g.drawLine(x + 3, y + 3, x + 1, y + 5);
      g.drawLine(x + 3, y + 3, x + 5, y + 6);
    } else {
      g.drawArc(x + 2, y + 2, 12, 12, 45, 270);
      g.drawLine(x + 13, y + 3, x + 15, y + 5);
      g.drawLine(x + 13, y + 3, x + 11, y + 6);
    }
    g.setColor(oldColor);
  }
}
