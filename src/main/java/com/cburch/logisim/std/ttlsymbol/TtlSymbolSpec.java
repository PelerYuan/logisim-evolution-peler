/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.ttlsymbol;

import com.cburch.logisim.std.ttl.AbstractTtlGate;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Peler Edition. How one 74xx chip is laid out as a logic symbol: a rectangle with the inputs on
 * the left and the outputs on the right, the way a datasheet's logic diagram and a lecture slide
 * draw it, rather than as the DIP package upstream draws.
 *
 * <p>This is the only per-chip data the feature needs. The simulation, the state objects, the port
 * widths, the name in the toolbox and the caption on the box are all reused from the DIP factory,
 * which is possible because {@code AbstractTtlGate} decides a port's index from its pin number
 * alone and never from where the port sits. A symbol is the same ports in a different arrangement,
 * and the indices below are indices into that same port array.
 *
 * <p><b>A row carries a port index, not a name.</b> The name is looked up in the DIP factory's
 * {@code getPortNames}, so the pinout is stated once, in upstream's file, and an index typed wrong
 * here shows the wrong name on screen instead of quietly renaming a correct one. Two kinds of chip
 * need a label spelled out anyway, and both keep that protection:
 *
 * <ul>
 *   <li>a chip whose factory declares no names at all -- the gate arrays -- uses {@link Row#named},
 *       and {@code TtlSymbolLayoutTest} checks the factory really declares none;
 *   <li>a chip whose upstream name is a sentence rather than a pin symbol, such as {@code "MR/CLR
 *       (Reset, active LOW)"}, uses {@link Row#renamed}, which carries the upstream name alongside
 *       the short one so the test can assert the two still belong to the same index.
 * </ul>
 *
 * <p>What genuinely cannot be derived and so lives here: which side a port belongs on (outputs are
 * usually right, but a carry-out or an enable is a judgement call), the order down a side (pin
 * order interleaves A1 B1 A2 B2 while a symbol wants A1 A2 A3 A4), where the blank rows go that
 * separate one group from the next, and which ports are active low or are clocks.
 */
public record TtlSymbolSpec(Supplier<AbstractTtlGate> delegate, List<Row> left, List<Row> right) {

  /**
   * One row of the symbol. A row with a negative {@code index} is a blank spacer between groups and
   * carries no port.
   *
   * @param index the port index, the same number {@code propagateTtl} passes to {@code
   *     getPortValue}; negative for a spacer
   * @param label the short name to write beside the port; null to use the DIP factory's own name,
   *     which is what every chip that declares a usable one should do
   * @param upstreamName what the DIP factory calls this port, given only when {@code label}
   *     shortens it; null when there is nothing to check the label against
   * @param bubble draw the inversion circle of an active-low port
   * @param clock draw the clock wedge
   */
  public record Row(int index, String label, String upstreamName, boolean bubble, boolean clock) {

    /** A port, named as its DIP pinout names it. */
    public static Row of(int index) {
      return new Row(index, null, null, false, false);
    }

    /** As {@link #of}, drawn with the inversion circle of an active-low port. */
    public static Row inverted(int index) {
      return new Row(index, null, null, true, false);
    }

    /** As {@link #of}, drawn with the wedge of a clock port. */
    public static Row clock(int index) {
      return new Row(index, null, null, false, true);
    }

    /** A port on a chip whose factory declares no pin names, so the name is given here. */
    public static Row named(int index, String label) {
      return new Row(index, label, null, false, false);
    }

    /** As {@link #named}, drawn with the inversion circle of an active-low port. */
    public static Row namedInverted(int index, String label) {
      return new Row(index, label, null, true, false);
    }

    /**
     * A port whose upstream name is a description rather than a pin symbol, shortened to the symbol
     * the datasheet's logic diagram uses. The upstream name is repeated so the layout test can hold
     * the short name and the port index together.
     */
    public static Row renamed(int index, String label, String upstreamName) {
      return new Row(index, label, upstreamName, false, false);
    }

    /** As {@link #renamed}, drawn with the inversion circle of an active-low port. */
    public static Row renamedInverted(int index, String label, String upstreamName) {
      return new Row(index, label, upstreamName, true, false);
    }

    /** As {@link #renamed}, drawn with the wedge of a clock port. */
    public static Row renamedClock(int index, String label, String upstreamName) {
      return new Row(index, label, upstreamName, false, true);
    }

    /** A blank row, used to separate one group of ports from the next. */
    public static Row gap() {
      return new Row(-1, null, null, false, false);
    }

    public boolean isGap() {
      return index < 0;
    }
  }

  /** Every row that carries a port, both columns, in no particular order. */
  public List<Row> ports() {
    return Stream.concat(left.stream(), right.stream()).filter(row -> !row.isGap()).toList();
  }

  /** Number of rows the symbol is tall, blanks included. */
  public int rows() {
    return Math.max(left.size(), right.size());
  }

  /** The chip this is a symbol for. A record's own toString would be a screenful of rows. */
  @Override
  public String toString() {
    return "Sym" + delegate.get().getName();
  }
}
