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

import com.cburch.logisim.tools.AnnotateTool;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.tools.Tool;
import java.util.Collections;
import java.util.List;

/**
 * Peler Edition Feature 5. A dedicated top-level library/category (shown in the component tree
 * alongside Base, Wiring, Gates, ...) holding just {@link AnnotateTool} -- not folded into {@code
 * BaseLibrary}, per explicit user request for "a new category on the left". Registered in {@code
 * com.cburch.logisim.std.Builtin}. See docs/peler-edition/ROADMAP.md, Feature 5.
 */
public class AnnotationLibrary extends Library {
  /**
   * Unique identifier of the library, used as reference in project files. Do NOT change as it
   * will prevent project files from loading.
   *
   * <p>Identifier value must MUST be unique string among all libraries.
   */
  public static final String _ID = "Annotation";

  private final List<Tool> tools = Collections.singletonList(new AnnotateTool());

  @Override
  public String getDisplayName() {
    return S.get("annotationLibrary");
  }

  @Override
  public List<Tool> getTools() {
    return tools;
  }
}
