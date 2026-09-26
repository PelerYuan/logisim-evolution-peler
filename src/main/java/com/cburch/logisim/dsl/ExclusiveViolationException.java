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

/**
 * Thrown by {@link Space#connect(Port, Port)} when it would merge two already-established nets
 * through a port that may only ever belong to one net (an exclusive end, per {@link
 * Port#exclusive()} -- typically a driving output). Ordinary fan-out (the same output connected to
 * several loads across several {@code connect} calls) is not this: it keeps extending the *same*
 * net, which is exactly what a real wire junction does.
 */
public final class ExclusiveViolationException extends DslException {
  public ExclusiveViolationException(Port offending, String existingNetId, List<Port> existingNetMembers) {
    super(
        offending + " already belongs to net " + existingNetId + "; connecting it here would merge"
            + " two different nets through an exclusive port",
        Map.of("port", offending, "existingNetId", existingNetId, "existingNetMembers", existingNetMembers),
        null);
  }
}
