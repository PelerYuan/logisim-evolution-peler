/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.htmlexport;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.std.wiring.Tunnel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Peler Edition. The circuit as the exported HTML page needs to see it: which components exist, what
 * their ports are, and which ports are electrically the same node.
 *
 * <p>Connectivity is derived here rather than borrowed from {@code CircuitWires}, whose bundle map
 * is package-private and carries far more than this needs (widths per thread, incompatibility
 * tracking, splitter fan-out). The rules it reproduces are the ones the editor itself applies:
 * component ports and wire ends that share a {@link Location} are connected, a wire end landing
 * anywhere along another wire joins it, and tunnels with the same label are one node however far
 * apart they sit.
 *
 * <p>Splitters are deliberately not treated as connectivity. A splitter maps bit ranges between one
 * combined end and several split ends, which is component behaviour, not a node -- so it is exported
 * as a component and the runtime does the slicing.
 */
public final class HtmlCircuitModel {

  /** One electrically connected node: what it joins, and the segments to draw for it. */
  public static final class Net {
    public final int id;
    public int width = 1;
    public final List<int[]> segments = new ArrayList<>();
    public final List<int[]> pins = new ArrayList<>(); // {component index, port index}

    Net(int id) {
      this.id = id;
    }
  }

  /** One non-wire component. */
  public static final class Comp {
    public final int id;
    public final String kind;
    public final Map<String, Object> attrs = new LinkedHashMap<>();
    public final List<Integer> ports = new ArrayList<>(); // net id per port, -1 when unconnected
    public final List<String> portDirs = new ArrayList<>();
    public final List<Integer> portWidths = new ArrayList<>();
    public final List<int[]> portLocs = new ArrayList<>();
    public String label = "";

    Comp(int id, String kind) {
      this.id = id;
      this.kind = kind;
    }
  }

  private final List<Net> nets = new ArrayList<>();
  private final List<Comp> comps = new ArrayList<>();
  private final List<Component> sources = new ArrayList<>();
  private final Map<Location, Integer> netOfLocation = new HashMap<>();

  private final Map<Location, Location> parent = new HashMap<>();

  public List<Net> getNets() {
    return nets;
  }

  public List<Comp> getComponents() {
    return comps;
  }

  /** The editor-side component behind each exported one, in the same order, for rendering. */
  public List<Component> getSourceComponents() {
    return sources;
  }

  public static HtmlCircuitModel of(Circuit circuit) {
    final var model = new HtmlCircuitModel();
    model.build(circuit);
    return model;
  }

  private void build(Circuit circuit) {
    final var wires = new ArrayList<>(circuit.getWires());
    final var parts = new ArrayList<>(circuit.getNonWires());

    for (final var wire : wires) {
      union(wire.getEnd0(), wire.getEnd1());
    }
    // A wire end, or a port, sitting anywhere along another wire joins it -- the editor draws a
    // junction dot there, and it conducts.
    final var touchPoints = new ArrayList<Location>();
    for (final var wire : wires) {
      touchPoints.add(wire.getEnd0());
      touchPoints.add(wire.getEnd1());
    }
    for (final var part : parts) {
      for (final var end : part.getEnds()) touchPoints.add(end.getLocation());
    }
    for (final var wire : wires) {
      for (final var point : touchPoints) {
        if (wire.contains(point)) union(point, wire.getEnd0());
      }
    }
    joinTunnels(parts);

    for (final var part : parts) {
      final var comp = new Comp(comps.size(), part.getFactory().getName());
      final var labelAttr = part.getAttributeSet().containsAttribute(StdAttr.LABEL)
          ? part.getAttributeSet().getValue(StdAttr.LABEL) : null;
      comp.label = labelAttr == null ? "" : labelAttr;
      readAttributes(part, comp);

      for (final var end : part.getEnds()) {
        final var loc = end.getLocation();
        final var net = netFor(loc);
        net.width = Math.max(net.width, end.getWidth().getWidth());
        net.pins.add(new int[] {comp.id, comp.ports.size()});
        comp.ports.add(net.id);
        comp.portDirs.add(end.isOutput() ? (end.isInput() ? "inout" : "out") : "in");
        comp.portWidths.add(end.getWidth().getWidth());
        comp.portLocs.add(new int[] {loc.getX(), loc.getY()});
      }
      comps.add(comp);
      sources.add(part);
    }

    for (final var wire : wires) {
      final var net = netFor(wire.getEnd0());
      net.segments.add(
          new int[] {
            wire.getEnd0().getX(), wire.getEnd0().getY(),
            wire.getEnd1().getX(), wire.getEnd1().getY()
          });
      net.width = Math.max(net.width, 1);
    }
  }

  /**
   * Tunnels with the same label are one node. Labels are compared exactly, as the editor does --
   * a leading space makes a different tunnel there too, so trimming here would connect nodes the
   * editor keeps apart.
   */
  private void joinTunnels(List<Component> parts) {
    final var byLabel = new TreeMap<String, Location>();
    for (final var part : parts) {
      if (!(part.getFactory() instanceof Tunnel)) continue;
      final var attrs = part.getAttributeSet();
      final var label = attrs.containsAttribute(StdAttr.LABEL) ? attrs.getValue(StdAttr.LABEL) : "";
      if (label == null || label.isEmpty()) continue;
      if (part.getEnds().isEmpty()) continue;
      final var here = part.getEnds().get(0).getLocation();
      final var first = byLabel.putIfAbsent(label, here);
      if (first != null) union(first, here);
    }
  }

  /** Only what the runtime actually needs to evaluate or draw a component. */
  private void readAttributes(Component part, Comp comp) {
    final var attrs = part.getAttributeSet();
    for (final var attr : attrs.getAttributes()) {
      final var name = attr.getName();
      final var value = attrs.getValue(attr);
      if (value == null) continue;
      switch (name) {
        case "width", "inputs", "size", "negate", "value", "facing", "radix", "tristate",
            "pull", "output", "incoming", "fanout", "bit0", "bit1", "bit2", "bit3", "bit4",
            "bit5", "bit6", "bit7", "appearance", "labelloc" ->
            comp.attrs.put(name, describe(value));
        default -> {
          // Everything else is presentation the SVG already carries, or behaviour this phase
          // does not model. Left out so the exported netlist stays readable.
        }
      }
    }
    if (part.getFactory() instanceof Pin) {
      comp.attrs.put("isInput", !attrs.getValue(Pin.ATTR_TYPE).equals(Pin.OUTPUT));
    }
  }

  private static Object describe(Object value) {
    if (value instanceof Number || value instanceof Boolean) return value;
    if (value instanceof com.cburch.logisim.data.BitWidth bitWidth) return bitWidth.getWidth();
    return value.toString();
  }

  private Net netFor(Location loc) {
    final var root = find(loc);
    final var existing = netOfLocation.get(root);
    if (existing != null) return nets.get(existing);
    final var net = new Net(nets.size());
    nets.add(net);
    netOfLocation.put(root, net.id);
    return net;
  }

  private Location find(Location loc) {
    var root = loc;
    var step = parent.get(root);
    while (step != null && !step.equals(root)) {
      root = step;
      step = parent.get(root);
    }
    // Path compression, so a long wire run does not turn lookups into a walk.
    var walk = loc;
    while (!walk.equals(root)) {
      final var next = parent.getOrDefault(walk, root);
      parent.put(walk, root);
      walk = next;
    }
    return root;
  }

  private void union(Location a, Location b) {
    parent.putIfAbsent(a, a);
    parent.putIfAbsent(b, b);
    final var rootA = find(a);
    final var rootB = find(b);
    if (!rootA.equals(rootB)) parent.put(rootA, rootB);
  }
}
