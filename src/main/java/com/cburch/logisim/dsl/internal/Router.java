/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl.internal;

import com.cburch.logisim.dsl.Port;
import com.cburch.logisim.dsl.RoutingException;
import java.util.ArrayList;
import java.util.List;

/**
 * Not part of the DSL's public surface -- see {@link com.cburch.logisim.dsl.Space#commit}, which
 * calls it. At most two bends per leg, same as the editor's own wiring tool (design doc, section
 * 六); obstacles are every *foreign* port location, since crossing is safe but passing through
 * someone else's pin silently wires into it (design doc, 3.3). A star topology from one hub per net
 * is enough: Logisim merges wires that share an endpoint on its own (design doc, 3.2), so several
 * legs meeting at the same hub point behave exactly like one real junction.
 *
 * <p>An explicit {@code viaColumn}/{@code viaRow} hint is tried exactly once and throws if blocked
 * -- it is never silently replaced with a different column (design doc, invariant 4). With no hint,
 * the router is free to try alternatives before giving up.
 */
public final class Router {
  public record Segment(int x0, int y0, int x1, int y1) {}

  private static final int[] SEARCH_OFFSETS = {10, -10, 20, -20, 30, -30, 40, -40, 50, -50};

  public List<Segment> route(NetHandle net, List<Port> allPendingPorts) {
    final var members = net.members();
    if (members.size() < 2) return List.of();

    final var drivers = net.drivers();
    final var hub = drivers.isEmpty() ? members.get(0) : drivers.get(0);
    final var hubDot = hub.at();

    final var obstacles = new ArrayList<int[]>();
    for (final var p : allPendingPorts) {
      if (!net.contains(p)) obstacles.add(new int[] {p.at().rawX(), p.at().rawY()});
    }

    final var segments = new ArrayList<Segment>();
    for (final var member : members) {
      if (member == hub) continue;
      final var dot = member.at();
      segments.addAll(routeTwoPoints(net, hubDot.rawX(), hubDot.rawY(), dot.rawX(), dot.rawY(), obstacles));
    }
    return segments;
  }

  private List<Segment> routeTwoPoints(NetHandle net, int x0, int y0, int x1, int y1, List<int[]> obstacles) {
    if (x0 == x1 && y0 == y1) return List.of();

    if ((x0 == x1 || y0 == y1) && clear(x0, y0, x1, y1, obstacles)) {
      return List.of(new Segment(x0, y0, x1, y1));
    }

    if (net.viaColumn().isPresent()) {
      final var vx = net.viaColumn().get() * 10;
      if (clear(x0, y0, vx, y0, obstacles) && clear(vx, y0, vx, y1, obstacles) && clear(vx, y1, x1, y1, obstacles)) {
        return path(x0, y0, vx, y0, vx, y1, x1, y1);
      }
      throw new RoutingException(net.id(), vx, y0, "viaColumn " + net.viaColumn().get() + " is blocked by a foreign pin",
          "try a different viaColumn, or drop the hint and let the router choose");
    }
    if (net.viaRow().isPresent()) {
      final var vy = net.viaRow().get() * 10;
      if (clear(x0, y0, x0, vy, obstacles) && clear(x0, vy, x1, vy, obstacles) && clear(x1, vy, x1, y1, obstacles)) {
        return path(x0, y0, x0, vy, x1, vy, x1, y1);
      }
      throw new RoutingException(net.id(), x0, vy, "viaRow " + net.viaRow().get() + " is blocked by a foreign pin",
          "try a different viaRow, or drop the hint and let the router choose");
    }

    if (net.preferredSide().isPresent()) {
      final var side = net.preferredSide().get();
      final var vertical = side == NetHandle.Side.ABOVE || side == NetHandle.Side.BELOW;
      if (vertical) {
        if (clear(x0, y0, x0, y1, obstacles) && clear(x0, y1, x1, y1, obstacles)) {
          return path(x0, y0, x0, y1, x1, y1);
        }
        throw new RoutingException(net.id(), x0, y1, "preferred side " + side + " is blocked by a foreign pin",
            "drop the side hint and let the router choose, or use viaColumn/viaRow instead");
      } else {
        if (clear(x0, y0, x1, y0, obstacles) && clear(x1, y0, x1, y1, obstacles)) {
          return path(x0, y0, x1, y0, x1, y1);
        }
        throw new RoutingException(net.id(), x1, y0, "preferred side " + side + " is blocked by a foreign pin",
            "drop the side hint and let the router choose, or use viaColumn/viaRow instead");
      }
    }

    if (clear(x0, y0, x1, y0, obstacles) && clear(x1, y0, x1, y1, obstacles)) {
      return path(x0, y0, x1, y0, x1, y1);
    }
    if (clear(x0, y0, x0, y1, obstacles) && clear(x0, y1, x1, y1, obstacles)) {
      return path(x0, y0, x0, y1, x1, y1);
    }

    for (final var offset : SEARCH_OFFSETS) {
      final var vx = x1 + offset;
      if (clear(x0, y0, vx, y0, obstacles) && clear(vx, y0, vx, y1, obstacles) && clear(vx, y1, x1, y1, obstacles)) {
        return path(x0, y0, vx, y0, vx, y1, x1, y1);
      }
    }

    throw new RoutingException(net.id(), x1, y1,
        "no Manhattan path with at most two bends avoids every foreign pin", null);
  }

  private List<Segment> path(int... coords) {
    final var segments = new ArrayList<Segment>();
    for (var i = 0; i + 3 < coords.length; i += 2) {
      segments.add(new Segment(coords[i], coords[i + 1], coords[i + 2], coords[i + 3]));
    }
    return segments;
  }

  private boolean clear(int x0, int y0, int x1, int y1, List<int[]> obstacles) {
    final var minX = Math.min(x0, x1);
    final var maxX = Math.max(x0, x1);
    final var minY = Math.min(y0, y1);
    final var maxY = Math.max(y0, y1);
    for (final var o : obstacles) {
      if (o[0] >= minX && o[0] <= maxX && o[1] >= minY && o[1] <= maxY) return false;
    }
    return true;
  }
}
