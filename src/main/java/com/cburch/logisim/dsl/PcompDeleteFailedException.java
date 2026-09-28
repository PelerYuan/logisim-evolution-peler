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

public final class PcompDeleteFailedException extends DslException {
  public PcompDeleteFailedException(String path, String cause) {
    super(
        "could not delete \"" + path + "\": " + cause,
        Map.of("path", path, "cause", cause),
        null);
  }
}
