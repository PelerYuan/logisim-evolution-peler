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
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Peler Edition Feature 11. Everything about handing this application to an AI client.
 *
 * <p>Its own menu rather than more entries under Help, because these are not documentation: they
 * hand out an access token and write an installable bundle. The whole menu is disabled while the
 * server is not running, which is most of the time -- it is off until switched on in Preferences.
 *
 * <p>Being disabled means it also cannot explain itself, so the discoverable path stays Preferences
 * -> Peler's Features, where the checkbox and the endpoint are.
 */
class MenuMcp extends JMenu implements ActionListener {

  private static final long serialVersionUID = 1L;

  private final LogisimMenuBar menubar;
  private final JMenuItem copyConfig = new JMenuItem();
  private final JMenuItem exportBundle = new JMenuItem();
  private final Runnable serverStateListener;

  MenuMcp(LogisimMenuBar menubar) {
    this.menubar = menubar;

    copyConfig.addActionListener(this);
    exportBundle.addActionListener(this);
    add(copyConfig);
    add(exportBundle);

    // A field, not an inline lambda: the manager holds its listeners weakly, so this reference is
    // the only thing keeping the registration alive, and it has to live as long as the menu does.
    serverStateListener = () -> SwingUtilities.invokeLater(this::syncToServerState);
    McpServerManager.addStateListener(serverStateListener);
    syncToServerState();
  }

  private void syncToServerState() {
    setEnabled(McpServerManager.getInstance().isRunning());
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

  private void notRunning() {
    OptionPane.showMessageDialog(
        menubar.getParentFrame(),
        S.get("mcpConfigNotRunning"),
        S.get("mcpConfigTitle"),
        OptionPane.INFORMATION_MESSAGE);
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
