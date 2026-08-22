/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.bfhsymbol;

import static com.cburch.logisim.std.Strings.S;
import static com.cburch.logisim.std.symbol.SymbolRow.named;

import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.std.bfh.BcdToSevenSegmentDisplay;
import com.cburch.logisim.std.symbol.SymbolGate;
import com.cburch.logisim.std.symbol.SymbolLayout;
import com.cburch.logisim.std.symbol.SymbolRow;
import java.util.ArrayList;
import java.util.List;

/**
 * Peler Edition. The BCD to seven-segment decoder drawn as a logic symbol.
 *
 * <p>The component this redraws places its seven outputs where the segments sit on a display: a and
 * b along the top edge, f and g also along the top, c, d and e along the bottom, and the BCD input
 * underneath. That is a picture of the display, not of the function, and reading it means holding
 * the segment map in your head. Here the input is on the left and a to g run down the right in
 * order.
 *
 * <p>The decoding itself is not restated -- {@code propagate} hands to the delegate, whose logic
 * addresses its ports by index and never by position.
 */
public class BcdToSevenSegmentSymbol extends SymbolGate {

  public static final String _ID = "Sym" + BcdToSevenSegmentDisplay._ID;

  /** The symbol never changes shape, so one layout serves every attribute set. */
  private static final Object ONE_SHAPE = new Object();

  /** Segment names, in port-index order: index 0 is segment a, index 6 is segment g. */
  private static final String[] SEGMENTS = {"a", "b", "c", "d", "e", "f", "g"};

  private final BcdToSevenSegmentDisplay delegate = new BcdToSevenSegmentDisplay();

  public BcdToSevenSegmentSymbol() {
    super(_ID, S.getter("BCD2SevenSegment"));
    setAttributes(
        new Attribute[] {StdAttr.FACING, StdAttr.LABEL, StdAttr.LABEL_FONT},
        new Object[] {Direction.EAST, "", StdAttr.DEFAULT_LABEL_FONT});
    setFacingAttribute(StdAttr.FACING);
  }

  @Override
  protected Object layoutKey(AttributeSet attrs) {
    return ONE_SHAPE;
  }

  @Override
  protected SymbolLayout buildLayout(AttributeSet attrs) {
    final var ports = BcdToSevenSegmentDisplay.BCD_IN + 1;
    final var labels = new String[ports];
    final var kinds = new int[ports];
    for (var i = 0; i < SEGMENTS.length; i++) {
      labels[i] = SEGMENTS[i];
      kinds[i] = SymbolLayout.OUTPUT;
    }
    labels[BcdToSevenSegmentDisplay.BCD_IN] = "BCD";
    kinds[BcdToSevenSegmentDisplay.BCD_IN] = SymbolLayout.INPUT;

    final var right = new ArrayList<SymbolRow>();
    for (var i = 0; i < SEGMENTS.length; i++) right.add(named(i, SEGMENTS[i]));

    return new SymbolLayout(
        "BCD/7SEG",
        List.of(named(BcdToSevenSegmentDisplay.BCD_IN, "BCD")),
        right,
        labels,
        new boolean[ports],
        kinds);
  }

  /** The BCD input is a four-bit bus; every segment output is one wire. */
  @Override
  protected int portWidth(Instance instance, int index) {
    return index == BcdToSevenSegmentDisplay.BCD_IN ? 4 : 1;
  }

  /** The component this is a second face of. Used by the tests to compare the two. */
  public BcdToSevenSegmentDisplay getDelegate() {
    return delegate;
  }

  @Override
  public void propagate(InstanceState state) {
    delegate.propagate(state);
  }
}
