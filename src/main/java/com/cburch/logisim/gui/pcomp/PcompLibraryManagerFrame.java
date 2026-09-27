/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.pcomp;

import static com.cburch.logisim.gui.Strings.S;

import com.cburch.logisim.file.LibraryEvent;
import com.cburch.logisim.file.LibraryListener;
import com.cburch.logisim.gui.generic.OptionPane;
import com.cburch.logisim.gui.menu.ProjectLibraryActions;
import com.cburch.logisim.pcomp.PcompLibraryExport;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.util.JFileChoosers;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.ListSelectionModel;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Peler Edition. The window that replaces {@code PcompManagerDialog}: manages every component
 * library the project has loaded, not just the one fixed "My Components" directory. See {@code
 * docs/peler-edition/design/pcomp-libraries.md} section five.
 *
 * <p>A plain {@link JFrame}, not a dialog, and deliberately not modal even in spirit. Managing
 * several libraries is a back-and-forth: pick a library on the left, go save a circuit into it from
 * the project window, come back and see it appear on the right. A modal window would make that
 * round trip impossible by construction.
 *
 * <p>The left list is not this window's own state -- it is read straight from {@code
 * project.getLogisimFile().getLibraries()} every time it needs to be shown, and a {@link
 * LibraryListener} keeps it in step with libraries loaded or unloaded from anywhere else (the
 * ordinary Project menu, another copy of this window, undo). There is deliberately no separate
 * "refresh" button for that reason.
 */
public class PcompLibraryManagerFrame extends JFrame {
  private static final long serialVersionUID = 1L;

  private final Project project;
  private final DefaultListModel<PcompLibraryTarget> libraryListModel = new DefaultListModel<>();
  private final JList<PcompLibraryTarget> libraryList = new JList<>(libraryListModel);
  private final JPanel tableHost = new JPanel(new BorderLayout());
  private final JLabel selectedLabel = new JLabel(" ");
  private final JButton newLibrary = new JButton();
  private final JButton loadLibrary = new JButton();
  private final JButton unload = new JButton();
  private final JButton export = new JButton();
  private final JButton saveHere = new JButton();
  private final LibraryListener listener = event -> onLibraryEvent(event);

  private PcompLibraryManagerFrame(Project project) {
    super(S.get("pcompLibraryManagerTitle"));
    this.project = project;

    libraryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    libraryList.setCellRenderer(
        (list, value, index, isSelected, hasFocus) ->
            new JLabel(value.displayName()));
    libraryList.addListSelectionListener(event -> {
      if (!event.getValueIsAdjusting()) showSelected();
    });
    final var listArea = new JScrollPane(libraryList);
    listArea.setPreferredSize(new Dimension(200, 260));

    newLibrary.setText(S.get("projectNewPcompLibraryItem"));
    loadLibrary.setText(S.get("projectLoadPcompLibraryItem"));
    unload.setText(S.get("pcompLibraryUnloadButton"));
    export.setText(S.get("pcompLibraryExportButton"));
    newLibrary.addActionListener(event -> ProjectLibraryActions.doNewPcompLibrary(project));
    loadLibrary.addActionListener(event -> ProjectLibraryActions.doLoadPcompLibrary(project));
    unload.addActionListener(event -> onUnload());
    export.addActionListener(event -> onExport());

    final var listButtons = new JPanel();
    listButtons.setLayout(new BoxLayout(listButtons, BoxLayout.Y_AXIS));
    newLibrary.setAlignmentX(CENTER_ALIGNMENT);
    loadLibrary.setAlignmentX(CENTER_ALIGNMENT);
    unload.setAlignmentX(CENTER_ALIGNMENT);
    export.setAlignmentX(CENTER_ALIGNMENT);
    listButtons.add(newLibrary);
    listButtons.add(loadLibrary);
    listButtons.add(unload);
    listButtons.add(export);

    final var left = new JPanel(new BorderLayout());
    left.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 6));
    left.add(listArea, BorderLayout.CENTER);
    left.add(listButtons, BorderLayout.SOUTH);

    saveHere.addActionListener(event -> onSaveHere());
    saveHere.setText(S.get("pcompLibrarySaveHereButton"));
    final var top = new JPanel(new BorderLayout());
    top.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
    top.add(selectedLabel, BorderLayout.WEST);
    final var saveRow = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    saveRow.add(saveHere);
    top.add(saveRow, BorderLayout.EAST);

    final var right = new JPanel(new BorderLayout());
    right.setBorder(BorderFactory.createEmptyBorder(10, 6, 10, 10));
    right.add(top, BorderLayout.NORTH);
    right.add(tableHost, BorderLayout.CENTER);

    final var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
    split.setDividerLocation(210);

    setLayout(new BorderLayout());
    add(split, BorderLayout.CENTER);
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosed(WindowEvent event) {
            project.removeLibraryListener(listener);
          }
        });
    project.addLibraryListener(listener);

    reloadLibraryList();
    pack();
    setLocationRelativeTo(project.getFrame());
  }

  /** Opens the window for one project. Non-modal, so more than one may be open at once. */
  public static void open(Project project) {
    new PcompLibraryManagerFrame(project).setVisible(true);
  }

  private void onLibraryEvent(LibraryEvent event) {
    if (event.getAction() == LibraryEvent.ADD_LIBRARY
        || event.getAction() == LibraryEvent.REMOVE_LIBRARY) {
      reloadLibraryList();
    }
  }

  private void reloadLibraryList() {
    final var previous = libraryList.getSelectedValue();
    final var targets = PcompLibraryTarget.allIn(project.getLogisimFile());
    libraryListModel.clear();
    PcompLibraryTarget toSelect = null;
    for (final var target : targets) {
      libraryListModel.addElement(target);
      if (previous != null && target.asLibrary() == previous.asLibrary()) toSelect = target;
    }
    if (toSelect == null && !targets.isEmpty()) toSelect = targets.get(0);
    if (toSelect != null) {
      libraryList.setSelectedValue(toSelect, true);
    } else {
      showSelected();
    }
  }

  private void showSelected() {
    tableHost.removeAll();
    final var target = libraryList.getSelectedValue();
    if (target == null) {
      selectedLabel.setText(" ");
      saveHere.setEnabled(false);
      unload.setEnabled(false);
      export.setEnabled(false);
    } else {
      selectedLabel.setText(target.displayName());
      saveHere.setEnabled(true);
      unload.setEnabled(project.getLogisimFile().getUnloadLibraryMessage(target.asLibrary()) == null);
      export.setEnabled(true);
      tableHost.add(new PcompComponentTable(this, project, target), BorderLayout.CENTER);
    }
    tableHost.revalidate();
    tableHost.repaint();
  }

  private void onUnload() {
    final var target = libraryList.getSelectedValue();
    if (target == null) return;
    ProjectLibraryActions.doUnloadLibrary(project, target.asLibrary());
  }

  private void onSaveHere() {
    final var target = libraryList.getSelectedValue();
    if (target instanceof PcompLibraryTarget.OfLibrary of) {
      PcompSaveDialog.open(project, of.library());
    } else {
      PcompSaveDialog.open(project);
    }
  }

  private void onExport() {
    final var target = libraryList.getSelectedValue();
    if (target == null) return;

    final var chooser = JFileChoosers.create();
    chooser.setDialogTitle(S.get("pcompLibraryExportTitle"));
    chooser.setFileFilter(new FileNameExtensionFilter("ZIP", "zip"));
    chooser.setSelectedFile(new File(suggestedZipName(target.displayName())));
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

    var destination = chooser.getSelectedFile();
    if (!destination.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
      destination = new File(destination.getParentFile(), destination.getName() + ".zip");
    }

    try {
      PcompLibraryExport.export(target.directory(), destination);
      OptionPane.showMessageDialog(
          this,
          S.get("pcompLibraryExportDone", destination.getAbsolutePath()),
          S.get("pcompLibraryManagerTitle"),
          OptionPane.INFORMATION_MESSAGE);
    } catch (IOException e) {
      OptionPane.showMessageDialog(
          this,
          S.get("pcompLibraryExportFailed", String.valueOf(e.getMessage())),
          S.get("pcompLibraryManagerTitle"),
          OptionPane.ERROR_MESSAGE);
    }
  }

  private static String suggestedZipName(String displayName) {
    final var sanitized = displayName.replaceAll("[^A-Za-z0-9._-]+", "_");
    return sanitized.isEmpty() ? "library.zip" : sanitized + ".zip";
  }
}
