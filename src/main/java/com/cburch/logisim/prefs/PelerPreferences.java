/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.prefs;

import static com.cburch.logisim.gui.Strings.S;

import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import javax.swing.JOptionPane;

/**
 * Peler Edition. Keeps this edition's settings in their own preferences node, and offers to bring
 * across whatever is in the shared one.
 *
 * <p>Until now both editions stored everything under {@code com/cburch/logisim} -- that is what
 * {@code Preferences.userNodeForPackage} gives you, and the package name is identical in both, so
 * installing this edition alongside the official one silently merged their settings. In practice
 * that meant the interface language, every window's size and position, the recent-file list, the
 * FPGA board selection and all keyboard shortcuts were one shared set: switching this edition to
 * Chinese switched the official one too, and this edition's own keys (the Quick Rotate hint, the
 * component-finder hotkey) were being written into the official edition's store.
 *
 * <p>The import is offered once, at first launch, and is available afterwards from the File menu.
 * It has to run before {@link AppPreferences} is touched: that class reads every stored value as it
 * initialises, so importing later would leave the imported language, theme and window layout
 * sitting on disk unused until the next restart.
 */
public final class PelerPreferences {
  private PelerPreferences() {}

  /**
   * This edition's node. A sibling of the shared one rather than a child, so neither edition sees
   * the other's keys even when walking sub-nodes.
   */
  public static final String NODE = "com/cburch/logisim-peler";

  /** What {@code Preferences.userNodeForPackage(Main.class)} used to resolve to, and still does for
   * official Logisim-evolution. */
  private static final String LEGACY_NODE = "com/cburch/logisim";

  /** Set once the question has been asked, whether or not the answer was yes. */
  private static final String ASKED_KEY = "pelerImportAsked";

  /** Mirrors the key {@code AppPreferences.FPGA_Workspace} is declared with; keep the two in step. */
  private static final String FPGA_WORKSPACE_KEY = "FPGAWorkspace";

  /**
   * Where this edition generates FPGA output, unless the user points it somewhere else.
   *
   * <p>Upstream defaults to {@code ~/logisim_evolution_workspace}, and both editions used to write
   * there. That directory is not just an output dump: {@code DownloadBase} lays it out as
   * {@code <workspace>/<project file name>/<circuit>/} and wipes each circuit's directory before
   * regenerating it, so the same project opened in either edition maps to the same path and each
   * download deletes whatever the other edition left there.
   */
  public static String defaultFpgaWorkspace() {
    return System.getProperty("user.home") + "/logisim_evolution_peler_workspace";
  }

  public static Preferences node() {
    return Preferences.userRoot().node(NODE);
  }

  private static Preferences legacyNode() {
    return Preferences.userRoot().node(LEGACY_NODE);
  }

  /**
   * Whether there is anything worth importing.
   *
   * <p>Deliberately keyed on stored settings rather than on whether official Logisim-evolution is
   * installed: settings outlive an uninstall, an install that has never been run has none, and the
   * check would need writing three times for three platforms. It is also worth being clear about
   * whose settings these are -- the shared node holds both the official edition's and every
   * setting this edition wrote before the split, because up to now they were the same store.
   */
  public static boolean hasImportableSettings() {
    try {
      if (!Preferences.userRoot().nodeExists(LEGACY_NODE)) return false;
      final var legacy = legacyNode();
      return legacy.keys().length > 0 || legacy.childrenNames().length > 0;
    } catch (BackingStoreException e) {
      return false;
    }
  }

  /**
   * First launch after the split: ask once whether to bring the old settings across.
   *
   * <p>Called from {@code Main} before anything reads a preference, which is also why the prompt
   * cannot use the usual {@code S.get(...)}: {@code LocaleManager} would pull in
   * {@link AppPreferences} and freeze the current (empty) values before the import happened. The
   * message bundle is loaded directly instead, using the language recorded in the settings being
   * offered, so the question appears in the language the user was already using.
   */
  public static void offerImportOnFirstRun() {
    if (GraphicsEnvironment.isHeadless()) return;
    final var self = node();
    if (self.getBoolean(ASKED_KEY, false)) return;
    if (!hasImportableSettings()) {
      self.putBoolean(ASKED_KEY, true);
      return;
    }

    final var strings = earlyBundle();
    final var options =
        new Object[] {text(strings, "pelerImportOpt", "Import"),
            text(strings, "pelerImportSkipOpt", "Start fresh")};
    final var answer =
        JOptionPane.showOptionDialog(
            null,
            text(
                strings,
                "pelerImportMessage",
                "Settings from a previous Logisim-evolution installation were found.\n"
                    + "Import them into Peler's Edition?\n"
                    + "From now on the two keep their settings separate."),
            text(strings, "pelerImportTitle", "Import settings"),
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            options,
            options[0]);

    if (answer == 0) copyLegacySettings();
    self.putBoolean(ASKED_KEY, true);
    flush(self);
  }

  /**
   * Runs the import on demand, from the File menu. Unlike the first-run prompt this one warns that
   * it overwrites, since by then the user has settings of their own worth losing, and it says the
   * application has to restart -- everything was already read into memory when it started.
   *
   * @return true if settings were imported
   */
  public static boolean importInteractively(Component parent) {
    if (!hasImportableSettings()) {
      message(parent, "pelerImportNoneMessage");
      return false;
    }
    if (!confirm(parent, "pelerImportConfirmMessage")) return false;

    final var imported = copyLegacySettings();
    message(parent, imported ? "pelerImportDoneMessage" : "pelerImportFailedMessage");
    return imported;
  }

  private static boolean copyLegacySettings() {
    try {
      copyNode(legacyNode(), node(), true);
      flush(node());
      return true;
    } catch (BackingStoreException e) {
      return false;
    }
  }

  /**
   * Copies keys and sub-nodes, leaving anything already in the destination that the source has no
   * opinion about.
   *
   * <p>Two top-level keys are deliberately not brought across. {@link #ASKED_KEY} is this class's
   * own bookkeeping. {@code FPGAWorkspace} is a path both editions would then generate into and
   * clean out from under each other -- see {@link #defaultFpgaWorkspace()} -- which is the opposite
   * of what the import dialog promises, so this edition keeps its own workspace and the user can
   * still point it at the old one by hand.
   */
  private static void copyNode(Preferences from, Preferences to, boolean isRoot)
      throws BackingStoreException {
    for (final var key : from.keys()) {
      if (isRoot && (ASKED_KEY.equals(key) || FPGA_WORKSPACE_KEY.equals(key))) continue;
      final var value = from.get(key, null);
      if (value != null) to.put(key, value);
    }
    for (final var child : from.childrenNames()) {
      copyNode(from.node(child), to.node(child), false);
    }
  }

  private static void flush(Preferences prefs) {
    try {
      prefs.flush();
    } catch (BackingStoreException ignored) {
      // Nothing useful to do about it, and failing to flush is not worth aborting startup for.
    }
  }

  private static ResourceBundle earlyBundle() {
    var tag = "";
    try {
      tag = legacyNode().get("locale", "");
    } catch (RuntimeException ignored) {
      // Unreadable store; fall through to the platform language.
    }
    final var locale = tag.isBlank() ? Locale.getDefault() : Locale.forLanguageTag(tag);
    try {
      return ResourceBundle.getBundle("resources/logisim/strings/gui/gui", locale);
    } catch (MissingResourceException e) {
      return null;
    }
  }

  private static String text(ResourceBundle bundle, String key, String fallback) {
    if (bundle == null) return fallback;
    try {
      return bundle.getString(key);
    } catch (MissingResourceException e) {
      return fallback;
    }
  }

  // The menu-driven path runs long after start-up, so it can use the normal localisation.

  private static void message(Component parent, String key) {
    JOptionPane.showMessageDialog(
        parent, S.get(key), S.get("pelerImportTitle"), JOptionPane.INFORMATION_MESSAGE);
  }

  private static boolean confirm(Component parent, String key) {
    return JOptionPane.showConfirmDialog(
            parent, S.get(key), S.get("pelerImportTitle"), JOptionPane.YES_NO_OPTION)
        == JOptionPane.YES_OPTION;
  }
}
