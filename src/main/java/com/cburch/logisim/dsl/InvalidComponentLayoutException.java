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

/** Thrown by {@link Pcomp#saveAsComponent(String, String, String)} when the source circuit's own
 * pins do not make a saveable component -- mirrors the four {@link
 * com.cburch.logisim.pcomp.PortLayoutDraft.Problem} cases {@code PcompSaveDialog} reports on its
 * save button: no ports at all, a pin with no label (every port must be named before it can be
 * published), two pins sharing one label, or two ports landing on the same coordinate. Since
 * {@link Pcomp} always derives the layout automatically (see its class javadoc), only the first two
 * are actually reachable through it in practice -- the latter two are carried anyway so this stays
 * a complete mirror of {@code PortLayoutDraft.Problem} rather than a partial one. */
public final class InvalidComponentLayoutException extends DslException {
  public InvalidComponentLayoutException(String circuitName, String problem) {
    super(
        "circuit \"" + circuitName + "\" cannot be saved as a component: " + problem,
        Map.of("circuitName", circuitName, "problem", problem),
        null);
  }
}
