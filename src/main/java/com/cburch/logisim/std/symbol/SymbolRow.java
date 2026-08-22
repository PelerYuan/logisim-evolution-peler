/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.symbol;

/**
 * Peler Edition. One row of a logic symbol: a port on one side of the box, or a blank spacer
 * between two groups of them.
 *
 * <p><b>A row carries a port index, not a name.</b> Where a name can be read off the component
 * being redrawn it is left null and looked up there, so the pinout is stated once and an index
 * typed wrong in a layout table shows the wrong name on screen instead of quietly renaming a
 * correct port. The variants below are for the cases where that lookup cannot serve: a factory
 * that declares no names at all, and a name that is a sentence rather than a pin symbol.
 *
 * @param index the port index, the same number the delegate's propagate means by it; negative for
 *     a spacer
 * @param label the short name to write beside the port; null to use the delegate's own name
 * @param upstreamName what the delegate calls this port, given only when {@code label} shortens
 *     it, so a test can hold the two together; null when there is nothing to check against
 * @param bubble draw the inversion circle of an active-low port
 * @param clock draw the clock wedge
 */
public record SymbolRow(
    int index, String label, String upstreamName, boolean bubble, boolean clock) {

  /** A port, named as the delegate names it. */
  public static SymbolRow of(int index) {
    return new SymbolRow(index, null, null, false, false);
  }

  /** As {@link #of}, drawn with the inversion circle of an active-low port. */
  public static SymbolRow inverted(int index) {
    return new SymbolRow(index, null, null, true, false);
  }

  /** As {@link #of}, drawn with the wedge of a clock port. */
  public static SymbolRow clock(int index) {
    return new SymbolRow(index, null, null, false, true);
  }

  /** A port on a factory that declares no pin names, so the name is given here. */
  public static SymbolRow named(int index, String label) {
    return new SymbolRow(index, label, null, false, false);
  }

  /** As {@link #named}, drawn with the inversion circle of an active-low port. */
  public static SymbolRow namedInverted(int index, String label) {
    return new SymbolRow(index, label, null, true, false);
  }

  /**
   * A port whose upstream name is a description rather than a pin symbol, shortened to the symbol
   * the datasheet's logic diagram uses. The upstream name is repeated so a layout test can hold the
   * short name and the port index together.
   */
  public static SymbolRow renamed(int index, String label, String upstreamName) {
    return new SymbolRow(index, label, upstreamName, false, false);
  }

  /** As {@link #renamed}, drawn with the inversion circle of an active-low port. */
  public static SymbolRow renamedInverted(int index, String label, String upstreamName) {
    return new SymbolRow(index, label, upstreamName, true, false);
  }

  /** As {@link #renamed}, drawn with the wedge of a clock port. */
  public static SymbolRow renamedClock(int index, String label, String upstreamName) {
    return new SymbolRow(index, label, upstreamName, false, true);
  }

  /** A blank row, used to separate one group of ports from the next. */
  public static SymbolRow gap() {
    return new SymbolRow(-1, null, null, false, false);
  }

  public boolean isGap() {
    return index < 0;
  }
}
