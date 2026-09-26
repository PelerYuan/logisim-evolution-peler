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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.PcompLowering;
import com.cburch.logisim.file.PcompWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Peler Edition. P4: this rework (see {@code docs/peler-edition/design/pcomp-libraries.md}) is
 * meant to be purely additive -- a project that never touches a loaded component library should be
 * unable to tell the difference. Nothing here should ever need new production code; if it does, P1
 * through P3 broke the promise this phase exists to check.
 */
class PcompMigrationTest {

  @BeforeEach
  @AfterEach
  void forgetTheDefaultCatalog() {
    PcompCatalog.useDirectory(null);
  }

  private static final PortLayout PORTS =
      PcompLayouts.automatic(
          "Top",
          PcompLayouts.nth("A", PortSide.LEFT, 0),
          PcompLayouts.nth("B", PortSide.LEFT, 1),
          PcompLayouts.nth("S", PortSide.RIGHT, 0));

  /**
   * The shape of a project saved before this rework existed: one {@code <lib desc="#...">}
   * pointing at the default catalog, and nothing that names a component library at all. Every
   * piece this rework touched -- lookup, locking, compatible-save lowering -- has to keep working
   * for exactly this project unchanged.
   */
  @Test
  public void projectNamingOnlyTheDefaultCatalogStillWorksEndToEnd(@TempDir Path dir)
      throws Exception {
    PcompCatalog.useDirectory(dir.toFile());
    final var metadata = PcompMetadata.firstVersion("Top", PORTS);
    final var source = PcompProjects.read(PcompProjects.THREE_CIRCUITS);
    final var componentFile = dir.resolve(metadata.mainCircuit() + PcompFile.EXTENSION).toFile();
    PcompWriter.write(
        componentFile, source, source.getCircuit("Top"), metadata, new Loader(null));
    PcompCatalog.install(componentFile, new Loader(null));
    final var installed = PcompCatalog.installed().get(0);

    final var project =
        PcompProjects.read(
            """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <project source="4.1.0" version="1.0">
              <lib desc="#Wiring" name="0"/>
              <lib desc="#%s" name="1"/>
              <main name="main"/>
              <circuit name="main">
                <comp lib="1" loc="(100,100)" name="%s"/>
              </circuit>
            </project>
            """
                .formatted(PcompCatalogLibrary._ID, metadata.mainCircuit()));

    assertSame(
        installed,
        PcompLibraries.componentOf(project, installed.getCircuit()),
        "lookup by a specific project's library tree");
    assertSame(
        installed,
        PcompLibraries.componentOf(installed.getCircuit()),
        "lookup with no project in hand, PcompLock's usual case");
    assertEquals(
        installed.isLocked(),
        PcompLock.blocksEntryInto(installed.getCircuit()),
        "locking should track the component's own lock, not fall silent");
    assertTrue(
        PcompLowering.plan(project).containsKey(installed.getCircuit()),
        "the placed component should still be planned for compatible-save inlining");
  }

  /**
   * {@code default.templ} is what a brand new project loads with zero user action -- see {@code
   * Builtin.java}'s comment on it and CLAUDE.md's own warning that it is fragile: a stray {@code
   * --} inside one of its XML comments has silently broken every new project before. This
   * automates the validation CLAUDE.md otherwise asks a human to run by hand before pushing.
   */
  @Test
  public void theDefaultTemplateIsWellFormedXml() throws Exception {
    try (final var in =
        PcompCatalogLibrary.class
            .getClassLoader()
            .getResourceAsStream("resources/logisim/default.templ")) {
      assertNotNull(in, "default.templ is not on the classpath");
      final var factory = DocumentBuilderFactory.newInstance();
      final var document = factory.newDocumentBuilder().parse(in);
      assertNotNull(document.getDocumentElement());
    }
  }

  /**
   * A brand new project carries the default catalog -- {@code
   * PcompComponentTest.theToolboxCategoryIsInEveryNewProject} already checks that -- but this
   * rework's whole point is that it must carry nothing else: no component library is loaded onto a
   * project that never asked for one.
   */
  @Test
  public void freshProjectHasNoLoadedComponentLibrary() throws Exception {
    final var file = PcompProjects.read(PcompProjects.THREE_CIRCUITS);

    for (final var lib : file.getLibraries()) {
      assertFalse(
          lib instanceof PcompComponentLibrary,
          "a new project should not come with any component library pre-loaded: " + lib.getName());
    }
  }
}
