/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.script;

import java.util.Map;
import java.util.Optional;

/**
 * What {@link LuaSandbox#eval} throws for a script that raised an uncaught error. Wraps the Lua
 * error value rather than flattening it to a string (design doc, section 8): a
 * {@link com.cburch.logisim.dsl.DslException} that crossed into Lua and back out uncaught keeps its
 * {@code type}/{@code details}/{@code suggestion} fields intact here, exactly as
 * {@link LuaBindings} put them into the Lua error table.
 */
public final class ScriptException extends RuntimeException {
  private final String type;
  private final Map<String, Object> details;
  private final String suggestion;

  ScriptException(String message, String type, Map<String, Object> details, String suggestion) {
    super(message);
    this.type = type;
    this.details = details;
    this.suggestion = suggestion;
  }

  /** The originating exception's simple class name (e.g. {@code "RoutingException"}), or
   * {@code "LuaError"} for a plain script error with no structured cause. */
  public String type() {
    return type;
  }

  public Map<String, Object> details() {
    return details;
  }

  public Optional<String> suggestion() {
    return Optional.ofNullable(suggestion);
  }
}
