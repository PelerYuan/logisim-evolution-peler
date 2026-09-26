/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Base of every exception the operation layer throws. Each subclass carries structured fields a
 * caller can act on programmatically -- callers must never resort to parsing {@link #getMessage()}
 * (design doc, section 五: "每个异常都带结构化字段... 绝不靠解析异常字符串").
 */
public abstract class DslException extends RuntimeException {
  private final Map<String, Object> details;
  private final String suggestion;

  protected DslException(String message, Map<String, Object> details, String suggestion) {
    super(message);
    this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    this.suggestion = suggestion;
  }

  /** Structured diagnostic fields, named in domain language (e.g. "port", "existingWidth"). */
  public Map<String, Object> details() {
    return details;
  }

  /**
   * A concrete retry suggestion, when the failure is one the router or resolver already knows how
   * to fix (design doc, 13.3) -- e.g. "use viaColumn(26)" or "did you mean 'sum'?".
   */
  public Optional<String> suggestion() {
    return Optional.ofNullable(suggestion);
  }
}
