/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.bfhsymbol;

import static com.cburch.logisim.std.Strings.S;

import com.cburch.logisim.std.symbol.SymbolLibrary;
import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.tools.Tool;
import java.util.List;

/**
 * Peler Edition. The two BFH converters again, drawn as logic symbols instead of in the shape of
 * the thing they drive.
 *
 * <p>A separate category for the same reason the TTL symbols have one: two toolbox entries pointing
 * at a single factory are indistinguishable downstream, because {@code AddTool.equals} compares
 * factories and {@code XmlWriter.findLibrary} hands a component to whichever library claims it
 * first. The second entry would exist on screen and nowhere else.
 */
public class BfhSymbolLibrary extends SymbolLibrary {
  /**
   * Unique identifier of the library, used as reference in project files. Do NOT change as it will
   * prevent project files from loading.
   */
  public static final String _ID = "BFH Symbols";

  private List<Tool> tools = null;

  @Override
  public String getDisplayName() {
    return S.get("bfhSymbolLibrary");
  }

  @Override
  public List<? extends Tool> getTools() {
    if (tools == null) {
      tools =
          List.of(
              new AddTool(new BinToBcdSymbol()), new AddTool(new BcdToSevenSegmentSymbol()));
    }
    return tools;
  }
}
