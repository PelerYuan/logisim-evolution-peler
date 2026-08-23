/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.proj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.file.LoadFailedException;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.std.ttl.TtlLibrary;
import com.cburch.logisim.util.StringUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Peler Edition Feature 14: the Project menu's two commands for the drawing of every TTL chip.
 *
 * <p>What is worth holding down here is not that an attribute can be written -- the properties
 * panel has always been able to do that -- but the three claims the menu item makes that a
 * selection could not: that it reaches chips the user cannot see because they live in another
 * circuit, that the whole sweep steps back in one undo, and that it is safe to run on a wired
 * project because nothing about a chip's geometry depends on the attribute being flipped.
 */
class ProjectWideAttributeTest {

  private static final Attribute<Boolean> DRAWING = TtlLibrary.DRAW_INTERNAL_STRUCTURE;

  private static final String[] LOCALES = {
    "", "_de", "_el", "_es", "_fr", "_it", "_ja", "_nl", "_pl", "_pt", "_ru", "_zh"
  };

  private static final Path BUNDLES = Path.of("src/main/resources/resources/logisim/strings/gui");

  /**
   * A project with a TTL chip in the main circuit, a second one down in a subcircuit, and a plain
   * gate that has no such attribute at all.
   *
   * <p>The chip in {@code sub} is the point of the fixture: it is the one a selection in the main
   * canvas can never reach, no matter how the user selects.
   */
  private static LogisimFile mixedProject(Path workDir) throws IOException, LoadFailedException {
    final var xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project version="1.0">
         <lib name="1" desc="#Gates" />
         <lib name="2" desc="#TTL" />
         <main name="main" />
         <circuit name="sub">
          <a name="circuit" val="sub" />
          <comp lib="2" loc="(300,300)" name="7404" />
         </circuit>
         <circuit name="main">
          <a name="circuit" val="main" />
          <comp lib="2" loc="(400,400)" name="7400" />
          <comp lib="1" loc="(500,500)" name="AND Gate" />
          <comp loc="(600,600)" name="sub" />
         </circuit>
        </project>
        """;
    final var source = workDir.resolve("mixed.circ");
    Files.writeString(source, xml, StandardCharsets.UTF_8);
    return new Loader(null).openLogisimFile(source.toFile());
  }

  /** An otherwise identical project with no TTL chip anywhere. */
  private static LogisimFile gatesOnlyProject(Path workDir) throws IOException, LoadFailedException {
    final var xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project version="1.0">
         <lib name="1" desc="#Gates" />
         <main name="main" />
         <circuit name="main">
          <a name="circuit" val="main" />
          <comp lib="1" loc="(500,500)" name="AND Gate" />
         </circuit>
        </project>
        """;
    final var source = workDir.resolve("gates.circ");
    Files.writeString(source, xml, StandardCharsets.UTF_8);
    return new Loader(null).openLogisimFile(source.toFile());
  }

  /** Every component of the file that carries the drawing attribute, keyed by circuit and name. */
  private static Map<String, Component> chipsOf(LogisimFile file) {
    final var found = new LinkedHashMap<String, Component>();
    for (final var circuit : file.getCircuits()) {
      for (final var comp : sortedByLocation(circuit)) {
        if (comp.getAttributeSet().containsAttribute(DRAWING)) {
          found.put(circuit.getName() + "/" + comp.getFactory().getName(), comp);
        }
      }
    }
    return found;
  }

  /** {@code getNonWires} is a set, so give the fixture a stable order to report failures in. */
  private static List<Component> sortedByLocation(Circuit circuit) {
    final var comps = new ArrayList<>(circuit.getNonWires());
    comps.sort(
        (a, b) -> {
          final var byX = Integer.compare(a.getLocation().getX(), b.getLocation().getX());
          return byX != 0 ? byX : Integer.compare(a.getLocation().getY(), b.getLocation().getY());
        });
    return comps;
  }

  @Test
  @DisplayName("the sweep reaches chips inside subcircuits, which a selection never could")
  void chipsInSubcircuitsAreReached(@TempDir Path workDir) throws Exception {
    final var file = mixedProject(workDir);
    final var proj = new Project(file);
    final var chips = chipsOf(file);
    assertEquals(2, chips.size(), "the fixture should hold one chip in each circuit");
    assertTrue(chips.containsKey("sub/7404"), "the fixture lost the chip in the subcircuit");

    proj.doAction(
        ProjectWideAttribute.setEverywhere(file, DRAWING, true, StringUtil.constantGetter("show the gates")));

    for (final var entry : chips.entrySet()) {
      assertTrue(
          entry.getValue().getAttributeSet().getValue(DRAWING),
          entry.getKey() + " was not switched to the gate drawing");
    }
  }

  @Test
  @DisplayName("components without the attribute are left alone")
  void componentsWithoutTheAttributeAreUntouched(@TempDir Path workDir) throws Exception {
    final var file = mixedProject(workDir);
    final var proj = new Project(file);
    final var main = file.getCircuit("main");
    final var others =
        sortedByLocation(main).stream()
            .filter(comp -> !comp.getAttributeSet().containsAttribute(DRAWING))
            .toList();
    assertEquals(2, others.size(), "the fixture should hold a gate and a subcircuit instance");
    // Snapshot the values, not the attribute list: getAttributes hands back a live view, so
    // comparing it with itself afterwards would pass however badly the sweep had misbehaved.
    final var before = others.stream().map(ProjectWideAttributeTest::snapshot).toList();

    proj.doAction(
        ProjectWideAttribute.setEverywhere(
            file, DRAWING, true, StringUtil.constantGetter("show the gates")));

    for (var i = 0; i < others.size(); i++) {
      assertFalse(
          others.get(i).getAttributeSet().containsAttribute(DRAWING),
          others.get(i).getFactory().getName() + " grew an attribute it does not own");
      assertEquals(
          before.get(i),
          snapshot(others.get(i)),
          others.get(i).getFactory().getName() + " had one of its own attributes changed");
    }
  }

  /** Every attribute of one component and what it holds, as text, taken by value. */
  private static Map<String, String> snapshot(Component comp) {
    final var attrs = comp.getAttributeSet();
    final var values = new LinkedHashMap<String, String>();
    for (final var attr : attrs.getAttributes()) {
      values.put(attr.getName(), String.valueOf(attrs.getValue(attr)));
    }
    return values;
  }

  @Test
  @DisplayName("the whole project steps back in a single undo")
  void oneUndoRestoresEveryChip(@TempDir Path workDir) throws Exception {
    final var file = mixedProject(workDir);
    final var proj = new Project(file);
    final var chips = chipsOf(file);
    final var before = new LinkedHashMap<String, Boolean>();
    chips.forEach((name, comp) -> before.put(name, comp.getAttributeSet().getValue(DRAWING)));

    proj.doAction(
        ProjectWideAttribute.setEverywhere(file, DRAWING, true, StringUtil.constantGetter("show the gates")));
    proj.doAction(
        ProjectWideAttribute.setEverywhere(file, DRAWING, false, StringUtil.constantGetter("draw the packages")));
    proj.undoAction();

    for (final var entry : chips.entrySet()) {
      assertTrue(
          entry.getValue().getAttributeSet().getValue(DRAWING),
          entry.getKey()
              + " did not come back to the gate drawing, so the sweep took more than one undo");
    }

    proj.undoAction();
    for (final var entry : chips.entrySet()) {
      assertEquals(
          before.get(entry.getKey()),
          entry.getValue().getAttributeSet().getValue(DRAWING),
          entry.getKey() + " did not come back to how the file had it");
    }
    assertNull(proj.getLastAction(), "two sweeps should have left exactly two undo entries");
  }

  @Test
  @DisplayName("flipping the drawing moves neither a chip's outline nor any of its pins")
  void geometryDoesNotDependOnTheDrawing(@TempDir Path workDir) throws Exception {
    final var file = mixedProject(workDir);
    final var proj = new Project(file);
    final var chips = chipsOf(file);
    final var bounds = new LinkedHashMap<String, Object>();
    final var ends = new LinkedHashMap<String, Object>();
    chips.forEach(
        (name, comp) -> {
          bounds.put(name, comp.getBounds());
          ends.put(name, comp.getEnds().stream().map(end -> end.getLocation()).toList());
        });

    proj.doAction(
        ProjectWideAttribute.setEverywhere(file, DRAWING, true, StringUtil.constantGetter("show the gates")));

    for (final var entry : chips.entrySet()) {
      final var comp = entry.getValue();
      assertEquals(
          bounds.get(entry.getKey()),
          comp.getBounds(),
          entry.getKey() + " changed size, which would have moved whatever was drawn beside it");
      assertEquals(
          ends.get(entry.getKey()),
          comp.getEnds().stream().map(end -> end.getLocation()).toList(),
          entry.getKey() + " moved a pin, which would have left the wires on it dangling");
    }
  }

  @Test
  @DisplayName("a sweep that would change nothing produces no undo entry at all")
  void sweepingTwiceProducesNoSecondUndoEntry(@TempDir Path workDir) throws Exception {
    final var file = mixedProject(workDir);
    final var proj = new Project(file);

    final var first = ProjectWideAttribute.setEverywhere(file, DRAWING, true, StringUtil.constantGetter("show"));
    assertNotNull(first, "the fixture starts with the chips drawn as packages");
    proj.doAction(first);

    assertNull(
        ProjectWideAttribute.setEverywhere(file, DRAWING, true, StringUtil.constantGetter("show")),
        "repeating a sweep should not park a no-op entry in the undo history");
  }

  @Test
  @DisplayName("the menu can tell whether the project holds a chip worth sweeping")
  void enablementFollowsWhetherAnyChipIsPresent(@TempDir Path workDir) throws Exception {
    assertTrue(
        ProjectWideAttribute.isCarriedByAnyComponent(mixedProject(workDir), DRAWING),
        "a project with TTL chips reported none");
    assertFalse(
        ProjectWideAttribute.isCarriedByAnyComponent(gatesOnlyProject(workDir), DRAWING),
        "a project of plain gates reported a chip, so the menu would offer a sweep of nothing");
  }

  @Test
  @DisplayName("every locale names the submenu and both commands")
  void everyLocaleNamesTheCommands() throws Exception {
    for (final var locale : LOCALES) {
      final var bundle = BUNDLES.resolve("gui" + locale + ".properties");
      final var props = new Properties();
      try (final var in = Files.newBufferedReader(bundle, StandardCharsets.UTF_8)) {
        props.load(in);
      }
      for (final var key :
          List.of("projectTtlDrawingMenu", "projectTtlShowGatesItem", "projectTtlShowPackageItem")) {
        final var text = props.getProperty(key);
        assertNotNull(text, key + " is missing from " + bundle.getFileName());
        assertFalse(text.isBlank(), key + " is blank in " + bundle.getFileName());
      }
    }
  }
}
