/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Peler Edition. Packs a component library directory (a {@code library.pcomplib} manifest plus its
 * {@code .pcomp} files, see {@link PcompLibraryFile}) into a single zip file, so it can be handed to
 * someone else the same way any other file is shared. The directory itself is already the
 * sharable unit -- see {@code docs/peler-edition/design/pcomp-libraries.md} section "分享" -- this
 * is only the convenience of not making the recipient zip it up by hand.
 *
 * <p>Unzipping the result anywhere and pointing "Load Library..." at that folder reconstructs the
 * exact same library, since every entry is a plain relative path under the source directory.
 */
public final class PcompLibraryExport {
  private PcompLibraryExport() {}

  /**
   * Zips every regular file under {@code sourceDirectory} (recursively) into {@code
   * destinationZip}, using paths relative to {@code sourceDirectory} as the zip entry names.
   */
  public static void export(File sourceDirectory, File destinationZip) throws IOException {
    final var sourcePath = sourceDirectory.toPath();
    try (var out = new ZipOutputStream(new FileOutputStream(destinationZip))) {
      try (var files = Files.walk(sourcePath)) {
        for (final var path : files.sorted().toList()) {
          if (Files.isDirectory(path)) continue;
          final var entryName = sourcePath.relativize(path).toString().replace(File.separatorChar, '/');
          out.putNextEntry(new ZipEntry(entryName));
          try (var in = new FileInputStream(path.toFile())) {
            in.transferTo(out);
          }
          out.closeEntry();
        }
      }
    }
  }
}
