/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.start;

import static com.cburch.logisim.gui.Strings.S;

import com.cburch.logisim.Main;
import com.cburch.logisim.generated.BuildInfo;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.net.URI;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.event.HyperlinkEvent;

/**
 * "About Peler's Edition": what this personal fork changes, kept deliberately separate from the
 * upstream {@link About} dialog.
 *
 * <p>The split matters. {@link AboutCredits} is upstream's credits roll and carries upstream's
 * copyright notice; a fork has no business editing itself into it. So that screen is left exactly
 * as upstream ships it, crediting upstream, and everything this fork adds is described here
 * instead -- including an explicit statement that this is an unofficial personal fork and that
 * upstream is not responsible for it.
 */
public final class AboutPelerEdition {
  private static final int PANEL_WIDTH = 640;
  private static final int PANEL_HEIGHT = 460;

  private static final String UPSTREAM_URL = "https://github.com/logisim-evolution/logisim-evolution";

  private AboutPelerEdition() {}

  public static void showDialog(JFrame owner) {
    if (!Main.hasGui()) return;

    final var pane = new JEditorPane("text/html", buildHtml());
    pane.setEditable(false);
    pane.setOpaque(false);
    pane.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
    // Render at the platform's own UI font rather than the JEditorPane default (Times), which
    // otherwise makes this window look nothing like the rest of the application.
    final var uiFont = new JPanel().getFont();
    pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
    pane.setFont(uiFont.deriveFont(Font.PLAIN, uiFont.getSize2D()));
    pane.addHyperlinkListener(
        e -> {
          if (e.getEventType() != HyperlinkEvent.EventType.ACTIVATED) return;
          openInBrowser(e.getURL() == null ? null : e.getURL().toString());
        });
    pane.setCaretPosition(0);

    final var scroller = new JScrollPane(pane);
    scroller.setPreferredSize(new Dimension(PANEL_WIDTH, PANEL_HEIGHT));
    scroller.getVerticalScrollBar().setUnitIncrement(16);

    final var content = new JPanel(new BorderLayout());
    content.add(scroller, BorderLayout.CENTER);

    final var optionPane = new JOptionPane(content, JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION);
    final var dialog = new JDialog(owner, S.get("aboutPelerEditionTitle"), true);
    dialog.setContentPane(optionPane);
    optionPane.addPropertyChangeListener(
        JOptionPane.VALUE_PROPERTY,
        e -> {
          if (dialog.isVisible() && e.getSource() == optionPane) dialog.setVisible(false);
        });
    dialog.pack();
    dialog.setLocationRelativeTo(owner);
    dialog.setResizable(true);
    dialog.setVisible(true);
    dialog.dispose();
  }

  private static void openInBrowser(String url) {
    if (url == null) return;
    try {
      if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(new URI(url));
      }
    } catch (Exception ignored) {
      // Opening a browser is a convenience, never worth surfacing an error dialog over.
    }
  }

  private static String buildHtml() {
    return "<html><body style='margin:8px'>"
        + "<h2 style='margin-bottom:2px'>"
        + esc(S.get("aboutPelerEditionHeading"))
        + "</h2>"
        + "<p style='margin-top:0'><i>"
        + esc(S.fmt("aboutPelerEditionVersion", BuildInfo.version))
        + "</i></p>"
        + "<p>"
        + esc(S.get("aboutPelerEditionIntro"))
        + "</p>"
        + "<p><b>"
        + esc(S.get("aboutPelerEditionUpstreamHeading"))
        + "</b><br>"
        + esc(S.get("aboutPelerEditionUpstream"))
        + "<br><a href='"
        + UPSTREAM_URL
        + "'>"
        + UPSTREAM_URL
        + "</a></p>"
        + "<p><b>"
        + esc(S.get("aboutPelerEditionChangesHeading"))
        + "</b></p>"
        + "<ul>"
        + li(S.get("aboutPelerEditionFeatureSticky"))
        + li(S.get("aboutPelerEditionFeatureQuickRotate"))
        + li(S.get("aboutPelerEditionFeatureWireSnap"))
        + li(S.get("aboutPelerEditionFeatureAnnotate"))
        + li(S.get("aboutPelerEditionFeatureFind"))
        + li(S.get("aboutPelerEditionFeatureFormat"))
        + "</ul>"
        + "<p><b>"
        + esc(S.get("aboutPelerEditionLicenseHeading"))
        + "</b><br>"
        + esc(S.get("aboutPelerEditionLicense"))
        + "</p>"
        + "</body></html>";
  }

  private static String li(String text) {
    return "<li style='margin-bottom:4px'>" + esc(text) + "</li>";
  }

  /** Minimal HTML escaping -- these strings come from translators, not from a trusted template. */
  private static String esc(String raw) {
    if (raw == null) return "";
    return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
