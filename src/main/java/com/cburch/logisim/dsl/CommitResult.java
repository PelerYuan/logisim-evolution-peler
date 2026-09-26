/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import com.cburch.logisim.proj.Action;
import java.util.List;

/** What a {@link Space#commit(String)} call produced: the single undo-log entry it submitted, the
 * placed components with their final coordinates, and the nets now readable post-commit. */
public record CommitResult(Action action, List<Comp> placed, List<Net> nets) {}
