/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.menu;

import static com.cburch.logisim.gui.Strings.S;

import com.cburch.logisim.gui.find.FindToolDialog;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.prefs.PrefMonitorKeyStroke;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.ProjectWideAttribute;
import com.cburch.logisim.std.ttl.TtlLibrary;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

class MenuProject extends Menu {
  private static final long serialVersionUID = 1L;
  private final LogisimMenuBar menubar;
  private final MyListener myListener = new MyListener();
  private final MenuItemImpl addCircuit = new MenuItemImpl(this, LogisimMenuBar.ADD_CIRCUIT);
  private final MenuItemImpl addVhdl = new MenuItemImpl(this, LogisimMenuBar.ADD_VHDL);
  private final MenuItemImpl importVhdl = new MenuItemImpl(this, LogisimMenuBar.IMPORT_VHDL);
  private final JMenu loadLibrary = new JMenu();
  private final JMenuItem loadBuiltin = new JMenuItem();
  private final JMenuItem loadLogisim = new JMenuItem();
  private final JMenuItem loadJar = new JMenuItem();
  private final JMenuItem unload = new JMenuItem();
  private final MenuItemImpl moveUp = new MenuItemImpl(this, LogisimMenuBar.MOVE_CIRCUIT_UP);
  private final MenuItemImpl moveDown = new MenuItemImpl(this, LogisimMenuBar.MOVE_CIRCUIT_DOWN);
  private final MenuItemImpl remove = new MenuItemImpl(this, LogisimMenuBar.REMOVE_CIRCUIT);
  private final MenuItemImpl setAsMain = new MenuItemImpl(this, LogisimMenuBar.SET_MAIN_CIRCUIT);
  private final MenuItemImpl revertAppearance =
      new MenuItemImpl(this, LogisimMenuBar.REVERT_APPEARANCE);
  private final MenuItemImpl layout = new MenuItemImpl(this, LogisimMenuBar.EDIT_LAYOUT);
  private final MenuItemImpl appearance = new MenuItemImpl(this, LogisimMenuBar.EDIT_APPEARANCE);
  private final MenuItemImpl toggleLayoutAppearance =
      new MenuItemImpl(this, LogisimMenuBar.TOGGLE_APPEARANCE);
  private final MenuItemImpl analyze = new MenuItemImpl(this, LogisimMenuBar.ANALYZE_CIRCUIT);
  private final MenuItemImpl stats = new MenuItemImpl(this, LogisimMenuBar.CIRCUIT_STATS);
  private final JMenuItem options = new JMenuItem();
  /**
   * Peler Edition Feature 6. Lives in the Project menu because that is the menu that owns the
   * toolbox and its libraries -- this finds a component to place, which is not the "find in this
   * document" that Edit would suggest. Kept as a plain JMenuItem with its own listener, like
   * `options` above, rather than going through LogisimMenuBar.registerItem: that machinery exists
   * so different windows can supply different handlers for the same item, and there is only ever
   * one handler for this one.
   */
  private final JMenuItem findTool = new JMenuItem();
  /**
   * Peler Edition Feature 14. Two commands rather than one checkbox: the chips in a project can be
   * drawn either way at the same time, so there is no single current state for a checkbox to show
   * and no honest answer to what a click on a half-ticked box should mean. Plain JMenuItems with
   * their own listener, for the same reason as `findTool` above.
   */
  private final JMenu ttlDrawing = new JMenu();
  private final JMenuItem ttlShowGates = new JMenuItem();
  private final JMenuItem ttlShowPackage = new JMenuItem();

  MenuProject(LogisimMenuBar menubar) {
    this.menubar = menubar;

    /* Hotkey Bindings */
    moveUp.setAccelerator(
        ((PrefMonitorKeyStroke) AppPreferences.HOTKEY_PROJ_MOVE_UP).getWithMask(0));
    moveDown.setAccelerator(
        ((PrefMonitorKeyStroke) AppPreferences.HOTKEY_PROJ_MOVE_DOWN).getWithMask(0));

    menubar.registerItem(LogisimMenuBar.ADD_CIRCUIT, addCircuit);
    menubar.registerItem(LogisimMenuBar.ADD_VHDL, addVhdl);
    menubar.registerItem(LogisimMenuBar.IMPORT_VHDL, importVhdl);
    loadBuiltin.addActionListener(myListener);
    loadLogisim.addActionListener(myListener);
    loadJar.addActionListener(myListener);
    unload.addActionListener(myListener);
    menubar.registerItem(LogisimMenuBar.MOVE_CIRCUIT_UP, moveUp);
    menubar.registerItem(LogisimMenuBar.MOVE_CIRCUIT_DOWN, moveDown);
    menubar.registerItem(LogisimMenuBar.SET_MAIN_CIRCUIT, setAsMain);
    menubar.registerItem(LogisimMenuBar.REMOVE_CIRCUIT, remove);
    menubar.registerItem(LogisimMenuBar.REVERT_APPEARANCE, revertAppearance);
    menubar.registerItem(LogisimMenuBar.EDIT_LAYOUT, layout);
    menubar.registerItem(LogisimMenuBar.EDIT_APPEARANCE, appearance);
    menubar.registerItem(LogisimMenuBar.TOGGLE_APPEARANCE, toggleLayoutAppearance);
    menubar.registerItem(LogisimMenuBar.ANALYZE_CIRCUIT, analyze);
    menubar.registerItem(LogisimMenuBar.CIRCUIT_STATS, stats);
    options.addActionListener(myListener);
    findTool.addActionListener(myListener);
    findTool.setAccelerator(
        ((PrefMonitorKeyStroke) AppPreferences.HOTKEY_FIND_TOOL).getWithMask(0));
    ttlShowGates.addActionListener(myListener);
    ttlShowPackage.addActionListener(myListener);
    ttlDrawing.add(ttlShowGates);
    ttlDrawing.add(ttlShowPackage);

    loadLibrary.add(loadBuiltin);
    loadLibrary.add(loadLogisim);
    loadLibrary.add(loadJar);

    /* add myself to hotkey sync */
    AppPreferences.gui_sync_objects.add(this);

    add(findTool);
    addSeparator();
    add(addCircuit);
    add(addVhdl);
    add(importVhdl);
    add(loadLibrary);
    add(unload);
    addSeparator();
    add(moveUp);
    add(moveDown);
    add(setAsMain);
    add(remove);
    add(revertAppearance);
    addSeparator();
    add(layout);
    add(appearance);
    add(ttlDrawing);
    addSeparator();
    add(analyze);
    add(stats);
    addSeparator();
    add(options);

    final var known = menubar.getSaveProject() != null;
    loadLibrary.setEnabled(known);
    loadBuiltin.setEnabled(known);
    loadLogisim.setEnabled(known);
    loadJar.setEnabled(known);
    unload.setEnabled(known);
    options.setEnabled(known);
    findTool.setEnabled(known);
    // Whether the project holds any TTL chip is only true until the next edit, so the answer is
    // taken when the menu opens rather than kept up to date. That keeps this off the edit path,
    // where a per-component sweep on every change would be paid for constantly and read never.
    addMenuListener(
        new MenuListener() {
          @Override
          public void menuSelected(MenuEvent event) {
            computeTtlDrawingEnabled();
          }

          @Override
          public void menuDeselected(MenuEvent event) {
            // Nothing to do: the next open recomputes it.
          }

          @Override
          public void menuCanceled(MenuEvent event) {
            // Nothing to do: the next open recomputes it.
          }
        });
    computeTtlDrawingEnabled();
    computeEnabled();
  }

  /**
   * Peler Edition Feature 14. Greys out the submenu when the project holds no chip either command
   * could touch, so an empty sweep is visible before the click rather than after it.
   */
  private void computeTtlDrawingEnabled() {
    final var proj = menubar.getSaveProject();
    ttlDrawing.setEnabled(
        proj != null
            && ProjectWideAttribute.isCarriedByAnyComponent(
                proj.getLogisimFile(), TtlLibrary.DRAW_INTERNAL_STRUCTURE));
  }

  /**
   * Peler Edition Feature 14. Sets the drawing of every TTL chip in the project -- every circuit,
   * subcircuits included -- in one undoable step.
   *
   * <p>Safe to do in bulk because {@code AbstractTtlGate} reads this attribute only when painting:
   * neither {@code getOffsetBounds} nor {@code instanceAttributeChanged} consults it, so no chip
   * changes size and no port moves, and there is no way for the sweep to leave a wire dangling.
   *
   * <p>The settings page has the matching switch for chips that do not exist yet. Between them the
   * split is: the preference decides how the next chip is drawn, this decides how the ones already
   * placed are drawn.
   */
  private void setTtlDrawing(Project proj, boolean showGates) {
    final var action =
        ProjectWideAttribute.setEverywhere(
            proj.getLogisimFile(),
            TtlLibrary.DRAW_INTERNAL_STRUCTURE,
            showGates,
            S.getter(showGates ? "projectTtlShowGatesItem" : "projectTtlShowPackageItem"));
    // Null when every chip is drawn that way already; doAction ignores it, but say so explicitly.
    if (action != null) proj.doAction(action);
  }

  public void hotkeyUpdate() {
    moveUp.setAccelerator(
        ((PrefMonitorKeyStroke) AppPreferences.HOTKEY_PROJ_MOVE_UP).getWithMask(0));
    moveDown.setAccelerator(
        ((PrefMonitorKeyStroke) AppPreferences.HOTKEY_PROJ_MOVE_DOWN).getWithMask(0));
    findTool.setAccelerator(
        ((PrefMonitorKeyStroke) AppPreferences.HOTKEY_FIND_TOOL).getWithMask(0));
  }

  @Override
  protected void computeEnabled() {
    setEnabled(
        menubar.getSaveProject() != null
            || addCircuit.hasListeners()
            || addVhdl.hasListeners()
            || importVhdl.hasListeners()
            || moveUp.hasListeners()
            || moveDown.hasListeners()
            || setAsMain.hasListeners()
            || remove.hasListeners()
            || layout.hasListeners()
            || revertAppearance.hasListeners()
            || appearance.hasListeners()
            || analyze.hasListeners()
            || stats.hasListeners());
    menubar.fireEnableChanged();
  }

  public void localeChanged() {
    setText(S.get("projectMenu"));
    addCircuit.setText(S.get("projectAddCircuitItem"));
    addVhdl.setText(S.get("projectAddVhdlItem"));
    importVhdl.setText(S.get("projectImportVhdlItem"));
    loadLibrary.setText(S.get("projectLoadLibraryItem"));
    loadBuiltin.setText(S.get("projectLoadBuiltinItem"));
    loadLogisim.setText(S.get("projectLoadLogisimItem"));
    loadJar.setText(S.get("projectLoadJarItem"));
    unload.setText(S.get("projectUnloadLibrariesItem"));
    moveUp.setText(S.get("projectMoveCircuitUpItem"));
    moveDown.setText(S.get("projectMoveCircuitDownItem"));
    setAsMain.setText(S.get("projectSetAsMainItem"));
    remove.setText(S.get("projectRemoveCircuitItem"));
    revertAppearance.setText(S.get("projectRevertAppearanceItem"));
    layout.setText(S.get("projectEditCircuitLayoutItem"));
    appearance.setText(S.get("projectEditCircuitAppearanceItem"));
    toggleLayoutAppearance.setText(S.get("projectToggleCircuitAppearanceItem"));
    analyze.setText(S.get("projectAnalyzeCircuitItem"));
    stats.setText(S.get("projectGetCircuitStatisticsItem"));
    options.setText(S.get("projectOptionsItem"));
    findTool.setText(S.get("projectFindToolItem"));
    ttlDrawing.setText(S.get("projectTtlDrawingMenu"));
    ttlShowGates.setText(S.get("projectTtlShowGatesItem"));
    ttlShowPackage.setText(S.get("projectTtlShowPackageItem"));
  }

  private class MyListener implements ActionListener {
    @Override
    public void actionPerformed(ActionEvent event) {
      final var src = event.getSource();
      final var proj = menubar.getSaveProject();
      if (proj == null) {
        return;
      }
      if (src == loadBuiltin) {
        ProjectLibraryActions.doLoadBuiltinLibrary(proj);
      } else if (src == loadLogisim) {
        ProjectLibraryActions.doLoadLogisimLibrary(proj);
      } else if (src == loadJar) {
        ProjectLibraryActions.doLoadJarLibrary(proj);
      } else if (src == unload) {
        ProjectLibraryActions.doUnloadLibraries(proj);
      } else if (src == options) {
        proj.getOptionsFrame().setVisible(true);
      } else if (src == findTool) {
        FindToolDialog.open(proj.getFrame(), proj);
      } else if (src == ttlShowGates) {
        setTtlDrawing(proj, true);
      } else if (src == ttlShowPackage) {
        setTtlDrawing(proj, false);
      }
    }
  }
}
