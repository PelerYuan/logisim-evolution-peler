/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.find;

import static com.cburch.logisim.gui.Strings.S;

import com.cburch.logisim.comp.ComponentDrawContext;
import com.cburch.logisim.gui.main.Frame;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.tools.ContinuousPlacement;
import com.cburch.logisim.tools.Tool;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Peler Edition Feature 6. A floating finder for the toolbox: one hotkey, type a few letters, hit
 * Enter, and the component is selected and ready to place -- without hunting through the tree or
 * remembering which library something lives in. Official Logisim-evolution has nothing like it
 * (there is no search field anywhere in its interface, verified against v4.1.0).
 *
 * <p>Behaves the way this kind of palette is expected to: it is undecorated, it takes keyboard
 * focus, and it disappears the moment it loses focus or Escape is pressed. Deliberately a modeless
 * {@link JDialog} rather than a {@code JWindow} -- a JWindow does not reliably accept keyboard
 * focus on Windows, which would make the whole thing useless.
 *
 * <p>Icons are the real component icons, because Logisim components draw their own icons rather
 * than shipping images: {@link ToolIcon} hands the tool a {@link ComponentDrawContext} exactly as
 * {@code ProjectExplorer} does for the tree, so what shows up here always matches the toolbox,
 * including custom subcircuit appearances.
 */
public final class FindToolDialog extends JDialog {
  private static final long serialVersionUID = 1L;

  /** How many matches to show. Enough to choose from, few enough to scan without scrolling much. */
  private static final int MAX_RESULTS = 12;

  private static FindToolDialog current;

  private final Project proj;
  private final Frame frame;
  private final JTextField input = new JTextField();
  private final DefaultListModel<ToolSearch.Entry> model = new DefaultListModel<>();
  private final JList<ToolSearch.Entry> results = new JList<>(model);
  private final List<ToolSearch.Entry> allTools;

  /**
   * Opens the finder, or brings the open one back to the front. Never opens a second copy: the
   * hotkey is easy to hit twice, and two of these fighting over focus would close each other.
   */
  public static void open(Frame frame, Project proj) {
    if (proj == null || frame == null) return;
    if (current != null && current.isDisplayable()) {
      current.toFront();
      current.input.requestFocusInWindow();
      return;
    }
    current = new FindToolDialog(frame, proj);
    current.setVisible(true);
  }

  private FindToolDialog(Frame frame, Project proj) {
    super(frame, false);
    this.frame = frame;
    this.proj = proj;
    this.allTools = ToolSearch.index(proj.getLogisimFile());

    setUndecorated(true);
    setFocusableWindowState(true);

    input.putClientProperty("JTextField.placeholderText", S.get("findToolPrompt"));
    input.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
    input.setFont(input.getFont().deriveFont(input.getFont().getSize2D() + 2f));

    results.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    results.setCellRenderer(new EntryRenderer());
    results.setFocusable(false); // the text field keeps focus; arrows are forwarded to the list

    final var scroll = new JScrollPane(results);
    scroll.setBorder(BorderFactory.createEmptyBorder());

    final var content = new JPanel(new BorderLayout());
    content.setBorder(BorderFactory.createLineBorder(new Color(0x80808080, true)));
    content.add(input, BorderLayout.NORTH);
    content.add(scroll, BorderLayout.CENTER);
    setContentPane(content);

    input.getDocument().addDocumentListener(new RefreshListener());
    installKeys();

    results.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            final var index = results.locationToIndex(e.getPoint());
            if (index < 0) return;
            results.setSelectedIndex(index);
            // Double-clicking a match is the mouse's Enter, so it means whatever Enter means, and
            // Shift reverses it the same way. Hardcoding "keep placing" here would leave the
            // setting looking ignored for anyone who picks with the mouse.
            if (e.getClickCount() >= 2) {
              final var shifted = (e.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0;
              chooseSelected(continuousUnlessShifted() != shifted);
            }
          }
        });

    addWindowFocusListener(
        new WindowAdapter() {
          @Override
          public void windowGainedFocus(WindowEvent e) {
            // Belt and braces: the text field is the first focusable component so it normally gets
            // focus on its own, but asking here is the only way that is guaranteed -- calling
            // requestFocusInWindow() before the window is shown is a silent no-op.
            input.requestFocusInWindow();
          }

          @Override
          public void windowLostFocus(WindowEvent e) {
            close();
          }
        });

    refresh();
    setSize(new Dimension(AppPreferences.getScaled(520), AppPreferences.getScaled(360)));
    setLocationRelativeTo(frame);
    // Sitting dead centre puts it over the part of the canvas being worked on; a little above
    // centre keeps that clear while staying somewhere the eye already is.
    setLocation(getX(), Math.max(frame.getY() + AppPreferences.getScaled(60), getY() - getHeight() / 3));
  }

  private void installKeys() {
    final var inputMap = input.getInputMap(JComponent.WHEN_FOCUSED);
    final var actionMap = input.getActionMap();

    inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "find.close");
    actionMap.put(
        "find.close",
        new AbstractAction() {
          private static final long serialVersionUID = 1L;

          @Override
          public void actionPerformed(ActionEvent e) {
            close();
          }
        });

    inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "find.next");
    actionMap.put("find.next", new MoveAction(1));
    inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "find.previous");
    actionMap.put("find.previous", new MoveAction(-1));

    // Which of these keeps placing is Preferences -> Peler's Features (Feature 10); Shift always
    // means the other one, so both behaviours stay one keystroke away whichever way it is set.
    inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "find.accept");
    actionMap.put("find.accept", new AcceptAction(false));
    inputMap.put(
        KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.SHIFT_DOWN_MASK),
        "find.acceptSwapped");
    actionMap.put("find.acceptSwapped", new AcceptAction(true));
  }

  /**
   * Whether accepting a match without Shift should keep the tool armed. Read at the moment the key
   * is pressed rather than when the dialog was built, so changing the setting takes effect on the
   * next search rather than the next launch.
   */
  private static boolean continuousUnlessShifted() {
    return AppPreferences.FINDER_CONTINUOUS.equals(AppPreferences.FINDER_PLACEMENT.get());
  }

  private void refresh() {
    final var matches = ToolSearch.search(allTools, input.getText(), MAX_RESULTS);
    model.clear();
    for (final var entry : matches) model.addElement(entry);
    if (!model.isEmpty()) {
      results.setSelectedIndex(0);
      results.ensureIndexIsVisible(0);
    }
  }

  private void move(int delta) {
    if (model.isEmpty()) return;
    final var size = model.getSize();
    // Wrap around: with a short list it is quicker to press Up once than Down eleven times.
    final var next = (results.getSelectedIndex() + delta + size) % size;
    results.setSelectedIndex(next);
    results.ensureIndexIsVisible(next);
  }

  /**
   * Selects the chosen tool and gets out of the way.
   *
   * <p>Not called {@code accept}. {@link javax.swing.Action} has carried a
   * {@code default boolean accept(Object)} since JDK 9, so inside {@link AcceptAction} -- which
   * inherits it -- an unqualified {@code accept(sticky)} resolves to that default method, boxing
   * the flag and returning true without ever reaching this class. It compiles without a warning and
   * silently does nothing, which is exactly what Enter and Shift+Enter used to do. Any name that no
   * nested {@code Action} can inherit is safe; this one is not up for tidying back.
   *
   * @param sticky arm continuous placement, as double-clicking the tool elsewhere does
   */
  private void chooseSelected(boolean sticky) {
    final var entry = results.getSelectedValue();
    if (entry == null) return;
    final var tool = entry.tool();
    close();

    // Subcircuits and VHDL entities are excluded from continuous mode inside arm(), which is also
    // what the toolbox tree and the toolbar go through, so all three stay in step by construction.
    ContinuousPlacement.arm(proj, tool, sticky);
    // Without this the canvas has no keyboard focus, so the tool is armed but the first click is
    // spent just focusing the canvas again.
    SwingUtilities.invokeLater(() -> frame.getCanvas().requestFocus());
  }

  private void close() {
    if (current == this) current = null;
    setVisible(false);
    dispose();
  }

  private final class MoveAction extends AbstractAction {
    private static final long serialVersionUID = 1L;
    private final int delta;

    MoveAction(int delta) {
      this.delta = delta;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      move(delta);
    }
  }

  private final class AcceptAction extends AbstractAction {
    private static final long serialVersionUID = 1L;

    /** Whether Shift was held, not whether to keep placing -- the setting decides which is which. */
    private final boolean shifted;

    AcceptAction(boolean shifted) {
      this.shifted = shifted;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
      chooseSelected(continuousUnlessShifted() != shifted);
    }
  }

  private final class RefreshListener implements DocumentListener {
    @Override
    public void insertUpdate(DocumentEvent e) {
      refresh();
    }

    @Override
    public void removeUpdate(DocumentEvent e) {
      refresh();
    }

    @Override
    public void changedUpdate(DocumentEvent e) {
      refresh();
    }
  }

  /**
   * Icon, library, and -- when it differs from what is displayed -- the English identifier, since
   * that is what a search like "and" actually matched in a non-English interface.
   */
  private final class EntryRenderer extends JPanel implements ListCellRenderer<ToolSearch.Entry> {
    private static final long serialVersionUID = 1L;
    private final JLabel icon = new JLabel();
    private final JLabel name = new JLabel();
    private final JLabel detail = new JLabel();

    EntryRenderer() {
      setLayout(new BorderLayout(8, 0));
      setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
      // BorderLayout rather than a vertical BoxLayout on purpose. A list renderer is rubber-stamped
      // -- one panel painted over and over with different text -- and it never joins a real
      // container hierarchy, so revalidate() does nothing and BoxLayout happily reuses the child
      // widths it cached from an earlier row. That showed up as the first result rendering as
      // "NAND..." because the row painted before it had a shorter name. BorderLayout gives each
      // child the full width every time and has nothing to cache.
      final var text = new JPanel(new BorderLayout());
      text.setOpaque(false);
      text.add(name, BorderLayout.NORTH);
      text.add(detail, BorderLayout.CENTER);
      detail.setFont(detail.getFont().deriveFont(Font.PLAIN, detail.getFont().getSize2D() - 1f));
      add(icon, BorderLayout.WEST);
      add(text, BorderLayout.CENTER);
    }

    @Override
    public Component getListCellRendererComponent(
        JList<? extends ToolSearch.Entry> list,
        ToolSearch.Entry value,
        int index,
        boolean isSelected,
        boolean cellHasFocus) {
      setOpaque(true);
      setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
      final var fg = isSelected ? list.getSelectionForeground() : list.getForeground();
      name.setForeground(fg);
      detail.setForeground(isSelected ? fg : UiColors.secondary(fg, list.getBackground()));

      icon.setIcon(new ToolIcon(value.tool()));
      name.setText(value.displayName());
      detail.setText(
          value.idName().equalsIgnoreCase(value.displayName())
              ? value.library()
              : value.library() + "  ·  " + value.idName());
      return this;
    }
  }

  /** Muted colour for the secondary line, derived so it works in a light or a dark theme. */
  private static final class UiColors {
    private UiColors() {}

    static Color secondary(Color foreground, Color background) {
      return new Color(
          (foreground.getRed() + background.getRed()) / 2,
          (foreground.getGreen() + background.getGreen()) / 2,
          (foreground.getBlue() + background.getBlue()) / 2);
    }
  }

  /**
   * Paints a tool's own icon, the way {@code ProjectExplorer} paints them for the toolbox tree --
   * the tool draws itself into a {@link ComponentDrawContext}. Copied rather than shared because
   * upstream's version is a private inner class that also draws the halo and the "currently viewed
   * circuit" magnifier, neither of which belongs in a search result.
   */
  private final class ToolIcon implements Icon {
    private final Tool tool;

    ToolIcon(Tool tool) {
      this.tool = tool;
    }

    @Override
    public int getIconHeight() {
      return AppPreferences.getScaled(AppPreferences.BOX_SIZE);
    }

    @Override
    public int getIconWidth() {
      return AppPreferences.getScaled(AppPreferences.BOX_SIZE);
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
      g.setColor(new Color(AppPreferences.COMPONENT_ICON_COLOR.get()));
      final var gfxIcon = g.create();
      final var context = new ComponentDrawContext(c, null, null, g, gfxIcon);
      tool.paintIcon(
          context,
          x + AppPreferences.getScaled(AppPreferences.ICON_BORDER),
          y + AppPreferences.getScaled(AppPreferences.ICON_BORDER));
      gfxIcon.dispose();
    }
  }
}
