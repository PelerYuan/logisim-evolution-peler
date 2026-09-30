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
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.circuit.WireSet;
import com.cburch.logisim.circuit.WireTidier;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.dsl.internal.KindRegistry;
import com.cburch.logisim.dsl.internal.NetHandle;
import com.cburch.logisim.dsl.internal.PendingNetlist;
import com.cburch.logisim.dsl.internal.Router;
import com.cburch.logisim.file.LogisimFileActions;
import com.cburch.logisim.gui.htmlexport.ExportHtml;
import com.cburch.logisim.gui.htmlexport.HtmlExporter;
import com.cburch.logisim.gui.main.ExportImage;
import com.cburch.logisim.proj.Action;
import com.cburch.logisim.proj.JoinedAction;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.gates.CircuitBuilder;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.tools.move.MoveGesture;
import com.cburch.logisim.util.StringUtil;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
      final var names = new ArrayList<String>();
      for (final var c : proj.getLogisimFile().getCircuits()) names.add(c.getName());
      throw new UnknownCircuitException(circuitName, names);
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

  /** The circuit as text a reader can check at a glance: one line per component, then one per
   * net with every port on it. Ports are written {@code id.name} when Logisim names them and
   * {@code id.in[k]} / {@code id.out[k]} (the position in {@code inputs()} / {@code outputs()})
   * otherwise. */
  public String describe() {
    final var out = new StringBuilder();
    final var comps = allComponents().sorted(Comparator.comparing(Comp::id)).collect(Collectors.toList());
    out.append("components (").append(comps.size()).append("):\n");
    for (final var c : comps) {
      out.append("  ").append(c.id()).append(' ').append(c.kind().key());
      c.label().ifPresent(l -> out.append(" \"").append(l).append('"'));
      out.append(" at ").append(c.origin()).append('\n');
    }
    final var handles = netlist.allNets();
    out.append("nets (").append(handles.size()).append("):\n");
    for (final var handle : handles) {
      out.append("  ").append(handle.id()).append(" [").append(handle.width()).append("b]:");
      for (final var p : handle.members()) out.append(' ').append(portName(p));
      out.append('\n');
    }
    final var loose = new ArrayList<String>();
    for (final var c : comps) {
      for (final var p : c.ports()) {
        if (p.dir() != Port.Dir.OUT && p.net().isEmpty()) loose.add(portName(p));
      }
    }
    if (!loose.isEmpty()) out.append("unconnected inputs: ").append(String.join(" ", loose)).append('\n');
    return out.toString();
  }

  private static String portName(Port p) {
    final var owner = p.owner();
    final var name = p.name();
    if (name.isPresent() && !name.get().isEmpty()) return owner.id() + "." + name.get();
    final var ins = owner.inputs().indexOf(p);
    if (p.dir() != Port.Dir.OUT && ins >= 0) return owner.id() + ".in[" + (ins + 1) + "]";
    return owner.id() + ".out[" + (owner.outputs().indexOf(p) + 1) + "]";
  }

  public CheckReport check() {
    final var unconnected = new ArrayList<Port>();
    for (final var c : allComponents().collect(Collectors.toList())) {
      for (final var p : c.ports()) {
        if (p.dir() != Port.Dir.OUT && p.net().isEmpty()) unconnected.add(p);
      }
    }
    final var undriven = new ArrayList<Net>();
    final var multiplyDriven = new ArrayList<Net>();
    for (final var handle : netlist.allNets()) {
      final var drivers = handle.drivers();
      if (drivers.isEmpty()) undriven.add(new Net(handle));
      if (drivers.stream().filter(p -> p.dir() == Port.Dir.OUT).count() > 1) {
        multiplyDriven.add(new Net(handle));
      }
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

  /**
   * Removes a component. A staged one is simply dropped from this session's pending set. One this
   * circuit already holds (committed earlier, or drawn by hand) is deleted from the circuit as one
   * immediate, undo-logged action -- exactly what selecting it and pressing Delete does, so wires
   * that were attached to it stay behind, now ending at nothing. That immediate path needs
   * nothing pending for the same reason {@link #tidyWires()} does: it re-derives this session's
   * connectivity from the circuit, which would silently drop staged connections.
   */
  public void remove(Comp c) {
    if (existingComponents.contains(c)) {
      removeCommitted(c);
      return;
    }
    pending.remove(c);
    for (final var p : c.ports()) {
      p.net().ifPresent(n -> netlist.disconnect(n.handle()));
    }
  }

  private void removeCommitted(Comp c) {
    if (isDirty()) throw new UncommittedChangesException(pending.size());
    final var mutation = new CircuitMutation(circuit);
    mutation.remove(c.rawComponent());
    proj.doAction(mutation.toAction(StringUtil.constantGetter("delete " + c.id())));
    existingComponents.remove(c);
    netlist.clear();
    existingNetCounter = 0;
    seedConnectivity(existingComponents);
  }

  /** What {@link #copyRegion} did. */
  public record Copied(int components, int wires) {}

  /**
   * Copies everything lying fully inside a rectangle of this circuit -- every component (splitters,
   * tunnels and probes included, not only the kinds the DSL can name) and every wire -- shifted by
   * ({@code dCol}, {@code dRow}) cells into {@code targetCircuit} (this circuit when null), as one
   * immediate, undo-logged action. This is the script counterpart of select, Ctrl+C, Ctrl+V, and
   * like it copies attributes (a ROM's contents included) rather than sharing them.
   *
   * <p>The rectangle starts at grid cell ({@code col}, {@code row}) and spans {@code cols} by
   * {@code rows} cells; a component belongs to it only if its whole bounding box does, the rule the
   * canvas's rubber-band applies. Unlike the GUI, which slides a paste until it finds free space,
   * the offset is the caller's: a copy that would land a component on a pin or on top of another
   * component, run outside the canvas, or place a circuit inside itself is refused, changing
   * nothing. Copies into this circuit add {@link Comp}s to this session; any {@link Net} obtained
   * earlier is stale afterwards. Needs nothing pending in this session.
   */
  public Copied copyRegion(
      int col, int row, int cols, int rows, int dCol, int dRow, String targetCircuit) {
    if (isDirty()) throw new UncommittedChangesException(pending.size());
    final var target = targetCircuit == null ? circuit : proj.getLogisimFile().getCircuit(targetCircuit);
    if (target == null) {
      throw new UnknownCircuitException(targetCircuit, Circuits.of(this).list());
    }
    final var region = Bounds.create(col * 10, row * 10, cols * 10, rows * 10);
    final var dx = dCol * 10;
    final var dy = dRow * 10;
    final var picked = new ArrayList<Component>();
    for (final var component : circuit.getAllWithin(region)) picked.add(component);
    if (picked.isEmpty()) {
      throw new CopyRegionException(
          "empty", "nothing lies fully inside the region", "widen the region to cover whole components");
    }

    final var mutation = new CircuitMutation(target);
    final var copies = new ArrayList<Component>();
    var components = 0;
    var wires = 0;
    for (final var original : picked) {
      if (original instanceof Wire wire) {
        final var a = wire.getEnd0().translate(dx, dy);
        final var b = wire.getEnd1().translate(dx, dy);
        checkOnCanvas(a);
        checkOnCanvas(b);
        mutation.add(Wire.create(a, b));
        wires++;
        continue;
      }
      final var bounds = original.getBounds().translate(dx, dy);
      if (bounds.getX() < 0 || bounds.getY() < 0) {
        throw new CopyRegionException(
            "off-canvas", "a copy would land at a negative coordinate", "choose a larger offset");
      }
      if (original.getFactory() instanceof SubcircuitFactory sub
          && !proj.getDependencies().canAdd(target, sub.getSubcircuit())) {
        throw new CopyRegionException(
            "circular",
            "circuit \"" + sub.getSubcircuit().getName() + "\" cannot be placed inside \""
                + target.getName() + "\"",
            "copy into a circuit that is not used by the copied subcircuit");
      }
      final var copy =
          original
              .getFactory()
              .createComponent(
                  original.getLocation().translate(dx, dy),
                  (com.cburch.logisim.data.AttributeSet) original.getAttributeSet().clone());
      checkNoConflict(target, copy, original);
      mutation.add(copy);
      copies.add(copy);
      components++;
    }
    proj.doAction(mutation.toAction(StringUtil.constantGetter("copy region")));
    if (target == circuit) {
      final var known = new ArrayList<Comp>(existingComponents);
      for (final var copy : copies) {
        KindRegistry.resolveExisting(proj, copy)
            .ifPresent(
                r ->
                    known.add(
                        new Comp(
                            this,
                            new Kind(r.key(), r.factory(), r.subcircuit()),
                            nextId(r.key()),
                            copy)));
      }
      existingComponents.clear();
      existingComponents.addAll(known);
      netlist.clear();
      existingNetCounter = 0;
      seedConnectivity(existingComponents);
    }
    return new Copied(components, wires);
  }

  private static void checkOnCanvas(Location at) {
    if (at.getX() < 0 || at.getY() < 0) {
      throw new CopyRegionException(
          "off-canvas", "a wire would land at a negative coordinate", "choose a larger offset");
    }
  }

  /** The two conflicts the canvas's paste avoids: a pin that another component already owns, and
   * a component sitting exactly on another of the same bounds. */
  private static void checkNoConflict(Circuit target, Component copy, Component original) {
    for (final var end : copy.getEnds()) {
      if (end != null && end.isExclusive() && target.getExclusive(end.getLocation()) != null) {
        throw new CopyRegionException(
            "conflict",
            "a copied pin would land on a pin that is already used at " + end.getLocation(),
            "choose an offset that leaves the copy in free space");
      }
    }
    for (final var other : target.getAllContaining(copy.getLocation())) {
      if (other.getBounds().equals(copy.getBounds())) {
        throw new CopyRegionException(
            "conflict",
            "a copy of " + original.getFactory().getName() + " would sit exactly on another component at "
                + copy.getLocation(),
            "choose an offset that leaves the copy in free space");
      }
    }
  }

  /** What {@link #move} did: how many of the moved component's ports were left with no wire to
   * where they used to connect (0 when every connection could be re-drawn). */
  public record Moved(int unconnectedPorts) {}

  /**
   * Moves a component this circuit already holds so its anchor lands on grid position ({@code col},
   * {@code row}) -- the same anchor {@link Comp#origin()} reports and {@code Placement#anchorAt}
   * targets -- as one immediate, undo-logged action. When {@code keepConnections} is true the wires
   * that were attached are re-routed to follow it, using the very same connection-preserving move
   * the canvas performs when a user drags a selection; when false only the component moves and its
   * old wires are left where they were.
   *
   * <p>Needs nothing pending, and only ever moves a committed component: a staged one has no
   * circuit position to move from yet, so re-place it instead. Like {@link #tidyWires()} it keeps
   * every {@link Comp} object and id, but any {@link Net} obtained before the call is stale.
   */
  public Moved move(Comp c, int col, int row, boolean keepConnections) {
    if (isDirty()) throw new UncommittedChangesException(pending.size());
    if (!existingComponents.contains(c)) throw new ComponentNotCommittedException(c);
    final var old = c.rawComponent();
    final var oldLoc = old.getLocation();
    final var newLoc = Location.create(col * 10, row * 10, false);
    final var dx = newLoc.getX() - oldLoc.getX();
    final var dy = newLoc.getY() - oldLoc.getY();
    if (dx == 0 && dy == 0) return new Moved(0);

    final var mutation = new CircuitMutation(circuit);
    final var copy = old.getFactory().createComponent(newLoc, old.getAttributeSet());
    mutation.replace(old, copy);
    var unconnected = 0;
    if (keepConnections) {
      final var gesture = new MoveGesture(null, circuit, List.of(old));
      final var result = gesture.forceRequest(dx, dy);
      if (result != null) {
        mutation.replace(result.getReplacementMap());
        unconnected = result.getUnconnectedLocations().size();
      }
    }
    proj.doAction(mutation.toAction(StringUtil.constantGetter("move " + c.id())));
    c.rebind(copy);
    netlist.clear();
    existingNetCounter = 0;
    seedConnectivity(existingComponents);
    return new Moved(unconnected);
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

  int pendingCount() {
    return pending.size();
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
    final var routed = new LinkedHashMap<NetHandle, List<int[]>>();
    final var existingSegments = new ArrayList<int[]>();
    for (final var wire : circuit.getWires()) {
      existingSegments.add(
          new int[] {
            wire.getEnd0().getX(), wire.getEnd0().getY(), wire.getEnd1().getX(), wire.getEnd1().getY()
          });
    }
    for (final var net : nets) {
      final var foreign = new ArrayList<int[]>();
      for (final var segment : existingSegments) {
        if (!isSegmentOf(net.path(), segment)) foreign.add(segment);
      }
      foreign.addAll(pendingWires);
      for (final var other : routed.values()) foreign.addAll(other);
      final var segments = router.route(net, allPorts, foreign);
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
    verifyNoShorts(nets, existingSegments, routed);

    final var action = withMissingLibrariesLoaded(mutation.toAction(StringUtil.constantGetter(actionName)));
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

  /** Refuses a commit whose new wires would physically join ports that belong to different nets. */
  private void verifyNoShorts(
      List<NetHandle> nets, List<int[]> existingSegments, Map<NetHandle, List<int[]>> routed) {
    final var segments = new ArrayList<int[]>(existingSegments);
    final var firstNew = segments.size();
    for (final var dots : routed.values()) segments.addAll(dots);
    segments.addAll(pendingWires);

    final var parent = new int[segments.size()];
    for (var i = 0; i < parent.length; i++) parent[i] = i;
    for (var i = 0; i < segments.size(); i++) {
      for (var j = i + 1; j < segments.size(); j++) {
        final var a = segments.get(i);
        final var b = segments.get(j);
        if (Router.connects(a[0], a[1], a[2], a[3], b[0], b[1], b[2], b[3])) {
          parent[find(parent, i)] = find(parent, j);
        }
      }
    }

    final var groups = new HashMap<Integer, Set<NetHandle>>();
    final var groupPorts = new HashMap<Integer, List<String>>();
    for (final var net : nets) {
      for (final var port : net.members()) {
        final var x = port.at().rawX();
        final var y = port.at().rawY();
        for (var i = 0; i < segments.size(); i++) {
          final var seg = segments.get(i);
          if (!Router.connects(x, y, x, y, seg[0], seg[1], seg[2], seg[3])) continue;
          final var root = find(parent, i);
          groups.computeIfAbsent(root, k -> new HashSet<>()).add(net);
          groupPorts.computeIfAbsent(root, k -> new ArrayList<>()).add(port.toString());
        }
      }
    }
    for (final var entry : groups.entrySet()) {
      if (entry.getValue().size() < 2) continue;
      var hasNew = false;
      for (var i = firstNew; i < segments.size(); i++) {
        if (find(parent, i) == entry.getKey()) {
          hasNew = true;
          break;
        }
      }
      if (hasNew) throw new ShortCircuitException(groupPorts.get(entry.getKey()));
    }
  }

  private static int find(int[] parent, int i) {
    var root = i;
    while (parent[root] != root) root = parent[root];
    while (parent[i] != root) {
      final var next = parent[i];
      parent[i] = root;
      i = next;
    }
    return root;
  }

  private static boolean isSegmentOf(List<int[]> path, int[] segment) {
    for (final var own : path) {
      if ((own[0] == segment[0] && own[1] == segment[1] && own[2] == segment[2] && own[3] == segment[3])
          || (own[0] == segment[2] && own[1] == segment[3] && own[2] == segment[0] && own[3] == segment[1])) {
        return true;
      }
    }
    return false;
  }

  /**
   * {@link KindRegistry} resolves a built-in kind straight through the loader's shared builtin
   * tree, never checking whether this project's own file has that component's library loaded --
   * deliberate, so a plain test fixture with no libraries of its own can still place anything (see
   * {@link KindRegistry}'s own javadoc). That gap is invisible until the circuit is saved: the file
   * writer attributes a component to whichever of the project's own loaded libraries contains it,
   * and a component whose library was never loaded is silently dropped with a "component not
   * found" error -- exactly what this closes, by loading whatever this commit's newly placed
   * components actually need, as part of the same undo-log entry.
   */
  private Action withMissingLibrariesLoaded(Action mutationAction) {
    final var missing = missingLibrariesFor(pending);
    if (missing.isEmpty()) return mutationAction;
    final var file = proj.getLogisimFile();
    final var actions = new ArrayList<Action>();
    for (final var lib : missing) actions.add(LogisimFileActions.loadLibraryQuiet(lib, file));
    actions.add(mutationAction);
    return new JoinedAction(actions.toArray(new Action[0]));
  }

  private List<Library> missingLibrariesFor(List<Comp> comps) {
    final var missing = new LinkedHashSet<Library>();
    for (final var comp : comps) {
      final var factory = comp.rawComponent().getFactory();
      if (isReachableFromFile(factory)) continue;
      final var owner = builtinLibraryOwning(factory);
      if (owner != null) missing.add(owner);
    }
    return List.copyOf(missing);
  }

  /** Mirrors the check the file writer itself makes when attributing a component to a library
   * (only the project's own file and the libraries it has directly loaded, never a level deeper) --
   * matching that exactly is the point, not a generalization of it. */
  private boolean isReachableFromFile(ComponentFactory factory) {
    final var file = proj.getLogisimFile();
    if (file.contains(factory)) return true;
    for (final var lib : file.getLibraries()) {
      if (lib.contains(factory)) return true;
    }
    return false;
  }

  private Library builtinLibraryOwning(ComponentFactory factory) {
    for (final var lib : proj.getLogisimFile().getLoader().getBuiltin().getLibraries()) {
      if (lib.contains(factory)) return lib;
    }
    return null;
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

  /** Safety net for the MCP script surface's auto-tidy-and-fit hook (run after every {@code eval},
   * outside this package): a component facing north or east draws part of its own body to the left
   * of or above its anchor point (see {@link com.cburch.logisim.std.wiring.Probe#getOffsetBounds}
   * -- an EAST-facing pin's whole body sits at x in {@code [-width, 0]} relative to its anchor), so
   * a circuit built with a component anchored at or near {@code (0, 0)} -- exactly what that tool's
   * own documented example script used to do -- ends up with a real, negative-inclusive
   * {@link Circuit#getBounds()}. The Layout canvas's scrollable area is hard-anchored at content
   * {@code x=0}/{@code y=0}, and a plain scrollbar's minimum is 0, so that negative region can never
   * be scrolled into view -- no amount of correct zooming or centering can show it once a circuit's
   * bounds actually cross an axis. This shifts every component and wire in the circuit by the same
   * offset so the whole bounding box clears {@code margin} on both axes, exactly like a human
   * selecting everything and dragging it away from the corner.
   *
   * <p>Returns {@code false} with nothing changed if the circuit is already clear of both axes by at
   * least {@code margin} -- including an empty circuit, which has no bounds to be too close to
   * anything. Requires nothing pending, for the same reason as {@link #tidyWires()}: a placement not
   * yet committed lives only in {@code pending}, invisible to the {@link Circuit} this reads bounds
   * from and issues the mutation against. */
  public boolean ensureAwayFromOrigin(int margin) {
    if (isDirty()) {
      throw new UncommittedChangesException(pending.size());
    }
    final var bounds = circuit.getBounds();
    if (bounds.getWidth() == 0 || bounds.getHeight() == 0) return false;
    final var dx = bounds.getX() < margin ? roundUpToGrid(margin - bounds.getX()) : 0;
    final var dy = bounds.getY() < margin ? roundUpToGrid(margin - bounds.getY()) : 0;
    if (dx == 0 && dy == 0) return false;

    final var mutation = new CircuitMutation(circuit);
    final var replaced = new HashMap<Component, Component>();
    for (final var comp : circuit.getNonWires()) {
      final var newLoc = comp.getLocation().translate(dx, dy);
      final var copy = comp.getFactory().createComponent(newLoc, comp.getAttributeSet());
      mutation.replace(comp, copy);
      replaced.put(comp, copy);
    }
    for (final var wire : circuit.getWires()) {
      final var copy =
          Wire.create(wire.getEnd0().translate(dx, dy), wire.getEnd1().translate(dx, dy));
      mutation.replace(wire, copy);
      replaced.put(wire, copy);
    }
    proj.doAction(mutation.toAction(StringUtil.constantGetter("move circuit into view")));

    // Same concern as tidyWires(): existingComponents must keep the same Comp objects and ids,
    // just rebound to the fresh Component the mutation actually put in the circuit.
    for (final var c : existingComponents) {
      final var newComponent = replaced.get(c.rawComponent());
      if (newComponent != null) c.rebind(newComponent);
    }

    // Every Port netlist already holds (from any connect() call in this or an earlier commit this
    // session) still points at End objects on the discarded pre-shift Component instances -- using
    // one now would either report a stale location or trip Port's own generation check (rebind()
    // just bumped it). Re-derived from scratch exactly like tidyWires() does after its own mutation:
    // seedConnectivity() only reads Comp.ports(), which lazily rebuilds from each Comp's now-current
    // (rebound) Component, so nothing stale survives into the fresh netlist.
    netlist.clear();
    existingNetCounter = 0;
    seedConnectivity(existingComponents);
    return true;
  }

  private static int roundUpToGrid(int value) {
    return ((value + 9) / 10) * 10;
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

  /** Renders this circuit to an image or vector file -- {@code format} is one of {@code "png"},
   * {@code "gif"}, {@code "jpg"}, {@code "svg"}, {@code "tikz"} (case-insensitive), {@code scale}
   * a linear multiplier applied to the circuit's own drawn size (1.0 = actual size), and {@code
   * printerView} whether to draw in the black-on-white style used for printing rather than the
   * on-screen colors. Delegates to {@link ExportImage#exportSingle}, which needs no {@code Frame}
   * or open window -- this method works identically whether or not this project has a GUI. */
  public void exportImage(String path, String format, double scale, boolean printerView) {
    final var fmt = resolveImageFormat(format);
    try {
      ExportImage.exportSingle(proj, circuit, new File(path), fmt, scale, printerView);
    } catch (IOException e) {
      throw new ExportFailedException(path, e.getMessage());
    }
  }

  public void exportImage(String path, String format) {
    exportImage(path, format, 1.0, false);
  }

  private static int resolveImageFormat(String format) {
    return switch (format.toLowerCase(Locale.ROOT)) {
      case "png" -> ExportImage.FORMAT_PNG;
      case "gif" -> ExportImage.FORMAT_GIF;
      case "jpg", "jpeg" -> ExportImage.FORMAT_JPG;
      case "svg" -> ExportImage.FORMAT_SVG;
      case "tikz" -> ExportImage.FORMAT_TIKZ;
      default -> throw new InvalidExportFormatException(format);
    };
  }

  /** Writes this circuit out as a self-contained, interactively-simulatable HTML page (Peler
   * Edition's experimental feature -- see {@link HtmlExporter}). Refuses up front, with a
   * structured {@link UnsupportedForHtmlExportException}, if the circuit (or any subcircuit it
   * contains) uses a component kind {@link HtmlExporter#supportedKinds()} cannot simulate, exactly
   * like {@link ExportHtml#doExport}'s own dialog-based refusal -- this is the headless
   * equivalent, so a script sees the same list of offending kinds instead of a dialog it can never
   * click through. */
  public void exportHtml(String path) {
    final var unsupported = new TreeSet<String>();
    ExportHtml.collectUnsupported(circuit, unsupported, new HashSet<>());
    if (!unsupported.isEmpty()) {
      throw new UnsupportedForHtmlExportException(List.copyOf(unsupported));
    }
    try {
      new HtmlExporter(proj, circuit).writeTo(new File(path));
    } catch (IOException e) {
      throw new ExportFailedException(path, e.getMessage());
    }
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
