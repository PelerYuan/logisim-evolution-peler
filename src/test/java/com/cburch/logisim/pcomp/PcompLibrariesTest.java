/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.PcompWriter;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Peler Edition. "Which component owns this circuit", asked against a specific project's own
 * library tree rather than the fixed default catalog alone.
 *
 * <p>The {@code Circuit}-only form's open-projects search is not exercised here: {@code
 * Projects.getOpenProjects()} only sees a project once a real {@code Frame} for it has opened (a
 * headless {@code new Project(file)} alone never registers), so that branch is only reachable
 * end to end from a GUI scenario. What is exercised here is that the same form still falls back to
 * the default catalog even with zero open projects to search -- the {@code (LogisimFile, Circuit)}
 * form promises that fallback unconditionally, and {@link PcompLock}'s callers, which mostly have
 * no project in hand at all, must see it too.
 */
class PcompLibrariesTest {

  private static final PortLayout TOP_PORTS =
      PcompLayouts.automatic(
          "Top",
          PcompLayouts.nth("A", PortSide.LEFT, 0),
          PcompLayouts.nth("B", PortSide.LEFT, 1),
          PcompLayouts.nth("S", PortSide.RIGHT, 0),
          PcompLayouts.nth("C", PortSide.RIGHT, 1));

  @BeforeEach
  @AfterEach
  void forgetTheDefaultCatalog() {
    PcompCatalog.useDirectory(null);
  }

  @Test
  public void circuitWithNoOwningLibraryIsNull() throws Exception {
    final var file = PcompProjects.read(PcompProjects.THREE_CIRCUITS);

    assertNull(PcompLibraries.componentOf(file, file.getCircuit("Unrelated")));
  }

  @Test
  public void nullFileOrCircuitIsNull() throws Exception {
    final var file = PcompProjects.read(PcompProjects.THREE_CIRCUITS);

    assertNull(PcompLibraries.componentOf(null, file.getCircuit("Top")));
    assertNull(PcompLibraries.componentOf(file, null));
  }

  @Test
  public void findsAComponentInALibraryLoadedIntoTheFile(@TempDir Path dir) throws Exception {
    final var source = PcompProjects.read(PcompProjects.THREE_CIRCUITS);
    PcompLibraryFile.create(dir.toFile(), "My Gates");
    PcompWriter.write(
        dir.resolve("Top.pcomp").toFile(),
        source,
        source.getCircuit("Top"),
        PcompMetadata.firstVersion("Top", TOP_PORTS),
        new Loader(null));
    final var library = PcompComponentLibrary.load(dir.toFile(), new Loader(null));
    final var component = library.getComponents().get(0);

    final var file = PcompProjects.read(PcompProjects.THREE_CIRCUITS);
    file.addLibrary(library);

    assertSame(component, PcompLibraries.componentOf(file, component.getCircuit()));
  }

  @Test
  public void theDefaultCatalogIsReachableEvenWithoutBeingInTheFilesLibraries(@TempDir Path dir)
      throws Exception {
    final var source = PcompProjects.read(PcompProjects.THREE_CIRCUITS);
    PcompWriter.write(
        dir.resolve("Top.pcomp").toFile(),
        source,
        source.getCircuit("Top"),
        PcompMetadata.firstVersion("Top", TOP_PORTS),
        new Loader(null));
    PcompCatalog.useDirectory(dir.toFile());
    final var installed = PcompCatalog.installed().get(0);

    final var file = PcompProjects.read(PcompProjects.THREE_CIRCUITS);

    assertSame(installed, PcompLibraries.componentOf(file, installed.getCircuit()));
  }

  /**
   * {@link PcompLock}'s callers are mostly type-level code with no {@code Project} in hand, so they
   * go through the {@code Circuit}-only form. With no project open to search, it must still find a
   * default-catalog component rather than reporting every circuit as unowned -- the same thing
   * {@link #theDefaultCatalogIsReachableEvenWithoutBeingInTheFilesLibraries} checks for the
   * {@code (LogisimFile, Circuit)} form.
   */
  @Test
  public void theCircuitOnlyFormAlsoReachesTheDefaultCatalogWithNoProjectOpen(@TempDir Path dir)
      throws Exception {
    final var source = PcompProjects.read(PcompProjects.THREE_CIRCUITS);
    PcompWriter.write(
        dir.resolve("Top.pcomp").toFile(),
        source,
        source.getCircuit("Top"),
        PcompMetadata.firstVersion("Top", TOP_PORTS),
        new Loader(null));
    PcompCatalog.useDirectory(dir.toFile());
    final var installed = PcompCatalog.installed().get(0);

    assertSame(installed, PcompLibraries.componentOf(installed.getCircuit()));
  }

  @Test
  public void theCircuitOnlyFormIsNullWhenNothingOwnsTheCircuit() throws Exception {
    final var file = PcompProjects.read(PcompProjects.THREE_CIRCUITS);

    assertNull(PcompLibraries.componentOf(file.getCircuit("Unrelated")));
  }
}
