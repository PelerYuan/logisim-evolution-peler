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

import com.cburch.logisim.std.bfh.BfhLibrary;
import com.cburch.logisim.std.bfhsymbol.BfhSymbolLibrary;
import com.cburch.logisim.std.ttl.TtlLibrary;
import com.cburch.logisim.std.ttlsymbol.TtlSymbolLibrary;
import com.cburch.logisim.tools.Library;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Peler Edition Feature 12: what a save to official {@code .circ} does with the logic symbols.
 *
 * <p>They are dropped, components and library both, and the user is warned first. The alternative
 * -- lowering each one to the component it delegates to -- is rejected in {@code PelerCompat}, and
 * {@link #theOriginalComponentsAreUntouched} is here so that rejection cannot quietly turn into
 * dropping the upstream library the symbols stand for as well.
 *
 * <p>Both families are swept rather than just the first. The writer used to name the TTL symbol
 * library by class, so the BFH one would have gone out into every compatible file this edition
 * wrote and upstream would have reported every one of them as unavailable -- the exact bug the
 * annotation library had, a second time. Both guards now match on the shared base types, and this
 * is what holds them there.
 */
class PelerCompatSaveTest {

  private static final String[] LOCALES = {
    "", "_de", "_el", "_es", "_fr", "_it", "_ja", "_nl", "_pl", "_pt", "_ru", "_zh"
  };

  private static final Path BUNDLES = Path.of("src/main/resources/resources/logisim/strings/proj");

  /** One symbol family: the symbol, and the upstream component and library it stands for. */
  record Family(
      String symbolLibrary,
      String symbol,
      String originalLibrary,
      String original,
      Supplier<Library> library) {
    @Override
    public String toString() {
      return symbolLibrary;
    }
  }

  static List<Family> families() {
    return List.of(
        new Family(
            TtlSymbolLibrary._ID, "Sym7400", TtlLibrary._ID, "7400", TtlSymbolLibrary::new),
        new Family(
            BfhSymbolLibrary._ID,
            "SymBCD_to_7_Segment_decoder",
            BfhLibrary._ID,
            "BCD_to_7_Segment_decoder",
            BfhSymbolLibrary::new));
  }

  /** One component in an otherwise empty project, from whichever library is named. */
  private static LogisimFile projectWith(String libraryId, String component, Path workDir)
      throws IOException, LoadFailedException {
    final var xml =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<project version=\"1.0\">\n"
            + " <lib name=\"1\" desc=\"#" + libraryId + "\" />\n"
            + " <main name=\"main\" />\n"
            + " <circuit name=\"main\">\n"
            + "  <a name=\"circuit\" val=\"main\" />\n"
            + "  <comp lib=\"1\" loc=\"(400,400)\" name=\"" + component + "\" />\n"
            + " </circuit>\n</project>\n";
    final var source = workDir.resolve(component + "-source.circ");
    Files.writeString(source, xml, StandardCharsets.UTF_8);
    return new Loader(null).openLogisimFile(source.toFile());
  }

  /** Saves under a name whose extension decides the dialect, and hands back the XML. */
  private static String savedAs(LogisimFile file, Path workDir, String fileName) {
    final var out = new ByteArrayOutputStream();
    file.write(out, file.getLoader(), new File(workDir.toFile(), fileName), null);
    return out.toString(StandardCharsets.UTF_8);
  }

  @ParameterizedTest
  @MethodSource("families")
  @DisplayName("a compatible save leaves the symbols and their library out")
  void symbolsAreLeftOutOfACompatibleFile(Family family, @TempDir Path workDir) throws Exception {
    final var file = projectWith(family.symbolLibrary(), family.symbol(), workDir);
    final var saved = savedAs(file, workDir, "out.circ");

    assertFalse(saved.contains(family.symbol()), "the symbol component reached a compatible file");
    assertFalse(
        saved.contains(family.symbolLibrary()),
        "the symbol library reached a compatible file, which upstream reports as unavailable");
  }

  @ParameterizedTest
  @MethodSource("families")
  @DisplayName("this edition's own format keeps them")
  void symbolsSurviveTheEditionsOwnFormat(Family family, @TempDir Path workDir) throws Exception {
    final var file = projectWith(family.symbolLibrary(), family.symbol(), workDir);
    final var saved = savedAs(file, workDir, "out.pcirc");

    assertTrue(saved.contains(family.symbol()), "the symbol component was lost from a .pcirc file");
    assertTrue(
        saved.contains(family.symbolLibrary()), "the symbol library was lost from a .pcirc file");
  }

  @ParameterizedTest
  @MethodSource("families")
  @DisplayName("the upstream components the symbols stand for still go through untouched")
  void theOriginalComponentsAreUntouched(Family family, @TempDir Path workDir) throws Exception {
    final var file = projectWith(family.originalLibrary(), family.original(), workDir);
    final var saved = savedAs(file, workDir, "out.circ");

    assertTrue(
        saved.contains("\"" + family.original() + "\""),
        family.original() + " was dropped from a compatible file");
    assertFalse(
        PelerCompat.hasSymbolChips(file), family.original() + " was mistaken for a symbol");
    assertFalse(
        PelerCompat.isLossy(file),
        "a project of ordinary " + family.originalLibrary() + " components was called lossy");
  }

  @ParameterizedTest
  @MethodSource("families")
  @DisplayName("a project holding symbols is what triggers the warning")
  void symbolsMakeTheSaveLossy(Family family, @TempDir Path workDir) throws Exception {
    final var file = projectWith(family.symbolLibrary(), family.symbol(), workDir);

    assertTrue(PelerCompat.hasSymbolChips(file), "the symbol went unnoticed");
    assertTrue(PelerCompat.isLossy(file), "a save that drops a component was not called lossy");
    assertFalse(PelerCompat.hasAnnotations(file), "a symbol was mistaken for an annotation");
  }

  @ParameterizedTest
  @MethodSource("families")
  @DisplayName("a symbol tool is left out of a compatible file's mappings and toolbar")
  void symbolToolsAreEditionOnly(Family family) {
    for (final var tool : family.library().get().getTools()) {
      assertTrue(
          PelerCompat.isPelerOnly(tool),
          tool.getName()
              + " would be named in a compatible file, which upstream cannot resolve");
    }
  }

  @Test
  @DisplayName("upstream's own tools are still written into a compatible file")
  void upstreamToolsAreNotMistakenForEditionOnes() {
    for (final var tool : new TtlLibrary().getTools()) {
      assertFalse(PelerCompat.isPelerOnly(tool), tool.getName() + " is not this edition's");
    }
    for (final var tool : new BfhLibrary().getTools()) {
      assertFalse(PelerCompat.isPelerOnly(tool), tool.getName() + " is not this edition's");
    }
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
