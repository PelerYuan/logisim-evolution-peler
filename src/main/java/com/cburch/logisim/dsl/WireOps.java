/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import java.util.List;

/**
 * The low-level wire API: exists so editing is never impossible, but deliberately not the path of
 * least resistance (design doc, invariant 3). {@link Space#connect(Port, Port)} stays the
 * ergonomic, validated way to wire two ports; everything here instead requires the caller to
 * already hold two {@link Dot}s and does none of the direction/width/exclusivity checking
 * {@code connect} does -- only the one guard {@link com.cburch.logisim.circuit.Wire#create} itself
 * skips (see design doc, 3.4): a diagonal pair throws instead of silently becoming a garbage wire.
 */
public interface WireOps {
  /** A raw placement coordinate, with no claim that anything there is connected (design doc,
   * invariant 1 -- this is the one legitimate way to turn bare integers into a {@link Dot}). */
  Dot dotAt(int col, int row);

  /** Escape hatch for a half-grid point read from an old or programmatically generated file. */
  Dot dotAt(int col, int row, boolean allowOffGrid);

  /** One axis-aligned wire segment. Throws {@link IllegalArgumentException} if {@code a} and
   * {@code b} share neither column nor row. */
  void add(Dot a, Dot b);

  /** A caller-supplied multi-bend path, decomposed into consecutive axis-aligned segments. */
  void add(List<Dot> path);

  /** True if a pending or already-placed component's port sits exactly at {@code d}. */
  boolean isOccupied(Dot d);
}
