/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import com.cburch.logisim.analyze.model.AnalyzerModel;
import com.cburch.logisim.analyze.model.Expression;
import com.cburch.logisim.analyze.model.Parser;
import com.cburch.logisim.analyze.model.ParserException;
import com.cburch.logisim.analyze.model.Var;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.circuit.WireSet;
import com.cburch.logisim.circuit.WireTidier;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.dsl.internal.KindRegistry;
import com.cburch.logisim.dsl.internal.NetHandle;
import com.cburch.logisim.dsl.internal.PendingNetlist;
import com.cburch.logisim.dsl.internal.Router;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.gates.CircuitBuilder;
import com.cburch.logisim.util.StringUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The façade over one circuit (design doc, section 五). Placement is immediate -- every {@link
 * Comp} returned already has real, live ports (design doc, 3.11) -- but nothing reaches the actual
 * {@link Circuit} until {@link #commit(String)}, which submits everything placed and connected
 * since the last commit as exactly one undo-log entry (design doc, section 六).
 *
 * <p>P4 (design doc, section 十一): every component already in the circuit when a {@link Space} is
 * constructed is discovered and wrapped too, alongside whatever the DSL places in this session, so
 * the read side (component/net queries) sees a hand-drawn circuit correctly rather than only this
 * session's own diffs. Connectivity for that pre-existing content is derived from
 * {@link Circuit#getWireSet(Wire)} (a per-net bundle of wires the circuit already computes) plus
 * exact-location coincidence for two ports that touch directly with no wire between them, per
 * design doc 3.10 -- deliberately not a from-scratch union-find over {@code WireBundle}, which is
 * package-private and unnecessary once the circuit's own bundle computation is reused.
 *
 * <p>Known scope limit: existing {@link Wire} geometry is not fed to the {@link Router} as an
 * obstacle, only existing components' ports are. A newly routed net therefore avoids landing on an
 * existing pin but is not guaranteed to avoid crossing an existing wire's path; per design doc 3.3
 * a plain crossing is electrically safe (only endpoints connect), so this only matters if a new
 * route's segment would run exactly along an existing wire's own line.
 */
public final class Space {
  private final Project proj;
  private final Circuit circuit;
  private final List<Comp> pending = new ArrayList<>();
  private final List<Comp> existingComponents = new ArrayList<>();
  private final PendingNetlist netlist = new PendingNetlist();
  private final Router router = new Router();
  private final Map<String, Integer> idCounters = new HashMap<>();
  private final List<int[]> pendingWires = new ArrayList<>();
  private int autoLayoutCol;
  private int existingNetCounter;

  private Space(Project proj, Circuit circuit) {
    this.proj = proj;
    this.circuit = circuit;
    discoverExisting();
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
    for (final var c : allComponents().collect(Collectors.toList())) {
      counts.merge(c.kind().key(), 1, Integer::sum);
      for (final var p : c.ports()) {
        if (p.dir() == Port.Dir.IN) inputs++;
        if (p.dir() == Port.Dir.OUT) outputs++;
      }
    }
    return new Summary(Map.copyOf(counts), inputs, outputs);
  }

  public List<Comp> components() {
    return allComponents().collect(Collectors.toList());
  }

  public List<Comp> componentsOf(Kind kind) {
    return allComponents().filter(c -> c.kind().key().equals(kind.key())).collect(Collectors.toList());
  }

  public Optional<Comp> byLabel(String label) {
    return allComponents().filter(c -> c.label().equals(Optional.of(label))).findFirst();
  }

  /** Looks up a component by the stable id it was given at placement (design doc, 13.1): the
   * correct way to reach a component from a later, possibly-fresh script session, since a Lua local
   * variable is not something a dropped session can recover. */
  public Optional<Comp> byId(String id) {
    return allComponents().filter(c -> c.id().equals(id)).findFirst();
  }

  public List<Comp> near(Comp c, int cells) {
    final var origin = c.origin();
    return allComponents()
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
    for (final var c : allComponents().collect(Collectors.toList())) {
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
    for (final var c : existingComponents) allPorts.addAll(c.ports());

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
    // pending components are now real circuit content -- move them into existingComponents (same
    // Comp, same id) rather than dropping them, so this Space's own later reads (components(),
    // byLabel(), check()...) see what it just committed without needing a fresh Space#of.
    existingComponents.addAll(pending);
    pending.clear();
    pendingWires.clear();
    return new CommitResult(action, placed, resultNets);
  }

  /** Re-routes every wire already in this circuit for readability -- see {@link WireTidier} -- as
   * exactly one undo-log entry, without moving, adding, or removing a single component. This is the
   * remedy for a circuit whose connections are correct but whose layout is not (design doc, 十二):
   * where {@link #commit(String)}'s own router only ever draws the nets it is asked to route, this
   * discards and rebuilds every wire the circuit already has, including ones drawn by a human,
   * loaded from a file, or committed in an earlier session.
   *
   * <p>Requires nothing pending: {@link WireTidier} works from the circuit's own committed state,
   * so a component placed or connected this session but not yet committed would simply be invisible
   * to it and its wiring silently dropped -- {@link #isDirty()} must be false, or this throws {@link
   * UncommittedChangesException} rather than doing that.
   *
   * <p>Returns {@code false} with nothing changed if the circuit had nothing to tidy (matching
   * {@link WireTidier#buildTidyMutation}'s own {@code null}-means-nothing-to-do contract) --
   * for instance an empty circuit, or one with no multi-terminal nets at all.
   *
   * <p>Only {@link Net}s go stale: a previously-held one still names wire geometry that no longer
   * exists, mirroring the documented across-session risk in design doc 13.1 (a dropped Lua session
   * loses its local variables) rather than introducing a new kind of staleness -- the safe pattern is
   * the same: look nets back up by rereading a {@link Comp}'s ports afterward rather than holding
   * onto an old {@link Net}. {@link Comp} identity and ids are entirely unaffected, and deliberately
   * not rederived from scratch: since {@link WireTidier} never adds, removes, or moves a component,
   * this reuses the exact same {@link Comp} objects it already handed out rather than rescanning the
   * circuit, which would sort by (possibly changed) visual position and could hand two components
   * each other's former ids. */
  public boolean tidyWires() {
    if (isDirty()) {
      throw new UncommittedChangesException(pending.size());
    }
    final var mutation = WireTidier.buildTidyMutation(circuit);
    if (mutation == null) {
      return false;
    }
    proj.doAction(mutation.toAction(StringUtil.constantGetter("tidy wires")));

    // Only wires changed -- WireTidier never adds, removes, or moves a component -- so the already-
    // identified existingComponents (same Comp objects, same ids) are still completely valid; only
    // the connectivity derived from wire geometry is stale. Re-running full discoverExisting() here
    // would look like the safe, simple option but is actually wrong: it re-sorts by visual position
    // and hands out fresh ids in that order, which silently diverges from the placement-order ids a
    // caller placed this session might already be holding, reassigning old ids to different
    // components entirely (caught by SpaceTidyWiresAcceptanceTest).
    netlist.clear();
    existingNetCounter = 0;
    seedConnectivity(existingComponents);
    return true;
  }

  /** Declarative generation (design doc, section 十一): builds this circuit's entire gate-level
   * content from a truth-table-style spec via {@link CircuitBuilder}, in exactly one undo-log
   * entry, then immediately re-derives this {@link Space}'s own view of the result the same way
   * {@link #discoverExisting()} would for a hand-drawn circuit -- so a later {@link #components()}/
   * {@link #nets()} call in the same session sees what was just built without a fresh {@link
   * Space#of} round trip. Because {@code CircuitBuilder.build} starts by clearing the destination
   * circuit, this only ever targets a circuit that is still completely empty: it is a convenience
   * layered on top of {@link #place}/{@link #connect}, never a replacement, and never an
   * incremental edit (design doc, closing discussion of 十一). */
  public SynthesisResult synthesize(Synthesis spec) {
    if (allComponents().findAny().isPresent() || !pendingWires.isEmpty()) {
      throw new NonEmptyCircuitException(circuit.getName(), (int) allComponents().count());
    }

    final var model = new AnalyzerModel();
    final var inputVars = new ArrayList<Var>();
    for (final var in : spec.inputs()) inputVars.add(new Var(in.name(), 1));
    final var outputVars = new ArrayList<Var>();
    for (final var out : spec.outputs()) outputVars.add(new Var(out.name(), 1));
    model.setVariables(inputVars, outputVars);

    for (final var out : spec.outputs()) {
      final Expression expr;
      try {
        expr = Parser.parse(out.expression(), model);
      } catch (ParserException e) {
        throw new ExpressionSyntaxException(out.name(), out.expression(), e);
      }
      model.getOutputExpressions().setExpression(out.name(), expr, out.expression());
    }

    final var mutation =
        CircuitBuilder.build(circuit, model, spec.isTwoInputGatesOnly(), spec.isNandOnly());
    final var action = mutation.toAction(StringUtil.constantGetter("synthesize " + circuit.getName()));
    proj.doAction(action);

    discoverExisting();
    return new SynthesisResult(action, List.copyOf(existingComponents));
  }

  Project project() {
    return proj;
  }

  Circuit circuit() {
    return circuit;
  }

  /** Every component a new placement must not collide with -- this session's own staged
   * components and whatever the circuit already had (design doc, P4, section 十一). */
  List<Comp> pendingComponents() {
    return allComponents().collect(Collectors.toList());
  }

  private Stream<Comp> allComponents() {
    return Stream.concat(pending.stream(), existingComponents.stream());
  }

  Optional<Net> netOf(Port p) {
    return netlist.netOf(p).map(Net::new);
  }

  Comp register(Kind kind, Component component) {
    final var comp = new Comp(this, kind, nextId(kind.key()), component);
    pending.add(comp);
    return comp;
  }

  private String nextId(String kindKey) {
    final var simple = kindKey.contains("/") ? kindKey.substring(kindKey.lastIndexOf('/') + 1) : kindKey;
    final var n = idCounters.merge(simple, 0, Integer::sum);
    idCounters.put(simple, n + 1);
    return simple + "_" + n;
  }

  int[] nextAutoLayoutDot() {
    final var col = autoLayoutCol;
    autoLayoutCol += 3;
    return new int[] {col, 0};
  }

  /** P4 discovery (design doc, section 十一): wraps every component already in {@link #circuit} as
   * a {@link Comp} -- in visual reading order (top-to-bottom, then left-to-right) so repeated reads
   * of an unchanged circuit tend to hand out the same ids, though nothing relies on that across a
   * {@code reset()} -- then derives the pre-existing connectivity between their ports and seeds it
   * into {@link #netlist} so {@link Port#net()} reads it exactly like a net this session made
   * itself. Anything whose factory {@link KindRegistry#resolveExisting} does not recognize
   * (splitters, tunnels, probes, ...) is left out of the DSL's view entirely -- P4 reads circuits
   * built from the same component families the DSL can place, not arbitrary Logisim content. */
  private void discoverExisting() {
    final var raw = new ArrayList<>(circuit.getNonWires());
    raw.sort(Comparator.<Component>comparingInt(c -> c.getLocation().getY())
        .thenComparingInt(c -> c.getLocation().getX()));
    for (final var component : raw) {
      final var resolved = KindRegistry.resolveExisting(proj, component);
      if (resolved.isEmpty()) continue;
      final var kind = new Kind(resolved.get().key(), resolved.get().factory(), resolved.get().subcircuit());
      existingComponents.add(new Comp(this, kind, nextId(kind.key()), component));
    }
    seedConnectivity(existingComponents);
  }

  /** The connectivity half of {@link #discoverExisting()}, split out so {@link #tidyWires()} can
   * re-derive connectivity for the same, already-identified {@link Comp}s after their wires are
   * rebuilt -- without also re-running the id-assigning half, which sorts by visual position and so
   * would silently hand existing {@link Comp} objects different ids than the ones a caller may
   * already be holding (component discovery order at construction time need not match the visual
   * order this scan uses, since it instead follows placement order within this session). */
  private void seedConnectivity(List<Comp> comps) {
    final var byLocation = new LinkedHashMap<Dot, List<Port>>();
    for (final var comp : comps) {
      for (final var port : comp.ports()) {
        byLocation.computeIfAbsent(port.at(), ignored -> new ArrayList<>()).add(port);
      }
    }
    if (byLocation.isEmpty()) return;

    final var wires = new ArrayList<>(circuit.getWires());
    final var handled = new boolean[wires.size()];
    final var consumed = new java.util.HashSet<Dot>();
    for (var i = 0; i < wires.size(); i++) {
      if (handled[i]) continue;
      final var bundle = circuit.getWireSet(wires.get(i));
      final var memberWires = new ArrayList<Wire>();
      for (var j = 0; j < wires.size(); j++) {
        if (!handled[j] && bundle.containsWire(wires.get(j))) {
          handled[j] = true;
          memberWires.add(wires.get(j));
        }
      }
      final var members = new ArrayList<Port>();
      for (final var entry : byLocation.entrySet()) {
        final var dot = entry.getKey();
        if (bundle.containsLocation(Location.create(dot.rawX(), dot.rawY(), false))) {
          members.addAll(entry.getValue());
          consumed.add(dot);
        }
      }
      if (members.isEmpty()) continue;
      final var path = new ArrayList<int[]>();
      for (final var wire : memberWires) {
        final var ends = wire.getEnds();
        final var a = ends.get(0).getLocation();
        final var b = ends.get(1).getLocation();
        path.add(new int[] {a.getX(), a.getY(), b.getX(), b.getY()});
      }
      netlist.seedExisting("wire_" + (existingNetCounter++), members, path);
    }

    // Two ports that coincide exactly with no wire between them are still electrically one node
    // (design doc, 3.1/3.10) -- e.g. a gate's output placed directly against another gate's input.
    for (final var entry : byLocation.entrySet()) {
      if (consumed.contains(entry.getKey())) continue;
      final var members = entry.getValue();
      if (members.size() < 2) continue;
      netlist.seedExisting("wire_" + (existingNetCounter++), members, List.of());
    }
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
