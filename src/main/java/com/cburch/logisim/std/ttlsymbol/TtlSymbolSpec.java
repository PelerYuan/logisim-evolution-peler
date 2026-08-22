/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.ttlsymbol;

import com.cburch.logisim.std.symbol.SymbolRow;
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
public record TtlSymbolSpec(Supplier<AbstractTtlGate> delegate, List<SymbolRow> left, List<SymbolRow> right) {

  /** Every row that carries a port, both columns, in no particular order. */
  public List<SymbolRow> ports() {
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
