/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.htmlexport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Peler Edition. Just enough JSON to write the exported netlist, so the export does not pull a
 * dependency in for one file format it only ever writes.
 *
 * <p>Ordering is preserved, which matters only because it makes an exported page readable when
 * something needs debugging by hand.
 */
final class HtmlJson {

  private final Map<String, Object> values = new LinkedHashMap<>();

  HtmlJson put(String key, Object value) {
    values.put(key, value);
    return this;
  }

  /**
   * Component attributes share the namespace with the exporter's own keys. Prefixing keeps an
   * attribute called "id" or "kind" from overwriting the entry's identity.
   */
  HtmlJson putAttribute(String key, Object value) {
    values.put("a_" + key, value);
    return this;
  }

  @Override
  public String toString() {
    final var out = new StringBuilder();
    write(values, out);
    return out.toString();
  }

  private static void write(Object value, StringBuilder out) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof HtmlJson json) {
      write(json.values, out);
    } else if (value instanceof Map<?, ?> map) {
      out.append('{');
      var first = true;
      for (final var entry : map.entrySet()) {
        if (!first) out.append(',');
        first = false;
        writeString(String.valueOf(entry.getKey()), out);
        out.append(':');
        write(entry.getValue(), out);
      }
      out.append('}');
    } else if (value instanceof List<?> list) {
      out.append('[');
      for (var i = 0; i < list.size(); i++) {
        if (i > 0) out.append(',');
        write(list.get(i), out);
      }
      out.append(']');
    } else if (value instanceof int[] array) {
      out.append('[');
      for (var i = 0; i < array.length; i++) {
        if (i > 0) out.append(',');
        out.append(array[i]);
      }
      out.append(']');
    } else if (value instanceof Number || value instanceof Boolean) {
      out.append(value);
    } else {
      writeString(String.valueOf(value), out);
    }
  }

  private static void writeString(String value, StringBuilder out) {
    out.append('"');
    for (var i = 0; i < value.length(); i++) {
      final var c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        // "</script" inside a string would end the block the netlist is embedded in.
        case '<' -> out.append("\\u003c");
        case '>' -> out.append("\\u003e");
        case '&' -> out.append("\\u0026");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  /** For text dropped straight into the page rather than into the netlist. */
  static String escapeText(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
  }
}
