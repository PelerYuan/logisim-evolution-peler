/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Peler Edition. Acceptance tests for {@link PcompLibraryExport}, the "Export..." button behind
 * {@code PcompLibraryManagerFrame}: zipping a library directory so it can be handed to someone
 * else, and unzipping it back into a directory {@link PcompLibraryFile} still recognizes as the
 * same library -- see {@code docs/peler-edition/design/pcomp-libraries.md} section "分享".
 */
class PcompLibraryExportTest {

  @Test
  public void exportedZipContainsTheManifestAndEveryComponentFile(@TempDir Path dir)
      throws IOException {
    final var source = dir.resolve("source").toFile();
    PcompLibraryFile.create(source, "Gates");
    Files.writeString(
        new File(source, "adder.pcomp").toPath(), "not real xml, only presence matters",
        StandardCharsets.UTF_8);

    final var zip = dir.resolve("export.zip").toFile();
    PcompLibraryExport.export(source, zip);

    try (var zipFile = new ZipFile(zip)) {
      assertTrue(
          zipFile.getEntry(PcompLibraryFile.MANIFEST_FILE_NAME) != null,
          "the manifest should be at the root of the zip");
      assertTrue(
          zipFile.getEntry("adder.pcomp") != null,
          "every .pcomp file in the directory should be included");
    }
  }

  @Test
  public void unzippedDirectoryIsStillTheSameLibrary(@TempDir Path dir) throws IOException {
    final var source = dir.resolve("source").toFile();
    final var manifest = PcompLibraryFile.create(source, "TTL");
    Files.writeString(
        new File(source, "nand.pcomp").toPath(), "not real xml, only presence matters",
        StandardCharsets.UTF_8);

    final var zip = dir.resolve("export.zip").toFile();
    PcompLibraryExport.export(source, zip);

    final var destination = dir.resolve("reimported").toFile();
    unzip(zip, destination);

    assertTrue(PcompLibraryFile.isPcompLibraryDirectory(destination));
    final var reread = PcompLibraryFile.read(destination);
    assertEquals(manifest, reread);
    assertTrue(new File(destination, "nand.pcomp").isFile());
  }

  private static void unzip(File zip, File destination) throws IOException {
    try (var zipFile = new ZipFile(zip)) {
      final var entries = zipFile.entries();
      while (entries.hasMoreElements()) {
        final var entry = entries.nextElement();
        final var target = new File(destination, entry.getName());
        Files.createDirectories(target.getParentFile().toPath());
        try (var in = zipFile.getInputStream(entry)) {
          Files.copy(in, target.toPath());
        }
      }
    }
  }
}
