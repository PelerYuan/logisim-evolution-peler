/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import java.util.List;
import java.util.Map;

/** Thrown by {@link Space#exportHtml(String)}: the interactive HTML export refuses rather than
 * silently producing a page that would compute the wrong answer (see
 * {@link com.cburch.logisim.gui.htmlexport.ExportHtml}'s own javadoc for the GUI's identical
 * refusal), so this names every unsupported component kind up front -- looking inside subcircuits
 * too, since a subcircuit is flattened into the page on export. */
public final class UnsupportedForHtmlExportException extends DslException {
  public UnsupportedForHtmlExportException(List<String> unsupportedKinds) {
    super(
        "circuit uses component kind(s) the HTML export cannot simulate: " + unsupportedKinds,
        Map.of("unsupportedKinds", unsupportedKinds),
        null);
  }
}
