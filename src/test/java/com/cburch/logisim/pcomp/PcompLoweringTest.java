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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.PcompLowering;
import com.cburch.logisim.file.PcompWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Peler Edition. Saving a project that uses custom components to official {@code .circ}.
 *
 * <p>Custom components are the one addition of this edition that does not have to be lost over
 * there: the component is a circuit and its drawing is that circuit's own appearance, both of which
 * official Logisim-evolution already has. So the compatible file gets the component written into it
 * as an ordinary circuit rather than dropped, and what these tests check is that nothing on the
 * page moves in the process -- same coordinates, same appearance, same ports.
 *
 * <p>The catalog is a process-wide registry, so each test points it at its own folder and puts it
 * back afterwards.
 */
class PcompLoweringTest {

  @BeforeEach
  @AfterEach
  void forgetTheCatalog() {
    PcompCatalog.useDirectory(null);
  }

  private static final PortLayout PORTS =
      PcompLayouts.automatic(
          "Top",
          PcompLayouts.nth("A", PortSide.LEFT, 0),
          PcompLayouts.nth("B", PortSide.LEFT, 1),
          PcompLayouts.nth("S", PortSide.RIGHT, 0),
          PcompLayouts.nth("C", PortSide.BOTTOM, 0));

  /**
   * A project that places one custom component, and nothing else.
   *
   * <p>The component library is named the way a project file names it -- by descriptor -- so the
   * fixture goes through the same resolution the real reader does, and would fail here rather than
   * quietly if the catalog were not registered.
   */
  private static String projectUsing(String component) {
    return """
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <project source="4.1.0" version="1.0">
          <lib desc="#Wiring" name="0"/>
          <lib desc="#Gates" name="1"/>
          <lib desc="#Base" name="2"/>
          <lib desc="#%s" name="3"/>
          <main name="main"/>
          <circuit name="main">
            <comp lib="0" loc="(100,110)" name="Pin">
              <a name="label" val="IN"/>
            </comp>
            <comp lib="3" loc="(300,200)" name="%s">
              <a name="facing" val="north"/>
              <a name="label" val="U1"/>
            </comp>
          </circuit>
        </project>
        """
        .formatted(PcompCatalogLibrary._ID, component);
  }

  /** Publishes {@code Top} from the shared fixture as a component and installs it. */
  private static PcompMetadata installTop(Path dir) throws Exception {
    PcompCatalog.useDirectory(dir.toFile());
    final var metadata = PcompMetadata.firstVersion("Top", PORTS);
    final var source = PcompProjects.read(PcompProjects.THREE_CIRCUITS);
    final var file = dir.resolve(metadata.mainCircuit() + PcompFile.EXTENSION).toFile();
    PcompWriter.write(file, source, source.getCircuit("Top"), metadata, new Loader(null));
    PcompCatalog.install(file, new Loader(null));
    return metadata;
  }

  private static LogisimFile read(String xml) throws Exception {
    final var project =
        LogisimFile.load(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), new Loader(null));
    assertNotNull(project, "the fixture did not load");
    return project;
  }

  /** Saves through the real path, which is where the destination's extension picks the dialect. */
  private static String saveTo(LogisimFile project, File dest) throws Exception {
    assertTrue(project.getLoader().save(project, dest), "the project did not save");
    return Files.readString(dest.toPath(), StandardCharsets.UTF_8);
  }

  /**
   * The component and the circuits it is built from are written into the compatible file, and the
   * library that offered it is not.
   */
  @Test
  public void theCompatibleSaveWritesTheComponentInAsACircuit(@TempDir Path dir) throws Exception {
    installTop(dir);
    final var project = read(projectUsing("Top_v1"));

    final var written = saveTo(project, dir.resolve("out.circ").toFile());

    assertTrue(written.contains("<circuit name=\"Top_v1\""), "the component circuit is missing");
    assertTrue(written.contains("<circuit name=\"Half\""), "the component's dependency is missing");
    assertFalse(
        written.contains(PcompCatalogLibrary._ID),
        "the compatible file still names a library upstream does not have");
    assertFalse(
        written.contains("Unrelated"),
        "a circuit the component does not use came along with it");
  }

  /**
   * The placed component becomes a reference to a circuit of this file: no library, and no leftover
   * {@code circuit} attribute pointing back at the component.
   */
  @Test
  public void thePlacedComponentBecomesAPlainSubcircuit(@TempDir Path dir) throws Exception {
    installTop(dir);
    final var project = read(projectUsing("Top_v1"));

    final var written = saveTo(project, dir.resolve("out.circ").toFile());

    assertTrue(
        written.contains("<comp loc=\"(300,200)\" name=\"Top_v1\">"),
        "the placed component should carry no library: " + written);
    final var placed =
        written.substring(
            written.indexOf("<comp loc=\"(300,200)\""),
            written.indexOf("</comp>", written.indexOf("<comp loc=\"(300,200)\"")));
    assertFalse(
        placed.contains("name=\"circuit\""),
        "the component still records which circuit it came from: " + placed);
    assertTrue(placed.contains("val=\"north\""), "the component lost its facing: " + placed);
    assertTrue(placed.contains("val=\"U1\""), "the component lost its label: " + placed);
  }

  /**
   * Saving compatibly does not change the project being saved. It is the same objection as any
   * other lowering: the user asked for a file in another dialect, not for their open project to
   * become that.
   */
  @Test
  public void theProjectItselfIsLeftAlone(@TempDir Path dir) throws Exception {
    installTop(dir);
    final var project = read(projectUsing("Top_v1"));

    saveTo(project, dir.resolve("out.circ").toFile());

    assertEquals(
        List.of("main"),
        project.getCircuits().stream().map(circuit -> circuit.getName()).toList(),
        "the lowered circuits were added to the open project");
  }

  /**
   * Reading the compatible file back gives the same drawing in the same place.
   *
   * <p>The port offsets are the point. They come from the appearance, they are where a wire may
   * attach, and if the lowering had dropped the appearance the circuit would open over there as a
   * default box with its ports somewhere else -- a file that looks like it survived and is wired
   * wrongly.
   */
  @Test
  public void theComponentComesBackDrawnTheSameWay(@TempDir Path dir) throws Exception {
    installTop(dir);
    final var before = read(projectUsing("Top_v1"));
    final var offsetsBefore =
        PcompCatalog.installed().get(0).getCircuit().getAppearance().getPortOffsets(Direction.EAST);

    final var dest = dir.resolve("out.circ").toFile();
    saveTo(before, dest);
    PcompCatalog.useDirectory(dir.resolve("empty").toFile());
    final var after = LogisimFile.load(dest, new Loader(null));

    assertNotNull(after, "the compatible file did not load");
    final var lowered = after.getCircuit("Top_v1");
    assertNotNull(lowered, "the component circuit is not in the file");
    assertFalse(
        lowered.getAppearance().isDefaultAppearance(),
        "the component came back as an ordinary subcircuit box");
    assertEquals(
        offsetsBefore.keySet(),
        lowered.getAppearance().getPortOffsets(Direction.EAST).keySet(),
        "the ports moved, so every wire that reached one is now somewhere else");

    var placed = 0;
    for (final var component : after.getCircuit("main").getNonWires()) {
      if (!(component.getFactory() instanceof SubcircuitFactory factory)) continue;
      placed++;
      assertEquals(lowered, factory.getSubcircuit(), "it is pointing at some other circuit");
      assertEquals(Location.create(300, 200, true), component.getLocation(), "it moved");
    }
    assertEquals(1, placed, "the placed component did not survive the trip");
  }

  /**
   * A project circuit already called what the component would be called does not get overwritten by
   * it. Two {@code <circuit>} elements of one name is a file whose subcircuit references have two
   * answers, and the one that loses is the user's own work.
   */
  @Test
  public void namesTheProjectAlreadyUsesAreNotTakenFromIt(@TempDir Path dir) throws Exception {
    installTop(dir);
    final var project = read(projectUsing("Top_v1").replace("<main name=\"main\"/>",
        "<main name=\"main\"/>\n  <circuit name=\"Top_v1\"/>"));

    final var written = saveTo(project, dir.resolve("out.circ").toFile());

    assertTrue(written.contains("<circuit name=\"Top_v1\""), "the project's own circuit is gone");
    assertTrue(written.contains("<circuit name=\"Top_v1_1\""), "the component was not renamed");
    assertTrue(
        written.contains("<comp loc=\"(300,200)\" name=\"Top_v1_1\">"),
        "the placed component still names the circuit it lost: " + written);
  }

  /** Nothing happens to a project that uses no custom components. */
  @Test
  public void projectsWithoutComponentsAreUnaffected(@TempDir Path dir) throws Exception {
    PcompCatalog.useDirectory(dir.toFile());
    final var project = PcompProjects.read(PcompProjects.THREE_CIRCUITS);

    assertTrue(PcompLowering.plan(project).isEmpty());
  }

  /**
   * The default catalog is not the only place a component can come from any more: it may equally
   * come from a component library the project loaded through {@code LibraryManager} (see {@code
   * docs/peler-edition/design/pcomp-libraries.md}). Lowering has to reach it there too -- this
   * points the default catalog at an unrelated, empty directory so the component can only be found
   * through the loaded library, not by accident through the old fixed path.
   */
  @Test
  public void componentFromALoadedLibraryIsAlsoLoweredCompatibly(@TempDir Path dir)
      throws Exception {
    PcompCatalog.useDirectory(dir.resolve("emptyCatalog").toFile());
    final var libraryDir = dir.resolve("mylib").toFile();
    PcompLibraryFile.create(libraryDir, "My Gates");
    final var source = PcompProjects.read(PcompProjects.THREE_CIRCUITS);
    PcompWriter.write(
        new File(libraryDir, "Top.pcomp"),
        source,
        source.getCircuit("Top"),
        PcompMetadata.firstVersion("Top", PORTS),
        new Loader(null));

    final var loader = new Loader(null);
    final var projectFile = dir.resolve("project.circ").toFile();
    Files.writeString(
        projectFile.toPath(),
        """
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <project source="4.1.0" version="1.0">
          <lib desc="#Wiring" name="0"/>
          <lib desc="#Gates" name="1"/>
          <lib desc="#Base" name="2"/>
          <lib desc="pcomplib#mylib" name="3"/>
          <main name="main"/>
          <circuit name="main">
            <comp lib="0" loc="(100,110)" name="Pin">
              <a name="label" val="IN"/>
            </comp>
            <comp lib="3" loc="(300,200)" name="Top_v1">
              <a name="facing" val="north"/>
              <a name="label" val="U1"/>
            </comp>
          </circuit>
        </project>
        """,
        StandardCharsets.UTF_8);
    final var project = loader.openLogisimFile(projectFile);

    final var written = saveTo(project, dir.resolve("out.circ").toFile());

    assertTrue(written.contains("<circuit name=\"Top_v1\""), "the component circuit is missing");
    assertTrue(written.contains("<circuit name=\"Half\""), "the component's dependency is missing");
    assertFalse(
        written.contains("pcomplib"),
        "the compatible file still names a library upstream does not have");
    assertTrue(
        written.contains("<comp loc=\"(300,200)\" name=\"Top_v1\">"),
        "the placed component should carry no library: " + written);
  }
}
