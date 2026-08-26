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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.std.Builtin;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Peler Edition. A component file, loaded the way the program loads one.
 *
 * <p>Everything up to here worked on a layout in memory. This is the first point at which a file
 * written to disk has to come back as something placeable, so it is where the pieces are checked
 * against each other rather than each on its own: the pins named in the metadata have to be the
 * pins in the circuit, the derived appearance has to reach the circuit rather than be quietly
 * replaced by the default box, and the toolbox has to be offered the component and not its parts.
 */
class PcompLibraryTest {

  /**
   * A component with two inputs and one output, written the way the writer will write one.
   *
   * <p>Keep every wire horizontal or vertical. A diagonal {@code <wire>} is not merely drawn
   * oddly: {@code WireRepair.doOverlaps} runs on load and fills the heap on one, and the resulting
   * OutOfMemoryError reaches Gradle as "Test process encountered an unexpected problem", naming no
   * test and no line.
   */
  private static File writeAdder(Path dir, String name, PortLayout layout) throws IOException {
    final var portXml = new StringBuilder();
    for (final var port : layout.placements()) {
      portXml.append(
          "    <port name=\"%s\" side=\"%s\" x=\"%d\" y=\"%d\"/>%n"
              .formatted(port.name(), port.side().toXmlValue(), port.x(), port.y()));
    }
    final var xml =
        """
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <project source="4.1.0" version="1.0">
          <lib desc="#Wiring" name="0"/>
          <lib desc="#Gates" name="1"/>
          <main name="%s"/>
          <circuit name="%s">
            <comp lib="0" loc="(100,110)" name="Pin">
              <a name="label" val="A"/>
            </comp>
            <comp lib="0" loc="(100,130)" name="Pin">
              <a name="label" val="B"/>
            </comp>
            <comp lib="0" loc="(300,120)" name="Pin">
              <a name="type" val="output"/>
              <a name="label" val="SUM"/>
            </comp>
            <comp lib="1" loc="(220,120)" name="AND Gate">
              <a name="inputs" val="2"/>
            </comp>
            <wire from="(100,110)" to="(170,110)"/>
            <wire from="(100,130)" to="(170,130)"/>
            <wire from="(220,120)" to="(300,120)"/>
          </circuit>
          <pcomp id="11111111-2222-4333-8444-555555555555" version="1" main="%s" locked="true"
                 width="%d" height="%d" caption-x="%d" caption-y="%d">
        %s  </pcomp>
        </project>
        """
            .formatted(
                name,
                name,
                name,
                layout.width(),
                layout.height(),
                layout.captionX(),
                layout.captionY(),
                portXml);
    final var file = dir.resolve(name + PcompFile.EXTENSION).toFile();
    Files.writeString(file.toPath(), xml, StandardCharsets.UTF_8);
    return file;
  }

  private static PortLayout defaultLayout(String name) {
    return PcompLayouts.automatic(
        name,
        PcompLayouts.nth("A", PortSide.LEFT, 0),
        PcompLayouts.nth("B", PortSide.LEFT, 1),
        PcompLayouts.nth("SUM", PortSide.RIGHT, 0));
  }

  /**
   * The component loads, offers exactly itself, and its ports are where the layout says. The port
   * check goes through {@code CircuitAppearance.getPortOffsets}, which is the same call the
   * simulator makes when it decides where a wire may attach.
   */
  @Test
  public void theComponentLoadsWithItsPortsWhereTheLayoutPutThem(@TempDir Path dir) throws Exception {
    final var layout = defaultLayout("Adder");
    final var file = writeAdder(dir, "Adder", layout);

    final var library = PcompLibrary.load(file, new Loader(null));

    assertEquals(1, library.getTools().size(), "the toolbox should be offered the component alone");
    assertEquals("Adder v1", library.getDisplayName());

    final var offsets = library.getCircuit().getAppearance().getPortOffsets(Direction.EAST);
    assertEquals(3, offsets.size());
    for (final var port : layout.placements()) {
      final var expected = layout.offsetOf(port.name());
      assertTrue(
          offsets.containsKey(expected),
          port.name() + " should be at " + expected + " but the circuit has " + offsets.keySet());
    }
  }

  /**
   * The derived appearance actually takes effect. Setting the shapes without also switching the
   * circuit to a custom appearance leaves {@code isDefaultAppearance} true, and the drawing quietly
   * reverts to the ordinary subcircuit box with its ports somewhere else entirely.
   */
  @Test
  public void theDerivedAppearanceIsTheOneInUse(@TempDir Path dir) throws Exception {
    final var layout = defaultLayout("Adder");
    final var file = writeAdder(dir, "Adder", layout);

    final var library = PcompLibrary.load(file, new Loader(null));

    assertFalse(
        library.getCircuit().getAppearance().isDefaultAppearance(),
        "the component fell back to the default subcircuit box");
    assertEquals(
        com.cburch.logisim.data.Bounds.create(-1, -1, layout.width() + 2, layout.height() + 2),
        library.getCircuit().getAppearance().getOffsetBounds());
  }

  /** Ports on all four sides survive the trip through the file. */
  @Test
  public void portsOnEverySideSurviveTheFile(@TempDir Path dir) throws Exception {
    final var layout =
        PcompLayouts.automatic(
            "Spread",
            PcompLayouts.nth("A", PortSide.LEFT, 0),
            PcompLayouts.nth("B", PortSide.TOP, 0),
            PcompLayouts.nth("SUM", PortSide.BOTTOM, 0));
    final var file = writeAdder(dir, "Spread", layout);

    final var library = PcompLibrary.load(file, new Loader(null));

    final var offsets = library.getCircuit().getAppearance().getPortOffsets(Direction.EAST);
    for (final var port : layout.placements()) {
      assertTrue(offsets.containsKey(layout.offsetOf(port.name())), port.name() + " moved");
    }
  }

  /**
   * A box the user dragged is drawn as they dragged it, not put back to the default.
   *
   * <p>The one thing the whole free-layout half of this feature rests on: the file states the
   * geometry, and loading it states nothing of its own.
   */
  @Test
  public void theBoxComesBackTheShapeItWasSaved(@TempDir Path dir) throws Exception {
    final var layout =
        new PortLayout(
            "Adder",
            180,
            40,
            90,
            20,
            List.of(
                new PortPlacement("A", PortSide.LEFT, 0, 10),
                new PortPlacement("B", PortSide.LEFT, 0, 30),
                new PortPlacement("SUM", PortSide.BOTTOM, 150, 40)));
    final var file = writeAdder(dir, "Adder", layout);

    final var library = PcompLibrary.load(file, new Loader(null));

    assertEquals(
        com.cburch.logisim.data.Bounds.create(-1, -1, 182, 42),
        library.getCircuit().getAppearance().getOffsetBounds(),
        "the box was not the one the file described");
    final var offsets = library.getCircuit().getAppearance().getPortOffsets(Direction.EAST);
    for (final var port : layout.placements()) {
      assertTrue(offsets.containsKey(layout.offsetOf(port.name())), port.name() + " moved");
    }
  }

  /**
   * A component naming a port its circuit has no pin for is refused outright. Loading it anyway
   * would put a component in the toolbox with a port that cannot be wired and no way to tell why.
   */
  @Test
  public void portsThatNoPinAnswersToAreRefused(@TempDir Path dir) throws Exception {
    final var ports = new ArrayList<>(defaultLayout("Wrong").placements());
    ports.add(new PortPlacement("CARRY", PortSide.RIGHT, 0, 10));
    final var file = writeAdder(dir, "Wrong", PcompLayouts.automatic("Wrong", ports));

    final var failure =
        assertThrows(IOException.class, () -> PcompLibrary.load(file, new Loader(null)));
    assertTrue(failure.getMessage().contains("CARRY"), "the message should name the missing pin");
  }

  /**
   * One unloadable file does not take the scan down with it. This directory holds whatever a user
   * put there, and it is read while the program is starting.
   */
  @Test
  public void oneBrokenFileIsSkippedRatherThanFatal(@TempDir Path dir) throws Exception {
    writeAdder(dir, "Good", defaultLayout("Good"));
    Files.writeString(dir.resolve("Broken.pcomp"), "not a component", StandardCharsets.UTF_8);
    Files.writeString(dir.resolve("Ignored.circ"), "not scanned either", StandardCharsets.UTF_8);
    final var problems = new ArrayList<String>();

    final var found =
        PcompCatalog.scan(dir.toFile(), new Loader(null), (file, why) -> problems.add(file.getName()));

    assertEquals(1, found.size(), "the good component should still have loaded");
    assertEquals("Good v1", found.get(0).getDisplayName());
    assertEquals(List.of("Broken.pcomp"), problems, "the broken file should have been reported");
  }

  /**
   * The toolbox category is registered and reachable by the name a project file uses. Registering
   * it in {@code Builtin} is only half of it: a new project loads exactly the libraries {@code
   * default.templ} names, so a category missing from that list exists and is invisible.
   */
  @Test
  public void theToolboxCategoryIsInEveryNewProject() throws Exception {
    final var builtin = new Builtin();
    assertNotNull(
        builtin.getLibrary(PcompCatalogLibrary._ID),
        "the category is not registered as a built-in library");

    final String template;
    try (final var in =
        PcompCatalogLibrary.class
            .getClassLoader()
            .getResourceAsStream("resources/logisim/default.templ")) {
      assertNotNull(in, "default.templ is not on the classpath");
      template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertTrue(
        template.contains("desc=\"#" + PcompCatalogLibrary._ID + "\""),
        "default.templ does not name the category, so new projects will not show it");
  }

  /**
   * Two components claiming one circuit name: the first keeps it. A project file records a placed
   * component as the library plus the tool name, so a second tool under that name in the same
   * category would be a reference with two answers.
   */
  @Test
  public void twoComponentsCannotShareACircuitName(@TempDir Path dir) throws Exception {
    final var first = PcompLibrary.load(writeAdder(dir, "Adder", defaultLayout("Adder")), new Loader(null));
    final var other = Files.createTempDirectory(dir, "other");
    final var second =
        PcompLibrary.load(writeAdder(other, "Adder", defaultLayout("Adder")), new Loader(null));

    final var tools = PcompCatalogLibrary.toolsOf(List.of(first, second));

    assertEquals(1, tools.size(), "the clashing name should have been left out");
    assertSame(first.getTools().get(0), tools.get(0), "the first component should have kept it");
  }

  @Test
  public void scanningSomewhereThatIsNotADirectoryFindsNothing() {
    assertTrue(PcompCatalog.scan(null, new Loader(null), null).isEmpty());
    assertTrue(
        PcompCatalog.scan(new File("no-such-directory-here"), new Loader(null), null).isEmpty());
  }

  /** The component directory is this edition's own, not shared with the official build. */
  @Test
  public void theComponentDirectoryIsThisEditionsOwn() {
    final var directory = PcompCatalog.defaultDirectory();
    assertTrue(directory.contains("logisim-peler"), directory + " is not this edition's own");
    assertFalse(directory.contains("logisim-defaults"), "that directory belongs to both editions");
  }
}
