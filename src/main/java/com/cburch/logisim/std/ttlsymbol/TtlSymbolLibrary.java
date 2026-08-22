/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.ttlsymbol;

import static com.cburch.logisim.std.Strings.S;

import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.tools.Tool;
import java.util.ArrayList;
import java.util.List;

/**
 * Peler Edition. The 74xx chips again, drawn as logic symbols instead of DIP packages.
 *
 * <p>A separate category rather than an attribute on the existing chips, because two toolbox
 * entries pointing at one factory are indistinguishable downstream: {@code AddTool.equals} compares
 * factories, and {@code XmlWriter.findLibrary} hands a component to whichever library it finds
 * first. The second entry would exist on screen and nowhere else.
 *
 * <p>The layouts live in {@link TtlSymbolLayouts}. Adding a chip means adding one entry to the
 * list there and nothing else: the id, the name in the toolbox and the caption on the box all come
 * from the chip it delegates to.
 */
public class TtlSymbolLibrary extends Library {
  /**
   * Unique identifier of the library, used as reference in project files. Do NOT change as it will
   * prevent project files from loading.
   */
  public static final String _ID = "TTL Symbols";

  private List<Tool> tools = null;

  @Override
  public String getDisplayName() {
    return S.get("ttlSymbolLibrary");
  }

  @Override
  public List<? extends Tool> getTools() {
    if (tools == null) {
      final var built = new ArrayList<Tool>();
      for (final var spec : TtlSymbolLayouts.SPECS) built.add(new AddTool(new TtlSymbolGate(spec)));
      tools = built;
    }
    return tools;
  }
}
