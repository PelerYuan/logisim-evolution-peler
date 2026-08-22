/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.bfhsymbol;

import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.std.bfh.BcdToSevenSegmentDisplay;
import com.cburch.logisim.std.bfh.BfhLibrary;
import com.cburch.logisim.std.bfh.BinToBcd;
import com.cburch.logisim.std.symbol.SymbolGate;
import java.util.ArrayList;
import java.util.List;

/**
 * Peler Edition. One symbol, the component it redraws, and the attributes both are to be built
 * with, so the tests in this package all sweep the same set.
 *
 * <p>The binary converter appears once per input width. Its port count follows that attribute, so
 * "the symbol" is really ten symbols, and a check that only ever looked at the default width would
 * miss nine of them -- including the two ends of the range, where an off-by-one in the digit count
 * would show.
 */
record BfhSymbolCases(
    SymbolGate symbol,
    ComponentFactory delegate,
    AttributeSet symbolAttrs,
    AttributeSet delegateAttrs,
    String id) {

  /** The widths {@code BinToBcd.ATTR_BinBits} accepts, ends included. */
  static final int MIN_BITS = 4;

  static final int MAX_BITS = 13;

  static List<BfhSymbolCases> all() {
    final var cases = new ArrayList<BfhSymbolCases>();
    cases.add(sevenSegment());
    for (var bits = MIN_BITS; bits <= MAX_BITS; bits++) cases.add(binToBcd(bits));
    return cases;
  }

  static BfhSymbolCases sevenSegment() {
    final var symbol = new BcdToSevenSegmentSymbol();
    final var delegate = new BcdToSevenSegmentDisplay();
    return new BfhSymbolCases(
        symbol,
        delegate,
        symbol.createAttributeSet(),
        delegate.createAttributeSet(),
        BcdToSevenSegmentSymbol._ID);
  }

  static BfhSymbolCases binToBcd(int bits) {
    final var symbol = new BinToBcdSymbol();
    final var delegate = new BinToBcd();
    final var symbolAttrs = symbol.createAttributeSet();
    symbolAttrs.setValue(BinToBcd.ATTR_BinBits, BitWidth.create(bits));
    final var delegateAttrs = delegate.createAttributeSet();
    delegateAttrs.setValue(BinToBcd.ATTR_BinBits, BitWidth.create(bits));
    return new BfhSymbolCases(
        symbol, delegate, symbolAttrs, delegateAttrs, BinToBcdSymbol._ID + "/" + bits + "bit");
  }

  /** Which library each half lives in, which is what the fixture needs to name in the file. */
  static final String SYMBOL_LIBRARY = BfhSymbolLibrary._ID;

  static final String DELEGATE_LIBRARY = BfhLibrary._ID;

  @Override
  public String toString() {
    return id;
  }
}
