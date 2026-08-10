/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.htmlexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.LoadFailedException;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Peler Edition. Runs the same circuit through both simulators -- Logisim's own {@code Propagator}
 * and the JavaScript engine that ships inside an exported page -- and fails when they disagree.
 *
 * <p>The HTML export necessarily states the semantics of every supported component twice, in two
 * languages. Nothing makes the second copy follow the first, and a divergence shows up as a page
 * that quietly computes the wrong answer, which is the worst possible failure for this feature.
 * This test is the only thing standing between that and a growing component list, so it is meant to
 * be extended with a circuit for every component the exporter learns.
 *
 * <p>The comparison runs over every combination of the input pins, so keep the fixtures small.
 */
public class HtmlExportDifferentialTest {

  private static final Pattern NETLIST = Pattern.compile("const CIRCUIT = (.*?);\\n", Pattern.DOTALL);

  @TempDir Path workDir;

  @ParameterizedTest
  @ValueSource(strings = {"and2", "mixed"})
  public void testEngineAgreesWithLogisim(String fixture) throws Exception {
    assumeTrue(node() != null, "node is not on PATH; skipping the JavaScript half");

    final var circuitFile = copyFixture(fixture);
    final var project = openProject(circuitFile);
    final var circuit = project.getLogisimFile().getMainCircuit();

    final var inputs = new ArrayList<Instance>();
    final var outputs = new ArrayList<Instance>();
    for (final var component : circuit.getNonWires()) {
      if (!(component.getFactory() instanceof Pin)) continue;
      final var instance = Instance.getInstanceFor(component);
      if (Pin.FACTORY.isInputPin(instance)) inputs.add(instance);
      else outputs.add(instance);
    }
    assertFalse(inputs.isEmpty(), fixture + " has no input pins to drive");
    assertFalse(outputs.isEmpty(), fixture + " has no output pins to compare");
    // Ordering has to match between the two engines; the exporter numbers components in the order
    // getNonWires() returns them, and this walks the same collection.
    inputs.sort((a, b) -> compareLocations(a, b));
    outputs.sort((a, b) -> compareLocations(a, b));

    final var page = workDir.resolve(fixture + ".html").toFile();
    new HtmlExporter(project, circuit).writeTo(page);

    final var fromJava = simulateInJava(project, circuit, inputs, outputs);
    final var fromPage = simulateInJavaScript(page, inputs, outputs);

    assertEquals(fromJava.size(), fromPage.size(), "row count");
    for (var row = 0; row < fromJava.size(); row++) {
      assertEquals(
          fromJava.get(row),
          fromPage.get(row),
          "row " + row + " of " + fixture + ": Logisim and the exported page disagree");
    }
  }

  // ------------------------------------------------------------------ Logisim's own simulator

  private List<String> simulateInJava(
      Project project, com.cburch.logisim.circuit.Circuit circuit,
      List<Instance> inputs, List<Instance> outputs) {
    final var rows = new ArrayList<String>();
    for (var combination = 0; combination < (1 << totalBits(inputs)); combination++) {
      // A fresh root state per row, exactly as TtyInterface's truth-table mode does: it avoids one
      // row's leftover state colouring the next.
      final var state = CircuitState.createRootState(project, circuit, Thread.currentThread());
      final var propagator = state.getPropagator();
      var bit = 0;
      for (final var pin : inputs) {
        final var width = pin.getAttributeValue(StdAttr.WIDTH).getWidth();
        final var bits = new Value[width];
        for (var b = 0; b < width; b++) {
          bits[width - 1 - b] = ((combination >> (bit + b)) & 1) == 1 ? Value.TRUE : Value.FALSE;
        }
        bit += width;
        Pin.FACTORY.driveInputPin(state.getInstanceState(pin), Value.create(bits));
      }
      propagator.propagate();

      final var row = new ArrayList<String>();
      for (final var pin : outputs) {
        if (propagator.isOscillating()) {
          row.add("E");
        } else {
          row.add(render(Pin.FACTORY.getValue(state.getInstanceState(pin))));
        }
      }
      rows.add(String.join(",", row));
    }
    return rows;
  }

  /** Same shape the page's {@code formatValue} produces, so the two are comparable as text. */
  private static String render(Value value) {
    if (value.isErrorValue()) return "E";
    if (!value.isFullyDefined()) return "x";
    final var width = value.getWidth();
    final var number = value.toLongValue();
    return width <= 4 ? Long.toString(number) : Long.toHexString(number).toUpperCase();
  }

  private static int totalBits(List<Instance> inputs) {
    var total = 0;
    for (final var pin : inputs) total += pin.getAttributeValue(StdAttr.WIDTH).getWidth();
    return total;
  }

  private static int compareLocations(Instance a, Instance b) {
    final var la = a.getLocation();
    final var lb = b.getLocation();
    return la.getY() != lb.getY() ? la.getY() - lb.getY() : la.getX() - lb.getX();
  }

  // ------------------------------------------------------------------ the exported page's engine

  private List<String> simulateInJavaScript(File page, List<Instance> inputs, List<Instance> outputs)
      throws IOException, InterruptedException {
    final var html = Files.readString(page.toPath(), StandardCharsets.UTF_8);
    final var script = html.substring(html.indexOf("<script>") + 8, html.indexOf("</script>"));
    // The template marks where its DOM-free half ends. Anchoring on a sentinel rather than on a
    // section heading means the page can be re-commented without silently truncating the engine.
    final var end = script.indexOf("// __SIMULATION_END__");
    assertTrue(end > 0, "the exported page has lost its __SIMULATION_END__ marker");
    final var simulationOnly = script.substring(0, end);

    final var harness = """
        %s

        const inputs = CIRCUIT.components
          .filter((c) => c.kind === "Pin" && c.a_isInput)
          .sort((a, b) => a.portLocs[0][1] - b.portLocs[0][1] || a.portLocs[0][0] - b.portLocs[0][0]);
        const outputs = CIRCUIT.components
          .filter((c) => c.kind === "Pin" && !c.a_isInput)
          .sort((a, b) => a.portLocs[0][1] - b.portLocs[0][1] || a.portLocs[0][0] - b.portLocs[0][0]);
        const widths = inputs.map((c) => c.portWidths[0] || 1);
        const total = widths.reduce((a, b) => a + b, 0);
        const sim = new Simulation(CIRCUIT);
        const rows = [];
        for (let combination = 0; combination < (1 << total); combination++) {
          let bit = 0;
          inputs.forEach((pin, i) => {
            const width = widths[i];
            const bits = new Uint8Array(width);
            for (let b = 0; b < width; b++) bits[b] = (combination >> (bit + b)) & 1;
            bit += width;
            sim.inputs.set(pin.id, bits);
          });
          sim.run();
          rows.push(outputs.map((pin) =>
            sim.oscillating ? "E" : formatValue(sim.valueOfPort(pin, 0))).join(","));
        }
        console.log(rows.join("\\n"));
        """.formatted(simulationOnly);

    final var harnessFile = workDir.resolve("harness.cjs");
    Files.writeString(harnessFile, harness, StandardCharsets.UTF_8);

    final var process = new ProcessBuilder(node(), harnessFile.toString())
        .redirectErrorStream(true)
        .start();
    final var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "node did not finish");
    assertEquals(0, process.exitValue(), "node failed:\n" + output);
    return List.of(output.strip().split("\n"));
  }

  private static String node() {
    for (final var candidate : System.getenv("PATH").split(File.pathSeparator)) {
      final var file = new File(candidate, "node");
      if (file.canExecute()) return file.getAbsolutePath();
    }
    return null;
  }

  // ------------------------------------------------------------------ fixtures

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
    final var file = loader.openLogisimFile(circuitFile);
    return new Project(file);
  }
}
