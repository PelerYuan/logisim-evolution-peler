/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.pcomp;

import static com.cburch.logisim.gui.Strings.S;

import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.PcompReplacement;
import com.cburch.logisim.gui.generic.OptionPane;
import com.cburch.logisim.pcomp.PcompCatalog;
import com.cburch.logisim.pcomp.PcompFile;
import com.cburch.logisim.pcomp.PcompLibrary;
import com.cburch.logisim.pcomp.PortSignature;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.ProjectActions;
import com.cburch.logisim.util.JFileChoosers;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;

/**
 * Peler Edition. The window that installs, removes and swaps custom components.
 *
 * <p>Installing from here rather than from a menu action is deliberate. A component is not a thing
 * you open, it is a thing you keep: once installed it is in the toolbox of every project, this one
 * and the next one, until it is removed. A list you can see the whole of says that; a file dialog
 * hanging off the File menu says the opposite, and would leave "what have I actually got installed"
 * with no answer anywhere in the program.
 *
 * <p><b>There is no rename and no grouping here</b>, though both were sketched. A project file
 * records a placed component by the name of the circuit inside it, so renaming a published
 * component would turn every project already using it into a project referring to something that no
 * longer exists -- the same reason the layout is fixed, and with the same way out, which is to
 * publish a new version. Grouping would be arranging a list whose order is already fixed by the one
 * thing that matters, which is which versions belong to which component.
 */
public class PcompManagerDialog extends JDialog {
  private static final long serialVersionUID = 1L;

  private final Project project;
  private final ComponentTable model = new ComponentTable();
  private final JTable table = new JTable(model);
  private final JLabel problems = new JLabel(" ");
  private final JButton importing = new JButton();
  private final JButton open = new JButton();
  private final JButton replace = new JButton();
  private final JButton delete = new JButton();
  private final JButton close = new JButton();

  private PcompManagerDialog(Window parent, Project project) {
    super(parent, S.get("pcompManagerTitle"), ModalityType.APPLICATION_MODAL);
    this.project = project;

    final var hint = new JLabel(S.get("pcompManagerHint"));
    hint.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setRowHeight(table.getRowHeight() + 4);
    table.getSelectionModel().addListSelectionListener(event -> enableButtons());
    final var area = new JScrollPane(table);
    area.setPreferredSize(new Dimension(720, 260));
    area.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));

    problems.setBorder(BorderFactory.createEmptyBorder(6, 10, 0, 10));

    importing.addActionListener(event -> onImport());
    open.addActionListener(event -> onOpen());
    replace.addActionListener(event -> onReplace());
    delete.addActionListener(event -> onDelete());
    close.addActionListener(event -> dispose());

    final var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    buttons.add(importing);
    buttons.add(open);
    buttons.add(replace);
    buttons.add(delete);
    buttons.add(Box.createHorizontalStrut(16));
    buttons.add(close);

    final var bottom = new JPanel();
    bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
    bottom.add(problems);
    bottom.add(Box.createVerticalStrut(4));
    bottom.add(buttons);

    setLayout(new BorderLayout());
    add(hint, BorderLayout.NORTH);
    add(area, BorderLayout.CENTER);
    add(bottom, BorderLayout.SOUTH);
    localeChanged();
    refresh();
    pack();
    setLocationRelativeTo(parent);
  }

  public static void open(Project project) {
    final var parent = project == null ? null : project.getFrame();
    new PcompManagerDialog(parent, project).setVisible(true);
  }

  private void localeChanged() {
    importing.setText(S.get("pcompImportButton"));
    open.setText(S.get("pcompOpenButton"));
    replace.setText(S.get("pcompReplaceButton"));
    delete.setText(S.get("pcompDeleteButton"));
    close.setText(S.get("pcompCloseButton"));
  }

  private void refresh() {
    model.reload();
    final var trouble = PcompCatalog.problemsFromLastScan();
    problems.setText(
        trouble.isEmpty()
            ? " "
            : S.get("pcompProblemsHeader", String.join("; ", trouble.keySet())));
    enableButtons();
  }

  private void enableButtons() {
    final var chosen = selected();
    open.setEnabled(chosen != null);
    delete.setEnabled(chosen != null);
    replace.setEnabled(chosen != null && !otherVersionsOf(chosen).isEmpty());
  }

  private PcompLibrary selected() {
    final var row = table.getSelectedRow();
    return row < 0 || row >= model.rows.size() ? null : model.rows.get(row);
  }

  /** The installed components sharing this one's identity, which a replacement can choose from. */
  private List<PcompLibrary> otherVersionsOf(PcompLibrary component) {
    final var others = new ArrayList<PcompLibrary>();
    for (final var sibling : PcompCatalog.versionsOf(component.getMetadata().id())) {
      if (sibling != component) others.add(sibling);
    }
    return others;
  }

  private void onImport() {
    final var chooser = JFileChoosers.create();
    chooser.setDialogTitle(S.get("pcompImportTitle"));
    chooser.setFileFilter(
        new FileNameExtensionFilter(
            S.get("pcompImportFilter"), PcompFile.EXTENSION.substring(1)));
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
    final var chosen = chooser.getSelectedFile();
    try {
      refuseIfTheNameIsTaken(chosen);
      // Copied into the component directory rather than referenced where it sits: a component is
      // kept, and one kept by reference stops working the day the user tidies their downloads.
      final var installed = copyIntoCatalog(chosen);
      final var component = PcompCatalog.install(installed, new Loader(null));
      PcompSaveDialog.rebuildEveryToolbox();
      refresh();
      OptionPane.showMessageDialog(this,
          S.get("pcompImportDone", component.getMetadata().displayName()),
          S.get("pcompManagerTitle"), OptionPane.INFORMATION_MESSAGE);
    } catch (IOException e) {
      OptionPane.showMessageDialog(this, S.get("pcompImportFailed", chosen.getName(),
          String.valueOf(e.getMessage())), S.get("pcompManagerTitle"), OptionPane.ERROR_MESSAGE);
    }
  }

  /**
   * Refuses a component whose circuit name a different component already answers to.
   *
   * <p>The catalog is one flat category, so the circuit name is what a project file records and
   * what identifies the component when that project is read back. Two components claiming one name
   * would be one reference with two answers, and the loser would simply not appear -- which looks
   * like an import that silently did nothing. Saying so is better, and the way out is to rename the
   * component in the project it was built from and publish it again.
   */
  private void refuseIfTheNameIsTaken(File chosen) throws IOException {
    final var arriving = PcompFile.read(chosen);
    if (arriving == null) throw new IOException(chosen.getName() + " is not a custom component");
    for (final var installed : PcompCatalog.installed()) {
      final var other = installed.getMetadata();
      if (other.mainCircuit().equals(arriving.mainCircuit())
          && !other.id().equals(arriving.id())) {
        throw new IOException(S.get("pcompNameTaken", arriving.displayName()));
      }
    }
  }

  /** Puts a copy of the chosen file in the component directory, and hands back the copy. */
  private File copyIntoCatalog(File chosen) throws IOException {
    final var directory = PcompCatalog.directoryFile();
    if (!directory.isDirectory() && !directory.mkdirs()) {
      throw new IOException(directory.getAbsolutePath());
    }
    final var destination = new File(directory, chosen.getName());
    if (destination.equals(chosen)) return destination;
    if (destination.exists()) {
      final var answer = OptionPane.showConfirmDialog(this,
          S.get("pcompOverwriteMessage", destination.getName()), S.get("pcompManagerTitle"),
          OptionPane.YES_NO_OPTION);
      if (answer != OptionPane.YES_OPTION) throw new IOException(destination.getName());
    }
    java.nio.file.Files.copy(chosen.toPath(), destination.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    return destination;
  }

  /**
   * Opens the component's own file in the main window, which is the whole of "unlock for editing".
   *
   * <p>A component file is an ordinary project file, so there is nothing to unlock: what locking
   * stops is reaching the internals from a project that merely uses the component, and this is not
   * that project. Saving from there goes back through the layout window, where the port signature
   * decides whether the result may overwrite this version or has to become the next one.
   */
  private void onOpen() {
    final var chosen = selected();
    if (chosen == null) return;
    dispose();
    ProjectActions.doOpen(project == null ? null : project.getFrame(), project, chosen.getSource());
  }

  private void onDelete() {
    final var chosen = selected();
    if (chosen == null) return;
    final var uses = usesOf(chosen);
    if (uses > 0) {
      OptionPane.showMessageDialog(this,
          S.get("pcompDeleteInUse", chosen.getMetadata().displayName(), Integer.toString(uses)),
          S.get("pcompManagerTitle"), OptionPane.WARNING_MESSAGE);
      return;
    }
    final var answer = OptionPane.showConfirmDialog(this,
        S.get("pcompDeleteConfirm", chosen.getMetadata().displayName(),
            chosen.getSource().getAbsolutePath()),
        S.get("pcompManagerTitle"), OptionPane.YES_NO_OPTION);
    if (answer != OptionPane.YES_OPTION) return;
    try {
      PcompCatalog.uninstall(chosen);
    } catch (IOException e) {
      OptionPane.showMessageDialog(this,
          S.get("pcompDeleteFailed", String.valueOf(e.getMessage())), S.get("pcompManagerTitle"),
          OptionPane.ERROR_MESSAGE);
      return;
    }
    PcompSaveDialog.rebuildEveryToolbox();
    refresh();
  }

  /**
   * Replaces every instance of the selected version in this project with another version of it.
   *
   * <p>The differences are shown before anything moves, because this is the one action in the
   * program that can leave a wire attached to nothing on purpose. When the signatures match there
   * is nothing to show and nothing to worry about, and the confirmation says so.
   */
  private void onReplace() {
    final var chosen = selected();
    if (chosen == null || project == null) return;
    final var others = otherVersionsOf(chosen);
    if (others.isEmpty()) return;
    final var names = others.stream().map(other -> other.getMetadata().displayName()).toArray();
    final var picked = OptionPane.showInputDialog(this,
        S.get("pcompReplacePrompt", chosen.getMetadata().displayName()),
        S.get("pcompReplaceTitle"), OptionPane.QUESTION_MESSAGE, null, names, names[0]);
    if (picked == null) return;
    final var target = others.get(List.of(names).indexOf(picked));

    final var uses = usesOf(chosen);
    if (uses == 0) {
      OptionPane.showMessageDialog(this, S.get("pcompReplaceNothing"), S.get("pcompReplaceTitle"),
          OptionPane.INFORMATION_MESSAGE);
      return;
    }
    final var changes =
        PortSignature.differences(PortSignature.of(chosen), PortSignature.of(target)).stream()
            .map(PcompText::describe)
            .toList();
    final var question =
        changes.isEmpty()
            ? S.get("pcompReplaceSameSignature", Integer.toString(uses),
                target.getMetadata().displayName())
            : S.get("pcompReplaceDiffers", Integer.toString(uses),
                target.getMetadata().displayName(), String.join("\n", changes));
    if (OptionPane.showConfirmDialog(this, question, S.get("pcompReplaceTitle"),
        OptionPane.YES_NO_OPTION) != OptionPane.YES_OPTION) {
      return;
    }
    final var action = PcompReplacement.replace(project.getLogisimFile(), chosen, target,
        S.getter("pcompReplaceAction"));
    if (action != null) project.doAction(action);
    refresh();
    OptionPane.showMessageDialog(this,
        S.get("pcompReplaceDone", Integer.toString(uses), target.getMetadata().displayName()),
        S.get("pcompReplaceTitle"), OptionPane.INFORMATION_MESSAGE);
  }

  private int usesOf(PcompLibrary component) {
    return project == null ? 0 : PcompReplacement.countUses(project.getLogisimFile(), component);
  }

  /** One row per installed version, versions of one component together and in order. */
  private class ComponentTable extends AbstractTableModel {
    private static final long serialVersionUID = 1L;
    private List<PcompLibrary> rows = List.of();

    private void reload() {
      rows =
          PcompCatalog.installed().stream()
              .sorted(
                  (a, b) -> {
                    final var byName =
                        a.getMetadata().name().compareToIgnoreCase(b.getMetadata().name());
                    return byName != 0 ? byName : a.getMetadata().version()
                        - b.getMetadata().version();
                  })
              .toList();
      fireTableDataChanged();
    }

    @Override
    public int getRowCount() {
      return rows.size();
    }

    @Override
    public int getColumnCount() {
      return 5;
    }

    @Override
    public String getColumnName(int column) {
      return switch (column) {
        case 0 -> S.get("pcompColumnComponent");
        case 1 -> S.get("pcompColumnVersion");
        case 2 -> S.get("pcompColumnPorts");
        case 3 -> S.get("pcompColumnFile");
        default -> S.get("pcompColumnInUse");
      };
    }

    @Override
    public Object getValueAt(int row, int column) {
      final var component = rows.get(row);
      final var metadata = component.getMetadata();
      return switch (column) {
        case 0 -> metadata.name();
        case 1 -> metadata.version();
        case 2 -> metadata.ports().size();
        case 3 -> component.getSource().getName();
        default -> {
          final var uses = usesOf(component);
          yield uses == 0 ? S.get("pcompInUseNo") : S.get("pcompInUseYes", Integer.toString(uses));
        }
      };
    }
  }
}
