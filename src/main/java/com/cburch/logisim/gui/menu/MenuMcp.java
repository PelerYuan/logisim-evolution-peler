/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.menu;

import static com.cburch.logisim.gui.Strings.S;

import com.cburch.logisim.gui.generic.OptionPane;
import com.cburch.logisim.gui.prefs.PreferencesFrame;
import com.cburch.logisim.mcp.McpBundleWriter;
import com.cburch.logisim.mcp.McpServerManager;
import com.cburch.logisim.util.JFileChoosers;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Peler Edition Feature 11. Everything about handing this application to an AI client.
 *
 * <p>Its own menu rather than more entries under Help, because these are not documentation: they
 * hand out an access token and write an installable bundle.
 *
 * <p>Stays enabled while the server is switched off, which is most of the time -- it is off until
 * asked for. A greyed-out menu is the one thing that cannot say why it is greyed out, and this one
 * would be greyed out on a first run, which is exactly when someone is looking for the feature.
 * Both entries instead explain what MCP is and offer to open the settings page that turns it on.
 */
class MenuMcp extends JMenu implements ActionListener {

  private static final long serialVersionUID = 1L;

  private final LogisimMenuBar menubar;
  private final JMenuItem copyConfig = new JMenuItem();
  private final JMenuItem exportBundle = new JMenuItem();

  MenuMcp(LogisimMenuBar menubar) {
    this.menubar = menubar;

    copyConfig.addActionListener(this);
    exportBundle.addActionListener(this);
    add(copyConfig);
    add(exportBundle);
  }

  @Override
  public void actionPerformed(ActionEvent e) {
    final var src = e.getSource();
    if (copyConfig.equals(src)) {
      showConfigDialog();
    } else if (exportBundle.equals(src)) {
      exportBundle();
    }
  }

  private void showConfigDialog() {
    final var json = McpServerManager.getInstance().clientConfigJson();
    if (json == null) {
      notRunning();
      return;
    }
    var displayText = json;
    try {
      Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(json), null);
      displayText = json + "\n\n" + S.get("mcpConfigCopied");
    } catch (Exception ignored) {
      // clipboard may be unavailable in headless/test environments
    }
    final var textArea = new JTextArea(displayText);
    textArea.setEditable(false);
    // deriveFont rather than new Font(name, ...): a physical font built by name carries no
    // fallback, which is how CJK turns into boxes elsewhere in this application. See CLAUDE.md.
    textArea.setFont(textArea.getFont().deriveFont(Font.PLAIN, 12f));
    textArea.setRows(10);
    textArea.setColumns(55);
    OptionPane.showMessageDialog(
        menubar.getParentFrame(),
        new JScrollPane(textArea),
        S.get("mcpConfigTitle"),
        OptionPane.INFORMATION_MESSAGE);
  }

  private void exportBundle() {
    final var manager = McpServerManager.getInstance();
    final var endpoint = manager.endpoint();
    if (endpoint == null) {
      notRunning();
      return;
    }

    final var chooser =
        JFileChoosers.createSelected(new File(McpBundleWriter.suggestedFileName(port(endpoint))));
    chooser.setFileFilter(new FileNameExtensionFilter(S.get("mcpBundleTitle"), "mcpb"));
    if (chooser.showSaveDialog(menubar.getParentFrame()) != JFileChooser.APPROVE_OPTION) return;

    var target = chooser.getSelectedFile();
    if (!target.getName().toLowerCase().endsWith(".mcpb")) {
      target = new File(target.getParentFile(), target.getName() + ".mcpb");
    }
    if (target.exists()) {
      final var answer =
          OptionPane.showConfirmDialog(
              menubar.getParentFrame(),
              S.get("mcpBundleOverwriteQuestion", target.getName()),
              S.get("mcpBundleOverwriteTitle"),
              OptionPane.YES_NO_OPTION,
              OptionPane.WARNING_MESSAGE);
      if (answer != OptionPane.YES_OPTION) return;
    }

    try {
      McpBundleWriter.write(target.toPath(), endpoint, manager.token());
    } catch (IOException ex) {
      OptionPane.showMessageDialog(
          menubar.getParentFrame(),
          S.get("mcpBundleFailed") + "\n" + ex.getMessage(),
          S.get("mcpBundleTitle"),
          OptionPane.ERROR_MESSAGE);
      return;
    }

    final var message =
        S.get("mcpBundleWritten", target.getPath())
            + (manager.tokenConfigured() ? "\n\n" + S.get("mcpBundleTokenWarning") : "");
    OptionPane.showMessageDialog(
        menubar.getParentFrame(), message, S.get("mcpBundleTitle"), OptionPane.INFORMATION_MESSAGE);
  }

  /**
   * Explains what the menu is for, and offers to open the settings page that switches it on.
   *
   * <p>The button is what makes this worth a dialog. Telling someone to go to Preferences -> Peler's
   * Features means writing that path in twelve languages and keeping all twelve in step with the
   * menus they name; opening the page is the same instruction, correct by construction.
   */
  private void notRunning() {
    final var options =
        new Object[] {S.get("mcpNotRunningOpenPrefs"), S.get("mcpNotRunningDismiss")};
    final var answer =
        OptionPane.showOptionDialog(
            menubar.getParentFrame(),
            wrapped(S.get("mcpNotRunningMessage")),
            S.get("mcpNotRunningTitle"),
            OptionPane.YES_NO_OPTION,
            OptionPane.INFORMATION_MESSAGE,
            null,
            options,
            options[0]);
    if (answer == OptionPane.YES_OPTION) PreferencesFrame.showPelerPreferences();
  }

  /**
   * Lays a paragraph of prose out at a fixed width, as HTML.
   *
   * <p>A dialog given a plain string breaks it only where the string does. That is survivable in
   * English, where a translator can put the line breaks in, and not in Chinese or Japanese, where a
   * paragraph carries no spaces and would come back as one line a few thousand pixels wide. So the
   * width is set here and the wrapping is left to Swing's HTML view, which knows where each script
   * may break.
   */
  private static String wrapped(String text) {
    final var escaped =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
    return "<html><body style='width:380px'>" + escaped + "</body></html>";
  }

  /** The bound port, used only to name the file so two exports are told apart. */
  private static int port(String endpoint) {
    try {
      return java.net.URI.create(endpoint).getPort();
    } catch (IllegalArgumentException e) {
      return 0;
    }
  }

  public void localeChanged() {
    setText(S.get("mcpMenu"));
    copyConfig.setText(S.get("mcpCopyConfigItem"));
    exportBundle.setText(S.get("mcpExportBundleItem"));
  }
}
