/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.annotate;

import static com.cburch.logisim.std.Strings.S;

import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.tools.AnnotateComponentTool;
import com.cburch.logisim.tools.AnnotateWireTool;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.tools.Tool;
import java.util.Arrays;
import java.util.List;

/**
 * Peler Edition Feature 5. A dedicated top-level library/category (shown in the component tree
 * alongside Base, Wiring, Gates, ...) holding {@link AnnotateComponentTool} and {@link
 * AnnotateWireTool} -- not folded into {@code BaseLibrary}, per explicit user request for "a new
 * category on the left". Registered in {@code com.cburch.logisim.std.Builtin}. See
 * docs/peler-edition/ROADMAP.md, Feature 5.
 *
 * <p>Neither tool is a plain {@link AddTool} (each creates its {@link Annotation} component
 * programmatically after a modal dialog, not by drag-and-drop placement), so {@link Annotation}
 * itself never appears in {@link #getTools()}. That means the default {@link
 * Library#contains(ComponentFactory)} / {@link Library#getTool(String)} -- which only look inside
 * {@code AddTool}s -- can never resolve it, breaking both {@code XmlWriter}'s save-time library
 * lookup ("Annotation component not found", found via live testing: any circuit with an
 * annotation failed to save) and {@code XmlReader}'s equivalent load-time lookup. Fixed the same
 * way {@code BaseLibrary} already solved this exact problem for {@code Text} (also created by a
 * bespoke {@code Tool}, not an {@code AddTool}): keep a private {@code AddTool} around purely so
 * these two overrides have something to delegate to.
 */
public class AnnotationLibrary extends Library {
  /**
   * Unique identifier of the library, used as reference in project files. Do NOT change as it
   * will prevent project files from loading.
   *
   * <p>Identifier value must MUST be unique string among all libraries.
   */
  public static final String _ID = "Annotation";

  private final List<Tool> tools = Arrays.asList(new AnnotateComponentTool(), new AnnotateWireTool());
  private final AddTool annotationAdder = new AddTool(Annotation.FACTORY);

  @Override
  public boolean contains(ComponentFactory query) {
    return super.contains(query) || (query instanceof Annotation);
  }

  @Override
  public Tool getTool(String name) {
    final var t = super.getTool(name);
    if (t == null && name.equals(Annotation._ID)) {
      return annotationAdder; // needed by XmlCircuitReader
    }
    return t;
  }

  @Override
  public String getDisplayName() {
    return S.get("annotationLibrary");
  }

  @Override
  public List<Tool> getTools() {
    return tools;
  }
}
