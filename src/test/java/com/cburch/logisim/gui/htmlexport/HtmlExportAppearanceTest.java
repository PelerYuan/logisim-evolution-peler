/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.htmlexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.file.LoadFailedException;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Peler Edition. Checks that what an exported page will draw for a component is what Logisim draws
 * for it, in every state the component has.
 *
 * <p>The page does not redraw a display; it applies a stored difference to a stored picture. That
 * is a compression scheme, and a compression scheme that is subtly lossy fails in the worst way
 * available here -- the page shows a digit, the digit is wrong, and nothing anywhere complains. So
 * every state is unpacked and compared against a fresh render from Logisim's own painter.
 *
 * <p>The chosen encoding is asserted as well. A component quietly falling back from per-bit
 * patches to whole pictures stays correct but multiplies the page's size, and there is no other
 * signal that it happened.
 */
public class HtmlExportAppearanceTest {

  /** What each component should cost, so a regression to a bulkier encoding is visible. */
  private static final Map<String, HtmlAppearance.Mode> EXPECTED_MODES = Map.of(
      "LED", HtmlAppearance.Mode.BITS,
      "7-Segment Display", HtmlAppearance.Mode.BITS,
      "Hex Digit Display", HtmlAppearance.Mode.TABLE,
      "DipSwitch", HtmlAppearance.Mode.BITS,
      // A released button is drawn as a raised polygon and a pressed one as a plain rectangle, so
      // there is no shared shape to describe a difference against.
      "Button", HtmlAppearance.Mode.FULL);

  @TempDir Path workDir;

  @ParameterizedTest
  @ValueSource(strings = {"display", "poke"})
  public void testEveryStateMatchesLogisim(String fixture) throws Exception {
    final var project = openProject(copyFixture(fixture));
    final var circuit = project.getLogisimFile().getMainCircuit();
    final var prepared = new HtmlExporter(project, circuit).prepare();

    var animated = 0;
    for (final var component : prepared) {
      final var appearance = component.appearance();
      if (appearance == null) continue;
      animated++;
      final var kind = component.component().getFactory().getName();
      final var expectedMode = EXPECTED_MODES.get(kind);
      if (expectedMode != null) {
        assertEquals(expectedMode, appearance.mode, kind + " changed how its states are packed");
      }
      checkEveryState(kind, component, appearance);
    }
    assertTrue(animated > 0, fixture + " exported nothing that changes with its value");
  }

  private void checkEveryState(
      String kind, HtmlExporter.Prepared component, HtmlAppearance appearance) {
    final var base = component.renderer().render(0);
    assertNotNull(base, kind + " has no base picture");
    final var states = 1L << appearance.bits;
    for (var state = 0L; state < states; state++) {
      final var actual = component.renderer().render(state);
      assertNotNull(actual, kind + " would not render state " + state);

      if (appearance.mode == HtmlAppearance.Mode.FULL) {
        assertEquals(
            actual.svg(),
            appearance.fragments.get((int) state),
            kind + " state " + state + ": the stored picture is not the one Logisim draws");
        continue;
      }

      final var wanted = HtmlAppearance.diff(base.elements(), actual.elements());
      assertNotNull(wanted, kind + " state " + state + " changed shape, which no patch can encode");
      assertEquals(
          asMap(wanted),
          predict(appearance, state),
          kind + " state " + state + ": the stored patch does not rebuild Logisim's picture");
    }
  }

  /** What the page would end up applying for this state, in the same form as a real difference. */
  private static Map<String, String> predict(HtmlAppearance appearance, long state) {
    if (appearance.mode == HtmlAppearance.Mode.TABLE) {
      return asMap(appearance.patches.get((int) state));
    }
    final var out = new LinkedHashMap<String, String>();
    for (var bit = 0; bit < appearance.bits; bit++) {
      if (((state >> bit) & 1) == 0) continue;
      for (final var change : appearance.patches.get(bit)) {
        out.put(change.element() + " " + change.attribute(), change.value());
      }
    }
    return out;
  }

  private static Map<String, String> asMap(List<HtmlAppearance.Change> changes) {
    final var out = new LinkedHashMap<String, String>();
    for (final var change : changes) {
      out.put(change.element() + " " + change.attribute(), change.value());
    }
    return out;
  }

  /**
   * A component with memory must not get an appearance keyed on its inputs.
   *
   * <p>A flip-flop's indicator shows the bit it latched. Encoding that against the value on D
   * produces a page that draws a confident, wrong picture, and nothing else would notice. The
   * exporter refuses, and this is the only thing that says so out loud.
   */
  @Test
  public void testComponentsWithMemoryStayStill() throws Exception {
    final var project = openProject(copyFixture("shift"));
    final var circuit = project.getLogisimFile().getMainCircuit();
    var flipFlops = 0;
    for (final var component : new HtmlExporter(project, circuit).prepare()) {
      if (!"D Flip-Flop".equals(component.component().getFactory().getName())) continue;
      flipFlops++;
      assertNull(
          component.appearance(),
          "a flip-flop was given an appearance that follows its inputs rather than its contents");
    }
    assertEquals(2, flipFlops, "the shift fixture stopped holding two flip-flops");
  }

  /**
   * The fixture's own wiring, asserted rather than assumed.
   *
   * <p>A wire that misses a port by ten leaves the circuit unconnected without any complaint from
   * the loader, and the export of a disconnected circuit looks perfectly plausible. This pins down
   * which pin drives which port so an edit that moves a component has to notice.
   */
  @Test
  public void testDisplayFixtureIsWiredAsDescribed() throws Exception {
    final var project = openProject(copyFixture("display"));
    final var model = HtmlCircuitModel.of(project.getLogisimFile().getMainCircuit());
    assertEquals("A", driverOf(model, "LED", 0));
    assertEquals("H", driverOf(model, "Hex Digit Display", 0));
    assertEquals("S1", driverOf(model, "7-Segment Display", 0));
    assertEquals("S2", driverOf(model, "7-Segment Display", 1));
    assertEquals("S0", driverOf(model, "7-Segment Display", 5));
  }

  @Test
  public void testPokeFixtureIsWiredAsDescribed() throws Exception {
    final var project = openProject(copyFixture("poke"));
    final var model = HtmlCircuitModel.of(project.getLogisimFile().getMainCircuit());
    assertEquals("B", readerOf(model, "Button", 0));
    assertEquals("S1", readerOf(model, "DipSwitch", 0));
    assertEquals("S2", readerOf(model, "DipSwitch", 1));
  }

  private static String driverOf(HtmlCircuitModel model, String kind, int port) {
    return pinOnSameNet(model, kind, port);
  }

  private static String readerOf(HtmlCircuitModel model, String kind, int port) {
    return pinOnSameNet(model, kind, port);
  }

  /** The label of the pin sharing a node with the given port, or "" when nothing does. */
  private static String pinOnSameNet(HtmlCircuitModel model, String kind, int port) {
    for (final var comp : model.getComponents()) {
      if (!comp.kind.equals(kind)) continue;
      final var net = comp.ports.get(port);
      for (final var pin : model.getNets().get(net).pins) {
        final var other = model.getComponents().get(pin[0]);
        if (other.kind.equals(Pin._ID)) return other.label;
      }
      return "";
    }
    return "";
  }

  private File copyFixture(String name) throws IOException {
    try (final var in = getClass().getResourceAsStream("/htmlexport/" + name + ".circ")) {
      assertTrue(in != null, "missing fixture " + name + ".circ");
      final var target = workDir.resolve(name + ".circ");
      Files.write(target, in.readAllBytes());
      return target.toFile();
    }
  }

  private static Project openProject(File circuitFile) throws LoadFailedException {
    final var loader = new Loader(null);
    return new Project(loader.openLogisimFile(circuitFile));
  }
}
