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
import com.cburch.logisim.data.Location;
import com.cburch.logisim.gui.main.Canvas;
import com.cburch.logisim.gui.main.SelectionActions;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.annotate.Annotation;
import com.cburch.logisim.std.annotate.AnnotationAnchorTracker;
import com.cburch.logisim.std.annotate.AnnotationAttributes;
import com.cburch.logisim.std.base.Text;
import com.cburch.logisim.util.StringUtil;
import java.awt.Cursor;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * Peler Edition Feature 5: click a component or wire endpoint to attach a free-text annotation to
 * it, anchored so it moves/deletes along with its target (see {@link AnnotationAnchorTracker}).
 *
 * <p>Text entry is a modal dialog (a multi-line {@link JTextArea}), not inline canvas caret
 * editing like {@link TextTool} uses for {@code Text} -- deliberately: caret editing on a
 * hand-rolled {@link Caret}/{@link CaretListener} pair turned out fragile for this tool (typed
 * text could silently fail to reach the placed component -- {@code xn.add(comp)} adds whatever
 * the component's attributes were AT CREATION TIME, and nothing copied the caret's live buffer
 * back into them before that) and doesn't support newlines at all, both real problems reported
 * against the first cut of this feature. A modal dialog sidesteps both: {@code getText()} is read
 * directly when the user clicks OK and set on the component's attributes before it's ever added,
 * and Enter is a newline by default since there's no default-button binding to steal it (see
 * {@link #showAnnotationDialog}).
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
  public void mousePressed(Canvas canvas, Graphics g, MouseEvent e) {
    final var proj = canvas.getProject();
    final var circ = canvas.getCircuit();
    AnnotationAnchorTracker.getOrAttach(proj, circ);

    final var act = SelectionActions.dropAll(canvas.getSelection());
    proj.doAction(act);

    if (!proj.getLogisimFile().contains(circ)) {
      canvas.setErrorMessage(S.getter("cannotModifyError"));
      return;
    }

    final var loc = Location.create(e.getX(), e.getY(), false);
    final var owner = proj.getFrame();

    // Clicking an existing Annotation re-opens it for editing -- checked first so editing an
    // annotation always takes priority over accidentally re-anchoring a new one to it.
    for (final var comp : circ.getAllContaining(loc, g)) {
      if (!(comp.getFactory() instanceof Annotation)) continue;
      editExisting(proj, circ, comp, owner);
      return;
    }

    // Otherwise, look for something to anchor a brand new annotation to.
    final var anchor = findAnchorTarget(circ, loc, g);
    if (anchor == null) {
      canvas.setErrorMessage(S.getter("annotateNoTargetHint"));
      return;
    }
    createNew(proj, circ, anchor, loc);
  }

  private void editExisting(Project proj, Circuit circ, Component comp, Frame owner) {
    final var currentText = comp.getAttributeSet().getValue(Text.ATTR_TEXT);
    final var newText = showAnnotationDialog(owner, currentText);
    if (newText == null) return; // cancelled
    final var xn = new CircuitMutation(circ);
    if (StringUtil.isNullOrEmpty(newText)) {
      xn.remove(comp);
      proj.doAction(xn.toAction(S.getter("removeComponentAction", Annotation.FACTORY.getDisplayGetter())));
      AnnotationAnchorTracker.getOrAttach(proj, circ).forget(comp);
    } else if (!newText.equals(currentText)) {
      xn.set(comp, Text.ATTR_TEXT, newText);
      proj.doAction(xn.toAction(S.getter("changeComponentAttributesAction")));
    }
  }

  private void createNew(Project proj, Circuit circ, Component anchor, Location clickLoc) {
    final var owner = proj.getFrame();
    final var text = showAnnotationDialog(owner, "");
    if (StringUtil.isNullOrEmpty(text)) return; // cancelled, or nothing typed -- don't add a blank note

    final var anchorLoc = anchorLocationOf(anchor, clickLoc);
    final var placeX = Canvas.snapXToGrid(anchorLoc.getX());
    final var placeY = Canvas.snapYToGrid(anchorLoc.getY() + DEFAULT_OFFSET_Y);
    final var placeLoc = Location.create(placeX, placeY, false);

    final var attrs = (AnnotationAttributes) Annotation.FACTORY.createAttributeSet();
    attrs.setValue(Text.ATTR_TEXT, text);
    attrs.setValue(AnnotationAttributes.ANCHOR_LOC, anchorLoc);
    final var comp = Annotation.FACTORY.createComponent(placeLoc, attrs);

    final var xn = new CircuitMutation(circ);
    xn.add(comp);
    proj.doAction(xn.toAction(S.getter("addComponentAction", Annotation.FACTORY.getDisplayGetter())));
    AnnotationAnchorTracker.getOrAttach(proj, circ).registerAnchor(comp, anchor);
  }

  /**
   * Modal multi-line text entry. Returns the entered text, {@code ""} if the user cleared it
   * (caller decides what emptying means -- discard for a new annotation, delete for an existing
   * one), or {@code null} if the dialog was cancelled/closed without confirming.
   *
   * <p>Uses a raw {@link JOptionPane} rather than a hand-built modal dialog for everything else
   * (button layout, Escape-to-cancel, centering on the owner) but explicitly clears the resulting
   * dialog's default button -- otherwise {@code JOptionPane} binds Enter to the OK button at the
   * root-pane level, which would steal every newline the user tries to type into the text area
   * instead of the intended "add a line break" behavior.
   */
  private static String showAnnotationDialog(Frame owner, String initialText) {
    final var textArea = new JTextArea(initialText == null ? "" : initialText, 6, 32);
    textArea.setLineWrap(true);
    textArea.setWrapStyleWord(true);
    final var scroll = new JScrollPane(textArea);

    final var pane = new JOptionPane(scroll, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
    final var dialog = pane.createDialog(owner, S.get("annotateDialogTitle"));
    dialog.getRootPane().setDefaultButton(null);
    dialog.setResizable(true);
    textArea.requestFocusInWindow();
    dialog.setVisible(true);
    dialog.dispose();

    final var selected = pane.getValue();
    if (selected instanceof Integer i && i == JOptionPane.OK_OPTION) {
      return textArea.getText();
    }
    return null;
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
   * Finds a click target to anchor a new annotation to. Checked in this order: (1) any non-wire,
   * non-Annotation component whose painted shape directly contains the click -- checked FIRST so
   * clicking a component's body always wins even when one of its own connected wires' endpoints
   * happens to be nearby (routine in a real wired-up circuit, since a pin's wire endpoint sits
   * right at the component's edge -- this ordering used to be reversed, which made it look like
   * annotating a component was broken any time it had a wire attached); (2) failing that, a wire
   * endpoint within {@link #ANCHOR_SNAP_RADIUS} pixels. Returns {@code null} if neither is found
   * -- empty canvas is not a valid target, per the original request ("click a component or wire
   * endpoint").
   */
  private Component findAnchorTarget(Circuit circ, Location loc, Graphics g) {
    for (final var comp : circ.getAllContaining(loc, g)) {
      if (comp instanceof Wire) continue;
      if (comp.getFactory() instanceof Annotation) continue;
      return comp;
    }

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
}
