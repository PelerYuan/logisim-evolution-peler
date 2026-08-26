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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.util.SyntaxChecker;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Peler Edition. The {@code .pcomp} format: what survives a trip to disk and back.
 *
 * <p>A component file is the one place the box and its ports are recorded. Everything a project
 * that uses the component depends on -- where its ports are, therefore where its wires attach --
 * comes from what is written here, so a field that quietly fails to round-trip does not show up as
 * a parse error but as a component whose ports have moved.
 */
class PcompFileTest {

  private static PcompMetadata sample() {
    return new PcompMetadata(
        "5f2c1d90-0000-4000-8000-000000000001",
        3,
        "Adder4",
        "Adder4_v3",
        true,
        PcompLayouts.automatic(
            "Adder4",
            PcompLayouts.nth("A", PortSide.LEFT, 0),
            PcompLayouts.nth("B", PortSide.LEFT, 1),
            PcompLayouts.nth("CIN", PortSide.BOTTOM, 0),
            PcompLayouts.nth("SUM", PortSide.RIGHT, 0)));
  }

  private static String projectXml(String extra) {
    return """
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <project source="4.1.0" version="1.0">
          <lib desc="#Wiring" name="0"/>
          <lib desc="#Gates" name="1"/>
          <main name="Adder4"/>
          <circuit name="Adder4"/>
        %s
        </project>
        """
        .formatted(extra);
  }

  private static String elementXml(PcompMetadata metadata) throws Exception {
    final var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
    final var root = document.createElement("project");
    document.appendChild(root);
    root.appendChild(PcompFile.toElement(document, metadata));
    final var out = new java.io.StringWriter();
    TransformerFactory.newInstance()
        .newTransformer()
        .transform(new DOMSource(document), new StreamResult(out));
    final var xml = out.toString();
    return xml.substring(xml.indexOf("<pcomp"), xml.lastIndexOf("</project>"));
  }

  /** Everything the metadata carries comes back, field for field, port for port. */
  @Test
  public void metadataSurvivesTheRoundTrip(@TempDir Path dir) throws Exception {
    final var original = sample();
    final var file = dir.resolve("Adder4" + PcompFile.EXTENSION).toFile();
    Files.writeString(file.toPath(), projectXml(elementXml(original)), StandardCharsets.UTF_8);

    final var read = PcompFile.read(file);

    assertNotNull(read, "the component metadata was not found in the file");
    assertEquals(original, read);
  }

  /**
   * A project file that is not a component reads as one with no metadata rather than as an error.
   * The component library scans a directory the user can put anything in.
   */
  @Test
  public void anOrdinaryProjectFileHasNoMetadata(@TempDir Path dir) throws Exception {
    final var file = dir.resolve("plain.circ").toFile();
    Files.writeString(file.toPath(), projectXml(""), StandardCharsets.UTF_8);

    assertNull(PcompFile.read(file));
  }

  @Test
  public void filesThatAreNotXmlAreReportedRatherThanIgnored(@TempDir Path dir) throws Exception {
    final var file = dir.resolve("broken" + PcompFile.EXTENSION).toFile();
    Files.writeString(file.toPath(), "this is not a component", StandardCharsets.UTF_8);

    assertThrows(IOException.class, () -> PcompFile.read(file));
  }

  /**
   * The reader refuses a port it cannot place rather than dropping it. A dropped port would leave a
   * component whose pins outnumber its ports, which draws fine and cannot be wired.
   */
  @Test
  public void portsWithNoUsableSideOrPlaceAreRefused(@TempDir Path dir) throws Exception {
    final var noSide =
        "<pcomp id=\"x\" version=\"1\" main=\"Adder4\" locked=\"true\">"
            + "<port name=\"A\" side=\"sideways\" x=\"0\" y=\"20\"/></pcomp>";
    final var nowhere =
        "<pcomp id=\"x\" version=\"1\" main=\"Adder4\" locked=\"true\">"
            + "<port name=\"A\" side=\"left\"/></pcomp>";
    final var halfAPlace =
        "<pcomp id=\"x\" version=\"1\" main=\"Adder4\" locked=\"true\">"
            + "<port name=\"A\" side=\"left\" x=\"0\" y=\"over there\"/></pcomp>";
    for (final var broken : List.of(noSide, nowhere, halfAPlace)) {
      final var file = dir.resolve(Math.abs(broken.hashCode()) + PcompFile.EXTENSION).toFile();
      Files.writeString(file.toPath(), projectXml(broken), StandardCharsets.UTF_8);
      assertThrows(IOException.class, () -> PcompFile.read(file));
    }
  }

  /**
   * A component published before the layout window could do more than order the ports still opens.
   *
   * <p>Those files say an edge and a place in that edge's order and leave the geometry to the
   * reader, so they are laid out by {@link PortLayout#automatic} -- the same arithmetic, in the same
   * place, that produced their coordinates when they were written. What they get back is therefore
   * the tidy default rather than to the pixel what they had, that arithmetic having been corrected
   * since; what matters is that the component still loads, still places and still wires up.
   */
  @Test
  public void filesFromTheSlotFormStillLoad(@TempDir Path dir) throws Exception {
    final var slots =
        "<pcomp id=\"x\" version=\"1\" name=\"Adder4\" main=\"Adder4\" locked=\"true\">"
            + "<port name=\"A\" side=\"left\" slot=\"0\"/>"
            + "<port name=\"B\" side=\"left\" slot=\"1\"/>"
            + "<port name=\"SUM\" side=\"right\" slot=\"0\"/></pcomp>";
    final var file = dir.resolve("legacy" + PcompFile.EXTENSION).toFile();
    Files.writeString(file.toPath(), projectXml(slots), StandardCharsets.UTF_8);

    final var read = PcompFile.read(file);

    assertNotNull(read);
    assertEquals(
        PcompLayouts.automatic(
            "Adder4",
            PcompLayouts.nth("A", PortSide.LEFT, 0),
            PcompLayouts.nth("B", PortSide.LEFT, 1),
            PcompLayouts.nth("SUM", PortSide.RIGHT, 0)),
        read.layout(),
        "an older component came back as a different shape from the default one");
  }

  /**
   * The box and the caption's place survive on their own, not only the ports.
   *
   * <p>They are what the user dragged, and a file that lost them would open every component back at
   * the default shape -- which is exactly the shape the layout window exists to get away from.
   */
  @Test
  public void theBoxAndTheCaptionSurviveTheRoundTrip(@TempDir Path dir) throws Exception {
    final var dragged =
        new PcompMetadata(
            "5f2c1d90-0000-4000-8000-000000000002",
            1,
            "Wide",
            "Wide_v1",
            true,
            new PortLayout(
                "Wide",
                240,
                40,
                90,
                10,
                List.of(
                    new PortPlacement("A", PortSide.LEFT, 0, 20),
                    new PortPlacement("SUM", PortSide.BOTTOM, 200, 40))));
    final var file = dir.resolve("Wide" + PcompFile.EXTENSION).toFile();
    Files.writeString(file.toPath(), projectXml(elementXml(dragged)), StandardCharsets.UTF_8);

    final var read = PcompFile.read(file);

    assertNotNull(read);
    assertEquals(dragged.layout(), read.layout());
    assertEquals(240, read.layout().width());
    assertEquals(90, read.layout().captionX());
  }

  /**
   * This edition opens a component file as an ordinary project. Upstream's reader throws on any
   * element under {@code <project>} it does not know, and that default is still there for
   * everything else -- {@code XmlReader} gained a case for {@code <pcomp>} and nothing more, so a
   * genuinely corrupt file is still refused.
   */
  @Test
  public void theProjectReaderOpensAComponentFile() throws Exception {
    final var xml = projectXml(elementXml(sample()));
    final var loaded =
        LogisimFile.load(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), new Loader(null));

    assertNotNull(loaded, "a component file did not open as a project");
    assertNotNull(loaded.getCircuit("Adder4"), "the component's own circuit is missing");
  }

  @Test
  public void anUnknownElementIsStillRefused() {
    final var xml = projectXml("<gibberish/>");
    assertThrows(
        Exception.class,
        () ->
            LogisimFile.load(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), new Loader(null)));
  }

  /**
   * A new version keeps the identity, bumps the number, and lands on a circuit name of its own.
   *
   * <p>The last one is what lets two versions sit in one project. A project file records a placed
   * component as its library plus the circuit name, so two versions sharing a circuit name would be
   * one reference with two answers -- and the older project would get whichever version happened to
   * load first, with its wires still where the other version's ports used to be.
   */
  @Test
  public void newVersionsKeepTheIdAndBumpTheNumber() {
    final var first = PcompMetadata.firstVersion("Adder4", sample().layout());
    final var second =
        first.nextVersion(
            PcompLayouts.automatic(
                "Adder4",
                PcompLayouts.nth("A", PortSide.LEFT, 0),
                PcompLayouts.nth("SUM", PortSide.RIGHT, 0)));

    assertEquals(first.id(), second.id(), "a new version should be the same component");
    assertEquals(first.version() + 1, second.version());
    assertEquals("Adder4", second.name(), "the name a user sees should not gain a version number");
    assertEquals("Adder4_v1", first.mainCircuit());
    assertEquals("Adder4_v2", second.mainCircuit());
    assertNotEquals(
        first.mainCircuit(), second.mainCircuit(), "two versions would be one reference");
  }

  /**
   * The circuit name a version carries is one an ordinary circuit could have.
   *
   * <p>It is set on a live circuit at some point, and {@code CircuitAttributes} refuses a name
   * {@code SyntaxChecker} rejects -- by opening a modal dialog and putting the old name back, which
   * in the middle of a write would be a component whose metadata names a circuit it does not have.
   */
  @Test
  public void theCircuitNameOfAVersionIsLegal() {
    assertNull(SyntaxChecker.getErrorMessage(PcompMetadata.circuitNameFor("Adder4", 1)));
    assertNull(SyntaxChecker.getErrorMessage(PcompMetadata.circuitNameFor("My_Adder", 12)));
  }

  @Test
  public void theExtensionIsRecognised(@TempDir Path dir) {
    assertTrue(PcompFile.isPcompFile(new File(dir.toFile(), "Adder4.pcomp")));
    assertFalse(PcompFile.isPcompFile(new File(dir.toFile(), "Adder4.circ")));
    assertFalse(PcompFile.isPcompFile(null));
  }
}
