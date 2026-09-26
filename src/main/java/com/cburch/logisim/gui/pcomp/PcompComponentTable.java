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
import com.cburch.logisim.pcomp.PcompComponent;
import com.cburch.logisim.pcomp.PcompFile;
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
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;

/**
 * Peler Edition. The install/open/replace/delete panel from the old {@code PcompManagerDialog},
 * extracted so {@code PcompLibraryManagerFrame} can point one copy of it at whichever library is
 * selected on the left -- the default catalog or any loaded {@link
 * com.cburch.logisim.pcomp.PcompComponentLibrary} -- instead of the one fixed directory the old
 * dialog assumed. See {@code docs/peler-edition/design/pcomp-libraries.md}.
 *
 * <p>Retargeted by disposing and recreating this panel's contents is not how this works: a {@link
 * PcompLibraryTarget} is fixed for the lifetime of one instance, and the owning frame swaps the
 * whole panel when the selected library changes. That keeps this class exactly as simple as the
 * dialog it came from.
 */
class PcompComponentTable extends JPanel {
  private static final long serialVersionUID = 1L;

  private final Window owner;
  private final Project project;
  private final PcompLibraryTarget target;
  private final ComponentTableModel model = new ComponentTableModel();
  private final JTable table = new JTable(model);
  private final JLabel problems = new JLabel(" ");
  private final JButton importing = new JButton();
  private final JButton open = new JButton();
  private final JButton replace = new JButton();
  private final JButton delete = new JButton();

  PcompComponentTable(Window owner, Project project, PcompLibraryTarget target) {
    super(new BorderLayout());
    this.owner = owner;
    this.project = project;
    this.target = target;

    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setRowHeight(table.getRowHeight() + 4);
    table.getSelectionModel().addListSelectionListener(event -> enableButtons());
    final var area = new JScrollPane(table);
    area.setPreferredSize(new Dimension(560, 220));

    problems.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

    importing.addActionListener(event -> onImport());
    open.addActionListener(event -> onOpen());
    replace.addActionListener(event -> onReplace());
    delete.addActionListener(event -> onDelete());
    importing.setText(S.get("pcompImportButton"));
    open.setText(S.get("pcompOpenButton"));
    replace.setText(S.get("pcompReplaceButton"));
    delete.setText(S.get("pcompDeleteButton"));

    final var buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
    buttons.add(importing);
    buttons.add(open);
    buttons.add(replace);
    buttons.add(delete);

    final var bottom = new JPanel(new BorderLayout());
    bottom.add(problems, BorderLayout.NORTH);
    bottom.add(buttons, BorderLayout.SOUTH);

    add(area, BorderLayout.CENTER);
    add(bottom, BorderLayout.SOUTH);
    refresh();
  }

  final void refresh() {
    model.reload();
    final var trouble = target.problems();
    problems.setText(
        trouble.isEmpty() ? " " : S.get("pcompProblemsHeader", String.join("; ", trouble.keySet())));
    enableButtons();
  }

  private void enableButtons() {
    final var chosen = selected();
    open.setEnabled(chosen != null);
    delete.setEnabled(chosen != null);
    replace.setEnabled(chosen != null && !otherVersionsOf(chosen).isEmpty());
  }

  private PcompComponent selected() {
    final var row = table.getSelectedRow();
    return row < 0 || row >= model.rows.size() ? null : model.rows.get(row);
  }

  /** The installed components sharing this one's identity, which a replacement can choose from. */
  private List<PcompComponent> otherVersionsOf(PcompComponent component) {
    final var others = new ArrayList<PcompComponent>();
    for (final var sibling : target.versionsOf(component.getMetadata().id())) {
      if (sibling != component) others.add(sibling);
    }
    return others;
  }

  private void onImport() {
    final var chooser = JFileChoosers.create();
    chooser.setDialogTitle(S.get("pcompImportTitle"));
    chooser.setFileFilter(
        new FileNameExtensionFilter(S.get("pcompImportFilter"), PcompFile.EXTENSION.substring(1)));
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
    final var chosen = chooser.getSelectedFile();
    try {
      refuseIfTheNameIsTaken(chosen);
      // Copied into the library directory rather than referenced where it sits: a component is
      // kept, and one kept by reference stops working the day the user tidies their downloads.
      final var installed = copyIntoLibrary(chosen);
      final var component = target.install(installed, new Loader(null));
      PcompSaveDialog.rebuildEveryToolbox();
      refresh();
      OptionPane.showMessageDialog(
          this,
          S.get("pcompImportDone", component.getMetadata().displayName()),
          S.get("pcompManagerTitle"),
          OptionPane.INFORMATION_MESSAGE);
    } catch (IOException e) {
      OptionPane.showMessageDialog(
          this,
          S.get("pcompImportFailed", chosen.getName(), String.valueOf(e.getMessage())),
          S.get("pcompManagerTitle"),
          OptionPane.ERROR_MESSAGE);
    }
  }

  /**
   * Refuses a component whose circuit name a different component already answers to.
   *
   * <p>The library is one flat category, so the circuit name is what a project file records and
   * what identifies the component when that project is read back. Two components claiming one name
   * would be one reference with two answers, and the loser would simply not appear -- which looks
   * like an import that silently did nothing. Saying so is better, and the way out is to rename the
   * component in the project it was built from and publish it again.
   */
  private void refuseIfTheNameIsTaken(File chosen) throws IOException {
    final var arriving = PcompFile.read(chosen);
    if (arriving == null) throw new IOException(chosen.getName() + " is not a custom component");
    for (final var installed : target.installed()) {
      final var other = installed.getMetadata();
      if (other.mainCircuit().equals(arriving.mainCircuit())
          && !other.id().equals(arriving.id())) {
        throw new IOException(S.get("pcompNameTaken", arriving.displayName()));
      }
    }
  }

  /** Puts a copy of the chosen file in the library's directory, and hands back the copy. */
  private File copyIntoLibrary(File chosen) throws IOException {
    final var directory = target.directory();
    if (!directory.isDirectory() && !directory.mkdirs()) {
      throw new IOException(directory.getAbsolutePath());
    }
    final var destination = new File(directory, chosen.getName());
    if (destination.equals(chosen)) return destination;
    if (destination.exists()) {
      final var answer =
          OptionPane.showConfirmDialog(
              this,
              S.get("pcompOverwriteMessage", destination.getName()),
              S.get("pcompManagerTitle"),
              OptionPane.YES_NO_OPTION);
      if (answer != OptionPane.YES_OPTION) throw new IOException(destination.getName());
    }
    java.nio.file.Files.copy(
        chosen.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    return destination;
  }

  /**
   * Opens the component's own file in the main window, which is the whole of "unlock for editing".
   *
   * <p>Unlike the dialog this table came from, the manager frame stays open: it is non-modal
   * precisely so the user can go back and forth between it and a project window, and closing it
   * behind an "Open" click would fight that.
   */
  private void onOpen() {
    final var chosen = selected();
    if (chosen == null) return;
    ProjectActions.doOpen(owner instanceof java.awt.Frame f ? f : null, project, chosen.getSource());
  }

  private void onDelete() {
    final var chosen = selected();
    if (chosen == null) return;
    final var uses = usesOf(chosen);
    if (uses > 0) {
      OptionPane.showMessageDialog(
          this,
          S.get("pcompDeleteInUse", chosen.getMetadata().displayName(), Integer.toString(uses)),
          S.get("pcompManagerTitle"),
          OptionPane.WARNING_MESSAGE);
      return;
    }
    final var answer =
        OptionPane.showConfirmDialog(
            this,
            S.get(
                "pcompDeleteConfirm",
                chosen.getMetadata().displayName(),
                chosen.getSource().getAbsolutePath()),
            S.get("pcompManagerTitle"),
            OptionPane.YES_NO_OPTION);
    if (answer != OptionPane.YES_OPTION) return;
    try {
      target.uninstall(chosen);
    } catch (IOException e) {
      OptionPane.showMessageDialog(
          this,
          S.get("pcompDeleteFailed", String.valueOf(e.getMessage())),
          S.get("pcompManagerTitle"),
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
    final var picked =
        OptionPane.showInputDialog(
            this,
            S.get("pcompReplacePrompt", chosen.getMetadata().displayName()),
            S.get("pcompReplaceTitle"),
            OptionPane.QUESTION_MESSAGE,
            null,
            names,
            names[0]);
    if (picked == null) return;
    final var replacement = others.get(List.of(names).indexOf(picked));

    final var uses = usesOf(chosen);
    if (uses == 0) {
      OptionPane.showMessageDialog(
          this, S.get("pcompReplaceNothing"), S.get("pcompReplaceTitle"),
          OptionPane.INFORMATION_MESSAGE);
      return;
    }
    final var changes =
        PortSignature.differences(PortSignature.of(chosen), PortSignature.of(replacement)).stream()
            .map(PcompText::describe)
            .toList();
    final var question =
        changes.isEmpty()
            ? S.get(
                "pcompReplaceSameSignature",
                Integer.toString(uses),
                replacement.getMetadata().displayName())
            : S.get(
                "pcompReplaceDiffers",
                Integer.toString(uses),
                replacement.getMetadata().displayName(),
                String.join("\n", changes));
    if (OptionPane.showConfirmDialog(this, question, S.get("pcompReplaceTitle"),
        OptionPane.YES_NO_OPTION) != OptionPane.YES_OPTION) {
      return;
    }
    final var action = PcompReplacement.replace(project.getLogisimFile(), chosen, replacement,
        S.getter("pcompReplaceAction"));
    if (action != null) project.doAction(action);
    refresh();
    OptionPane.showMessageDialog(
        this,
        S.get("pcompReplaceDone", Integer.toString(uses), replacement.getMetadata().displayName()),
        S.get("pcompReplaceTitle"),
        OptionPane.INFORMATION_MESSAGE);
  }

  private int usesOf(PcompComponent component) {
    return project == null ? 0 : PcompReplacement.countUses(project.getLogisimFile(), component);
  }

  /** One row per installed version, versions of one component together and in order. */
  private class ComponentTableModel extends AbstractTableModel {
    private static final long serialVersionUID = 1L;
    private List<PcompComponent> rows = List.of();

    private void reload() {
      rows =
          target.installed().stream()
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
