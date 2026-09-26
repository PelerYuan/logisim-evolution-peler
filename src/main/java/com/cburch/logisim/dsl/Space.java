/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.dsl.internal.NetHandle;
import com.cburch.logisim.dsl.internal.PendingNetlist;
import com.cburch.logisim.dsl.internal.Router;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.util.StringUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The façade over one circuit (design doc, section 五). Placement is immediate -- every {@link
 * Comp} returned already has real, live ports (design doc, 3.11) -- but nothing reaches the actual
 * {@link Circuit} until {@link #commit(String)}, which submits everything placed and connected
 * since the last commit as exactly one undo-log entry (design doc, section 六).
 *
 * <p>Reading an already-populated, hand-drawn circuit's existing components is P4's job (design
 * doc, section 十一); this first cut only tracks what the DSL itself places in this session.
 */
public final class Space {
  private final Project proj;
  private final Circuit circuit;
  private final List<Comp> pending = new ArrayList<>();
  private final PendingNetlist netlist = new PendingNetlist();
  private final Router router = new Router();
  private final Map<String, Integer> idCounters = new HashMap<>();
  private final List<int[]> pendingWires = new ArrayList<>();
  private int autoLayoutCol;

  private Space(Project proj, Circuit circuit) {
    this.proj = proj;
    this.circuit = circuit;
  }

  public static Space of(Project proj) {
    return new Space(proj, proj.getCurrentCircuit());
  }

  public static Space of(Project proj, String circuitName) {
    final var circuit = proj.getLogisimFile().getCircuit(circuitName);
    if (circuit == null) {
      throw new IllegalArgumentException("no circuit named \"" + circuitName + "\" in this project");
    }
    return new Space(proj, circuit);
  }

  public String circuitName() {
    return circuit.getName();
  }

  public Summary summary() {
    final var counts = new HashMap<String, Integer>();
    var inputs = 0;
    var outputs = 0;
    for (final var c : pending) {
      counts.merge(c.kind().key(), 1, Integer::sum);
      for (final var p : c.ports()) {
        if (p.dir() == Port.Dir.IN) inputs++;
        if (p.dir() == Port.Dir.OUT) outputs++;
      }
    }
    return new Summary(Map.copyOf(counts), inputs, outputs);
  }

  public List<Comp> components() {
    return List.copyOf(pending);
  }

  public List<Comp> componentsOf(Kind kind) {
    return pending.stream().filter(c -> c.kind().key().equals(kind.key())).collect(Collectors.toList());
  }

  public Optional<Comp> byLabel(String label) {
    return pending.stream().filter(c -> c.label().equals(Optional.of(label))).findFirst();
  }

  /** Looks up a component by the stable id it was given at placement (design doc, 13.1): the
   * correct way to reach a component from a later, possibly-fresh script session, since a Lua local
   * variable is not something a dropped session can recover. */
  public Optional<Comp> byId(String id) {
    return pending.stream().filter(c -> c.id().equals(id)).findFirst();
  }

  public List<Comp> near(Comp c, int cells) {
    final var origin = c.origin();
    return pending.stream()
        .filter(other -> other != c)
        .filter(other -> Math.abs(other.origin().rawX() - origin.rawX()) <= cells * 10
            && Math.abs(other.origin().rawY() - origin.rawY()) <= cells * 10)
        .collect(Collectors.toList());
  }

  public List<Net> nets() {
    return netlist.allNets().stream().map(Net::new).collect(Collectors.toList());
  }

  public CheckReport check() {
    final var unconnected = new ArrayList<Port>();
    for (final var c : pending) {
      for (final var p : c.ports()) {
        if (p.net().isEmpty()) unconnected.add(p);
      }
    }
    final var undriven = new ArrayList<Net>();
    final var multiplyDriven = new ArrayList<Net>();
    for (final var handle : netlist.allNets()) {
      final var drivers = handle.drivers().size();
      if (drivers == 0) undriven.add(new Net(handle));
      if (drivers > 1) multiplyDriven.add(new Net(handle));
    }
    return new CheckReport(unconnected, undriven, multiplyDriven, List.of());
  }

  public Placement place(Kind kind) {
    return new Placement(this, kind);
  }

  public Net connect(Port a, Port b) {
    return new Net(netlist.connect(a, b));
  }

  public Net connect(Port p, Net existing) {
    final var anyMember = existing.ports().get(0);
    final var handle = netlist.connect(p, anyMember);
    return new Net(handle);
  }

  public void remove(Comp c) {
    pending.remove(c);
    for (final var p : c.ports()) {
      p.net().ifPresent(n -> netlist.disconnect(n.handle()));
    }
  }

  public void disconnect(Net n) {
    netlist.disconnect(n.handle());
  }

  public WireOps wires() {
    return new WireOpsImpl();
  }

  public boolean isDirty() {
    return !pending.isEmpty();
  }

  public void rollback() {
    pending.clear();
    idCounters.clear();
    pendingWires.clear();
  }

  /** Checks -> routes -> submits exactly one {@link CircuitMutation} as exactly one undo-log entry
   * (design doc, section 六). Only a routing failure blocks the commit; an incomplete circuit
   * ({@link #check()} not {@code ok()}) is left to the caller to decide about. */
  public CommitResult commit(String actionName) {
    final var nets = netlist.allNets();
    final var allPorts = new ArrayList<Port>();
    for (final var c : pending) allPorts.addAll(c.ports());

    final var mutation = new CircuitMutation(circuit);
    final var components = new ArrayList<Component>();
    for (final var c : pending) components.add(c.rawComponent());
    mutation.addAll(components);

    final var wires = new ArrayList<Wire>();
    final var routed = new HashMap<NetHandle, List<int[]>>();
    for (final var net : nets) {
      final var segments = router.route(net, allPorts);
      final var dots = new ArrayList<int[]>();
      for (final var seg : segments) {
        wires.add(Wire.create(
            Location.create(seg.x0(), seg.y0(), false), Location.create(seg.x1(), seg.y1(), false)));
        dots.add(new int[] {seg.x0(), seg.y0(), seg.x1(), seg.y1()});
      }
      routed.put(net, dots);
    }
    for (final var seg : pendingWires) {
      wires.add(Wire.create(
          Location.create(seg[0], seg[1], false), Location.create(seg[2], seg[3], false)));
    }
    mutation.addAll(wires);

    final var action = mutation.toAction(StringUtil.constantGetter(actionName));
    proj.doAction(action);

    for (final var entry : routed.entrySet()) entry.getKey().markCommitted(entry.getValue());
    final var placed = List.copyOf(pending);
    final var resultNets = nets.stream().map(Net::new).collect(Collectors.toList());
    pending.clear();
    pendingWires.clear();
    return new CommitResult(action, placed, resultNets);
  }

  Project project() {
    return proj;
  }

  Circuit circuit() {
    return circuit;
  }

  List<Comp> pendingComponents() {
    return pending;
  }

  Optional<Net> netOf(Port p) {
    return netlist.netOf(p).map(Net::new);
  }

  Comp register(Kind kind, Component component) {
    final var simple = kind.key().contains("/") ? kind.key().substring(kind.key().lastIndexOf('/') + 1) : kind.key();
    final var n = idCounters.merge(simple, 0, Integer::sum);
    idCounters.put(simple, n + 1);
    final var comp = new Comp(this, kind, simple + "_" + n, component);
    pending.add(comp);
    return comp;
  }

  int[] nextAutoLayoutDot() {
    final var col = autoLayoutCol;
    autoLayoutCol += 3;
    return new int[] {col, 0};
  }

  /** Component-kind counts plus a pin count -- a cheap outline before pulling any component list
   * (design doc, section 七). */
  public record Summary(Map<String, Integer> countsByKind, int inputPins, int outputPins) {}

  private final class WireOpsImpl implements WireOps {
    @Override
    public Dot dotAt(int col, int row) {
      return new Dot(col * 10, row * 10);
    }

    @Override
    public Dot dotAt(int col, int row, boolean allowOffGrid) {
      return dotAt(col, row);
    }

    @Override
    public void add(Dot a, Dot b) {
      if (a.rawX() != b.rawX() && a.rawY() != b.rawY()) {
        throw new IllegalArgumentException(
            "wire endpoints must share a column or a row: " + a + " -> " + b);
      }
      pendingWires.add(new int[] {a.rawX(), a.rawY(), b.rawX(), b.rawY()});
    }

    @Override
    public void add(List<Dot> path) {
      for (var i = 0; i + 1 < path.size(); i++) add(path.get(i), path.get(i + 1));
    }

    @Override
    public boolean isOccupied(Dot d) {
      for (final var c : pending) {
        for (final var p : c.ports()) {
          if (p.at().rawX() == d.rawX() && p.at().rawY() == d.rawY()) return true;
        }
      }
      return false;
    }
  }
}
