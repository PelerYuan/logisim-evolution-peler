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

/** Thrown by {@link Kind#of(Space, String)} for a key that resolves to nothing. Carries the
 * nearest known keys so a typo can be corrected without a round trip to {@link Kind#available}. */
public final class UnknownKindException extends DslException {
  public UnknownKindException(String key, List<String> nearKeys) {
    super(
        "unknown kind \"" + key + "\""
            + (nearKeys.isEmpty() ? "" : "; did you mean " + nearKeys + "?"),
        Map.of("key", key, "nearKeys", nearKeys),
        nearKeys.isEmpty() ? null : "try Kind.of(space, \"" + nearKeys.get(0) + "\")");
  }
}
