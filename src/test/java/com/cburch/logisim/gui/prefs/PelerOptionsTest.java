/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.TestBase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Peler Edition Feature 10. Guards the Preferences panel that collects this edition's own settings.
 *
 * <p>Nothing here constructs the panel or touches {@link com.cburch.logisim.prefs.AppPreferences}:
 * its static initialiser reads the real preference store, and reading it headless writes wrong
 * defaults back (see CLAUDE.md). Everything is checked by reading the sources and the bundles,
 * which is enough for the two mistakes that actually happen -- a string key that exists in one
 * language, and a preference that has no way to reach it.
 */
public class PelerOptionsTest extends TestBase {

  private static final Path SRC = Path.of("src/main/java/com/cburch/logisim");
  private static final Path BUNDLES = Path.of("src/main/resources/resources/logisim/strings/gui");

  /** The twelve the application ships. A new language has to be added here as well as shipped. */
  private static final List<String> LOCALES =
      List.of("", "_de", "_el", "_es", "_fr", "_it", "_ja", "_nl", "_pl", "_pt", "_ru", "_zh");

  /** {@code S.get("key")} and {@code S.getter("key")}, which is how a panel names a string. */
  private static final Pattern STRING_KEY =
      Pattern.compile("S\\.(?:get|getter)\\(\"([^\"]+)\"\\)");

  /** A preference declaration in AppPreferences, e.g. {@code PrefMonitor<Boolean> WIRE_AUTO_SNAP}. */
  private static final Pattern PREFERENCE =
      Pattern.compile("PrefMonitor<[^>]+>\\s+([A-Z][A-Z0-9_]*)\\s*=");

  /**
   * Preferences this edition added that are deliberately not on the panel, with the reason. Anything
   * else the fork adds has to be reachable, which is the point of the test below.
   */
  private static final Set<String> NOT_USER_FACING =
      Set.of(
          // Records that the one-time "the menu moved to Ctrl+left-click" hint has been shown.
          // Internal bookkeeping, not a choice: a checkbox for it would offer to re-show a message
          // whose whole purpose is to appear once.
          "SHOWN_QUICK_ROTATE_HINT",
          // The MCP bearer token. Generated when the server is first switched on and handed to the
          // client by Help -> Copy MCP Configuration. Deliberately not an editable field: a secret
          // someone types into a settings page is a secret they choose badly and reuse.
          "MCP_TOKEN");

  /**
   * Every string the panel asks for has to exist in every language.
   *
   * <p>A missing key is not a crash: {@code LocaleManager} hands back the key wrapped in question
   * marks, so the panel still opens and only looks broken, and only in a language nobody building
   * the fork happens to run. That is precisely the failure a build should catch instead.
   */
  @Test
  public void testEveryStringThePanelAsksForExistsInEveryLanguage() throws IOException {
    final var wanted = keysAskedFor(SRC.resolve("gui/prefs/PelerOptions.java"));
    assertFalse(wanted.isEmpty(), "no S.get/S.getter calls found -- has the panel been renamed?");

    final var missing = new ArrayList<String>();
    for (final var locale : LOCALES) {
      final var bundle = load(BUNDLES.resolve("gui" + locale + ".properties"));
      for (final var key : wanted) {
        final var value = bundle.getProperty(key);
        if (value == null) {
          missing.add("gui" + locale + ": " + key + " missing");
        } else if (value.isBlank()) {
          missing.add("gui" + locale + ": " + key + " empty");
        }
      }
    }
    assertTrue(missing.isEmpty(), String.join("\n", missing));
  }

  /** The twelve bundles must agree on which of this edition's keys they carry, not just contain them. */
  @Test
  public void testTheTwelveBundlesCarryTheSamePelerKeys() throws IOException {
    Set<String> reference = null;
    var referenceName = "";
    for (final var locale : LOCALES) {
      final var name = "gui" + locale + ".properties";
      final var keys = new TreeSet<String>();
      for (final var key : load(BUNDLES.resolve(name)).stringPropertyNames()) {
        if (key.startsWith("peler")) keys.add(key);
      }
      if (reference == null) {
        reference = keys;
        referenceName = name;
        assertFalse(keys.isEmpty(), name + " carries no peler* keys at all");
      } else {
        assertEquals(reference, keys, name + " does not match " + referenceName);
      }
    }
  }

  /**
   * Every preference this edition adds has to be reachable from the Preferences window.
   *
   * <p>Written because one was not. {@code WIRE_AUTO_SNAP} shipped in Feature 3, the README said it
   * "can be turned off in preferences" from the same commit, and no preferences panel ever
   * mentioned it -- the value was read by {@code WiringTool} and written by nothing, so the only
   * way to change it was to edit the preference store by hand. A dead preference is invisible in
   * exactly the way a missing feature is not: everything compiles, and the documentation is what
   * turns out to be wrong.
   */
  @Test
  public void testEveryPelerPreferenceIsReachableFromThePreferencesWindow() throws IOException {
    final var declared = pelerPreferences();
    assertTrue(
        declared.contains("WIRE_AUTO_SNAP"),
        "premise of this test: WIRE_AUTO_SNAP is still declared inside the fork's block");

    final var panels = new StringBuilder();
    try (final var files = Files.list(SRC.resolve("gui/prefs"))) {
      for (final var file : files.toList()) {
        if (file.toString().endsWith(".java")) panels.append(Files.readString(file));
      }
    }

    final var unreachable = new ArrayList<String>();
    for (final var name : declared) {
      if (NOT_USER_FACING.contains(name)) continue;
      if (!panels.toString().contains("AppPreferences." + name)) unreachable.add(name);
    }
    assertTrue(
        unreachable.isEmpty(),
        "declared by this edition but not offered anywhere in Preferences: " + unreachable);
  }

  /** A panel nobody adds to the window is a panel nobody can open. */
  @Test
  public void testThePanelIsRegisteredInThePreferencesWindow() throws IOException {
    final var frame = Files.readString(SRC.resolve("gui/prefs/PreferencesFrame.java"));
    assertTrue(frame.contains("new PelerOptions(this)"), "PelerOptions is not in the tab list");
  }

  /** Names of the preferences declared inside this edition's own block of AppPreferences. */
  private static Set<String> pelerPreferences() throws IOException {
    final var source = Files.readString(SRC.resolve("prefs/AppPreferences.java"));
    final var start = source.indexOf("// Peler Edition Feature 3: whether WiringTool");
    assertTrue(start >= 0, "the fork's preference block has moved; this test needs updating");
    final var end = source.indexOf("public static final PrefMonitor<String> DefaultAppearance");
    assertTrue(end > start, "the end of the fork's preference block has moved");

    final var names = new LinkedHashSet<String>();
    final var matcher = PREFERENCE.matcher(source.substring(start, end));
    while (matcher.find()) names.add(matcher.group(1));
    return names;
  }

  private static Set<String> keysAskedFor(Path javaFile) throws IOException {
    final var keys = new TreeSet<String>();
    final var matcher = STRING_KEY.matcher(Files.readString(javaFile));
    while (matcher.find()) keys.add(matcher.group(1));
    return keys;
  }

  private static Properties load(Path path) throws IOException {
    final var properties = new Properties();
    try (final var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
    }
    return properties;
  }
}
