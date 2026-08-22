/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.symbol;

import com.cburch.logisim.tools.Library;

/**
 * Peler Edition. A toolbox category holding nothing but {@link SymbolGate}s.
 *
 * <p>Its only job is to be recognisable. Saving to official {@code .circ} has to leave every symbol
 * out, library entry included, and the code that decides this lives in {@code PelerCompat} and
 * {@code XmlWriter} -- outside {@code std}, and with no business knowing which symbol families
 * exist. Naming each one there is how the first family's entry got missed for a while, producing
 * files upstream reported as unavailable. A family that extends this is covered the day it is
 * written.
 */
public abstract class SymbolLibrary extends Library {}
