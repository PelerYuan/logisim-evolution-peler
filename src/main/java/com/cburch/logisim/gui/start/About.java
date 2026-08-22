/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.start;

import com.cburch.logisim.Main;
import com.cburch.logisim.generated.BuildInfo;
import com.cburch.logisim.util.LineBuffer;
import com.cburch.logisim.util.UniquelyNamedThread;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.Toolkit;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import java.awt.datatransfer.StringSelection;

import static com.cburch.logisim.gui.Strings.S;

public class About {
  static final int PADDING = 20;
  static final int PANEL_WIDTH = 600;
  static final int LOGO_HEIGHT = 200;
  static final int SCROLLER_HEIGHT = 200;

  private static final String LOGO_IMG = "resources/logisim/img/logisim-evolution-logo.png";

  /**
   * Peler Edition: the logo the splash screen shows, so the first window to appear already says
   * which edition is starting rather than looking exactly like official Logisim-evolution.
   *
   * <p>The About dialog below deliberately keeps upstream's logo: it is upstream's credits and
   * copyright screen, and this edition has its own under Help.
   */
  private static final String SPLASH_LOGO_IMG =
      "resources/logisim/img/logisim-evolution-peler-logo.png";

  private About() {}

  /** The panel the splash screen shows. Only the splash uses it, hence the edition's logo. */
  public static AboutPanel getImagePanel() {
    return new AboutPanel(false, SPLASH_LOGO_IMG);
  }

  public static void showAboutDialog(JFrame owner) {
    if (!Main.hasGui()) {
      return;
    }

    final var content = new JPanel(new BorderLayout());
    content.add(new AboutPanel(true, LOGO_IMG));
    content.setBorder(BorderFactory.createLineBorder(Color.BLACK, 2));

    final var dialog = new JDialog(owner, S.get("aboutDialogTitle"), true);
    final var optionPane = new JOptionPane(content, JOptionPane.PLAIN_MESSAGE);
    final var copyDetailsButton = new JButton(S.get("aboutDialogCopyDetails"));
    copyDetailsButton.addActionListener(event -> {
      final var info = new StringBuilder();
      info.append("Product: " + BuildInfo.displayName)
              .append("\n")
              .append(LineBuffer.format(
                      "Runs on: {{1}} v{{2}}\n",
                      System.getProperty("java.vm.name"),
                      System.getProperty("java.version")))
              .append("\n")
              .append(LineBuffer.format("Compiled: {{1}}\n", BuildInfo.dateIso8601))
              .append(LineBuffer.format("Build ID: {{1}}\n", BuildInfo.buildId))
              .append(LineBuffer.format("Built on: {{1}}\n", BuildInfo.jvm_version))
              ;

      final var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
      final var stringSelection = new StringSelection(info.toString());
      clipboard.setContents(stringSelection, null);
    });

    final var closeButton = new JButton(S.get("aboutDialogClose"));
    closeButton.addActionListener(e -> {
      dialog.dispose();
    });

    optionPane.setOptions(new JButton[]{copyDetailsButton, closeButton});

    dialog.setContentPane(optionPane);
    dialog.pack();
    dialog.setLocationRelativeTo(owner);
    dialog.setVisible(true);
  }

  private static class AboutPanel extends JPanel implements AncestorListener {
    private static final long serialVersionUID = 1L;
    private AboutCredits credits = null;
    private PanelThread thread = null;

    public AboutPanel(boolean includeCredits, String logoImg) {
      setLayout(null);

      final var prefWidth = PANEL_WIDTH + 2 * PADDING;
      var prefHeight = LOGO_HEIGHT + 2 * PADDING;
      if (includeCredits) {
        prefHeight += SCROLLER_HEIGHT;
      }

      setPreferredSize(new Dimension(prefWidth, prefHeight));
      setBackground(Color.WHITE);
      addAncestorListener(this);

      // Scaled to the panel's height, aspect ratio kept, so the resource can stay exactly the
      // image the README shows instead of a second copy cut to size and drifting from it. An
      // image that is already the right height is used as it is, which is upstream's logo.
      final var source =
          new ImageIcon(getClass().getClassLoader().getResource(logoImg)).getImage();
      final var scaled =
          source.getHeight(null) == LOGO_HEIGHT
              ? source
              : source.getScaledInstance(-1, LOGO_HEIGHT, Image.SCALE_SMOOTH);
      final var logo = new JLabel(new ImageIcon(scaled));
      logo.setBounds(0, 20, prefWidth, LOGO_HEIGHT);
      add(logo);

      if (includeCredits) {
        credits = new AboutCredits(PANEL_WIDTH, SCROLLER_HEIGHT);
        credits.setBounds(0, prefHeight / 2, prefWidth, SCROLLER_HEIGHT);
        add(credits);
      }
    }

    @Override
    public void ancestorAdded(AncestorEvent arg0) {
      if (credits != null) {
        if (thread == null) {
          thread = new PanelThread(this);
        }
        thread.start();
      }
    }

    @Override
    public void ancestorMoved(AncestorEvent arg0) {}

    @Override
    public void ancestorRemoved(AncestorEvent arg0) {
      if (thread != null) {
        thread.running = false;
      }
    }
  } // AboutPanel

  private static class PanelThread extends UniquelyNamedThread {
    private final AboutPanel panel;
    private boolean running = true;

    PanelThread(AboutPanel panel) {
      super("About-PanelThread");
      this.panel = panel;
    }

    @Override
    public void run() {
      while (running) {
        panel.repaint();
        try {
          Thread.sleep(20);
        } catch (InterruptedException ignored) {
          // Let's pretend nothing happened.
        }
      }
    }
  } // PanelThread
}
