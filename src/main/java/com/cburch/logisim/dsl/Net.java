/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import com.cburch.logisim.dsl.internal.NetHandle;
import java.util.ArrayList;
import java.util.List;

/** A group of ports the DSL has unified into one connection, pending or committed. Before commit,
 * {@link #path()} is empty -- the DSL only knows the intended endpoints until the router runs
 * (design doc, section 六); after commit it is the router's own Manhattan path. */
public final class Net {
  private final NetHandle handle;

  Net(NetHandle handle) {
    this.handle = handle;
  }

  public String id() {
    return handle.id();
  }

  public int width() {
    return handle.width();
  }

  public List<Port> ports() {
    return handle.members();
  }

  public List<Port> drivers() {
    return handle.drivers();
  }

  public List<Dot> path() {
    final var dots = new ArrayList<Dot>();
    for (final var seg : handle.path()) {
      dots.add(new Dot(seg[0], seg[1]));
      dots.add(new Dot(seg[2], seg[3]));
    }
    return dots;
  }

  public boolean isCommitted() {
    return handle.isCommitted();
  }

  public Net preferAbove() {
    handle.setPreferredSide(NetHandle.Side.ABOVE);
    return this;
  }

  public Net preferBelow() {
    handle.setPreferredSide(NetHandle.Side.BELOW);
    return this;
  }

  public Net preferLeft() {
    handle.setPreferredSide(NetHandle.Side.LEFT);
    return this;
  }

  public Net preferRight() {
    handle.setPreferredSide(NetHandle.Side.RIGHT);
    return this;
  }

  public Net viaColumn(int col) {
    handle.setViaColumn(col);
    return this;
  }

  public Net viaRow(int row) {
    handle.setViaRow(row);
    return this;
  }

  NetHandle handle() {
    return handle;
  }

  @Override
  public String toString() {
    return handle.id();
  }
}
