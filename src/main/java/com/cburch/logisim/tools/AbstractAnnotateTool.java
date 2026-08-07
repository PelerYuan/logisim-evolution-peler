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
import com.cburch.logisim.data.Location;
import com.cburch.logisim.gui.main.Canvas;
import com.cburch.logisim.gui.main.SelectionActions;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.annotate.Annotation;
import com.cburch.logisim.std.annotate.AnnotationAnchorTracker;
import com.cburch.logisim.std.annotate.AnnotationAttributes;
import com.cburch.logisim.std.base.Text;
import com.cburch.logisim.util.StringGetter;
import com.cburch.logisim.util.StringUtil;
import java.awt.Cursor;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.event.MouseEvent;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * Peler Edition Feature 5: shared machinery for the two annotate tools -- {@link
 * AnnotateComponentTool} (click a component, note lands directly above it) and {@link
 * AnnotateWireTool} (click a wire endpoint). Originally a single {@code AnnotateTool} that
 * guessed which kind of target a click meant by trying components first, then falling back to
 * nearby wire endpoints; split into two per explicit user request, "one dedicated to marking
 * components, one dedicated to marking wire endpoints, so it's easier to tell apart during
 * actual use" -- the priority-order heuristic was also a real source of bugs (a component's own
 * pins/wires sit right next to it, so the old single tool could favor the wrong target).
 *
 * <p>Text entry is a modal dialog (a multi-line {@link JTextArea}), not inline canvas caret
 * editing like {@link TextTool} uses for {@code Text} -- see {@link #showAnnotationDialog} for
 * why.
 *
 * <p>See docs/peler-edition/ROADMAP.md, Feature 5.
 */
public abstract class AbstractAnnotateTool extends Tool {
  private static final Cursor cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR);

  @Override
  public Cursor getCursor() {
    return cursor;
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

    // Clicking an existing Annotation re-opens it for editing -- checked first, and against
    // BOTH annotation kinds regardless of which tool is active, so switching tools doesn't
    // strand a note you're trying to edit.
    for (final var comp : circ.getAllContaining(loc, g)) {
      if (!(comp.getFactory() instanceof Annotation)) continue;
      editExisting(proj, circ, comp, owner);
      return;
    }

    final var anchor = findAnchorTarget(circ, loc, g);
    if (anchor == null) {
      canvas.setErrorMessage(noTargetHint());
      return;
    }
    createNew(proj, circ, anchor, loc, owner);
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

  private void createNew(Project proj, Circuit circ, Component anchor, Location clickLoc, Frame owner) {
    final var text = showAnnotationDialog(owner, "");
    if (StringUtil.isNullOrEmpty(text)) return; // cancelled, or nothing typed -- don't add a blank note

    final var anchorLoc = anchorLocationOf(anchor, clickLoc);
    final var placeLoc = placementFor(anchorLoc);

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
  static String showAnnotationDialog(Frame owner, String initialText) {
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
    paintStickyNote(c, x, y);
    paintKindGlyph(c, x, y);
  }

  /** Common sticky-note base every annotate tool icon shares, same style/effort as QuickRotateTool.paintIcon. */
  static void paintStickyNote(ComponentDrawContext c, int x, int y) {
    final var g = c.getGraphics();
    g.drawRect(x + 2, y + 2, 12, 12);
    g.fillPolygon(new int[] {x + 10, x + 14, x + 14}, new int[] {y + 2, y + 2, y + 6}, 3);
  }

  /** Small glyph on top of the sticky note distinguishing which kind of target this tool anchors to. */
  abstract void paintKindGlyph(ComponentDrawContext c, int x, int y);

  /**
   * Finds a click target to anchor a new annotation to, restricted to this tool's own kind
   * (component body, or wire endpoint -- never both, that ambiguity is exactly what the
   * component/wire split exists to remove). Returns {@code null} if nothing suitable is under
   * the click.
   */
  abstract Component findAnchorTarget(Circuit circ, Location loc, Graphics g);

  /** Where {@code target} is anchored, given the click that selected it. */
  abstract Location anchorLocationOf(Component target, Location clickLoc);

  /** Where to place a brand new note given the anchor point it's attached to. */
  abstract Location placementFor(Location anchorLoc);

  abstract StringGetter noTargetHint();
}
