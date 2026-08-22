/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.file;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.std.ttlsymbol.TtlSymbolLibrary;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Peler Edition Feature 12: what a save to official {@code .circ} does with the TTL logic symbols.
 *
 * <p>They are dropped, components and library both, and the user is warned first. The alternative
 * -- lowering each one to the DIP chip it delegates to -- is rejected in {@code PelerCompat}, and
 * {@link #theDipChipsAreUntouched()} is here so that rejection cannot quietly turn into dropping
 * upstream's own TTL library as well.
 */
class PelerCompatSaveTest {

  private static final String[] LOCALES = {
    "", "_de", "_el", "_es", "_fr", "_it", "_ja", "_nl", "_pl", "_pt", "_ru", "_zh"
  };

  private static final Path BUNDLES = Path.of("src/main/resources/resources/logisim/strings/proj");

  /** One chip in an otherwise empty project, from whichever library is named. */
  private static LogisimFile projectWith(String libraryId, String chip, Path workDir)
      throws IOException, LoadFailedException {
    final var xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<project version=\"1.0\">\n"
            + " <lib name=\"1\" desc=\"#" + libraryId + "\" />\n"
            + " <main name=\"main\" />\n"
            + " <circuit name=\"main\">\n"
            + "  <a name=\"circuit\" val=\"main\" />\n"
            + "  <comp lib=\"1\" loc=\"(400,400)\" name=\"" + chip + "\" />\n"
            + " </circuit>\n</project>\n";
    final var source = workDir.resolve(chip + "-source.circ");
    Files.writeString(source, xml, StandardCharsets.UTF_8);
    return new Loader(null).openLogisimFile(source.toFile());
  }

  /** Saves under a name whose extension decides the dialect, and hands back the XML. */
  private static String savedAs(LogisimFile file, Path workDir, String fileName) {
    final var out = new ByteArrayOutputStream();
    file.write(out, file.getLoader(), new File(workDir.toFile(), fileName), null);
    return out.toString(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("a compatible save leaves the symbol chips and their library out")
  void symbolChipsAreLeftOutOfACompatibleFile(@TempDir Path workDir) throws Exception {
    final var file = projectWith(TtlSymbolLibrary._ID, "Sym7400", workDir);
    final var saved = savedAs(file, workDir, "out.circ");

    assertFalse(saved.contains("Sym7400"), "the symbol component reached a compatible file");
    assertFalse(
        saved.contains(TtlSymbolLibrary._ID),
        "the symbol library reached a compatible file, which upstream reports as unavailable");
  }

  @Test
  @DisplayName("this edition's own format keeps them")
  void symbolChipsSurviveTheEditionsOwnFormat(@TempDir Path workDir) throws Exception {
    final var file = projectWith(TtlSymbolLibrary._ID, "Sym7400", workDir);
    final var saved = savedAs(file, workDir, "out.pcirc");

    assertTrue(saved.contains("Sym7400"), "the symbol component was lost from a .pcirc file");
    assertTrue(saved.contains(TtlSymbolLibrary._ID), "the symbol library was lost from a .pcirc file");
  }

  @Test
  @DisplayName("upstream's own DIP chips still go through untouched")
  void theDipChipsAreUntouched(@TempDir Path workDir) throws Exception {
    final var file = projectWith("TTL", "7400", workDir);
    final var saved = savedAs(file, workDir, "out.circ");

    assertTrue(saved.contains("\"7400\""), "a plain TTL chip was dropped from a compatible file");
    assertFalse(PelerCompat.hasSymbolChips(file), "a DIP chip was mistaken for a symbol");
    assertFalse(PelerCompat.isLossy(file), "a project of plain TTL chips was called lossy");
  }

  @Test
  @DisplayName("a project holding symbol chips is what triggers the warning")
  void symbolChipsMakeTheSaveLossy(@TempDir Path workDir) throws Exception {
    final var file = projectWith(TtlSymbolLibrary._ID, "Sym7400", workDir);

    assertTrue(PelerCompat.hasSymbolChips(file), "the symbol chip went unnoticed");
    assertTrue(PelerCompat.isLossy(file), "a save that drops a chip was not called lossy");
    assertFalse(PelerCompat.hasAnnotations(file), "a symbol chip was mistaken for an annotation");
  }

  @Test
  @DisplayName("a symbol tool is left out of a compatible file's mappings and toolbar")
  void symbolToolsAreEditionOnly() {
    final var tool = new TtlSymbolLibrary().getTools().get(0);
    assertTrue(
        PelerCompat.isPelerOnly(tool),
        "a symbol tool would be named in a compatible file, which upstream cannot resolve");
  }

  /**
   * Every language has the new warning.
   *
   * <p>Read as files rather than through {@code ResourceBundle}: a bundle falls back to the base
   * one for a key its own file is missing, so the obvious version of this test passes for a
   * language that has no translation at all and only shows English at the moment it matters.
   */
  @Test
  @DisplayName("every language has the new warning")
  void everyLocaleCarriesTheWarning() throws IOException {
    final var missing = new ArrayList<String>();
    for (final var locale : LOCALES) {
      final var name = "proj" + locale + ".properties";
      final var properties = new Properties();
      try (final var reader =
          Files.newBufferedReader(BUNDLES.resolve(name), StandardCharsets.UTF_8)) {
        properties.load(reader);
      }
      final var text = properties.getProperty("compatSaveSymbolMessage");
      if (text == null) missing.add(name + ": compatSaveSymbolMessage missing");
      else if (text.isBlank()) missing.add(name + ": compatSaveSymbolMessage empty");
    }
    assertTrue(missing.isEmpty(), String.join("\n", missing));
  }
}
