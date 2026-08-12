/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.prefs;

import static com.cburch.logisim.gui.Strings.S;

import com.cburch.logisim.mcp.McpServerManager;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.prefs.PrefMonitor;
import com.cburch.logisim.util.TableLayout;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.TitledBorder;

/**
 * Peler Edition Feature 10: one Preferences tab holding every setting this edition's own features
 * need.
 *
 * <p>Scattering them through upstream's panels was the obvious alternative and is worse in both
 * directions. Someone looking for a setting this edition added has nowhere to look, because there
 * is no way to tell from a panel's title whether the fork touched it; and someone comparing this
 * build against the official one cannot see at a glance what has been added, because the additions
 * would be interleaved with upstream's own options. One tab answers both.
 *
 * <p>The wire-snap toggle is here for a blunter reason: the README has claimed since Feature 3 that
 * auto-snap "can be turned off in preferences", and until this panel existed it could not -- the
 * preference was read by {@code WiringTool} and written by nothing.
 */
class PelerOptions extends OptionsPanel {
  private static final long serialVersionUID = 1L;

  /** Shown instead of a family name when the annotation font follows the platform's own. */
  private final JLabel annotationFontLabel = new JLabel();

  private final JLabel annotationSizeLabel = new JLabel();
  private final JLabel annotationColorLabel = new JLabel();
  private final JLabel snapRadiusLabel = new JLabel();
  private final JLabel mcpPortLabel = new JLabel();
  private final JLabel mcpStatus = new JLabel();

  private final PrefOptionList placement;
  private final PrefOptionList finder;
  private final PrefOptionList quickRotate;
  private final PrefOptionList lossySave;
  private final PrefBoolean wireAutoSnap;
  private final PrefBoolean mcpEnabled;
  private final JComboBox<String> annotationFont;
  private final TitledBorder placementBorder;
  private final TitledBorder wiringBorder;
  private final TitledBorder rotateBorder;
  private final TitledBorder annotationBorder;
  private final TitledBorder filesBorder;
  private final TitledBorder mcpBorder;

  /**
   * True while the combo box is being rewritten from the preference rather than by the user.
   * Removing and re-inserting the translated "default" entry changes the selection, which fires the
   * same action event a click does -- without this the first locale change would write whatever
   * family happened to land in slot zero into the preference.
   */
  private boolean syncing;

  PelerOptions(PreferencesFrame window) {
    super(window);

    placement =
        new PrefOptionList(
            AppPreferences.PLACEMENT_MODE,
            S.getter("pelerPlacementMode"),
            new PrefOption[] {
              new PrefOption(AppPreferences.PLACE_DOUBLE_STICKY, S.getter("pelerPlaceDouble")),
              new PrefOption(AppPreferences.PLACE_SINGLE_STICKY, S.getter("pelerPlaceSingle")),
              new PrefOption(AppPreferences.PLACE_NEVER_STICKY, S.getter("pelerPlaceNever")),
            });
    finder =
        new PrefOptionList(
            AppPreferences.FINDER_PLACEMENT,
            S.getter("pelerFinderPlacement"),
            new PrefOption[] {
              new PrefOption(AppPreferences.FINDER_CONTINUOUS, S.getter("pelerFinderContinuous")),
              new PrefOption(AppPreferences.FINDER_ONCE, S.getter("pelerFinderOnce")),
            });
    quickRotate =
        new PrefOptionList(
            AppPreferences.QUICK_ROTATE_MODE,
            S.getter("pelerQuickRotateMode"),
            new PrefOption[] {
              new PrefOption(AppPreferences.QUICK_ROTATE_CW, S.getter("pelerQuickRotateCw")),
              new PrefOption(AppPreferences.QUICK_ROTATE_CCW, S.getter("pelerQuickRotateCcw")),
              new PrefOption(AppPreferences.QUICK_ROTATE_OFF, S.getter("pelerQuickRotateOff")),
            });
    lossySave =
        new PrefOptionList(
            AppPreferences.LOSSY_SAVE_WARNING,
            S.getter("pelerLossySaveWarning"),
            new PrefOption[] {
              new PrefOption(AppPreferences.LOSSY_WARN_ALWAYS, S.getter("pelerLossyAlways")),
              new PrefOption(AppPreferences.LOSSY_WARN_ONCE, S.getter("pelerLossyOnce")),
              new PrefOption(AppPreferences.LOSSY_WARN_NEVER, S.getter("pelerLossyNever")),
            });
    wireAutoSnap = new PrefBoolean(AppPreferences.WIRE_AUTO_SNAP, S.getter("pelerWireAutoSnap"));
    mcpEnabled = new PrefBoolean(AppPreferences.MCP_ENABLED, S.getter("pelerMcpEnabled"));

    annotationFont = new JComboBox<>();
    annotationFont.addItem(S.get("pelerAnnotationFontDefault"));
    for (final var family :
        GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
      annotationFont.addItem(family);
    }
    selectAnnotationFamily();
    annotationFont.addActionListener(
        event -> {
          if (syncing) return;
          final var chosen = (String) annotationFont.getSelectedItem();
          AppPreferences.ANNOTATION_FONT_FAMILY.set(
              S.get("pelerAnnotationFontDefault").equals(chosen) ? "" : chosen);
        });
    AppPreferences.ANNOTATION_FONT_FAMILY.addPropertyChangeListener(
        event -> {
          if (AppPreferences.ANNOTATION_FONT_FAMILY.isSource(event)) selectAnnotationFamily();
        });

    // GridBagLayout rather than a BoxLayout: a titled box has no maximum height of its own, so a
    // BoxLayout stretches each one to share the window and the controls end up floating in the
    // middle of a tall empty rectangle. Here every section takes the height it asks for and one
    // weighted filler at the bottom absorbs the rest.
    setLayout(new GridBagLayout());
    final var gbc = new GridBagConstraints();
    gbc.gridx = 0;
    gbc.weightx = 1.0;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.anchor = GridBagConstraints.PAGE_START;
    gbc.insets = new Insets(4, 8, 0, 8);

    final var placementPanel = new JPanel(new TableLayout(2));
    placementPanel.add(placement.getJLabel());
    placementPanel.add(placement.getJComboBox());
    placementPanel.add(finder.getJLabel());
    placementPanel.add(finder.getJComboBox());
    placementBorder = section(placementPanel, "pelerPlacementSection", gbc);

    final var wiringPanel = new JPanel();
    wiringPanel.setLayout(new BoxLayout(wiringPanel, BoxLayout.PAGE_AXIS));
    final var snapRow = new JPanel(new TableLayout(2));
    snapRow.add(snapRadiusLabel);
    // Circuit units, not screen pixels, so the same number means the same distance at every zoom
    // level; a grid square is ten of them. Below one square the snap is imperceptible and above
    // four it starts grabbing pins the user was not aiming at.
    snapRow.add(spinner(AppPreferences.WIRE_SNAP_RADIUS, 0, 60, 5));
    wireAutoSnap.setAlignmentX(LEFT_ALIGNMENT);
    snapRow.setAlignmentX(LEFT_ALIGNMENT);
    wiringPanel.add(wireAutoSnap);
    wiringPanel.add(snapRow);
    wiringBorder = section(wiringPanel, "pelerWiringSection", gbc);

    final var rotatePanel = new JPanel(new TableLayout(2));
    rotatePanel.add(quickRotate.getJLabel());
    rotatePanel.add(quickRotate.getJComboBox());
    rotateBorder = section(rotatePanel, "pelerRotateSection", gbc);

    final var annotationPanel = new JPanel(new TableLayout(2));
    annotationPanel.add(annotationFontLabel);
    annotationPanel.add(annotationFont);
    annotationPanel.add(annotationSizeLabel);
    annotationPanel.add(spinner(AppPreferences.ANNOTATION_FONT_SIZE, 6, 72, 1));
    annotationPanel.add(annotationColorLabel);
    annotationPanel.add(new ColorChooserButton(window, AppPreferences.ANNOTATION_COLOR));
    annotationBorder = section(annotationPanel, "pelerAnnotationSection", gbc);

    final var filesPanel = new JPanel(new TableLayout(2));
    filesPanel.add(lossySave.getJLabel());
    filesPanel.add(lossySave.getJComboBox());
    filesBorder = section(filesPanel, "pelerFilesSection", gbc);

    final var mcpPanel = new JPanel();
    mcpPanel.setLayout(new BoxLayout(mcpPanel, BoxLayout.PAGE_AXIS));
    final var mcpRow = new JPanel(new TableLayout(2));
    mcpRow.add(mcpPortLabel);
    // Zero means "any free port", which is what someone behind a port conflict needs; the rest of
    // the range is arbitrary and only exists to keep a typo from producing an unbindable number.
    mcpRow.add(spinner(AppPreferences.MCP_PORT, 0, 65535, 1));
    mcpEnabled.setAlignmentX(LEFT_ALIGNMENT);
    mcpRow.setAlignmentX(LEFT_ALIGNMENT);
    mcpStatus.setAlignmentX(LEFT_ALIGNMENT);
    mcpStatus.setFont(mcpStatus.getFont().deriveFont(Font.ITALIC));
    mcpPanel.add(mcpEnabled);
    mcpPanel.add(mcpRow);
    mcpPanel.add(mcpStatus);
    mcpBorder = section(mcpPanel, "pelerMcpSection", gbc);
    AppPreferences.MCP_ENABLED.addPropertyChangeListener(
        event -> {
          if (AppPreferences.MCP_ENABLED.isSource(event)) applyMcpState();
        });
    applyMcpState();

    gbc.gridy++;
    gbc.weighty = 1.0;
    gbc.fill = GridBagConstraints.BOTH;
    add(Box.createGlue(), gbc);
    localeChanged();
  }

  /**
   * Wraps one group of settings in a titled box and adds it, returning the border to relabel.
   *
   * <p>The border goes on a wrapper rather than on the settings panel itself because {@link
   * TableLayout} ignores its container's insets in both {@code preferredLayoutSize} and {@code
   * layoutContainer}, so a titled border put straight on one is drawn through the first row of
   * controls. That is upstream's layout manager and used by upstream's own panels, so it is worked
   * around here rather than changed underneath them.
   */
  private TitledBorder section(JPanel content, String titleKey, GridBagConstraints gbc) {
    final var border = BorderFactory.createTitledBorder(S.get(titleKey));
    final var box = new JPanel(new BorderLayout());
    box.setBorder(border);
    box.add(content, BorderLayout.CENTER);
    gbc.gridy++;
    add(box, gbc);
    return border;
  }

  /**
   * A spinner bound to an integer preference in both directions. Written here rather than as
   * another {@code Pref*} class in this package because two spinners is not a pattern yet.
   */
  private JSpinner spinner(PrefMonitor<Integer> pref, int min, int max, int step) {
    final var model = new SpinnerNumberModel((int) pref.get(), min, max, step);
    final var field = new JSpinner(model);
    // No thousands separator: these are a port number and two lengths, and "8,765" in a port field
    // reads as a typo rather than as eight thousand seven hundred and sixty-five.
    field.setEditor(new JSpinner.NumberEditor(field, "#"));
    field.addChangeListener(event -> pref.set((Integer) field.getValue()));
    pref.addPropertyChangeListener(
        event -> {
          if (pref.isSource(event) && !pref.get().equals(field.getValue())) {
            field.setValue(pref.get());
          }
        });
    return field;
  }

  /**
   * Starts or stops the MCP server to match the checkbox, and says which it is.
   *
   * <p>Applied immediately rather than at the next launch: a setting that silently needs a restart
   * is one people conclude is broken, and this one is worth being able to switch off the moment it
   * is no longer wanted.
   */
  private void applyMcpState() {
    final var manager = McpServerManager.getInstance();
    if (AppPreferences.MCP_ENABLED.getBoolean()) {
      McpServerManager.ensureToken();
      if (!manager.isRunning()) McpServerManager.startFromPreferences();
    } else if (manager.isRunning()) {
      manager.close();
    }
    final var endpoint = manager.isRunning() ? manager.endpoint() : null;
    mcpStatus.setText(
        endpoint == null ? S.get("pelerMcpStopped") : S.get("pelerMcpRunning", endpoint));
  }

  private void selectAnnotationFamily() {
    final var family = AppPreferences.ANNOTATION_FONT_FAMILY.get();
    syncing = true;
    try {
      annotationFont.setSelectedItem(
          (family == null || family.isEmpty()) ? S.get("pelerAnnotationFontDefault") : family);
    } finally {
      syncing = false;
    }
  }

  @Override
  public String getHelpText() {
    return S.get("pelerHelp");
  }

  @Override
  public String getTitle() {
    return S.get("pelerTitle");
  }

  @Override
  public void localeChanged() {
    placement.localeChanged();
    finder.localeChanged();
    quickRotate.localeChanged();
    lossySave.localeChanged();
    wireAutoSnap.localeChanged();
    mcpEnabled.localeChanged();
    snapRadiusLabel.setText(S.get("pelerWireSnapRadius"));
    mcpPortLabel.setText(S.get("pelerMcpPort"));
    mcpBorder.setTitle(S.get("pelerMcpSection"));
    applyMcpState();
    annotationFontLabel.setText(S.get("pelerAnnotationFont"));
    annotationSizeLabel.setText(S.get("pelerAnnotationSize"));
    annotationColorLabel.setText(S.get("pelerAnnotationColor"));
    placementBorder.setTitle(S.get("pelerPlacementSection"));
    wiringBorder.setTitle(S.get("pelerWiringSection"));
    rotateBorder.setTitle(S.get("pelerRotateSection"));
    annotationBorder.setTitle(S.get("pelerAnnotationSection"));
    filesBorder.setTitle(S.get("pelerFilesSection"));
    // The default entry is the only item whose text is translated; rebuilding the whole family
    // list on a locale change would lose the selection for no gain.
    syncing = true;
    try {
      annotationFont.removeItemAt(0);
      annotationFont.insertItemAt(S.get("pelerAnnotationFontDefault"), 0);
    } finally {
      syncing = false;
    }
    selectAnnotationFamily();
    repaint();
  }
}
