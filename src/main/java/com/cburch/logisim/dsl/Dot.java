/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

/**
 * A point in Logisim's coordinate system. {@code col()}/{@code row()} are dot coordinates (raw
 * units divided by 10) and only make sense when the point is grid-aligned; {@code rawX()}/{@code
 * rawY()} are always readable, because the DSL can read half-grid points from old or
 * programmatically generated files even though it never produces them itself (see
 * docs/peler-edition/design/mcp-v2.md, 3.9).
 *
 * <p>There is no public constructor and no {@code Dot.at(x, y)} -- a {@code Dot} only comes from a
 * {@link Port}'s location, a placed {@link Comp}'s origin, or {@link WireOps#dotAt(int, int)}. That
 * asymmetry is what keeps a bare coordinate out of {@link Space#connect(Port, Port)}'s argument
 * list (invariant 1 in the design doc).
 */
public final class Dot {
  private final int rawX;
  private final int rawY;

  Dot(int rawX, int rawY) {
    this.rawX = rawX;
    this.rawY = rawY;
  }

  public boolean onGrid() {
    return rawX % 10 == 0 && rawY % 10 == 0;
  }

  public int col() {
    if (!onGrid()) throw new OffGridException(rawX, rawY);
    return rawX / 10;
  }

  public int row() {
    if (!onGrid()) throw new OffGridException(rawX, rawY);
    return rawY / 10;
  }

  public int rawX() {
    return rawX;
  }

  public int rawY() {
    return rawY;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Dot d && d.rawX == rawX && d.rawY == rawY;
  }

  @Override
  public int hashCode() {
    return 31 * rawX + rawY;
  }

  @Override
  public String toString() {
    return onGrid() ? "(" + col() + "," + row() + ")" : "(" + rawX + "," + rawY + " raw)";
  }
}
