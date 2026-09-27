/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import java.util.Map;

/** Thrown by {@link Space#exportImage(String, String, double, boolean)} and
 * {@link Space#exportHtml(String)} when writing the destination file fails -- wraps whatever
 * {@link java.io.IOException} the underlying exporter raised (a missing parent directory, a
 * read-only path, a full disk...) as a structured field rather than a bare stack trace, since a
 * script's {@code pcall} only ever sees what {@link DslException} exposes. */
public final class ExportFailedException extends DslException {
  public ExportFailedException(String path, String cause) {
    super(
        "could not export to \"" + path + "\": " + cause,
        Map.of("path", path, "cause", cause),
        null);
  }
}
