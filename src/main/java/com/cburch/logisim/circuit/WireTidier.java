/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.circuit;

import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Location;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Peler Edition Feature 4 ("Tidy Wires"): computes a from-scratch re-routing of every wire in a
 * circuit for readability, without moving any components. Deliberately standalone/UI-independent
 * (no Canvas/Project/Tool dependency) so it can be reasoned about and reviewed on its own; {@link
 * com.cburch.logisim.tools.TidyWiresTool} is the only caller.
 *
 * <p><b>The one invariant that must never break</b>: Logisim wire connectivity is determined
 * purely by exact {@link Location} equality between a {@link Wire}'s two endpoints and other
 * wires'/components' endpoints ({@code CircuitWires.connectWires}/{@code connectComponents} union
 * bundles only at a wire's own {@code e0}/{@code e1}; nothing tests for a wire passing *through*
 * another point). Two routed segments crossing in the middle, with neither having a vertex there,
 * are therefore NOT electrically connected -- that's the normal "crossing != connected" schematic
 * convention, not a bug to route around. The only way this class could silently corrupt a
 * circuit's logic is by placing a new wire's *endpoint* on a Location it shouldn't touch. See
 * {@code docs/peler-edition/ROADMAP.md}, Feature 4, for the full design rationale.
 */
public final class WireTidier {
  private WireTidier() {}

  /** Grid unit: matches {@code Canvas.snapXToGrid}'s implicit grid. Every pin/component-bound
      coordinate in Logisim is already a multiple of this. */
  private static final int GRID = 10;

  /** Pixels of slack added around the circuit's overall bounds so a route can detour around an
      obstacle that sits near the edge too. */
  private static final int SEARCH_MARGIN = 40;

  private static final int STEP_COST = 1;
  private static final int BEND_COST = 3;
  private static final int PIN_AVOID_COST = 5;

  private static final int[][] DIRS = {{GRID, 0}, {-GRID, 0}, {0, GRID}, {0, -GRID}};

  /**
   * Computes a {@link CircuitMutation} that removes every existing {@link Wire} in {@code
   * circuit} and replaces it with a freshly-routed equivalent, preserving every net's
   * connectivity. Returns {@code null} if there is nothing to do (no existing wires and no
   * multi-terminal net to (re)connect) -- callers should treat that as "nothing changed", not an
   * error.
   */
  public static CircuitMutation buildTidyMutation(Circuit circuit) {
    final var nets = extractNets(circuit);
    final var existingWires = circuit.getWires();
    if (nets.isEmpty() && existingWires.isEmpty()) {
      return null;
    }

    final var grid = buildObstacleGrid(circuit);
    final var newWires = new ArrayList<Wire>();
    for (final var net : nets) {
      routeNet(net, grid, newWires);
    }

    final var xn = new CircuitMutation(circuit);
    xn.removeAll(existingWires);
    xn.addAll(newWires);
    return xn;
  }

  // ---------------------------------------------------------------------------------------
  // Net extraction
  // ---------------------------------------------------------------------------------------

  /**
   * A net is the set of terminal (component pin) locations that must stay connected after
   * re-routing. Standalone union-find keyed by {@link Location} -- deliberately NOT reusing
   * {@code CircuitWires}' internal {@code Connectivity}/{@code WireBundle} machinery, which is
   * coupled to {@code CircuitState}/simulation timing and unnecessary for this purely geometric
   * operation.
   */
  private static List<List<Location>> extractNets(Circuit circuit) {
    final var parent = new HashMap<Location, Location>();

    for (final var wire : circuit.getWires()) {
      union(parent, wire.getEnd0(), wire.getEnd1());
    }

    // Every pin is a terminal -- unlike CircuitWires.connectComponents (which skips INPUT_ONLY
    // ends for its own simulation-specific reasons), we need every pin, input or output, since
    // all of them must stay connected.
    final var terminals = new ArrayList<Location>();
    for (final var comp : circuit.getNonWires()) {
      for (final var end : comp.getEnds()) {
        final var loc = end.getLocation();
        find(parent, loc); // ensure a union-find entry exists even if no wire ever touched it
        terminals.add(loc);
      }
    }

    final var groups = new HashMap<Location, List<Location>>();
    for (final var t : terminals) {
      groups.computeIfAbsent(find(parent, t), k -> new ArrayList<>()).add(t);
    }

    final var nets = new ArrayList<List<Location>>();
    for (final var group : groups.values()) {
      // Dedupe: two different components' pins can sit at the exact same Location (already
      // touching, no wire needed) -- both entered `terminals` separately above.
      final var distinct = new ArrayList<>(new HashSet<>(group));
      if (distinct.size() >= 2) {
        nets.add(distinct);
      }
      // 0 or 1 distinct terminal: nothing to connect (an unconnected pin, or a lone pin with no
      // wire at all) -- intentionally skipped.
    }
    return nets;
  }

  private static Location find(Map<Location, Location> parent, Location loc) {
    final var root = parent.getOrDefault(loc, loc);
    if (root.equals(loc)) {
      parent.putIfAbsent(loc, loc);
      return loc;
    }
    final var ultimateRoot = find(parent, root);
    parent.put(loc, ultimateRoot); // path compression
    return ultimateRoot;
  }

  private static void union(Map<Location, Location> parent, Location a, Location b) {
    final var rootA = find(parent, a);
    final var rootB = find(parent, b);
    if (!rootA.equals(rootB)) {
      parent.put(rootA, rootB);
    }
  }

  // ---------------------------------------------------------------------------------------
  // Obstacles
  // ---------------------------------------------------------------------------------------

  /**
   * Precomputed once per {@link #buildTidyMutation} call (not per net/edge): {@code blocked} is
   * every grid point covered by a non-wire component's bounds, MINUS that component's own pin
   * locations (a pin always sits on/at its owning component's boundary -- if it weren't
   * exempted, a route could never even leave its own starting pin). {@code pins} is every
   * component's pin locations, used as a soft (cost-penalty, not hard-blocked) avoid set so a
   * route doesn't visually cross exactly over an unrelated pin, even though doing so wouldn't be
   * electrically wrong per the invariant documented on this class.
   */
  private record ObstacleGrid(Set<Long> blocked, Set<Long> pins, Bounds searchBounds) {}

  private static long key(int x, int y) {
    return (((long) x) << 32) ^ (y & 0xffffffffL);
  }

  private static int floorToGrid(int v) {
    return Math.floorDiv(v, GRID) * GRID;
  }

  private static int ceilToGrid(int v) {
    return -Math.floorDiv(-v, GRID) * GRID;
  }

  private static ObstacleGrid buildObstacleGrid(Circuit circuit) {
    final var blocked = new HashSet<Long>();
    final var pins = new HashSet<Long>();
    Bounds overall = null;

    for (final var comp : circuit.getNonWires()) {
      final var bounds = comp.getBounds();
      overall = (overall == null) ? bounds : overall.add(bounds);

      final var ownPins = new HashSet<Long>();
      for (final var end : comp.getEnds()) {
        final var loc = end.getLocation();
        final var k = key(loc.getX(), loc.getY());
        ownPins.add(k);
        pins.add(k);
      }

      final var x0 = floorToGrid(bounds.getX());
      final var x1 = ceilToGrid(bounds.getX() + bounds.getWidth());
      final var y0 = floorToGrid(bounds.getY());
      final var y1 = ceilToGrid(bounds.getY() + bounds.getHeight());
      for (var x = x0; x <= x1; x += GRID) {
        for (var y = y0; y <= y1; y += GRID) {
          final var k = key(x, y);
          if (!ownPins.contains(k)) {
            blocked.add(k);
          }
        }
      }
    }

    if (overall == null) {
      overall = Bounds.create(0, 0, 0, 0);
    }
    return new ObstacleGrid(blocked, pins, overall.expand(SEARCH_MARGIN));
  }

  private static boolean isHardBlocked(ObstacleGrid grid, int x, int y) {
    return grid.blocked().contains(key(x, y));
  }

  private static boolean isSoftAvoid(ObstacleGrid grid, int x, int y) {
    return grid.pins().contains(key(x, y));
  }

  // ---------------------------------------------------------------------------------------
  // Routing
  // ---------------------------------------------------------------------------------------

  private static void routeNet(List<Location> terminals, ObstacleGrid grid, List<Wire> out) {
    for (final var edge : buildRectilinearMst(terminals)) {
      final var a = edge[0];
      final var b = edge[1];
      if (a.equals(b)) continue; // already touching (zero-length) -- nothing to draw
      emitWires(routeEdge(a, b, grid), out);
    }
  }

  /**
   * Rectilinear minimum spanning tree over {@code terminals} (Manhattan distance), a standard,
   * tractable approximation of the optimal Steiner tree. Prim's algorithm -- nets are small
   * (typically single digits, occasionally a few dozen terminals), so the O(n^2) approach is
   * simple and plenty fast.
   */
  private static List<Location[]> buildRectilinearMst(List<Location> terminals) {
    final var n = terminals.size();
    final var inTree = new boolean[n];
    final var minDist = new int[n];
    final var minFrom = new int[n];
    java.util.Arrays.fill(minDist, Integer.MAX_VALUE);
    minDist[0] = 0;
    minFrom[0] = -1;

    final var edges = new ArrayList<Location[]>();
    for (var iter = 0; iter < n; iter++) {
      var u = -1;
      for (var i = 0; i < n; i++) {
        if (!inTree[i] && (u == -1 || minDist[i] < minDist[u])) u = i;
      }
      inTree[u] = true;
      if (minFrom[u] != -1) {
        edges.add(new Location[] {terminals.get(minFrom[u]), terminals.get(u)});
      }
      for (var v = 0; v < n; v++) {
        if (!inTree[v]) {
          final var d = manhattan(terminals.get(u), terminals.get(v));
          if (d < minDist[v]) {
            minDist[v] = d;
            minFrom[v] = u;
          }
        }
      }
    }
    return edges;
  }

  private static int manhattan(Location a, Location b) {
    return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY());
  }

  /** One grid-graph node: position plus the direction the path last moved in, so a direction
      change can be charged {@link #BEND_COST} (biases the search toward straighter, tidier
      routes) without needing a separate visited-set per direction to still be correct. */
  private record Node(int x, int y, int dir) {}

  private static List<Location> routeEdge(Location start, Location end, ObstacleGrid grid) {
    final var path = dijkstra(start, end, grid);
    if (path != null) return path;
    // Fallback: a direct L-shaped connection (same corner convention WiringTool's own
    // mouseReleased uses for a two-segment drag), even if it overlaps a component visually. A
    // net must NEVER end up with an unconnected terminal after this runs -- that would be a
    // correctness regression, not just an ugly one. Expected to be rare given the generous
    // search space, but always available as a last resort.
    final var corner = Location.create(end.getX(), start.getY(), false);
    return List.of(start, corner, end);
  }

  private static List<Location> dijkstra(Location start, Location end, ObstacleGrid grid) {
    final var dist = new HashMap<Node, Integer>();
    final var prev = new HashMap<Node, Node>();
    final var visited = new HashSet<Node>();
    final var pq = new PriorityQueue<Node>((a, b) -> dist.get(a) - dist.get(b));

    final var startNode = new Node(start.getX(), start.getY(), -1);
    dist.put(startNode, 0);
    pq.add(startNode);

    final var minX = grid.searchBounds().getX();
    final var minY = grid.searchBounds().getY();
    final var maxX = minX + grid.searchBounds().getWidth();
    final var maxY = minY + grid.searchBounds().getHeight();

    Node goal = null;
    while (!pq.isEmpty()) {
      final var cur = pq.poll();
      if (visited.contains(cur)) continue;
      visited.add(cur);
      if (cur.x() == end.getX() && cur.y() == end.getY()) {
        goal = cur;
        break;
      }
      for (var d = 0; d < DIRS.length; d++) {
        final var nx = cur.x() + DIRS[d][0];
        final var ny = cur.y() + DIRS[d][1];
        if (nx < minX || nx > maxX || ny < minY || ny > maxY) continue;

        final var isTerminalCell =
            (nx == start.getX() && ny == start.getY()) || (nx == end.getX() && ny == end.getY());
        if (!isTerminalCell && isHardBlocked(grid, nx, ny)) continue;

        var cost = STEP_COST;
        if (cur.dir() != -1 && cur.dir() != d) cost += BEND_COST;
        if (!isTerminalCell && isSoftAvoid(grid, nx, ny)) cost += PIN_AVOID_COST;

        final var next = new Node(nx, ny, d);
        final var newDist = dist.get(cur) + cost;
        if (newDist < dist.getOrDefault(next, Integer.MAX_VALUE)) {
          dist.put(next, newDist);
          prev.put(next, cur);
          pq.add(next);
        }
      }
    }
    if (goal == null) return null;

    final var pathNodes = new ArrayList<Node>();
    for (var n = goal; n != null; n = prev.get(n)) {
      pathNodes.add(n);
    }
    Collections.reverse(pathNodes);

    final var locs = new ArrayList<Location>();
    for (final var node : pathNodes) {
      locs.add(Location.create(node.x(), node.y(), false));
    }
    return locs;
  }

  /** Converts a full grid-step path (one point per {@link #GRID} unit moved) into the fewest
      possible straight {@link Wire} segments by merging consecutive collinear steps -- mirrors
      how {@code WiringTool.mouseReleased} builds its own 1-2 segment wires, generalized to
      however many bends this path has. */
  private static void emitWires(List<Location> path, List<Wire> out) {
    if (path == null || path.size() < 2) return;
    var segStart = path.get(0);
    for (var i = 1; i < path.size(); i++) {
      final var cur = path.get(i);
      final var isLast = (i == path.size() - 1);
      final var turningNext = !isLast && !sameDirection(path.get(i - 1), cur, path.get(i + 1));
      if (turningNext || isLast) {
        if (!segStart.equals(cur)) {
          out.add(Wire.create(segStart, cur));
        }
        segStart = cur;
      }
    }
  }

  private static boolean sameDirection(Location a, Location b, Location c) {
    final var dx1 = Integer.signum(b.getX() - a.getX());
    final var dy1 = Integer.signum(b.getY() - a.getY());
    final var dx2 = Integer.signum(c.getX() - b.getX());
    final var dy2 = Integer.signum(c.getY() - b.getY());
    return dx1 == dx2 && dy1 == dy2;
  }
}
