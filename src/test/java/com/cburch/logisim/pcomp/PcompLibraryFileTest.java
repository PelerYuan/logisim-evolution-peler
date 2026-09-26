/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Peler Edition. The manifest at the root of a component library directory.
 *
 * <p>Two things matter here that do not matter for {@link PcompFile}: a library directory is
 * created fresh by the program itself ({@link PcompLibraryFile#create}), not merely read, and its
 * identity has to survive the round trip through disk unchanged, since it is what a project's
 * {@code pcomplib#} reference ultimately answers to.
 */
class PcompLibraryFileTest {

  @Test
  public void freshDirectoryIsNotYetALibrary(@TempDir Path dir) {
    assertFalse(PcompLibraryFile.isPcompLibraryDirectory(dir.toFile()));
  }

  @Test
  public void createWritesAManifestThatReadsBack(@TempDir Path dir) throws IOException {
    final var target = dir.resolve("gates").toFile();

    final var created = PcompLibraryFile.create(target, "My Gates");

    assertTrue(PcompLibraryFile.isPcompLibraryDirectory(target));
    final var read = PcompLibraryFile.read(target);
    assertEquals(created, read);
    assertEquals("My Gates", read.name());
    assertFalse(read.id().isBlank());
  }

  @Test
  public void createMakesTheDirectoryIfItDoesNotExist(@TempDir Path dir) throws IOException {
    final var target = dir.resolve("nested").resolve("library").toFile();

    PcompLibraryFile.create(target, "Nested");

    assertTrue(target.isDirectory());
  }

  @Test
  public void twoLibrariesGetTwoDifferentIds(@TempDir Path dir) throws IOException {
    final var first = PcompLibraryFile.create(dir.resolve("a").toFile(), "A");
    final var second = PcompLibraryFile.create(dir.resolve("b").toFile(), "B");

    assertNotEquals(first.id(), second.id());
  }

  @Test
  public void createRefusesADirectoryThatIsAlreadyALibrary(@TempDir Path dir) throws IOException {
    PcompLibraryFile.create(dir.toFile(), "First");

    assertThrows(IOException.class, () -> PcompLibraryFile.create(dir.toFile(), "Second"));
  }

  @Test
  public void writeThenReadRoundTripsAnExistingManifest(@TempDir Path dir) throws IOException {
    final var manifest = new PcompLibraryManifest("11111111-2222-4333-8444-555555555555", "TTL");

    PcompLibraryFile.write(dir.toFile(), manifest);

    assertEquals(manifest, PcompLibraryFile.read(dir.toFile()));
  }

  @Test
  public void readingADirectoryWithNoManifestFails(@TempDir Path dir) {
    assertThrows(IOException.class, () -> PcompLibraryFile.read(dir.toFile()));
  }

  @Test
  public void readingAManifestThatIsNotXmlFails(@TempDir Path dir) throws IOException {
    Files.writeString(
        dir.resolve(PcompLibraryFile.MANIFEST_FILE_NAME), "not xml", StandardCharsets.UTF_8);

    assertThrows(IOException.class, () -> PcompLibraryFile.read(dir.toFile()));
  }

  @Test
  public void readingAManifestWithNoIdFails(@TempDir Path dir) throws IOException {
    Files.writeString(
        dir.resolve(PcompLibraryFile.MANIFEST_FILE_NAME),
        "<pcomplib name=\"No Id\"/>",
        StandardCharsets.UTF_8);

    assertThrows(IOException.class, () -> PcompLibraryFile.read(dir.toFile()));
  }
}
