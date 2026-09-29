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

/** Thrown when a script names an attribute the component does not have. */
public final class UnknownAttributeException extends DslException {
  public UnknownAttributeException(String attribute, String kindKey, List<String> valid) {
    super(
        "\"" + attribute + "\" is not an attribute of " + kindKey + "; it has: " + String.join(", ", valid),
        Map.of("attribute", attribute, "kind", kindKey, "valid", String.join(",", valid)),
        "space:describeKind(\"" + kindKey + "\") lists each attribute with its legal values");
  }
}
