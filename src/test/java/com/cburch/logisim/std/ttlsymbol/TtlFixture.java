/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.ttlsymbol;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.file.LoadFailedException;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Peler Edition. One 74xx chip in a project of its own, with a pin sitting on each of its ports and
 * everything addressed by port index.
 *
 * <p>Nothing here is wired by hand. The port locations are asked of the factory and a pin is
 * dropped on each -- in Logisim two ports at one location are one node -- so the same code builds a
 * fixture for the DIP package and for the logic symbol even though their pins are nowhere near each
 * other. Position cannot be the correspondence between the two, and the port index is; the index is
 * carried through the file in each pin's label, which is the only thing about a pin that survives
 * the round trip.
 */
final class TtlFixture {

  private final Project project;
  private final CircuitState state;
  private final TreeMap<Integer, Instance> driven;
  private final TreeMap<Integer, Instance> probed;
  private final List<Integer> inputs = new ArrayList<>();
  private final List<Integer> outputs = new ArrayList<>();

  private TtlFixture(Project project, Circuit circuit) {
    this.project = project;
    this.driven = pinsLabelled(circuit, 'p');
    this.probed = pinsLabelled(circuit, 'q');
    this.state = CircuitState.createRootState(project, circuit, Thread.currentThread());
    for (final var entry : driven.entrySet()) {
      if (Pin.FACTORY.isInputPin(entry.getValue())) inputs.add(entry.getKey());
      else probed.put(entry.getKey(), entry.getValue());
    }
    outputs.addAll(probed.keySet());
  }

  /** Builds and opens a fixture for one chip, writing the project under {@code workDir}. */
  static TtlFixture open(ComponentFactory factory, String libraryId, Path workDir)
      throws IOException, LoadFailedException {
    final var anchor = Location.create(400, 400, true);
    final var probe = factory.createComponent(anchor, factory.createAttributeSet());

    final var xml = new StringBuilder();
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        .append("<project version=\"1.0\">\n")
        .append(" <lib name=\"0\" desc=\"#Wiring\" />\n")
        .append(" <lib name=\"1\" desc=\"#").append(libraryId).append("\" />\n")
        .append(" <lib name=\"8\" desc=\"#Base\" />\n")
        .append(" <main name=\"main\" />\n")
        .append(" <circuit name=\"main\">\n")
        .append("  <a name=\"circuit\" val=\"main\" />\n")
        .append("  <comp lib=\"1\" loc=\"").append(anchor).append("\" name=\"")
        .append(factory.getName()).append("\" />\n");

    final var ends = probe.getEnds();
    for (var i = 0; i < ends.size(); i++) {
      final var end = ends.get(i);
      final var bidirectional = end.isInput() && end.isOutput();
      pin(xml, end.getLocation().toString(), "p" + i, end.isOutput() && !bidirectional);
      if (!bidirectional) continue;
      // A bidirectional port needs two pins: one to drive the node and one to watch it, since a
      // Logisim pin does only one of the two. The 74245 transceiver is the whole reason -- without
      // this it has two pins that can be driven and sixteen that read as nothing at all. The
      // watching pin cannot share the port's spot, because two pins there are one component twice
      // over and the file will not load, so it sits one step outside the chip on a wire.
      final var outside = justOutside(end.getLocation(), probe.getBounds());
      pin(xml, outside.toString(), "q" + i, true);
      xml.append("  <wire from=\"").append(end.getLocation())
          .append("\" to=\"").append(outside).append("\" />\n");
    }
    xml.append(" </circuit>\n</project>\n");

    final var target = workDir.resolve(factory.getName() + "-" + libraryId.hashCode() + ".circ");
    Files.writeString(target, xml.toString(), StandardCharsets.UTF_8);

    final var loader = new Loader(null);
    final var opened = new Project(loader.openLogisimFile(target.toFile()));
    return new TtlFixture(opened, opened.getLogisimFile().getMainCircuit());
  }

  List<Integer> inputPorts() {
    return inputs;
  }

  List<Integer> outputPorts() {
    return outputs;
  }

  /**
   * Sets an input pin and marks it changed.
   *
   * <p>The second half matters and is easy to miss. {@code driveInputPin} only stores the value on
   * the pin's own state; it is the pokers in the editor that follow it with {@code
   * fireInvalidated}, and without that the propagator has no dirty point to start from. A test that
   * leaves it out still passes its first propagation -- everything is dirty when a circuit is first
   * built -- and then quietly stops responding to its own inputs, which is exactly the kind of
   * sweep that reports agreement it never actually measured.
   */
  void drive(int portIndex, Value value) {
    final var pin = driven.get(portIndex);
    Pin.FACTORY.driveInputPin(state.getInstanceState(pin), value);
    pin.fireInvalidated();
  }

  void settle() {
    state.getPropagator().propagate();
  }

  boolean oscillating() {
    return state.getPropagator().isOscillating();
  }

  Value read(int portIndex) {
    return Pin.FACTORY.getValue(state.getInstanceState(probed.get(portIndex)));
  }

  /** Every output port after one settle, as a comma-separated row. */
  String outputRow() {
    final var row = new ArrayList<String>();
    for (final var index : outputs) row.add(oscillating() ? "E" : read(index).toString());
    return String.join(",", row);
  }

  /** One grid step off the edge of the chip the port sits on, so a wire can reach it. */
  private static Location justOutside(Location port, com.cburch.logisim.data.Bounds bounds) {
    if (port.getX() == bounds.getX()) return port.translate(-10, 0);
    if (port.getX() == bounds.getX() + bounds.getWidth()) return port.translate(10, 0);
    if (port.getY() == bounds.getY()) return port.translate(0, -10);
    return port.translate(0, 10);
  }

  private static void pin(StringBuilder xml, String location, String label, boolean readOnly) {
    xml.append("  <comp lib=\"0\" loc=\"").append(location).append("\" name=\"Pin\">\n")
        .append("   <a name=\"label\" val=\"").append(label).append("\" />\n");
    if (readOnly) xml.append("   <a name=\"output\" val=\"true\" />\n");
    xml.append("  </comp>\n");
  }

  /**
   * The fixture's pins of one kind, keyed by the port index they sit on. The index is carried in
   * the label because nothing else about a pin survives the round trip through the file.
   */
  private static TreeMap<Integer, Instance> pinsLabelled(Circuit circuit, char kind) {
    final var found = new TreeMap<Integer, Instance>();
    for (final var component : circuit.getNonWires()) {
      if (!(component.getFactory() instanceof Pin)) continue;
      final var instance = Instance.getInstanceFor(component);
      final var label = instance.getAttributeValue(StdAttr.LABEL);
      if (label.charAt(0) != kind) continue;
      found.put(Integer.parseInt(label.substring(1)), instance);
    }
    return found;
  }

  Project getProject() {
    return project;
  }
}
