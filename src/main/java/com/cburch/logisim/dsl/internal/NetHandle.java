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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Not part of the DSL's public surface -- see {@link com.cburch.logisim.dsl.Net}, which wraps
 * exactly one of these and is what callers actually see. Holds the union-find membership, the
 * routing hints a script attaches before commit, and the routed path once commit has run. */
public final class NetHandle {
  /** Matches {@link com.cburch.logisim.dsl.Net}'s four directional hint methods. */
  public enum Side {
    ABOVE,
    BELOW,
    LEFT,
    RIGHT
  }

  private final String id;
  private final Set<Port> members = new LinkedHashSet<>();
  private Integer viaColumn;
  private Integer viaRow;
  private Side preferredSide;
  private boolean committed;
  private List<int[]> path;

  public NetHandle(String id) {
    this.id = id;
  }

  public String id() {
    return id;
  }

  public void add(Port p) {
    members.add(p);
  }

  public void addAll(NetHandle other) {
    members.addAll(other.members);
  }

  public List<Port> members() {
    return List.copyOf(members);
  }

  public boolean contains(Port p) {
    return members.contains(p);
  }

  public int width() {
    return members.isEmpty() ? 0 : members.iterator().next().width();
  }

  public List<Port> drivers() {
    final var out = new ArrayList<Port>();
    for (final var p : members) if (p.dir() == Port.Dir.OUT) out.add(p);
    if (!out.isEmpty()) return out;
    for (final var p : members) if (p.dir() == Port.Dir.INOUT) out.add(p);
    return out;
  }

  public void setViaColumn(int col) {
    this.viaColumn = col;
  }

  public void setViaRow(int row) {
    this.viaRow = row;
  }

  public void setPreferredSide(Side side) {
    this.preferredSide = side;
  }

  public Optional<Integer> viaColumn() {
    return Optional.ofNullable(viaColumn);
  }

  public Optional<Integer> viaRow() {
    return Optional.ofNullable(viaRow);
  }

  public Optional<Side> preferredSide() {
    return Optional.ofNullable(preferredSide);
  }

  public boolean isCommitted() {
    return committed;
  }

  public void markCommitted(List<int[]> routedPath) {
    this.committed = true;
    this.path = routedPath;
  }

  public List<int[]> path() {
    return path == null ? List.of() : path;
  }
}
