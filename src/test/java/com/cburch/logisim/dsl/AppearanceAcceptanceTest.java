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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Appearance editing: what the GUI's appearance editor can do, through {@link Appearance}. */
class AppearanceAcceptanceTest {
  private record Fixture(Space space, Appearance appearance, Project project) {}

  private static Fixture circuitWithTwoPins() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    final var space = Space.of(project);
    final var pin = Kind.of(space, "wiring/pin");
    space.place(pin).anchorAt(4, 4).with(Attrs.of("type", "input")).place().label("A");
    space.place(pin).anchorAt(20, 4).with(Attrs.of("type", "output")).place().label("Y");
    space.commit("pins");
    return new Fixture(space, Appearance.of(space), project);
  }

  private static long count(List<Appearance.Shape> shapes, String kind) {
    return shapes.stream().filter(s -> s.kind().equals(kind)).count();
  }

  @Test
  void aCircuitWithPinsListsAPortPerPinAndAnAnchor() {
    final var f = circuitWithTwoPins();
    final var shapes = f.appearance().list();
    assertEquals(2, count(shapes, "port"));
    assertEquals(1, count(shapes, "anchor"));
    assertTrue(shapes.stream().anyMatch(s -> "A".equals(s.pin())));
  }

  @Test
  void drawingAShapeSwitchesToCustomAndIsOneUndoableEntry() {
    final var f = circuitWithTwoPins();
    final var before = f.appearance().list().size();
    final var styleBefore = f.appearance().style();

    final var index = f.appearance().addRect(0, 0, 40, 30, Map.of("stroke", "#ff0000", "fill", "#00ff00"));

    assertEquals("custom", f.appearance().style());
    final var rect = f.appearance().list().get(index);
    assertEquals("rect", rect.kind());
    assertEquals(40, rect.width());
    assertEquals("#ff0000", rect.stroke());
    assertEquals("#00ff00", rect.fill());
    assertEquals(before + 1, f.appearance().list().size());

    History.of(f.space()).undo();
    assertEquals(before, f.appearance().list().size());
    assertEquals(styleBefore, f.appearance().style());
  }

  @Test
  void everyShapeKindCanBeAddedAndRemoved() {
    final var f = circuitWithTwoPins();
    final var a = f.appearance();
    final var base = a.list().size();
    a.addOval(0, 0, 10, 10, null);
    a.addLine(0, 0, 10, 10, null);
    a.addRoundRect(0, 0, 20, 20, 5, null);
    a.addPoly(List.of(new int[] {0, 0}, new int[] {10, 0}, new int[] {5, 8}), true, null);
    a.addPoly(List.of(new int[] {0, 0}, new int[] {10, 0}), false, null);
    final var text = a.addText(2, 2, "ALU", Map.of("size", 14));

    final var shapes = a.list();
    assertEquals(base + 6, shapes.size());
    assertEquals("ALU", shapes.get(text).text());
    assertEquals(1, count(shapes, "oval"));
    assertEquals(1, count(shapes, "roundrect"));
    assertEquals(1, count(shapes, "polygon"));
    assertEquals(1, count(shapes, "polyline"));

    // The default custom appearance already holds a removable box, so clear() takes that too.
    final var removable = shapes.size() - count(shapes, "port") - count(shapes, "anchor");
    assertEquals(removable, a.clear());
    assertEquals(3, a.list().size());
    History.of(f.space()).undo();
    assertEquals(base + 6, a.list().size());
  }

  @Test
  void portsCanBeMovedButNotRemoved() {
    final var f = circuitWithTwoPins();
    final var a = f.appearance();
    final var port = a.list().stream().filter(s -> "A".equals(s.pin())).findFirst().orElseThrow();

    assertThrows(InvalidAppearanceEditException.class, () -> a.remove(port.index()));

    a.move(port.index(), 10, 20);
    final var moved = a.list().stream().filter(s -> "A".equals(s.pin())).findFirst().orElseThrow();
    assertEquals(port.x() + 10, moved.x());
    assertEquals(port.y() + 20, moved.y());
    History.of(f.space()).undo();
    final var back = a.list().stream().filter(s -> "A".equals(s.pin())).findFirst().orElseThrow();
    assertEquals(port.x(), back.x());
  }

  @Test
  void reorderMovesAShapeToTheBottom() {
    final var f = circuitWithTwoPins();
    final var a = f.appearance();
    a.addRect(0, 0, 5, 5, null);
    final var top = a.addText(0, 0, "top", null);
    a.reorder(top, "bottom");
    assertEquals("text", a.list().get(0).kind());
  }

  @Test
  void resetDefaultRestoresTheBoxAndIsUndoable() {
    final var f = circuitWithTwoPins();
    final var a = f.appearance();
    final var pristine = a.list().size();
    a.addRect(0, 0, 5, 5, null);
    a.addOval(0, 0, 5, 5, null);

    a.resetDefault();
    assertEquals(pristine, a.list().size());
    History.of(f.space()).undo();
    assertEquals(pristine + 2, a.list().size());
  }

  @Test
  void loadLogisimDefaultReplacesTheShapes() {
    final var f = circuitWithTwoPins();
    final var a = f.appearance();
    a.loadLogisimDefault();
    assertEquals("custom", a.style());
    assertEquals(2, count(a.list(), "port"));
  }

  @Test
  void styleCanBeChosenAndBadNamesAreRefused() {
    final var f = circuitWithTwoPins();
    f.appearance().setStyle("evolution");
    assertEquals("evolution", f.appearance().style());
    f.appearance().setStyle("classic");
    assertEquals("classic", f.appearance().style());
    assertThrows(InvalidAppearanceEditException.class, () -> f.appearance().setStyle("neon"));
    History.of(f.space()).undo();
    assertEquals("evolution", f.appearance().style());
  }

  @Test
  void anchorFacingChanges() {
    final var f = circuitWithTwoPins();
    f.appearance().setAnchorFacing("north");
    assertFalse(f.appearance().list().isEmpty());
    History.of(f.space()).undo();
  }

  @Test
  void badInputsAreStructuredErrors() {
    final var f = circuitWithTwoPins();
    final var a = f.appearance();
    assertThrows(UnknownAppearanceShapeException.class, () -> a.remove(99));
    assertThrows(UnknownAppearanceShapeException.class, () -> a.move(-1, 1, 1));
    assertThrows(InvalidAppearanceEditException.class, () -> a.addRect(0, 0, 0, 5, null));
    assertThrows(InvalidAppearanceEditException.class, () -> a.addRect(0, 0, 5, 5, Map.of("colour", "red")));
    assertThrows(InvalidAppearanceEditException.class, () -> a.addRect(0, 0, 5, 5, Map.of("fill", "red")));
    assertThrows(InvalidAppearanceEditException.class, () -> a.addLine(0, 0, 5, 5, Map.of("fill", "#000000")));
    assertThrows(InvalidAppearanceEditException.class, () -> a.addPoly(List.of(new int[] {0, 0}), false, null));
    assertThrows(InvalidAppearanceEditException.class, () -> a.reorder(0, "sideways"));
  }
}
