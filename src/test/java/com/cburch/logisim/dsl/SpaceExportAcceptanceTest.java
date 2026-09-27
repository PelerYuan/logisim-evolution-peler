/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.proj.Project;
import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Covers the headless image/HTML export added to {@link Space} (built on top of
 * {@link com.cburch.logisim.gui.main.ExportImage#exportSingle} and
 * {@link com.cburch.logisim.gui.htmlexport.HtmlExporter}, neither of which needs an open GUI
 * window): a real file gets written for a supported circuit, and each of the documented failure
 * modes (bad format string, unsupported component kind, I/O failure) surfaces as the structured
 * {@link DslException} promised in {@code EVAL_DESCRIPTION} rather than a bare exception. */
class SpaceExportAcceptanceTest {

  @TempDir File tempDir;

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  private static void placeAndGate(Space space) {
    final var gate = Kind.of(space, "gates/and_gate");
    space.place(gate).anchorAt(0, 0).place();
    space.commit("place an and gate");
  }

  @Test
  void exportImageWritesAPngFile() throws Exception {
    final var space = Space.of(blankProject());
    placeAndGate(space);

    final var target = new File(tempDir, "out.png");
    space.exportImage(target.getAbsolutePath(), "png");

    assertTrue(target.isFile());
    final var bytes = Files.readAllBytes(target.toPath());
    assertTrue(bytes.length > 8);
    // PNG magic number.
    assertEquals((byte) 0x89, bytes[0]);
    assertEquals('P', bytes[1]);
    assertEquals('N', bytes[2]);
    assertEquals('G', bytes[3]);
  }

  @Test
  void exportImageIsCaseInsensitiveAboutFormatAndAcceptsAScale() throws Exception {
    final var space = Space.of(blankProject());
    placeAndGate(space);

    final var target = new File(tempDir, "out.svg");
    space.exportImage(target.getAbsolutePath(), "SVG", 2.0, false);

    assertTrue(target.isFile());
    assertTrue(Files.readString(target.toPath()).contains("svg"));
  }

  @Test
  void exportImageRejectsAnUnknownFormat() {
    final var space = Space.of(blankProject());
    placeAndGate(space);

    final var thrown = assertThrows(InvalidExportFormatException.class,
        () -> space.exportImage(new File(tempDir, "out.bmp").getAbsolutePath(), "bmp"));
    assertEquals("bmp", thrown.details().get("format"));
  }

  @Test
  void exportImageWrapsAnIoFailureAsExportFailedException() {
    final var space = Space.of(blankProject());
    placeAndGate(space);

    final var badPath = new File(tempDir, "no-such-directory/out.png").getAbsolutePath();
    final var thrown =
        assertThrows(ExportFailedException.class, () -> space.exportImage(badPath, "png"));
    assertEquals(badPath, thrown.details().get("path"));
  }

  @Test
  void exportHtmlWritesAFileForASupportedCircuit() throws Exception {
    final var space = Space.of(blankProject());
    placeAndGate(space);

    final var target = new File(tempDir, "out.html");
    space.exportHtml(target.getAbsolutePath());

    assertTrue(target.isFile());
    assertTrue(Files.readString(target.toPath()).toLowerCase().contains("html"));
  }

  @Test
  void exportHtmlRefusesACircuitUsingAnUnsupportedComponentKind() {
    final var space = Space.of(blankProject());
    final var ram = Kind.of(space, "Memory/RAM");
    space.place(ram).anchorAt(0, 0).place();
    space.commit("place a RAM");

    final var target = new File(tempDir, "out.html");
    final var thrown = assertThrows(UnsupportedForHtmlExportException.class,
        () -> space.exportHtml(target.getAbsolutePath()));
    @SuppressWarnings("unchecked")
    final var kinds = (java.util.List<String>) thrown.details().get("unsupportedKinds");
    assertTrue(kinds.contains("RAM"));
  }
}
