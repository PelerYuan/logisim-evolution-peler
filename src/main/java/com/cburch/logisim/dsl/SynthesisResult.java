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

/** What a {@link Space#synthesize(Synthesis)} call produced: the single undo-log entry it
 * submitted, and the generated components, already readable through the same {@link Space} (design
 * doc, section 十一 -- the whole point of the in-place refresh is that a caller does not have to
 * reopen a fresh {@link Space} just to see what it built). */
public record SynthesisResult(Action action, List<Comp> placed) {}
