/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.script;

import com.cburch.logisim.dsl.Attrs;
import com.cburch.logisim.dsl.CheckReport;
import com.cburch.logisim.dsl.Comp;
import com.cburch.logisim.dsl.Dot;
import com.cburch.logisim.dsl.DslException;
import com.cburch.logisim.dsl.Kind;
import com.cburch.logisim.dsl.Net;
import com.cburch.logisim.dsl.Placement;
import com.cburch.logisim.dsl.Port;
import com.cburch.logisim.dsl.Space;
import com.cburch.logisim.dsl.WireOps;
import java.util.ArrayList;
import java.util.List;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaUserdata;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * Hand-written {@code Space}/{@code Comp}/{@code Port}/{@code Dot}/{@code Net} bindings (design doc,
 * section 8) -- deliberately not LuaJ's {@code luajava} reflective binding, which is exactly the
 * RCE surface the sandbox exists to avoid. Every wrapped Java object is a {@link LuaUserdata} whose
 * metatable's {@code __index} is a table of closures built for that one instance; a
 * {@link DslException} raised inside a bound call is converted to a Lua table error (design doc,
 * 13.3) instead of flattening to a string, so a script's {@code pcall} sees the same
 * {@code type}/{@code details}/{@code suggestion} fields {@link ScriptException} exposes on the
 * Java side.
 *
 * <p>Every bound method uses colon-call convention: {@code self} arrives as Lua argument 1 but is
 * never read from it -- the wrapping closure already captured the real Java object -- so method
 * bodies index real arguments from {@link Varargs#subargs(int)} starting at 2.
 */
final class LuaBindings {
  private LuaBindings() {}

  @FunctionalInterface
  private interface Method {
    Varargs call(Varargs args);
  }

  private static LuaValue bind(Method m) {
    return new VarArgFunction() {
      @Override
      public Varargs invoke(Varargs args) {
        try {
          return m.call(args);
        } catch (DslException e) {
          throw new LuaError(errorTable(e));
        }
      }
    };
  }

  static LuaValue errorTable(DslException e) {
    final var t = new LuaTable();
    t.set("type", e.getClass().getSimpleName());
    t.set("message", e.getMessage());
    final var details = new LuaTable();
    for (final var entry : e.details().entrySet()) {
      details.set(entry.getKey(), toLua(entry.getValue()));
    }
    t.set("details", details);
    e.suggestion().ifPresent(s -> t.set("suggestion", s));
    final var meta = new LuaTable();
    meta.set("__tostring", bind(args -> LuaValue.valueOf(e.getMessage())));
    t.setmetatable(meta);
    return t;
  }

  private static LuaValue toLua(Object value) {
    if (value == null) return LuaValue.NIL;
    if (value instanceof Comp c) return wrap(c);
    if (value instanceof Port p) return wrap(p);
    if (value instanceof Net n) return wrap(n);
    if (value instanceof Dot d) return wrap(d);
    if (value instanceof Integer i) return LuaValue.valueOf(i);
    if (value instanceof Boolean b) return LuaValue.valueOf(b);
    if (value instanceof List<?> list) {
      final var t = new LuaTable();
      for (var i = 0; i < list.size(); i++) t.set(i + 1, toLua(list.get(i)));
      return t;
    }
    return LuaValue.valueOf(value.toString());
  }

  private static Attrs attrsFromTable(LuaTable table) {
    final var pairs = new ArrayList<Object>();
    LuaValue key = LuaValue.NIL;
    while (true) {
      final var next = table.next(key);
      if (next.arg1().isnil()) break;
      key = next.arg1();
      pairs.add(key.tojstring());
      pairs.add(next.arg(2).tojstring());
    }
    return Attrs.of(pairs.toArray());
  }

  // ---- Dot ----------------------------------------------------------------

  static LuaValue wrap(Dot dot) {
    final var methods = new LuaTable();
    methods.set("col", bind(a -> LuaValue.valueOf(dot.col())));
    methods.set("row", bind(a -> LuaValue.valueOf(dot.row())));
    methods.set("onGrid", bind(a -> LuaValue.valueOf(dot.onGrid())));
    methods.set("rawX", bind(a -> LuaValue.valueOf(dot.rawX())));
    methods.set("rawY", bind(a -> LuaValue.valueOf(dot.rawY())));
    final var meta = new LuaTable();
    meta.set("__index", methods);
    meta.set("__tostring", bind(a -> LuaValue.valueOf(dot.toString())));
    return new LuaUserdata(dot, meta);
  }

  // ---- Port -----------------------------------------------------------------

  static LuaValue wrap(Port port) {
    final var methods = new LuaTable();
    methods.set("owner", bind(a -> wrap(port.owner())));
    methods.set("index", bind(a -> LuaValue.valueOf(port.index())));
    methods.set("dir", bind(a -> LuaValue.valueOf(port.dir().name())));
    methods.set("width", bind(a -> LuaValue.valueOf(port.width())));
    methods.set("exclusive", bind(a -> LuaValue.valueOf(port.exclusive())));
    methods.set("name", bind(a -> port.name().<LuaValue>map(LuaValue::valueOf).orElse(LuaValue.NIL)));
    methods.set("desc", bind(a -> port.desc().<LuaValue>map(LuaValue::valueOf).orElse(LuaValue.NIL)));
    methods.set("at", bind(a -> wrap(port.at())));
    methods.set("net", bind(a -> port.net().<LuaValue>map(LuaBindings::wrap).orElse(LuaValue.NIL)));
    methods.set("isConnected", bind(a -> LuaValue.valueOf(port.isConnected())));
    final var meta = new LuaTable();
    meta.set("__index", methods);
    meta.set("__tostring", bind(a -> LuaValue.valueOf(port.toString())));
    return new LuaUserdata(port, meta);
  }

  private static Port unwrapPort(Varargs rest, int i) {
    return (Port) rest.checkuserdata(i, Port.class);
  }

  // ---- Net --------------------------------------------------------------

  static LuaValue wrap(Net net) {
    final var methods = new LuaTable();
    methods.set("id", bind(a -> LuaValue.valueOf(net.id())));
    methods.set("width", bind(a -> LuaValue.valueOf(net.width())));
    methods.set("ports", bind(a -> toLua(net.ports())));
    methods.set("drivers", bind(a -> toLua(net.drivers())));
    methods.set("path", bind(a -> toLua(net.path())));
    methods.set("isCommitted", bind(a -> LuaValue.valueOf(net.isCommitted())));
    methods.set("preferAbove", bind(a -> {
      net.preferAbove();
      return a.arg1();
    }));
    methods.set("preferBelow", bind(a -> {
      net.preferBelow();
      return a.arg1();
    }));
    methods.set("preferLeft", bind(a -> {
      net.preferLeft();
      return a.arg1();
    }));
    methods.set("preferRight", bind(a -> {
      net.preferRight();
      return a.arg1();
    }));
    methods.set("viaColumn", bind(a -> {
      net.viaColumn(a.subargs(2).checkint(1));
      return a.arg1();
    }));
    methods.set("viaRow", bind(a -> {
      net.viaRow(a.subargs(2).checkint(1));
      return a.arg1();
    }));
    final var meta = new LuaTable();
    meta.set("__index", methods);
    meta.set("__tostring", bind(a -> LuaValue.valueOf(net.toString())));
    return new LuaUserdata(net, meta);
  }

  private static Net unwrapNet(Varargs rest, int i) {
    return (Net) rest.checkuserdata(i, Net.class);
  }

  // ---- Comp ---------------------------------------------------------------

  static LuaValue wrap(Comp comp) {
    final var methods = new LuaTable();
    methods.set("kind", bind(a -> LuaValue.valueOf(comp.kind().key())));
    methods.set("id", bind(a -> LuaValue.valueOf(comp.id())));
    methods.set("label", bind(a -> comp.label().<LuaValue>map(LuaValue::valueOf).orElse(LuaValue.NIL)));
    methods.set("setLabel", bind(a -> {
      comp.label(a.subargs(2).checkjstring(1));
      return a.arg1();
    }));
    methods.set("ports", bind(a -> toLua(comp.ports())));
    methods.set("port", bind(a -> {
      final var rest = a.subargs(2);
      return rest.isstring(1) ? wrap(comp.port(rest.checkjstring(1))) : wrap(comp.port(rest.checkint(1)));
    }));
    methods.set("inputs", bind(a -> toLua(comp.inputs())));
    methods.set("outputs", bind(a -> toLua(comp.outputs())));
    methods.set("attrs", bind(a -> {
      final var t = new LuaTable();
      final var attrs = comp.attrs();
      for (final var name : attrs.names()) {
        final var value = attrs.get(name);
        t.set(name, value == null ? LuaValue.NIL : LuaValue.valueOf(value.toString()));
      }
      return t;
    }));
    methods.set("set", bind(a -> {
      final var rest = a.subargs(2);
      comp.set(rest.checkjstring(1), rest.checkjstring(2));
      return a.arg1();
    }));
    methods.set("facing", bind(a -> {
      comp.set("facing", a.subargs(2).checkjstring(1));
      return a.arg1();
    }));
    methods.set("origin", bind(a -> wrap(comp.origin())));
    methods.set("bounds", bind(a -> {
      final var b = comp.bounds();
      final var t = new LuaTable();
      t.set("x", b.getX());
      t.set("y", b.getY());
      t.set("width", b.getWidth());
      t.set("height", b.getHeight());
      return t;
    }));
    final var meta = new LuaTable();
    meta.set("__index", methods);
    meta.set("__tostring", bind(a -> LuaValue.valueOf(comp.toString())));
    return new LuaUserdata(comp, meta);
  }

  private static Comp unwrapComp(Varargs rest, int i) {
    return (Comp) rest.checkuserdata(i, Comp.class);
  }

  // ---- Placement ------------------------------------------------------------

  private static LuaValue wrap(Placement placement) {
    final var self = new LuaUserdata[1];
    final var methods = new LuaTable();
    methods.set("at", bind(a -> {
      final var rest = a.subargs(2);
      placement.at(rest.checkint(1), rest.checkint(2));
      return self[0];
    }));
    methods.set("anchorAt", bind(a -> {
      final var rest = a.subargs(2);
      placement.anchorAt(rest.checkint(1), rest.checkint(2));
      return self[0];
    }));
    methods.set("rightOf", bind(a -> {
      final var rest = a.subargs(2);
      placement.rightOf(unwrapComp(rest, 1), rest.checkint(2));
      return self[0];
    }));
    methods.set("below", bind(a -> {
      final var rest = a.subargs(2);
      placement.below(unwrapComp(rest, 1), rest.checkint(2));
      return self[0];
    }));
    methods.set("with", bind(a -> {
      placement.with(attrsFromTable(a.subargs(2).checktable(1)));
      return self[0];
    }));
    methods.set("facing", bind(a -> {
      placement.facing(com.cburch.logisim.data.Direction.parse(a.subargs(2).checkjstring(1)));
      return self[0];
    }));
    methods.set("place", bind(a -> wrap(placement.place())));
    final var meta = new LuaTable();
    meta.set("__index", methods);
    final var userdata = new LuaUserdata(placement, meta);
    self[0] = userdata;
    return userdata;
  }

  // ---- WireOps ------------------------------------------------------------

  private static LuaValue wrap(WireOps wires) {
    final var methods = new LuaTable();
    methods.set("dotAt", bind(a -> {
      final var rest = a.subargs(2);
      return wrap(wires.dotAt(rest.checkint(1), rest.checkint(2)));
    }));
    methods.set("add", bind(a -> {
      final var rest = a.subargs(2);
      wires.add((Dot) rest.checkuserdata(1, Dot.class), (Dot) rest.checkuserdata(2, Dot.class));
      return LuaValue.NONE;
    }));
    methods.set("isOccupied", bind(a -> {
      final var rest = a.subargs(2);
      return LuaValue.valueOf(wires.isOccupied((Dot) rest.checkuserdata(1, Dot.class)));
    }));
    final var meta = new LuaTable();
    meta.set("__index", methods);
    return new LuaUserdata(wires, meta);
  }

  // ---- Space ----------------------------------------------------------------

  static LuaValue wrap(Space space, Runnable onCommit) {
    final var methods = new LuaTable();
    methods.set("circuitName", bind(a -> LuaValue.valueOf(space.circuitName())));
    methods.set("summary", bind(a -> {
      final var s = space.summary();
      final var t = new LuaTable();
      final var counts = new LuaTable();
      for (final var e : s.countsByKind().entrySet()) counts.set(e.getKey(), e.getValue());
      t.set("countsByKind", counts);
      t.set("inputPins", s.inputPins());
      t.set("outputPins", s.outputPins());
      return t;
    }));
    methods.set("components", bind(a -> toLua(space.components())));
    methods.set("componentsOf", bind(a -> {
      final var key = a.subargs(2).checkjstring(1);
      return toLua(space.componentsOf(Kind.of(space, key)));
    }));
    methods.set("byLabel", bind(a -> {
      final var label = a.subargs(2).checkjstring(1);
      return space.byLabel(label).<LuaValue>map(LuaBindings::wrap).orElse(LuaValue.NIL);
    }));
    methods.set("byId", bind(a -> {
      final var id = a.subargs(2).checkjstring(1);
      return space.byId(id).<LuaValue>map(LuaBindings::wrap).orElse(LuaValue.NIL);
    }));
    methods.set("near", bind(a -> {
      final var rest = a.subargs(2);
      return toLua(space.near(unwrapComp(rest, 1), rest.checkint(2)));
    }));
    methods.set("nets", bind(a -> toLua(space.nets())));
    methods.set("check", bind(a -> wrap(space.check())));
    methods.set("place", bind(a -> {
      final var key = a.subargs(2).checkjstring(1);
      return wrap(space.place(Kind.of(space, key)));
    }));
    methods.set("connect", bind(a -> {
      final var rest = a.subargs(2);
      final var port = unwrapPort(rest, 1);
      if (rest.touserdata(2) instanceof Net) {
        return wrap(space.connect(port, unwrapNet(rest, 2)));
      }
      return wrap(space.connect(port, unwrapPort(rest, 2)));
    }));
    methods.set("remove", bind(a -> {
      space.remove(unwrapComp(a.subargs(2), 1));
      return LuaValue.NONE;
    }));
    methods.set("disconnect", bind(a -> {
      space.disconnect(unwrapNet(a.subargs(2), 1));
      return LuaValue.NONE;
    }));
    methods.set("wires", bind(a -> wrap(space.wires())));
    methods.set("commit", bind(a -> {
      final var name = a.subargs(2).checkjstring(1);
      final var result = space.commit(name);
      onCommit.run();
      final var t = new LuaTable();
      t.set("placed", toLua(result.placed()));
      t.set("nets", toLua(result.nets()));
      return t;
    }));
    methods.set("rollback", bind(a -> {
      space.rollback();
      return LuaValue.NONE;
    }));
    methods.set("isDirty", bind(a -> LuaValue.valueOf(space.isDirty())));
    final var meta = new LuaTable();
    meta.set("__index", methods);
    return new LuaUserdata(space, meta);
  }

  private static LuaValue wrap(CheckReport report) {
    final var t = new LuaTable();
    t.set("ok", LuaValue.valueOf(report.ok()));
    t.set("unconnected", toLua(report.unconnected()));
    t.set("undriven", toLua(report.undriven()));
    t.set("multiplyDriven", toLua(report.multiplyDriven()));
    return t;
  }
}
