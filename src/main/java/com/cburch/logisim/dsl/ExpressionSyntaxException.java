/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import com.cburch.logisim.analyze.model.ParserException;
import java.util.Map;

/** Thrown by {@link Space#synthesize(Synthesis)} when one output's boolean expression fails to
 * parse -- an unbalanced parenthesis, or a reference to a name never declared via {@link
 * Synthesis#input(String)}. Carries the offending output/expression/offset as structured fields
 * (design doc, 13.3) rather than leaving a caller to parse {@link #getMessage()}. */
public final class ExpressionSyntaxException extends DslException {
  public ExpressionSyntaxException(String output, String expression, ParserException cause) {
    super(
        "output \"" + output + "\": " + cause.getMessage() + " (at offset " + cause.getOffset() + ")",
        Map.of("output", output, "expression", expression, "offset", cause.getOffset()),
        null);
    initCause(cause);
  }
}
