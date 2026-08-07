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
    newAttrs.setValue(StdAttr.FACING, facing.getRight());

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
    // Minimal placeholder icon: a curved arrow suggesting clockwise rotation, drawn with simple
    // line segments in the style of WiringTool.paintIcon (no image resources needed).
    final var g = c.getGraphics();
    final var oldColor = g.getColor();
    g.setColor(Color.BLACK);
    g.drawArc(x + 2, y + 2, 12, 12, 45, 270);
    // Arrowhead at the end of the arc (pointing clockwise).
    g.drawLine(x + 13, y + 3, x + 15, y + 5);
    g.drawLine(x + 13, y + 3, x + 11, y + 6);
    g.setColor(oldColor);
  }
}
