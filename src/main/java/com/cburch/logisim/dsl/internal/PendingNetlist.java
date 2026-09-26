/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl.internal;

import com.cburch.logisim.dsl.ExclusiveViolationException;
import com.cburch.logisim.dsl.Port;
import com.cburch.logisim.dsl.PortDirectionException;
import com.cburch.logisim.dsl.WidthMismatchException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Not part of the DSL's public surface -- see {@link com.cburch.logisim.dsl.Space#connect} and
 * {@link com.cburch.logisim.dsl.Net}, which are. A pending union-find over ports: before commit,
 * Logisim has no netlist to query at all (design doc, section 六), so every direction/width/
 * exclusivity check has to run against this instead, and has to run *before* commit so the error
 * surfaces immediately rather than as a silently wrong circuit (design doc, invariant 4).
 */
public final class PendingNetlist {
  private final Map<Port, NetHandle> byPort = new LinkedHashMap<>();
  private int nextNetId;

  public NetHandle connect(Port a, Port b) {
    if (a.width() != b.width()) {
      throw new WidthMismatchException(a, b);
    }

    final var netA = byPort.get(a);
    final var netB = byPort.get(b);

    // Checked against the *resulting* net, not just the pair passed in: connecting a fresh output
    // into a net that already has a driver several hops away is exactly as much a conflict as
    // connecting two outputs directly, and a pairwise-only check would miss it.
    final var resultingDrivers = new LinkedHashSet<Port>();
    if (netA != null) resultingDrivers.addAll(netA.drivers());
    else if (isDriver(a)) resultingDrivers.add(a);
    if (netB != null) resultingDrivers.addAll(netB.drivers());
    else if (isDriver(b)) resultingDrivers.add(b);
    if (resultingDrivers.size() > 1) {
      final var it = resultingDrivers.iterator();
      throw new PortDirectionException(it.next(), it.next());
    }

    if (netA != null && netB != null && netA != netB) {
      if (a.exclusive() || b.exclusive()) {
        final var offending = a.exclusive() ? a : b;
        final var existing = a.exclusive() ? netA : netB;
        throw new ExclusiveViolationException(offending, existing.id(), existing.members());
      }
      netA.addAll(netB);
      for (final var member : netB.members()) byPort.put(member, netA);
      return netA;
    }
    if (netA != null) {
      netA.add(b);
      byPort.put(b, netA);
      return netA;
    }
    if (netB != null) {
      netB.add(a);
      byPort.put(a, netB);
      return netB;
    }
    final var fresh = new NetHandle("net_" + (nextNetId++));
    fresh.add(a);
    fresh.add(b);
    byPort.put(a, fresh);
    byPort.put(b, fresh);
    return fresh;
  }

  /** Registers a net discovered by reading an already-drawn circuit (P4, design doc section 十一)
   * rather than built up through {@link #connect}: the connection is already real and already
   * valid Logisim state, so none of {@code connect}'s width/direction/exclusivity checks apply --
   * this only has to make {@link #netOf} find it. {@code committedPath} is the net's real routed
   * geometry, so {@link com.cburch.logisim.dsl.Net#isCommitted()} and {@code path()} read true and
   * correct for it immediately, exactly as they would for a net this session committed itself. */
  public NetHandle seedExisting(String id, List<Port> members, List<int[]> committedPath) {
    final var handle = new NetHandle(id);
    for (final var member : members) {
      handle.add(member);
      byPort.put(member, handle);
    }
    handle.markCommitted(committedPath);
    return handle;
  }

  public Optional<NetHandle> netOf(Port p) {
    return Optional.ofNullable(byPort.get(p));
  }

  public List<NetHandle> allNets() {
    final var seen = new ArrayList<NetHandle>();
    for (final var net : byPort.values()) {
      if (!seen.contains(net)) seen.add(net);
    }
    return seen;
  }

  public void disconnect(NetHandle net) {
    for (final var member : net.members()) byPort.remove(member);
  }

  private static boolean isDriver(Port p) {
    return p.dir() == Port.Dir.OUT || p.dir() == Port.Dir.INOUT;
  }
}
