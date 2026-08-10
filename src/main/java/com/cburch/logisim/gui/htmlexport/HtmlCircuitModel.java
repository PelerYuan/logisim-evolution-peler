/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.htmlexport;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitAttributes;
import com.cburch.logisim.circuit.SplitterFactory;
import com.cburch.logisim.circuit.SubcircuitFactory;
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
 * <p>Splitters are connectivity too, but one bit at a time. A splitter joins individual bits of two
 * different nodes rather than the nodes themselves, so identity has to live on the bit -- see
 * {@link Net#threads}. Treating a splitter as a component that drives its ports does not work: it is
 * passive and bidirectional, so such a component has to re-drive whatever it read on the previous
 * round, and a driver that then changes value collides with that stale echo.
 *
 * <p>Subcircuits are flattened. The box keeps being drawn, but its contents are pulled into this one
 * netlist as components nobody draws, and each of the box's ports is made the same node as the pin
 * behind it. That is why nodes are named by scope as well as by location: two circuits are free to
 * use the same coordinates and the same tunnel names, and inside a subcircuit they mean different
 * wires.
 */
public final class HtmlCircuitModel {

  /**
   * One electrically connected node: what it joins, and the segments to draw for it.
   *
   * <p>{@link #threads} carries the identity of each bit. Two nets joined by a splitter are
   * different nodes that nonetheless share individual bits, so a value cannot live on the net --
   * it lives on the thread, exactly as {@code WireBundle} does it in the editor.
   */
  public static final class Net {
    public final int id;
    public int width = 1;
    public final List<int[]> segments = new ArrayList<>();
    public final List<int[]> pins = new ArrayList<>(); // {component index, port index}
    public int[] threads = new int[0];

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
    public int[] loc = new int[] {0, 0};
    public String label = "";
    /** Inside a subcircuit: it simulates, but the page shows the box instead of its contents. */
    public boolean hidden;
    /** The subcircuit box itself. It carries values through and drives nothing of its own. */
    public boolean passive;

    Comp(int id, String kind) {
      this.id = id;
      this.kind = kind;
    }
  }

  /** A node is a location in one particular circuit instance, not just a location. */
  private record Node(int scope, Location loc) {}

  /** One instance of one circuit. The root is drawn; everything reached through a box is not. */
  private record Scope(Circuit circuit, int id, boolean drawn) {}

  private final List<Net> nets = new ArrayList<>();
  private final List<Comp> comps = new ArrayList<>();
  private final List<Component> sources = new ArrayList<>();
  private final List<Scope> scopes = new ArrayList<>();
  private final Map<Node, Integer> netOfNode = new HashMap<>();
  private final Map<Node, Node> parent = new HashMap<>();
  private int threadCount;

  /** How many distinct bits the circuit has, once splitters have merged what they merge. */
  public int getThreadCount() {
    return threadCount;
  }

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

  /**
   * Connectivity has to be settled for the whole design, subcircuits included, before any node is
   * turned into a net: two nodes that a later merge would have joined would otherwise already be
   * two separate nets with no way back.
   */
  private void build(Circuit root) {
    connect(addScope(root, true));
    for (final var scope : scopes) materialise(scope);
    assignThreads();
  }

  private Scope addScope(Circuit circuit, boolean drawn) {
    final var scope = new Scope(circuit, scopes.size(), drawn);
    scopes.add(scope);
    return scope;
  }

  private void connect(Scope scope) {
    final var wires = new ArrayList<>(scope.circuit().getWires());
    final var parts = new ArrayList<>(scope.circuit().getNonWires());

    for (final var wire : wires) {
      union(node(scope, wire.getEnd0()), node(scope, wire.getEnd1()));
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
        if (wire.contains(point)) union(node(scope, point), node(scope, wire.getEnd0()));
      }
    }
    joinTunnels(scope, parts);

    for (final var part : parts) {
      if (!(part.getFactory() instanceof SubcircuitFactory factory)) continue;
      final var child = addScope(factory.getSubcircuit(), false);
      connect(child);
      // The box's ports and the pins behind them are the same wire. getPinInstances is the mapping
      // the editor itself built when it worked out where the ports go, so the two orders agree by
      // construction rather than by both sorting the pins the same way.
      final var pins = ((CircuitAttributes) part.getAttributeSet()).getPinInstances();
      final var ends = part.getEnds();
      if (pins == null) continue;
      for (var i = 0; i < ends.size() && i < pins.length; i++) {
        if (pins[i] == null) continue;
        union(node(scope, ends.get(i).getLocation()), node(child, pins[i].getLocation()));
      }
    }
  }

  private void materialise(Scope scope) {
    for (final var part : scope.circuit().getNonWires()) {
      // A pin inside a subcircuit is not a component of the flattened design: it is the point where
      // the wire crosses the boundary, and that crossing is already a single node.
      if (!scope.drawn() && part.getFactory() instanceof Pin) continue;

      final var comp = new Comp(comps.size(), part.getFactory().getName());
      final var attrs = part.getAttributeSet();
      final var labelAttr =
          attrs.containsAttribute(StdAttr.LABEL) ? attrs.getValue(StdAttr.LABEL) : null;
      comp.label = labelAttr == null ? "" : labelAttr;
      comp.loc = new int[] {part.getLocation().getX(), part.getLocation().getY()};
      comp.hidden = !scope.drawn();
      comp.passive = part.getFactory() instanceof SubcircuitFactory;
      readAttributes(part, comp);

      for (final var end : part.getEnds()) {
        final var loc = end.getLocation();
        final var net = netFor(node(scope, loc));
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

    for (final var wire : scope.circuit().getWires()) {
      final var net = netFor(node(scope, wire.getEnd0()));
      net.width = Math.max(net.width, 1);
      // Wires inside a subcircuit conduct but are never seen, so they contribute no segment.
      if (!scope.drawn()) continue;
      net.segments.add(
          new int[] {
            wire.getEnd0().getX(), wire.getEnd0().getY(),
            wire.getEnd1().getX(), wire.getEnd1().getY()
          });
    }
  }

  /**
   * Gives every bit of every net an identity, then makes splitters merge those identities.
   *
   * <p>A splitter cannot be modelled as a component that drives its ports. It is passive and
   * bidirectional, so an engine that treats it as a driver has to echo back whatever it read a
   * moment ago -- and then a driver changing value collides with the splitter's own stale echo and
   * the wire turns red for a round. Merging bits instead makes the two sides literally the same
   * wire, which is what they are.
   */
  private void assignThreads() {
    var next = 0;
    for (final var net : nets) {
      net.threads = new int[net.width];
      for (var bit = 0; bit < net.width; bit++) net.threads[bit] = next++;
    }
    final var threadUnion = new int[next];
    for (var i = 0; i < next; i++) threadUnion[i] = i;

    for (final var scope : scopes) {
      for (final var part : scope.circuit().getNonWires()) {
        if (!(part.getFactory() instanceof SplitterFactory)) continue;
        mergeSplitterBits(scope, part, threadUnion);
      }
    }

    // Compact, so the runtime can size one array by the thread count.
    final var renumbered = new HashMap<Integer, Integer>();
    for (final var net : nets) {
      for (var bit = 0; bit < net.threads.length; bit++) {
        final var root = findThread(threadUnion, net.threads[bit]);
        net.threads[bit] = renumbered.computeIfAbsent(root, key -> renumbered.size());
      }
    }
    threadCount = renumbered.size();
  }

  private void mergeSplitterBits(Scope scope, Component part, int[] threadUnion) {
    final var attrs = part.getAttributeSet();
    final var ends = part.getEnds();
    if (ends.isEmpty()) return;
    final var combined = netFor(node(scope, ends.get(0).getLocation()));
    final var incoming = ends.get(0).getWidth().getWidth();

    // bitN says which end bit N leaves by: 0 is "nowhere", otherwise it is the end's own index,
    // ends 1..fanout being the split side. Bits reaching the same end keep their relative order.
    final var usedPerEnd = new HashMap<Integer, Integer>();
    for (var bit = 0; bit < incoming; bit++) {
      final var attribute = attrs.getAttribute("bit" + bit);
      if (attribute == null) continue;
      final var raw = attrs.getValue(attribute);
      if (!(raw instanceof Integer endIndex) || endIndex <= 0 || endIndex >= ends.size()) continue;
      final var splitNet = netFor(node(scope, ends.get(endIndex).getLocation()));
      final var position = usedPerEnd.merge(endIndex, 1, Integer::sum) - 1;
      if (bit >= combined.threads.length || position >= splitNet.threads.length) continue;
      unionThread(threadUnion, combined.threads[bit], splitNet.threads[position]);
    }
  }

  private static int findThread(int[] union, int value) {
    var root = value;
    while (union[root] != root) root = union[root];
    var walk = value;
    while (union[walk] != root) {
      final var next = union[walk];
      union[walk] = root;
      walk = next;
    }
    return root;
  }

  private static void unionThread(int[] union, int a, int b) {
    final var rootA = findThread(union, a);
    final var rootB = findThread(union, b);
    if (rootA != rootB) union[rootA] = rootB;
  }

  /**
   * Tunnels with the same label are one node, within one circuit. Labels are compared exactly, as
   * the editor does -- a leading space makes a different tunnel there too, so trimming here would
   * connect nodes the editor keeps apart. A tunnel inside a subcircuit reaches only that circuit,
   * which is why this runs per scope.
   */
  private void joinTunnels(Scope scope, List<Component> parts) {
    final var byLabel = new TreeMap<String, Location>();
    for (final var part : parts) {
      if (!(part.getFactory() instanceof Tunnel)) continue;
      final var attrs = part.getAttributeSet();
      final var label = attrs.containsAttribute(StdAttr.LABEL) ? attrs.getValue(StdAttr.LABEL) : "";
      if (label == null || label.isEmpty()) continue;
      if (part.getEnds().isEmpty()) continue;
      final var here = part.getEnds().get(0).getLocation();
      final var first = byLabel.putIfAbsent(label, here);
      if (first != null) union(node(scope, first), node(scope, here));
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
            "pull", "output", "incoming", "fanout", "in_width", "out_width", "type",
            "appearance", "labelloc", "trigger", "highDuration", "lowDuration", "phaseOffset",
            "press", "number", "select", "enable", "disabled", "mode", "shift", "max",
            "ongoal" ->
            comp.attrs.put(name, describe(value));
        default -> {
          // A splitter carries one bitN per incoming bit, up to 64 of them, so they are matched
          // by shape. Anything else is presentation the SVG already carries, or behaviour this
          // phase does not model, and is left out so the netlist stays readable.
          if (name.startsWith("bit") && name.length() > 3
              && Character.isDigit(name.charAt(3))) {
            comp.attrs.put(name, describe(value));
          }
        }
      }
    }
    if (part.getFactory() instanceof Pin) {
      comp.attrs.put("isInput", !attrs.getValue(Pin.ATTR_TYPE).equals(Pin.OUTPUT));
    }
  }

  private static Object describe(Object value) {
    // A counter's limit is a Long read as unsigned, and its all-ones value is -1 as a signed long.
    // It travels as text so the page can widen it to a BigInt without having lost the sign first.
    if (value instanceof Long number) return Long.toUnsignedString(number);
    if (value instanceof Number || value instanceof Boolean) return value;
    if (value instanceof com.cburch.logisim.data.BitWidth bitWidth) return bitWidth.getWidth();
    return value.toString();
  }

  private static Node node(Scope scope, Location loc) {
    return new Node(scope.id(), loc);
  }

  private Net netFor(Node at) {
    final var root = find(at);
    final var existing = netOfNode.get(root);
    if (existing != null) return nets.get(existing);
    final var net = new Net(nets.size());
    nets.add(net);
    netOfNode.put(root, net.id);
    return net;
  }

  private Node find(Node at) {
    var root = at;
    var step = parent.get(root);
    while (step != null && !step.equals(root)) {
      root = step;
      step = parent.get(root);
    }
    // Path compression, so a long wire run does not turn lookups into a walk.
    var walk = at;
    while (!walk.equals(root)) {
      final var next = parent.getOrDefault(walk, root);
      parent.put(walk, root);
      walk = next;
    }
    return root;
  }

  private void union(Node a, Node b) {
    parent.putIfAbsent(a, a);
    parent.putIfAbsent(b, b);
    final var rootA = find(a);
    final var rootB = find(b);
    if (!rootA.equals(rootB)) parent.put(rootA, rootB);
  }
}
