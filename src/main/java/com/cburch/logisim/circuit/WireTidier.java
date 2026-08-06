/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.circuit;

import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Location;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Peler Edition Feature 4 ("Tidy Wires"): re-routes every wire in a circuit for readability
 * without moving any components. See {@code docs/peler-edition/ROADMAP.md}, Feature 4, for the
 * full design rationale.
 *
 * <p><b>The one invariant that must never break</b>: Logisim wire connectivity is determined
 * purely by exact {@link Location} equality between a {@link Wire}'s two endpoints and other
 * wires'/components' endpoints ({@link CircuitWires#connectWires}/{@code connectComponents} union
 * bundles only at {@code wire.e0}/{@code wire.e1}). Two routed segments crossing in the middle
 * with neither having a vertex there are simply not connected -- normal schematic convention, not
 * a bug. The only way this class can corrupt a circuit is by placing a new wire's *endpoint*
 * (either an original terminal or a routing bend) on a {@link Location} it shouldn't touch. Every
 * method below is written with that single failure mode in mind; see especially {@link
 * #routeEdge} and {@link #firstUnsafeVertex} for how bend placement is kept safe.
 *
 * <p>Deliberately does NOT reuse {@link CircuitWires}'s internal {@code Connectivity}/{@code
 * WireBundle} machinery -- that's entangled with {@link CircuitState}/simulation timing, which is
 * unnecessary coupling for this purely geometric operation. Net extraction below is a standalone
 * union-find keyed by {@link Location} instead.
 */
public final class WireTidier {
  private static final Logger LOGGER = LoggerFactory.getLogger(WireTidier.class);

  /** One grid unit, matching {@code Canvas.snapXToGrid}/the spacing between adjacent pins. */
  private static final int GRID = 10;

  /** Extra margin (px) added around the circuit's overall bounds when routing detours. */
  private static final int SEARCH_MARGIN = 40;

  private static final int STEP_COST = 1;
  private static final int BEND_COST = 4;
  private static final int PIN_PROXIMITY_COST = 6;

  /** Safety valve bounding worst-case A* search size; see {@link #findPath}. */
  private static final int MAX_EXPANSIONS = 200_000;

  /**
   * How many times {@link #routeEdge} retries a single edge after discovering its chosen path
   * would place a bend on a Location it must not touch (see class Javadoc), before giving up and
   * using the guaranteed-safe fallback. Kept small: this situation should already be rare thanks
   * to the soft-avoid cost, so a handful of retries (each blocking one more offending cell) is
   * enough without risking pathological slowdowns.
   */
  private static final int MAX_ROUTE_ATTEMPTS = 6;

  private static final int DIR_NONE = 0;
  private static final int DIR_NORTH = 1;
  private static final int DIR_SOUTH = 2;
  private static final int DIR_EAST = 3;
  private static final int DIR_WEST = 4;

  /** {dx, dy, direction-id} for each of the four grid moves a route may take. */
  private static final int[][] NEIGHBORS = {
    {GRID, 0, DIR_EAST},
    {-GRID, 0, DIR_WEST},
    {0, GRID, DIR_SOUTH},
    {0, -GRID, DIR_NORTH},
  };

  private WireTidier() {
    throw new IllegalStateException("Utility class. No instantiation allowed.");
  }

  /**
   * Computes a {@link CircuitMutation} that removes every existing {@link Wire} in {@code
   * circuit} and adds back a freshly-routed replacement set, one MST-based tree of wires per net.
   * Does not touch anything else (no components are moved, added, or removed).
   *
   * <p>Deliberately UI-independent: this never calls {@code toAction()}/{@code proj.doAction()}
   * itself -- the caller decides when/whether to actually apply it.
   *
   * <p><b>Contract</b>: returns {@code null} if there is genuinely nothing to do, i.e. the
   * circuit has no wires to remove AND no new wires would be added (for example: an empty
   * circuit, or one whose components have no multi-terminal nets at all). Callers must check for
   * {@code null} before calling {@code toAction()} on the result.
   *
   * <p>Note this intentionally removes ALL existing wires unconditionally, including any that
   * don't touch a component (dangling/floating wire stubs) -- those have no terminal to anchor a
   * replacement net to, so they are simply not recreated. This matches the design in
   * docs/peler-edition/ROADMAP.md, Feature 4.
   */
  public static CircuitMutation buildTidyMutation(Circuit circuit) {
    final var oldWires = new ArrayList<>(circuit.getWires());
    final var nets = extractNets(circuit);

    final var newWires = new LinkedHashSet<Wire>();
    if (!nets.isEmpty()) {
      final var ctx = buildRouterContext(circuit);

      // Tracks bend/junction Locations already used by nets that have FINISHED routing, so a
      // later net can never place a bend on the exact same Location an earlier, unrelated net
      // already used -- doing so would silently union the two nets together per the invariant in
      // the class Javadoc. Only merged in once a whole net is done (see below the inner loop), so
      // edges WITHIN the same net are always free to reconverge on each other's bends/terminals;
      // that's harmless, they're already the same net.
      final var claimedByOtherNets = new HashSet<Location>();
      for (final var net : nets) {
        final var netTerminals = new HashSet<>(net.terminals());
        final var netOwnBends = new HashSet<Location>();
        for (final var edge : buildMst(net.terminals())) {
          newWires.addAll(
              routeEdge(ctx, edge[0], edge[1], netTerminals, netOwnBends, claimedByOtherNets));
        }
        claimedByOtherNets.addAll(netOwnBends);
      }
    }

    final var xn = new CircuitMutation(circuit);
    if (!oldWires.isEmpty()) {
      xn.removeAll(oldWires);
    }
    if (!newWires.isEmpty()) {
      xn.addAll(newWires);
    }
    return xn.isEmpty() ? null : xn;
  }

  // ---------------------------------------------------------------------------------------
  // Part 1: net extraction
  // ---------------------------------------------------------------------------------------

  /** A single net: a set of 2+ distinct terminal Locations that must all stay connected. */
  private record Net(List<Location> terminals) {}

  /**
   * Extracts every net in {@code circuit} using a standalone union-find keyed by {@link
   * Location}: every {@link Wire}'s two endpoints are unioned, and every non-wire component's
   * {@code EndData} location is registered as a terminal (creating its own singleton group if it
   * wasn't already reached by a wire union). Unlike {@link CircuitWires}'s internal {@code
   * connectComponents}, {@code EndData.INPUT_ONLY} ends are NOT skipped here -- every pin, input
   * or output, is a terminal that must stay connected; that skip is a simulation-specific
   * optimization in {@code CircuitWires} that doesn't apply to this purely geometric operation.
   *
   * <p>Splitters need no special-casing: each port is just another {@code EndData} location,
   * handled generically. Tunnels and pull resistors likewise need none: neither exposes anything
   * this method would treat differently (a Tunnel's logical name-matching to other tunnels isn't
   * a {@link Location}-based connection at all, so it's simply outside what this class touches).
   *
   * <p>Terminal groups with fewer than 2 distinct Locations are dropped -- nothing to connect
   * (an unconnected pin), or terminals that already coincide at the same exact Location (no wire
   * needed, they're already the same point).
   */
  private static List<Net> extractNets(Circuit circuit) {
    final var uf = new UnionFind();
    for (final var wire : circuit.getWires()) {
      uf.union(wire.getEnd0(), wire.getEnd1());
    }

    final var grouped = new LinkedHashMap<Location, LinkedHashSet<Location>>();
    for (final var comp : circuit.getNonWires()) {
      for (final var end : comp.getEnds()) {
        final var loc = end.getLocation();
        final var root = uf.find(loc);
        grouped.computeIfAbsent(root, r -> new LinkedHashSet<>()).add(loc);
      }
    }

    final var nets = new ArrayList<Net>();
    for (final var terminals : grouped.values()) {
      if (terminals.size() >= 2) {
        nets.add(new Net(new ArrayList<>(terminals)));
      }
    }
    return nets;
  }

  /** Standalone union-find keyed by {@link Location}; intentionally has no ties to CircuitWires. */
  private static final class UnionFind {
    private final Map<Location, Location> parent = new HashMap<>();

    /** Iterative find-with-path-compression (no recursion, safe for very long wire chains). */
    Location find(Location loc) {
      parent.putIfAbsent(loc, loc);
      var root = loc;
      while (!parent.get(root).equals(root)) {
        root = parent.get(root);
      }
      var cur = loc;
      while (!cur.equals(root)) {
        final var next = parent.get(cur);
        parent.put(cur, root);
        cur = next;
      }
      return root;
    }

    void union(Location a, Location b) {
      final var ra = find(a);
      final var rb = find(b);
      if (!ra.equals(rb)) {
        parent.put(ra, rb);
      }
    }
  }

  // ---------------------------------------------------------------------------------------
  // Part 2: rectilinear minimum spanning tree per net
  // ---------------------------------------------------------------------------------------

  /**
   * Computes a rectilinear MST over {@code terminals} using Manhattan distance as edge weight
   * (Prim's algorithm -- nets are typically single-digit or low-double-digit terminal counts, so
   * the naive O(n^2) selection per step is more than fast enough). Returns each edge as a
   * two-element {@code Location[]}.
   */
  private static List<Location[]> buildMst(List<Location> terminals) {
    final var edges = new ArrayList<Location[]>();
    final var n = terminals.size();
    if (n < 2) {
      return edges;
    }

    final var inTree = new boolean[n];
    inTree[0] = true;
    var remaining = n - 1;
    while (remaining > 0) {
      var bestFrom = -1;
      var bestTo = -1;
      var bestDist = Integer.MAX_VALUE;
      for (var i = 0; i < n; i++) {
        if (!inTree[i]) continue;
        for (var j = 0; j < n; j++) {
          if (inTree[j]) continue;
          final var dist = terminals.get(i).manhattanDistanceTo(terminals.get(j));
          if (dist < bestDist) {
            bestDist = dist;
            bestFrom = i;
            bestTo = j;
          }
        }
      }
      inTree[bestTo] = true;
      remaining--;

      // Zero-length edges (both terminals at the same Location) shouldn't occur -- extractNets
      // already dedups a net's terminals by exact Location before building it -- but this mirrors
      // WiringTool.mouseReleased's `if (wire.getLength() > 0)` guard defensively anyway.
      if (bestDist > 0) {
        edges.add(new Location[] {terminals.get(bestFrom), terminals.get(bestTo)});
      }
    }
    return edges;
  }

  // ---------------------------------------------------------------------------------------
  // Part 3: per-edge grid routing
  // ---------------------------------------------------------------------------------------

  /** Precomputed, circuit-wide data reused by every routeEdge call. */
  private record RouterContext(Bounds searchBounds, List<Bounds> obstacles, Set<Location> pinLocations) {}

  private static RouterContext buildRouterContext(Circuit circuit) {
    final var searchBounds = circuit.getBounds().expand(SEARCH_MARGIN);
    final var obstacles = new ArrayList<Bounds>();
    final var pinLocations = new HashSet<Location>();
    for (final var comp : circuit.getNonWires()) {
      obstacles.add(comp.getBounds());
      for (final var end : comp.getEnds()) {
        pinLocations.add(end.getLocation());
      }
    }
    return new RouterContext(searchBounds, obstacles, pinLocations);
  }

  /**
   * Routes one MST edge (a, b) into a chain of straight {@link Wire} segments, retrying with
   * additional blocked cells if the chosen path would place a bend on a Location it must not
   * touch (see {@link #firstUnsafeVertex}), and finally falling back to a direct L-shaped
   * connection (see {@link #safeFallbackLShape}) if pathfinding can't produce a safe route within
   * {@link #MAX_ROUTE_ATTEMPTS}. A net must never end up with an unconnected terminal after this
   * runs, so the fallback always returns a valid (if potentially ugly) connection.
   */
  private static List<Wire> routeEdge(
      RouterContext ctx,
      Location a,
      Location b,
      Set<Location> netTerminals,
      Set<Location> netOwnBends,
      Set<Location> claimedByOtherNets) {
    final var extraBlocked = new HashSet<Location>();
    for (var attempt = 0; attempt < MAX_ROUTE_ATTEMPTS; attempt++) {
      final var path = findPath(ctx, a, b, extraBlocked);
      if (path == null) {
        break;
      }
      final var wires = pathToWires(path);
      final var junctions = junctionVertices(wires, a, b);
      final var unsafe = firstUnsafeVertex(junctions, ctx.pinLocations(), netTerminals, claimedByOtherNets);
      if (unsafe == null) {
        netOwnBends.addAll(junctions);
        return wires;
      }
      extraBlocked.add(unsafe);
    }

    // Fallback path: pathfinding either found no route at all, or every attempt's chosen route
    // kept placing a bend on a Location it must not touch. Rare by design (the soft-avoid cost
    // steers the search away from this in the first place). Logged clearly so it's easy to spot
    // in review -- search this class for "falling back" if auditing a circuit's tidy-wires
    // result.
    LOGGER.warn(
        "WireTidier: falling back to a direct L-shaped connection for edge {} -> {} (grid "
            + "pathfinding failed, or repeatedly collided with an unrelated terminal/bend)",
        a,
        b);
    final var fallback = safeFallbackLShape(a, b, ctx.pinLocations(), netTerminals, claimedByOtherNets);
    netOwnBends.addAll(junctionVertices(fallback, a, b));
    return fallback;
  }

  /**
   * Returns the first Location in {@code candidates} (bend/junction points of a proposed route)
   * that must not become a wire endpoint: either it's a Location already claimed by a different,
   * already-finished net, or it's some other component's pin that isn't part of the current net.
   * Locations that belong to the current net (its own terminals) are always safe to touch again.
   * Returns {@code null} if every candidate is safe.
   */
  private static Location firstUnsafeVertex(
      Set<Location> candidates,
      Set<Location> pinLocations,
      Set<Location> netTerminals,
      Set<Location> claimedByOtherNets) {
    for (final var v : candidates) {
      if (claimedByOtherNets.contains(v) || (pinLocations.contains(v) && !netTerminals.contains(v))) {
        return v;
      }
    }
    return null;
  }

  /**
   * Every Wire endpoint in {@code wires} that is not the overall route's start ({@code a}) or end
   * ({@code b}) is a bend/junction point -- collected this way (by endpoint membership) rather
   * than from path traversal order because {@link Wire}'s constructor normalizes e0/e1 so the
   * "lower" coordinate is always e0, which does not necessarily match path traversal order.
   */
  private static Set<Location> junctionVertices(List<Wire> wires, Location a, Location b) {
    final var junctions = new HashSet<Location>();
    for (final var w : wires) {
      if (!w.getEnd0().equals(a) && !w.getEnd0().equals(b)) {
        junctions.add(w.getEnd0());
      }
      if (!w.getEnd1().equals(a) && !w.getEnd1().equals(b)) {
        junctions.add(w.getEnd1());
      }
    }
    return junctions;
  }

  /**
   * A* grid search from {@code start} to {@code goal}, stepping {@link #GRID} pixels at a time
   * along the four axis directions. Search state includes the incoming direction so straight runs
   * can be preferred over bends ({@link #BEND_COST}). {@code extraBlocked} cells are hard-blocked
   * on top of the circuit-wide obstacles in {@code ctx} (used by {@link #routeEdge} to retry
   * around an unsafe vertex). {@code start}/{@code goal} themselves are always passable regardless
   * of any obstacle they might otherwise fall inside (a pin sits on/at its own component's
   * boundary -- without this override the search could never even leave its own start point).
   *
   * <p>Returns {@code null} if no path is found (goal unreachable) or the search exceeds {@link
   * #MAX_EXPANSIONS} (a safety valve bounding worst-case runtime, not expected to trigger in
   * normal circuits) -- either way, the caller falls back to a direct connection.
   */
  private static List<Location> findPath(
      RouterContext ctx, Location start, Location goal, Set<Location> extraBlocked) {
    final var open = new PriorityQueue<SearchNode>(Comparator.comparingInt(SearchNode::f));
    final var bestG = new HashMap<NodeKey, Integer>();
    final var cameFrom = new HashMap<NodeKey, NodeKey>();

    final var startKey = new NodeKey(start, DIR_NONE);
    bestG.put(startKey, 0);
    open.add(new SearchNode(startKey, 0, heuristic(start, goal)));

    var expansions = 0;
    while (!open.isEmpty() && expansions < MAX_EXPANSIONS) {
      final var cur = open.poll();
      if (cur.g() > bestG.getOrDefault(cur.key(), Integer.MAX_VALUE)) {
        continue; // stale queue entry -- a cheaper path to this state was already found
      }
      if (cur.key().loc().equals(goal)) {
        return reconstructPath(cameFrom, cur.key());
      }
      expansions++;

      for (final var move : NEIGHBORS) {
        final var next = cur.key().loc().translate(move[0], move[1]);
        if (!ctx.searchBounds().contains(next.getX(), next.getY())) continue;
        if (isBlocked(ctx, next, start, goal, extraBlocked)) continue;

        var stepCost = STEP_COST;
        if (cur.key().dir() != DIR_NONE && cur.key().dir() != move[2]) {
          stepCost += BEND_COST;
        }
        if (isSoftAvoid(ctx, next, start, goal)) {
          stepCost += PIN_PROXIMITY_COST;
        }

        final var g = cur.g() + stepCost;
        final var nextKey = new NodeKey(next, move[2]);
        if (g < bestG.getOrDefault(nextKey, Integer.MAX_VALUE)) {
          bestG.put(nextKey, g);
          cameFrom.put(nextKey, cur.key());
          open.add(new SearchNode(nextKey, g, g + heuristic(next, goal)));
        }
      }
    }
    return null;
  }

  private record NodeKey(Location loc, int dir) {}

  private record SearchNode(NodeKey key, int g, int f) {}

  private static int heuristic(Location a, Location b) {
    return (a.manhattanDistanceTo(b) / GRID) * STEP_COST;
  }

  /** Hard obstacle test: {@code start}/{@code goal} are always exempt (see {@link #findPath}). */
  private static boolean isBlocked(
      RouterContext ctx, Location node, Location start, Location goal, Set<Location> extraBlocked) {
    if (node.equals(start) || node.equals(goal)) return false;
    if (extraBlocked.contains(node)) return true;
    for (final var bounds : ctx.obstacles()) {
      if (bounds.contains(node.getX(), node.getY())) return true;
    }
    return false;
  }

  /**
   * Soft-avoid cost test: stepping onto another component's pin location isn't blocked (a wire
   * may legitimately pass straight through it without ending there, which is electrically
   * harmless per the class Javadoc), just penalized so the router prefers not to when a
   * similarly-cheap alternative exists.
   */
  private static boolean isSoftAvoid(RouterContext ctx, Location node, Location start, Location goal) {
    if (node.equals(start) || node.equals(goal)) return false;
    return ctx.pinLocations().contains(node);
  }

  private static List<Location> reconstructPath(Map<NodeKey, NodeKey> cameFrom, NodeKey goalKey) {
    final var reversed = new ArrayList<Location>();
    var cur = goalKey;
    reversed.add(cur.loc());
    while (cameFrom.containsKey(cur)) {
      cur = cameFrom.get(cur);
      reversed.add(cur.loc());
    }
    Collections.reverse(reversed);
    return reversed;
  }

  /**
   * Converts a grid path into the minimal chain of straight {@link Wire} segments, merging
   * consecutive collinear grid steps into one longer segment per straight run (generalizes the
   * 1-2 segment wires {@code WiringTool.mouseReleased} builds to however many bends the path
   * has).
   */
  private static List<Wire> pathToWires(List<Location> path) {
    final var wires = new ArrayList<Wire>();
    if (path.size() < 2) {
      return wires;
    }
    var runStart = path.get(0);
    var prevDx = 0;
    var prevDy = 0;
    for (var i = 1; i < path.size(); i++) {
      final var prev = path.get(i - 1);
      final var cur = path.get(i);
      final var dx = Integer.signum(cur.getX() - prev.getX());
      final var dy = Integer.signum(cur.getY() - prev.getY());
      if (i > 1 && (dx != prevDx || dy != prevDy)) {
        wires.add(Wire.create(runStart, prev));
        runStart = prev;
      }
      prevDx = dx;
      prevDy = dy;
    }
    final var last = path.get(path.size() - 1);
    if (!runStart.equals(last)) {
      wires.add(Wire.create(runStart, last));
    }
    return wires;
  }

  /**
   * Guaranteed-safe fallback: a direct L-shaped connection through one of the two possible
   * corners, same corner-picking {@code WiringTool.mouseReleased} already uses. Tries the corner
   * {@code (b.x, a.y)} first and falls back to {@code (a.x, b.y)} if that corner would be unsafe
   * (see {@link #firstUnsafeVertex}); if genuinely both corners are unsafe (an astronomically
   * unlikely coincidence), this still returns a connection through the first corner rather than
   * leaving the net unconnected -- an extra unintended union is a lesser evil than a dropped
   * connection, and a net must never end up unconnected after this class runs.
   */
  private static List<Wire> safeFallbackLShape(
      Location a, Location b, Set<Location> pinLocations, Set<Location> netTerminals, Set<Location> claimedByOtherNets) {
    if (a.getX() == b.getX() || a.getY() == b.getY()) {
      return a.equals(b) ? List.of() : List.of(Wire.create(a, b));
    }
    final var corner1 = Location.create(b.getX(), a.getY(), false);
    final var corner2 = Location.create(a.getX(), b.getY(), false);
    final var corner1Unsafe =
        firstUnsafeVertex(Set.of(corner1), pinLocations, netTerminals, claimedByOtherNets) != null;
    final var corner = corner1Unsafe ? corner2 : corner1;
    return List.of(Wire.create(a, corner), Wire.create(corner, b));
  }
}
