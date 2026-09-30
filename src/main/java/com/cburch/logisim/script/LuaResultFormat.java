/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.script;

import com.cburch.logisim.dsl.Comp;
import com.cburch.logisim.dsl.Net;
import com.cburch.logisim.dsl.Port;
import java.util.ArrayList;
import java.util.List;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaUserdata;
import org.luaj.vm2.LuaValue;

/**
 * Renders what a script returned as JSON, so a table or a component reads as data instead of
 * {@code table: 0x1f3a}. A returned string stays exactly as it is, since that is what most scripts
 * return and what the caller expects to see verbatim.
 */
final class LuaResultFormat {
  private static final int MAX_DEPTH = 6;

  private LuaResultFormat() {}

  static String format(LuaValue value) {
    if (value.isnil()) return "";
    if (value.type() == LuaValue.TSTRING || value.type() == LuaValue.TNUMBER) return value.tojstring();
    final var out = new StringBuilder();
    append(out, value, 0);
    return out.toString();
  }

  private static void append(StringBuilder out, LuaValue value, int depth) {
    if (value.isnil()) {
      out.append("null");
    } else if (value.isboolean()) {
      out.append(value.toboolean());
    } else if (value.type() == LuaValue.TNUMBER) {
      out.append(value.tojstring());
    } else if (value.type() == LuaValue.TSTRING) {
      quote(out, value.tojstring());
    } else if (value instanceof LuaTable table) {
      if (depth >= MAX_DEPTH) {
        quote(out, "...");
      } else {
        appendTable(out, table, depth);
      }
    } else if (value instanceof LuaUserdata data) {
      appendObject(out, data.userdata(), value);
    } else {
      quote(out, value.tojstring());
    }
  }

  private static void appendTable(StringBuilder out, LuaTable table, int depth) {
    final var length = table.length();
    var count = 0;
    var key = LuaValue.NIL;
    final var keys = new ArrayList<LuaValue>();
    while (true) {
      final var next = table.next(key);
      if (next.arg1().isnil()) break;
      key = next.arg1();
      keys.add(key);
      count++;
    }
    if (count == length) {
      out.append('[');
      for (var i = 1; i <= length; i++) {
        if (i > 1) out.append(',');
        append(out, table.get(i), depth + 1);
      }
      out.append(']');
      return;
    }
    keys.sort((a, b) -> a.tojstring().compareTo(b.tojstring()));
    out.append('{');
    var first = true;
    for (final var k : keys) {
      if (!first) out.append(',');
      first = false;
      quote(out, k.tojstring());
      out.append(':');
      append(out, table.get(k), depth + 1);
    }
    out.append('}');
  }

  private static void appendObject(StringBuilder out, Object object, LuaValue fallback) {
    if (object instanceof Comp comp) {
      out.append("{\"id\":");
      quote(out, comp.id());
      out.append(",\"kind\":");
      quote(out, comp.kind().key());
      comp.label().ifPresent(l -> {
        out.append(",\"label\":");
        quote(out, l);
      });
      out.append(",\"at\":");
      quote(out, comp.origin().toString());
      out.append('}');
    } else if (object instanceof Port port) {
      out.append("{\"port\":");
      quote(out, port.toString());
      out.append(",\"dir\":");
      quote(out, String.valueOf(port.dir()));
      out.append(",\"width\":").append(port.width());
      port.name().ifPresent(n -> {
        out.append(",\"name\":");
        quote(out, n);
      });
      port.desc().ifPresent(d -> {
        out.append(",\"desc\":");
        quote(out, d);
      });
      out.append(",\"at\":");
      quote(out, port.at().toString());
      port.net().ifPresent(n -> {
        out.append(",\"net\":");
        quote(out, n.id());
      });
      out.append('}');
    } else if (object instanceof Net net) {
      final List<String> members = new ArrayList<>();
      for (final var p : net.ports()) members.add(p.toString());
      out.append("{\"net\":");
      quote(out, net.id());
      out.append(",\"width\":").append(net.width()).append(",\"ports\":[");
      for (var i = 0; i < members.size(); i++) {
        if (i > 0) out.append(',');
        quote(out, members.get(i));
      }
      out.append("]}");
    } else {
      quote(out, fallback.tojstring());
    }
  }

  private static void quote(StringBuilder out, String text) {
    out.append('"');
    for (var i = 0; i < text.length(); i++) {
      final var c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
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
}
