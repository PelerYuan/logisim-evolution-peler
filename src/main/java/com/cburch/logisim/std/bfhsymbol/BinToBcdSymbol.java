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
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.std.bfh.BinToBcd;
import com.cburch.logisim.std.symbol.SymbolGate;
import com.cburch.logisim.std.symbol.SymbolLayout;
import com.cburch.logisim.std.symbol.SymbolRow;
import com.cburch.logisim.tools.key.BitWidthConfigurator;
import java.util.ArrayList;
import java.util.List;

/**
 * Peler Edition. The binary to BCD converter drawn as a logic symbol.
 *
 * <p>The component this redraws is a wide, short box with its digit outputs strung along the top
 * edge and the binary input out on the left. Here the input is on the left and the digits run down
 * the right, most significant first, which is the order they are read in.
 *
 * <p>Unlike the 74xx symbols this one changes shape: the number of BCD digits follows the width of
 * the binary input, so there is a layout per value of that attribute rather than one for the
 * component. {@link #layoutKey} is what keeps them apart.
 */
public class BinToBcdSymbol extends SymbolGate {

  public static final String _ID = "Sym" + BinToBcd._ID;

  private final BinToBcd delegate = new BinToBcd();

  public BinToBcdSymbol() {
    super(_ID, S.getter("Bin2BCD"));
    setAttributes(
        new Attribute[] {BinToBcd.ATTR_BinBits, StdAttr.FACING, StdAttr.LABEL, StdAttr.LABEL_FONT},
        new Object[] {BitWidth.create(9), Direction.EAST, "", StdAttr.DEFAULT_LABEL_FONT});
    setKeyConfigurator(new BitWidthConfigurator(BinToBcd.ATTR_BinBits, 4, 13, 0));
    setFacingAttribute(StdAttr.FACING);
  }

  /**
   * How many BCD digits a binary input of this width needs, worked out the way the delegate works
   * it out. Restating the formula rather than calling into the delegate would be a second copy of
   * the thing that decides the port count.
   */
  static int digitsFor(BitWidth bits) {
    return (int) (Math.log10(Math.pow(2.0, bits.getWidth())) + 1.0);
  }

  @Override
  protected Object layoutKey(AttributeSet attrs) {
    return attrs.getValue(BinToBcd.ATTR_BinBits);
  }

  @Override
  protected boolean affectsLayout(Attribute<?> attr) {
    return attr == BinToBcd.ATTR_BinBits;
  }

  @Override
  protected SymbolLayout buildLayout(AttributeSet attrs) {
    final var digits = digitsFor(attrs.getValue(BinToBcd.ATTR_BinBits));
    final var ports = digits + 1;
    final var labels = new String[ports];
    final var kinds = new int[ports];

    labels[0] = "Bin";
    kinds[0] = SymbolLayout.INPUT;
    // Port i carries the digit of weight 10^(i-1), which is how the delegate's propagate fills
    // them in. The label is that weight, the same text the original box writes above each pin.
    for (var i = 1; i < ports; i++) {
      labels[i] = Long.toString((long) Math.pow(10.0, i - 1));
      kinds[i] = SymbolLayout.OUTPUT;
    }

    // Most significant at the top, units at the bottom: the order the number is read in, and the
    // same left-to-right order the original box uses along its top edge.
    final var right = new ArrayList<SymbolRow>();
    for (var i = ports - 1; i >= 1; i--) right.add(named(i, labels[i]));

    return new SymbolLayout(
        "BIN/BCD", List.of(named(0, "Bin")), right, labels, new boolean[ports], kinds);
  }

  /** The binary input is as wide as the attribute says; every BCD digit is four bits. */
  @Override
  protected int portWidth(Instance instance, int index) {
    return index == 0 ? instance.getAttributeValue(BinToBcd.ATTR_BinBits).getWidth() : 4;
  }

  /** The component this is a second face of. Used by the tests to compare the two. */
  public BinToBcd getDelegate() {
    return delegate;
  }

  @Override
  public void propagate(InstanceState state) {
    delegate.propagate(state);
  }
}
