/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.pcomp;

import static com.cburch.logisim.gui.Strings.S;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.PcompWriter;
import com.cburch.logisim.gui.generic.OptionPane;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.pcomp.PcompComponentLibrary;
import com.cburch.logisim.pcomp.PcompFile;
import com.cburch.logisim.pcomp.PcompComponent;
import com.cburch.logisim.pcomp.PcompMetadata;
import com.cburch.logisim.pcomp.PortLayoutDraft;
import com.cburch.logisim.pcomp.PortSignature;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.Projects;
import com.cburch.logisim.tools.SetAttributeAction;
import com.cburch.logisim.util.SyntaxChecker;
import java.awt.BorderLayout;
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
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;

/**
 * Peler Edition. The window that turns a circuit into a custom component.
 *
 * <p>It exists because the layout is fixed at creation. Once a component is published its ports are
 * where they are -- changing them would move every wire in every project that already uses it -- so
 * this is the one and only chance to say where they go, and it is shown rather than assumed.
 *
 * <p>It opens in one of two modes, and the difference is not a setting but where it was opened
 * from. On an ordinary project it publishes a new component. On a component's own file -- which is
 * how a published component is edited, there being no other way in -- it republishes that one, and
 * then the port signature decides what "save" means: unchanged, it overwrites the version it came
 * from; changed, the only thing on offer is a new version, because the old one is under somebody's
 * wires.
 *
 * <p>Renaming a port renames the circuit's pin, as an undoable edit on the project. The metadata
 * names the ports and {@code PcompAppearance} binds each one to the pin carrying that label, so the
 * two cannot be allowed to drift; naming a port in this window is naming the pin.
 */
public class PcompSaveDialog extends JDialog {
  private static final long serialVersionUID = 1L;

  /**
   * The published component this window is a new edition of, when there is one.
   *
   * @param file where it lives, which an overwrite writes back to
   * @param metadata its identity, which a new version inherits
   * @param published its ports as they were published, read back off the file rather than taken
   *     from the circuit on screen -- that one is what the user has been editing, so comparing it
   *     with itself would never find a difference
   */
  private record Republish(File file, PcompMetadata metadata, List<PortSignature> published) {}

  private final Project project;
  private final Circuit circuit;
  private final Republish republish;
  /**
   * Peler Edition. Fixed for a republish -- see {@code docs/peler-edition/design/pcomp-libraries.md}
   * -- the library a new version has to stay in, resolved from whichever open project (not
   * necessarily this one) happens to have it loaded. Null either when this is not a republish, or
   * when it is one but no open project currently has that library loaded to resolve against; the
   * second case falls back to the file's own directory with no library to notify of the new version.
   */
  private final PcompLibraryTarget republishTarget;
  private final PortLayoutDraft draft;
  private final PcompLayoutCanvas canvas;
  private final PortTable model = new PortTable();
  private final JTable table = new JTable(model);
  private final JLabel nameLabel = new JLabel();
  private final JTextField name = new JTextField(18);
  private final JLabel targetLabel = new JLabel();
  private final JComboBox<PcompLibraryTarget> targetChooser = new JComboBox<>();
  private final JLabel targetFixed = new JLabel();
  private final JLabel problem = new JLabel(" ");
  private final JLabel plan = new JLabel(" ");
  private final JButton arrange = new JButton();
  private final JButton save = new JButton();
  private final JButton cancel = new JButton();

  private PcompSaveDialog(
      Window parent,
      Project project,
      Circuit circuit,
      Republish republish,
      PcompComponentLibrary defaultTarget) {
    super(parent, S.get("pcompSaveTitle"), ModalityType.APPLICATION_MODAL);
    this.project = project;
    this.circuit = circuit;
    this.republish = republish;
    this.republishTarget = republish == null ? null : resolveRepublishTarget(republish.file());
    this.draft =
        republish == null
            ? PortLayoutDraft.of(circuit)
            : PortLayoutDraft.of(
                circuit, republish.metadata().name(), republish.metadata().layout());
    this.canvas = new PcompLayoutCanvas(draft, this::refresh);

    name.setText(draft.caption());
    // The name is part of what a project file records about a placed component, so changing it
    // later would leave those references pointing at nothing. It is settled here, once.
    name.setEditable(republish == null);
    name.getDocument().addDocumentListener(new NameWatcher());

    final var hint = new JLabel(S.get("pcompSaveHint"));
    hint.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));
    final var nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    nameRow.setBorder(BorderFactory.createEmptyBorder(0, 6, 8, 10));
    nameRow.add(nameLabel);
    nameRow.add(name);

    // Peler Edition: which library this goes into. Fixed rather than offered when republishing --
    // see the field javadoc on republishTarget for why a new version cannot move libraries.
    targetChooser.setRenderer(
        (list, value, index, isSelected, hasFocus) ->
            new JLabel(value == null ? " " : value.displayName()));
    final var targets = republish == null ? PcompLibraryTarget.allIn(project.getLogisimFile())
        : List.<PcompLibraryTarget>of();
    for (final var target : targets) targetChooser.addItem(target);
    if (republish == null) {
      targetChooser.setSelectedItem(initialTarget(targets, defaultTarget));
      targetFixed.setVisible(false);
    } else {
      targetChooser.setVisible(false);
      targetFixed.setText(
          republishTarget != null
              ? republishTarget.displayName()
              : republish.file().getParentFile().getName());
    }
    final var targetRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    targetRow.setBorder(BorderFactory.createEmptyBorder(0, 6, 8, 10));
    targetRow.add(targetLabel);
    targetRow.add(targetChooser);
    targetRow.add(targetFixed);

    final var top = new JPanel();
    top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
    top.add(hint);
    top.add(nameRow);
    top.add(targetRow);

    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setRowHeight(table.getRowHeight() + 4);
    final var tableArea = new JScrollPane(table);
    tableArea.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));

    final var middle = new JPanel();
    middle.setLayout(new BoxLayout(middle, BoxLayout.X_AXIS));
    middle.add(canvas);
    middle.add(tableArea);

    problem.setBorder(BorderFactory.createEmptyBorder(6, 10, 0, 10));
    plan.setBorder(BorderFactory.createEmptyBorder(2, 10, 0, 10));

    arrange.addActionListener(
        event -> {
          draft.arrange();
          refresh();
        });
    save.addActionListener(event -> onSave());
    cancel.addActionListener(event -> dispose());
    final var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    buttons.add(arrange);
    buttons.add(cancel);
    buttons.add(save);

    final var bottom = new JPanel();
    bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
    bottom.add(problem);
    bottom.add(plan);
    bottom.add(Box.createVerticalStrut(4));
    bottom.add(buttons);

    setLayout(new BorderLayout());
    add(top, BorderLayout.NORTH);
    add(middle, BorderLayout.CENTER);
    add(bottom, BorderLayout.SOUTH);
    localeChanged();
    refresh();
    pack();
    setLocationRelativeTo(parent);
  }

  /** Opens the window for the circuit the user is looking at, defaulting into My Components. */
  public static void open(Project project) {
    open(project, null);
  }

  /**
   * Peler Edition. As {@link #open(Project)}, but the target-library dropdown starts on {@code
   * defaultTarget} instead of the built-in catalog -- used by the library manager window's "save
   * current circuit here", so picking a library there and saving into it is not a two-step trip
   * through this dialog's own dropdown as well. See {@code
   * docs/peler-edition/design/pcomp-libraries.md}.
   */
  public static void open(Project project, PcompComponentLibrary defaultTarget) {
    final var circuit = project == null ? null : project.getCurrentCircuit();
    final var parent = project == null ? null : project.getFrame();
    if (circuit == null) {
      OptionPane.showMessageDialog(parent, S.get("pcompNoCircuit"), S.get("pcompSaveTitle"),
          OptionPane.WARNING_MESSAGE);
      return;
    }
    new PcompSaveDialog(parent, project, circuit, republishOf(project, circuit), defaultTarget)
        .setVisible(true);
  }

  /** The entry in {@code targets} to preselect: {@code defaultTarget} if it is one of them, else My Components. */
  private static PcompLibraryTarget initialTarget(
      List<PcompLibraryTarget> targets, PcompComponentLibrary defaultTarget) {
    if (defaultTarget != null) {
      for (final var target : targets) {
        if (target instanceof PcompLibraryTarget.OfLibrary of && of.library() == defaultTarget) {
          return target;
        }
      }
    }
    for (final var target : targets) {
      if (target instanceof PcompLibraryTarget.OfCatalog) return target;
    }
    return targets.isEmpty() ? null : targets.get(0);
  }

  /**
   * The library {@code file} lives in, if any open project (not necessarily this dialog's own) has
   * it loaded right now. A component's own file never lists the library it is a member of as one of
   * its own dependencies -- there is no reason for it to -- so this cannot be answered by looking at
   * {@code file}'s own project the way {@link PcompLibraryTarget#allIn} normally would; it has to be
   * searched for the same way {@code PcompLibraries.componentOf(Circuit)} searches for an owning
   * component with no project in hand.
   */
  private static PcompLibraryTarget resolveRepublishTarget(File file) {
    final var directory = file.getParentFile();
    for (final var openProject : Projects.getOpenProjects()) {
      for (final var target : PcompLibraryTarget.allIn(openProject.getLogisimFile())) {
        if (target.directory().equals(directory)) return target;
      }
    }
    return null;
  }

  /**
   * Works out whether the project on screen is a component's own file.
   *
   * <p>Everything is read off the file rather than the catalog, so that editing a component the
   * user has since removed from the catalog still republishes that component rather than quietly
   * starting a new one with a new identity.
   */
  private static Republish republishOf(Project project, Circuit circuit) {
    final var file = project.getLogisimFile();
    final var loader = file == null ? null : file.getLoader();
    final var source = loader == null ? null : loader.getMainFile();
    if (!PcompFile.isPcompFile(source)) return null;
    try {
      final var metadata = PcompFile.read(source);
      if (metadata == null || !metadata.mainCircuit().equals(circuit.getName())) return null;
      final var published = PcompComponent.load(source, new Loader(null));
      return new Republish(source, metadata, PortSignature.of(published));
    } catch (IOException e) {
      // The file the user has open cannot be read back as a component. Publishing a new one is
      // still a reasonable thing to want, and refusing to open the window would explain nothing.
      return null;
    }
  }

  /** Asks for one port's name. Shared with the canvas, where a double click means "rename this". */
  static void askForName(java.awt.Component parent, PortLayoutDraft draft,
      PortLayoutDraft.Entry entry, Runnable onChange) {
    final var answer = OptionPane.showInputDialog(parent, S.get("pcompRenamePrompt"),
        S.get("pcompRenameTitle"), OptionPane.QUESTION_MESSAGE, null, null, entry.name());
    if (answer == null) return;
    draft.rename(entry, answer.toString());
    onChange.run();
  }

  private void localeChanged() {
    nameLabel.setText(S.get("pcompNameLabel"));
    targetLabel.setText(S.get("pcompTargetLabel"));
    cancel.setText(S.get("pcompCancelButton"));
    arrange.setText(S.get("pcompArrangeButton"));
  }

  private void refresh() {
    draft.setCaption(name.getText());
    model.reload();
    canvas.repaint();

    final var wrong = whatIsWrong();
    problem.setText(wrong == null ? " " : wrong);
    save.setEnabled(wrong == null);
    final var planned = wrong == null ? plannedMetadata() : null;
    save.setText(
        planned != null && republish != null && planned.version() != republish.metadata().version()
            ? S.get("pcompPublishButton")
            : S.get("pcompSaveButton"));
    plan.setText(planned == null ? " " : describe(planned));
  }

  /** The first thing standing between the user and a saveable component, in words. */
  private String whatIsWrong() {
    final var typed = name.getText().trim();
    if (typed.isEmpty()) return S.get("pcompProblemNoName");
    if (SyntaxChecker.getErrorMessage(PcompMetadata.circuitNameFor(typed, 1)) != null) {
      return S.get("pcompProblemBadName");
    }
    final var wrong = draft.problem();
    if (wrong != null) {
      return switch (wrong) {
        case NO_PORTS -> S.get("pcompProblemNoPorts");
        case UNNAMED -> S.get("pcompProblemUnnamed");
        case DUPLICATE_NAME -> S.get("pcompProblemDuplicate");
        case OVERLAP -> S.get("pcompProblemOverlap");
      };
    }
    return null;
  }

  /** What saving right now would write: a first version, the same version again, or the next one. */
  private PcompMetadata plannedMetadata() {
    final var layout = draft.layout();
    if (republish == null) return PcompMetadata.firstVersion(name.getText().trim(), layout);
    return PortSignature.of(draft).equals(republish.published())
        ? republish.metadata().withLayout(layout)
        : republish.metadata().nextVersion(layout);
  }

  private String describe(PcompMetadata planned) {
    if (republish == null) return S.get("pcompPlanNew", planned.displayName());
    if (planned.version() == republish.metadata().version()) {
      return S.get("pcompPlanSame", planned.displayName());
    }
    final var changes =
        PortSignature.differences(republish.published(), PortSignature.of(draft)).stream()
            .map(PcompText::describe)
            .toList();
    return S.get("pcompPlanNewVersion", planned.displayName(), String.join("; ", changes));
  }

  /** Which library is actually being saved into right now: fixed for a republish, else chosen. */
  private PcompLibraryTarget currentTarget() {
    return republish != null ? republishTarget : (PcompLibraryTarget) targetChooser.getSelectedItem();
  }

  private void onSave() {
    if (whatIsWrong() != null) return;
    final var metadata = plannedMetadata();
    final var destination = destination(metadata);
    if (destination == null) return;
    applyPinNames();
    try {
      PcompWriter.write(destination, project.getLogisimFile(), circuit, metadata,
          project.getLogisimFile().getLoader());
      // A loader of its own: the project's one carries the state of the file it is in the middle
      // of, and reading the component back has nothing to do with that. Null only for a republish
      // whose library nothing currently has loaded -- see republishTarget's javadoc -- in which case
      // there is nothing loaded to bring up to date either.
      final var target = currentTarget();
      if (target != null) target.install(destination, new Loader(null));
    } catch (IOException e) {
      OptionPane.showMessageDialog(this, S.get("pcompSaveFailed", String.valueOf(e.getMessage())),
          S.get("pcompSaveTitle"), OptionPane.ERROR_MESSAGE);
      return;
    }
    rebuildEveryToolbox();
    dispose();
    OptionPane.showMessageDialog(project.getFrame(),
        S.get("pcompSaveDone", destination.getAbsolutePath()), S.get("pcompSaveTitle"),
        OptionPane.INFORMATION_MESSAGE);
  }

  /** Every open project shows the catalog, so every open project has to be told it changed. */
  static void rebuildEveryToolbox() {
    for (final var open : Projects.getOpenProjects()) {
      final var frame = open.getFrame();
      if (frame != null) frame.rebuildToolbox();
    }
  }

  /**
   * Where the component goes.
   *
   * <p>The file is named after the versioned circuit inside it, so two versions never contend for
   * one name. A file that is already there is either this component's own previous copy, which is
   * what an overwrite is for, or a different component that got there first -- and that one is
   * refused rather than confirmed, because there would be no way back from saying yes.
   */
  private File destination(PcompMetadata metadata) {
    final var target = currentTarget();
    // Null only for a republish whose library nothing has loaded right now -- see republishTarget's
    // javadoc -- in which case there is nowhere else to look but where the file already is.
    final var directory = target != null ? target.directory() : republish.file().getParentFile();
    if (!directory.isDirectory() && !directory.mkdirs()) {
      OptionPane.showMessageDialog(this, S.get("pcompNoDirectory", directory.getAbsolutePath()),
          S.get("pcompSaveTitle"), OptionPane.ERROR_MESSAGE);
      return null;
    }
    final var installed = target != null ? target.installed() : List.<PcompComponent>of();
    for (final var other : installed) {
      final var otherMetadata = other.getMetadata();
      if (otherMetadata.mainCircuit().equals(metadata.mainCircuit())
          && !otherMetadata.id().equals(metadata.id())) {
        OptionPane.showMessageDialog(this, S.get("pcompNameTaken", metadata.displayName()),
            S.get("pcompSaveTitle"), OptionPane.ERROR_MESSAGE);
        return null;
      }
    }
    final var file = new File(directory, metadata.mainCircuit() + PcompFile.EXTENSION);
    if (file.exists() && (republish == null || !file.equals(republish.file()))) {
      final var answer = OptionPane.showConfirmDialog(this,
          S.get("pcompOverwriteMessage", file.getName()), S.get("pcompSaveTitle"),
          OptionPane.YES_NO_OPTION);
      if (answer != OptionPane.YES_OPTION) return null;
    }
    return file;
  }

  /**
   * Writes the names back onto the pins, as one undoable step.
   *
   * <p>Done before the component is written, because the writer works from a copy of the project
   * and reads the labels off it.
   */
  private void applyPinNames() {
    final var action = new SetAttributeAction(circuit, S.getter("pcompNamePinsAction"));
    var changed = false;
    for (final var entry : draft.all()) {
      final var current = entry.pin().getAttributeValue(StdAttr.LABEL);
      if (entry.name().equals(current)) continue;
      action.set(entry.pin().getComponent(), StdAttr.LABEL, entry.name());
      changed = true;
    }
    if (changed) project.doAction(action);
  }

  /** Keeps the preview and the verdict in step with the name as it is typed. */
  private class NameWatcher implements DocumentListener {
    @Override
    public void insertUpdate(DocumentEvent event) {
      refresh();
    }

    @Override
    public void removeUpdate(DocumentEvent event) {
      refresh();
    }

    @Override
    public void changedUpdate(DocumentEvent event) {
      refresh();
    }
  }

  /** The ports as rows: the name is the editable half, everything else follows the drawing. */
  private class PortTable extends AbstractTableModel {
    private static final long serialVersionUID = 1L;
    // Left empty on purpose: this is a field of the dialog, so it is built before the constructor
    // body has a draft to read. The constructor's own refresh() fills it in.
    private List<PortLayoutDraft.Entry> rows = List.of();

    private void reload() {
      rows = new ArrayList<>(draft.all());
      fireTableDataChanged();
    }

    @Override
    public int getRowCount() {
      return rows.size();
    }

    @Override
    public int getColumnCount() {
      return 3;
    }

    @Override
    public String getColumnName(int column) {
      return switch (column) {
        case 0 -> S.get("pcompColumnName");
        case 1 -> S.get("pcompColumnDirection");
        default -> S.get("pcompColumnSide");
      };
    }

    @Override
    public boolean isCellEditable(int row, int column) {
      return column == 0;
    }

    @Override
    public Object getValueAt(int row, int column) {
      final var entry = rows.get(row);
      return switch (column) {
        case 0 -> entry.name();
        case 1 -> S.get(entry.isInput() ? "pcompDirectionInput" : "pcompDirectionOutput");
        default -> PcompText.sideName(entry.side());
      };
    }

    @Override
    public void setValueAt(Object value, int row, int column) {
      if (column != 0) return;
      draft.rename(rows.get(row), value == null ? "" : value.toString());
      refresh();
    }
  }
}
