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

/** Thrown by {@link Space#exportImage(String, String, double, boolean)} when the {@code format}
 * string is not one of the formats {@link com.cburch.logisim.gui.main.ExportImage} knows how to
 * write. */
public final class InvalidExportFormatException extends DslException {
  private static final List<String> VALID = List.of("png", "gif", "jpg", "svg", "tikz");

  public InvalidExportFormatException(String format) {
    super(
        "unknown image export format \"" + format + "\"; expected one of " + VALID,
        Map.of("format", format, "validFormats", VALID),
        "use one of " + VALID);
  }
}
