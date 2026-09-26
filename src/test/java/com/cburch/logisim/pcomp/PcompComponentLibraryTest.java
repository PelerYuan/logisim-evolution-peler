/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.PcompWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Peler Edition. One loaded component library: a manifest plus whatever {@code .pcomp} files sit
 * next to it in the directory.
 *
 * <p>This is the class that lets a directory become a toolbox category of its own once it is
 * wired into {@code LibraryManager} (next phase) -- so what matters here is that it scans exactly
 * the directory it is given (not the fixed default catalog {@link PcompCatalog} answers for), and
 * that its {@code getTools()} follows the same first-name-wins rule a category always has, per
 * {@code docs/peler-edition/design/pcomp-libraries.md} section three.
 */
class PcompComponentLibraryTest {

  private static final PortLayout TOP_PORTS =
      PcompLayouts.automatic(
          "Top",
          PcompLayouts.nth("A", PortSide.LEFT, 0),
          PcompLayouts.nth("B", PortSide.LEFT, 1),
          PcompLayouts.nth("S", PortSide.RIGHT, 0),
          PcompLayouts.nth("C", PortSide.RIGHT, 1));

  private static final PortLayout HALF_PORTS =
      PcompLayouts.automatic(
          "Half",
          PcompLayouts.nth("X", PortSide.LEFT, 0),
          PcompLayouts.nth("Y", PortSide.LEFT, 1),
          PcompLayouts.nth("Z", PortSide.RIGHT, 0));

  private static File publish(File dir, String circuitName, PortLayout layout, String fileName)
      throws Exception {
    final var project = PcompProjects.read(PcompProjects.THREE_CIRCUITS);
    final var metadata = PcompMetadata.firstVersion(layout.caption(), layout);
    final var file = new File(dir, fileName);
    PcompWriter.write(file, project, project.getCircuit(circuitName), metadata, new Loader(null));
    return file;
  }

  @Test
  public void loadingRequiresAManifest(@TempDir Path dir) throws Exception {
    publish(dir.toFile(), "Top", TOP_PORTS, "Top.pcomp");

    assertThrows(IOException.class, () -> PcompComponentLibrary.load(dir.toFile(), new Loader(null)));
  }

  @Test
  public void libraryOffersEveryComponentInItsDirectory(@TempDir Path dir) throws Exception {
    PcompLibraryFile.create(dir.toFile(), "My Gates");
    publish(dir.toFile(), "Top", TOP_PORTS, "Top.pcomp");
    publish(dir.toFile(), "Half", HALF_PORTS, "Half.pcomp");

    final var library = PcompComponentLibrary.load(dir.toFile(), new Loader(null));

    assertEquals("My Gates", library.getDisplayName());
    assertEquals(dir.toFile(), library.getDirectory());
    assertEquals(2, library.getComponents().size());
    assertEquals(2, library.getTools().size());
  }

  @Test
  public void libraryDoesNotSeeComponentsInsideAnotherDirectory(@TempDir Path dir) throws Exception {
    PcompLibraryFile.create(dir.toFile(), "Empty");
    final var elsewhere = dir.resolve("elsewhere").toFile();
    elsewhere.mkdirs();
    publish(elsewhere, "Top", TOP_PORTS, "Top.pcomp");

    final var library = PcompComponentLibrary.load(dir.toFile(), new Loader(null));

    assertTrue(library.getComponents().isEmpty());
  }

  @Test
  public void twoComponentsInOneLibraryCannotShareACircuitName(@TempDir Path dir) throws Exception {
    PcompLibraryFile.create(dir.toFile(), "Clash");
    publish(dir.toFile(), "Top", TOP_PORTS, "First.pcomp");
    // Same layout and caption "Top" again, from a second file: both publish as circuit "Top_v1".
    publish(dir.toFile(), "Top", TOP_PORTS, "Second.pcomp");

    final var library = PcompComponentLibrary.load(dir.toFile(), new Loader(null));

    assertEquals(2, library.getComponents().size(), "both files should still have loaded");
    assertEquals(1, library.getTools().size(), "the clashing name should have been left out");
  }

  @Test
  public void componentOwningFindsTheCircuitItPublished(@TempDir Path dir) throws Exception {
    PcompLibraryFile.create(dir.toFile(), "My Gates");
    publish(dir.toFile(), "Top", TOP_PORTS, "Top.pcomp");
    final var library = PcompComponentLibrary.load(dir.toFile(), new Loader(null));
    final var component = library.getComponents().get(0);

    assertSame(component, library.componentOwning(component.getCircuit()));
    assertNull(library.componentOwning(null));
  }

  @Test
  public void brokenFileInTheDirectoryIsSkippedNotFatal(@TempDir Path dir) throws Exception {
    PcompLibraryFile.create(dir.toFile(), "Mixed");
    publish(dir.toFile(), "Top", TOP_PORTS, "Good.pcomp");
    java.nio.file.Files.writeString(dir.resolve("Broken.pcomp"), "not a component");
    final var reported = new ArrayList<String>();

    final var library =
        PcompComponentLibrary.load(dir.toFile(), new Loader(null), (file, why) -> reported.add(file.getName()));

    assertEquals(1, library.getComponents().size());
    assertEquals(List.of("Broken.pcomp"), reported);
  }
}
