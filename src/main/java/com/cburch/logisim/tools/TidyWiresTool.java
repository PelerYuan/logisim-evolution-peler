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
import com.cburch.logisim.circuit.WireTidier;
import com.cburch.logisim.comp.ComponentDrawContext;
import com.cburch.logisim.gui.generic.OptionPane;
import com.cburch.logisim.gui.main.Canvas;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.base.BaseLibrary;
import java.awt.Color;
import java.awt.Graphics;
import javax.swing.SwingUtilities;

/**
 * Peler Edition Feature 4: re-routes every wire in the current circuit for readability, without
 * moving any components. All the actual net/route computation lives in {@link WireTidier} (kept
 * UI-independent); this class is only the toolbar/tool-selection glue, mirroring how {@link
 * QuickRotateTool} wraps its own logic. See {@code docs/peler-edition/ROADMAP.md}, Feature 4.
 *
 * <p>This tool has no mouse/placement behavior of its own: clicking it (from the toolbar, or via
 * a mouse mapping) runs the whole confirm-and-tidy sequence directly from {@link #select}, then
 * immediately switches back to the Edit Tool -- see {@link #select} for why the switch-back is
 * deferred rather than called synchronously.
 */
public class TidyWiresTool extends Tool {
  /**
   * Unique identifier of the tool, used as reference in project files. Do NOT change as it will
   * prevent project files from loading.
   *
   * <p>Identifier value must MUST be unique string among all tools.
   */
  public static final String _ID = "Tidy Wires Tool";

  public TidyWiresTool() {}

  @Override
  public boolean equals(Object other) {
    return other instanceof TidyWiresTool;
  }

  @Override
  public int hashCode() {
    return TidyWiresTool.class.hashCode();
  }

  @Override
  public String getDescription() {
    return S.get("tidyWiresToolDesc");
  }

  @Override
  public String getDisplayName() {
    return S.get("tidyWiresTool");
  }

  @Override
  public void select(Canvas canvas) {
    if (canvas == null) {
      // Defensive only -- WiringTool's constructor calls super.select(null) once at startup, so
      // other Tool subclasses have historically had to tolerate this even though Project.setTool
      // itself always passes a real canvas.
      return;
    }
    final var proj = canvas.getProject();
    final var circ = canvas.getCircuit();
    if (proj.getLogisimFile().contains(circ)) {
      confirmAndTidy(proj, circ, canvas);
    } else {
      // Same "can this circuit be modified" guard AddTool/TextTool/WiringTool/QuickRotateTool
      // all use.
      canvas.setErrorMessage(S.getter("cannotModifyError"));
    }
    switchBackToEditTool(proj);
  }

  /**
   * Shared "confirm + run + doAction" sequence, reused by both {@link #select} above and {@code
   * MenuProject}'s "Tidy Wires" menu item so the logic isn't duplicated. Callers are responsible
   * for their own "can this circuit be modified" guard beforehand -- this method assumes it
   * already passed.
   */
  public static void confirmAndTidy(Project proj, Circuit circ, java.awt.Component dialogParent) {
    final var choice =
        OptionPane.showConfirmDialog(
            dialogParent,
            S.get("tidyWiresConfirmMessage"),
            S.get("tidyWiresConfirmTitle"),
            OptionPane.YES_NO_OPTION);
    if (choice != OptionPane.YES_OPTION) {
      return;
    }
    final var mutation = WireTidier.buildTidyMutation(circ);
    if (mutation != null) {
      proj.doAction(mutation.toAction(S.getter("tidyWiresAction")));
    }
  }

  /**
   * This tool has no mousePressed/placement behavior of its own -- after running (whether it
   * actually re-routed, was canceled at the confirmation dialog, or was blocked by the modify
   * guard in {@link #select}), it must not stay the visibly "selected" tool.
   *
   * <p>Deferred via {@link SwingUtilities#invokeLater} rather than calling {@code
   * proj.setTool(editTool)} synchronously from here: {@link #select} is itself invoked from
   * *inside* {@code Project.setTool(this)} -- which assigns its {@code tool} field to this
   * instance and only THEN calls {@code select(canvas)}. A synchronous, nested {@code
   * proj.setTool(editTool)} call from within {@code select} would reenter {@code Project.setTool}
   * while the outer call is still mid-method: the outer call's trailing {@code
   * fireEvent(ACTION_SET_TOOL, old, tool)} reads the (by then already-overwritten-by-the-inner-
   * call) {@code tool} field, so it would fire a second, stale/duplicate {@code ACTION_SET_TOOL}
   * event. Deferring via {@code invokeLater} sidesteps that reentrancy question entirely and is
   * the conventional Swing pattern for "run a follow-up UI action after the current event
   * finishes" -- see the Part B instructions in the Phase 4 task for the trace that led to this
   * choice.
   */
  private static void switchBackToEditTool(Project proj) {
    SwingUtilities.invokeLater(
        () -> {
          final var base = proj.getLogisimFile().getLibrary(BaseLibrary._ID);
          final var editTool = (base == null) ? null : base.getTool(EditTool._ID);
          if (editTool != null) {
            proj.setTool(editTool);
          }
        });
  }

  @Override
  public void paintIcon(ComponentDrawContext c, int x, int y) {
    // Minimal placeholder icon: a small tree of orthogonal branches (trunk + three straight
    // branches), suggesting a tidied/rectilinear wire tree -- no image resources needed, in the
    // same spirit as QuickRotateTool's hand-drawn icon.
    final var g = c.getGraphics();
    final var oldColor = g.getColor();
    g.setColor(Color.BLACK);
    // Trunk: a short horizontal stub coming in from the left-center.
    g.drawLine(x + 1, y + 8, x + 6, y + 8);
    // Three rectilinear branches fanning out to the right, like a routed MST.
    g.drawLine(x + 6, y + 3, x + 6, y + 13);
    g.drawLine(x + 6, y + 3, x + 14, y + 3);
    g.drawLine(x + 6, y + 8, x + 14, y + 8);
    g.drawLine(x + 6, y + 13, x + 14, y + 13);
    g.setColor(oldColor);
  }
}
