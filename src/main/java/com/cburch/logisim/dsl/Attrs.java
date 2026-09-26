/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** An immutable name-to-value bag, used both for {@link Placement#with(Attrs)} overrides and as
 * the read-only view {@link Comp#attrs()} returns. Names are Logisim's stable attribute names
 * (e.g. {@code "facing"}, {@code "width"}, {@code "inputs"}), never the localized display names. */
public final class Attrs {
  private final Map<String, Object> values;

  private Attrs(Map<String, Object> values) {
    this.values = values;
  }

  public static Attrs of() {
    return new Attrs(Map.of());
  }

  public static Attrs of(String name, Object value) {
    final var map = new LinkedHashMap<String, Object>();
    map.put(name, value);
    return new Attrs(map);
  }

  public static Attrs of(Object... namesAndValues) {
    if (namesAndValues.length % 2 != 0) {
      throw new IllegalArgumentException("Attrs.of expects name/value pairs");
    }
    final var map = new LinkedHashMap<String, Object>();
    for (var i = 0; i < namesAndValues.length; i += 2) {
      map.put((String) namesAndValues[i], namesAndValues[i + 1]);
    }
    return new Attrs(map);
  }

  static Attrs fromMap(Map<String, Object> values) {
    return new Attrs(new LinkedHashMap<>(values));
  }

  public Set<String> names() {
    return values.keySet();
  }

  public Object get(String name) {
    return values.get(name);
  }

  public boolean has(String name) {
    return values.containsKey(name);
  }

  Map<String, Object> asMap() {
    return values;
  }

  @Override
  public String toString() {
    return values.toString();
  }
}
