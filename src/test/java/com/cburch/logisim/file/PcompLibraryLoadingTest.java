/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.pcomp.PcompComponentLibrary;
import com.cburch.logisim.pcomp.PcompLibraryFile;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.tools.Library;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Peler Edition. P2 acceptance: a component library directory loaded through {@link
 * LibraryManager}/{@link Loader} behaves like the existing {@code file#}/{@code jar#} descriptors --
 * its reference survives a save/close/reopen round trip, and two projects that load the same
 * directory path share one {@link LoadedLibrary} instance, per {@code
 * docs/peler-edition/design/pcomp-libraries.md} section four.
 */
class PcompLibraryLoadingTest {

  private static final String EMPTY_PROJECT =
      """
      <?xml version="1.0" encoding="UTF-8" standalone="no"?>
      <project source="4.1.0" version="1.0">
        <main name="Empty"/>
        <circuit name="Empty">
        </circuit>
      </project>
      """;

  private static File writeEmptyProject(Path dir, String fileName) throws Exception {
    final var file = dir.resolve(fileName).toFile();
    Files.writeString(file.toPath(), EMPTY_PROJECT, StandardCharsets.UTF_8);
    return file;
  }

  private static Library unwrap(Library lib) {
    return lib instanceof LoadedLibrary loaded ? loaded.getBase() : lib;
  }

  @Test
  public void loadedPcompLibraryReferenceSurvivesSaveCloseReopen(@TempDir Path dir)
      throws Exception {
    final var libraryDir = dir.resolve("mylib").toFile();
    final var manifest = PcompLibraryFile.create(libraryDir, "My Gates");
    // The native format, not .circ: a compat-mode save deliberately drops a pcomplib reference the
    // same way it drops the default catalog's -- see XmlWriter.fromLibrary -- since whatever was
    // placed from it would have been inlined by PcompLowering instead. This test is about the
    // fork's own native round trip, where the reference is exactly what should survive.
    final var projectFile = writeEmptyProject(dir, "project.pcirc");

    final var loader = new Loader(null);
    final var file = loader.openLogisimFile(projectFile);
    final var lib = loader.loadPcompLibrary(libraryDir);
    assertNotNull(lib, "the library directory should have loaded");

    final var proj = new Project(file);
    proj.doAction(LogisimFileActions.loadLibraryQuiet(lib, file));
    assertTrue(file.getLibraries().contains(lib));

    assertTrue(loader.save(file, projectFile), "the project should have saved");

    // "Close and reopen": a brand new Loader, standing in for a fresh process opening the file.
    final var reopened = new Loader(null).openLogisimFile(projectFile);

    PcompComponentLibrary found = null;
    for (final var reopenedLib : reopened.getLibraries()) {
      if (unwrap(reopenedLib) instanceof PcompComponentLibrary pcompLib) {
        found = pcompLib;
      }
    }
    assertNotNull(found, "the pcomplib# reference should have resolved back to a library");
    assertEquals(manifest.id(), found.getManifest().id());
    assertEquals(libraryDir.getCanonicalFile(), found.getDirectory().getCanonicalFile());
  }

  @Test
  public void twoProjectsLoadingTheSameDirectoryShareOneLoadedLibrary(@TempDir Path dir)
      throws Exception {
    final var libraryDir = dir.resolve("shared").toFile();
    PcompLibraryFile.create(libraryDir, "Shared Gates");
    final var firstProjectFile = writeEmptyProject(dir, "first.circ");
    final var secondProjectFile = writeEmptyProject(dir, "second.circ");

    final var firstLoader = new Loader(null);
    firstLoader.openLogisimFile(firstProjectFile);
    final var secondLoader = new Loader(null);
    secondLoader.openLogisimFile(secondProjectFile);

    final var fromFirst = firstLoader.loadPcompLibrary(libraryDir);
    final var fromSecond = secondLoader.loadPcompLibrary(libraryDir);

    assertSame(fromFirst, fromSecond);
  }
}
