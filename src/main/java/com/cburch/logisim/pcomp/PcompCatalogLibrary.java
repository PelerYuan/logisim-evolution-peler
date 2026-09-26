/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import static com.cburch.logisim.std.Strings.S;

import com.cburch.logisim.tools.Library;
import com.cburch.logisim.tools.Tool;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Peler Edition. The toolbox category holding the user's own components.
 *
 * <p>Flat on purpose: the tools of every installed component sit directly in this one category
 * rather than each component being a sub-library of its own. The reason is how a project file
 * records what it uses -- {@code LibraryManager.getDescriptor} writes a built-in library as
 * {@code #Name} and reads it back with {@code Builtin.getLibrary(name)}, which looks one level
 * down and no further. A nested component would be written as a reference nothing could resolve.
 *
 * <p>Consequently two installed components may not share a circuit name; the second one is left
 * out and reported. That is a real restriction and it belongs to the file format, not to this
 * class: {@code <comp lib="0" name="Adder"/>} names the tool and nothing else.
 *
 * <p>Nothing is read until the toolbox asks. See {@link PcompCatalog#installed} for why the scan
 * cannot happen in a constructor.
 */
public class PcompCatalogLibrary extends Library {
  /**
   * Unique identifier of the library, used as reference in project files. Do NOT change as it will
   * prevent project files from loading.
   */
  public static final String _ID = "My Components";

  @Override
  public String getDisplayName() {
    return S.get("pcompLibrary");
  }

  @Override
  public List<? extends Tool> getTools() {
    return toolsOf(PcompCatalog.installed());
  }

  /** Flattens the installed components into one category, first claim on a name winning. */
  static List<Tool> toolsOf(List<PcompComponent> components) {
    final var tools = new ArrayList<Tool>();
    final var taken = new HashSet<String>();
    for (final var component : components) {
      for (final var tool : component.getTools()) {
        if (taken.add(tool.getName())) tools.add(tool);
      }
    }
    return tools;
  }
}
