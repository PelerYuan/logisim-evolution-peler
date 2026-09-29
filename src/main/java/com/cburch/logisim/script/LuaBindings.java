/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.script;

import com.cburch.logisim.dsl.Analysis;
import com.cburch.logisim.dsl.Appearance;
import com.cburch.logisim.dsl.Attrs;
import com.cburch.logisim.dsl.CheckReport;
import com.cburch.logisim.dsl.Circuits;
import com.cburch.logisim.dsl.CircuitStatistics;
import com.cburch.logisim.dsl.Comp;
import com.cburch.logisim.dsl.Dot;
import com.cburch.logisim.dsl.DslException;
import com.cburch.logisim.dsl.History;
import com.cburch.logisim.dsl.Kind;
import com.cburch.logisim.dsl.Libraries;
import com.cburch.logisim.dsl.Memory;
import com.cburch.logisim.dsl.Net;
import com.cburch.logisim.dsl.Pcomp;
import com.cburch.logisim.dsl.PlaTables;
import com.cburch.logisim.dsl.Placement;
import com.cburch.logisim.dsl.Port;
import com.cburch.logisim.dsl.Simulation;
import com.cburch.logisim.dsl.Space;
import com.cburch.logisim.dsl.Synthesis;
import com.cburch.logisim.dsl.TestVectors;
import com.cburch.logisim.dsl.VhdlEntities;
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

  /** {@code {inputs = {"A", "B"}, outputs = {Sum = "A xor B"}, twoInputGatesOnly = true}} --
   * {@code inputs} is a plain array, {@code outputs} a name-to-expression map, both other fields
   * optional (design doc, section 十一/P5). */
  private static Synthesis synthesisFromTable(LuaTable table) {
    final var spec = Synthesis.of();
    final var inputs = table.get("inputs");
    if (inputs.istable()) {
      final var arr = inputs.checktable();
      for (var i = 1; i <= arr.length(); i++) spec.input(arr.get(i).checkjstring());
    }
    final var outputs = table.get("outputs");
    if (outputs.istable()) {
      final var map = outputs.checktable();
      LuaValue key = LuaValue.NIL;
      while (true) {
        final var next = map.next(key);
        if (next.arg1().isnil()) break;
        key = next.arg1();
        spec.output(key.tojstring(), next.arg(2).tojstring());
      }
    }
    if (table.get("twoInputGatesOnly").toboolean()) spec.twoInputGatesOnly();
    if (table.get("nandOnly").toboolean()) spec.nandOnly();
    return spec;
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
      return rest.type(1) == LuaValue.TSTRING ? wrap(comp.port(rest.checkjstring(1))) : wrap(comp.port(rest.checkint(1)));
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
      placement.rightOf(unwrapComp(rest, 1), rest.optint(2, 2));
      return self[0];
    }));
    methods.set("below", bind(a -> {
      final var rest = a.subargs(2);
      placement.below(unwrapComp(rest, 1), rest.optint(2, 2));
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
    methods.set("kinds", bind(a -> {
      final var rest = a.subargs(2);
      final var filter = rest.isnil(1) ? "" : rest.checkjstring(1).toLowerCase(java.util.Locale.ROOT);
      final var t = new LuaTable();
      var i = 1;
      for (final var kind : Kind.available(space)) {
        final var text = (kind.key() + " " + kind.displayName()).toLowerCase(java.util.Locale.ROOT);
        if (text.contains(filter)) t.set(i++, LuaValue.valueOf(kind.key()));
      }
      return t;
    }));
    methods.set("describeKind", bind(a -> {
      final var rest = a.subargs(2);
      final var kind = Kind.of(space, rest.checkjstring(1));
      final var t = new LuaTable();
      t.set("key", kind.key());
      t.set("name", kind.displayName());
      final var attributes = new LuaTable();
      var i = 1;
      for (final var info : kind.attributeInfo()) {
        final var row = new LuaTable();
        row.set("name", info.name());
        row.set("type", info.type());
        row.set("default", info.defaultValue());
        if (!info.options().isEmpty()) row.set("options", toLua(new ArrayList<Object>(info.options())));
        attributes.set(i++, row);
      }
      t.set("attributes", attributes);
      final var ports = new LuaTable();
      i = 1;
      final var overrides = rest.istable(2) ? attrsFromTable((LuaTable) rest.arg(2)) : Attrs.of();
      var ins = 0;
      var outs = 0;
      for (final var info : kind.portInfo(overrides)) {
        final var row = new LuaTable();
        row.set("index", info.index());
        final var isIn = info.dir() != com.cburch.logisim.dsl.Port.Dir.OUT;
        if (isIn) ins++;
        if (info.dir() != com.cburch.logisim.dsl.Port.Dir.IN) outs++;
        row.set("use", isIn ? "inputs()[" + ins + "]" : "outputs()[" + outs + "]");
        row.set("dir", info.dir().toString());
        row.set("width", info.width());
        if (info.desc() != null) row.set("desc", info.desc());
        ports.set(i++, row);
      }
      t.set("ports", ports);
      return t;
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
    methods.set("describe", bind(a -> LuaValue.valueOf(space.describe())));
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
      onCommit.run();
      return LuaValue.NONE;
    }));
    methods.set("move", bind(a -> {
      final var rest = a.subargs(2);
      final var keep = rest.arg(4).isnil() ? true : rest.checkboolean(4);
      final var moved = space.move(unwrapComp(rest, 1), rest.checkint(2), rest.checkint(3), keep);
      onCommit.run();
      final var t = new LuaTable();
      t.set("unconnectedPorts", moved.unconnectedPorts());
      return t;
    }));
    methods.set("copyRegion", bind(a -> {
      final var r = a.subargs(2);
      final var target = r.arg(7);
      final var copied =
          space.copyRegion(
              r.checkint(1), r.checkint(2), r.checkint(3), r.checkint(4), r.checkint(5), r.checkint(6),
              target.isnil() ? null : target.checkjstring());
      onCommit.run();
      final var t = new LuaTable();
      t.set("components", copied.components());
      t.set("wires", copied.wires());
      return t;
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
    methods.set("tidyWires", bind(a -> {
      final var changed = space.tidyWires();
      if (changed) onCommit.run();
      return LuaValue.valueOf(changed);
    }));
    methods.set("synthesize", bind(a -> {
      final var result = space.synthesize(synthesisFromTable(a.subargs(2).checktable(1)));
      onCommit.run();
      final var t = new LuaTable();
      t.set("placed", toLua(result.placed()));
      return t;
    }));
    methods.set("exportImage", bind(a -> {
      final var rest = a.subargs(2);
      final var path = rest.checkjstring(1);
      final var format = rest.checkjstring(2);
      final var scale = rest.arg(3).isnil() ? 1.0 : rest.checkdouble(3);
      final var printerView = rest.arg(4).isnil() ? false : rest.checkboolean(4);
      space.exportImage(path, format, scale, printerView);
      return LuaValue.NONE;
    }));
    methods.set("exportHtml", bind(a -> {
      space.exportHtml(a.subargs(2).checkjstring(1));
      return LuaValue.NONE;
    }));
    final var meta = new LuaTable();
    meta.set("__index", methods);
    return new LuaUserdata(space, meta);
  }

  // ---- Circuits ---------------------------------------------------------

  static LuaValue wrap(Circuits circuits) {
    final var methods = new LuaTable();
    methods.set("list", bind(a -> toLua(circuits.list())));
    methods.set("mainName", bind(a -> {
      final var name = circuits.mainName();
      return name == null ? LuaValue.NIL : LuaValue.valueOf(name);
    }));
    methods.set("create", bind(a -> {
      circuits.create(a.subargs(2).checkjstring(1));
      return LuaValue.NONE;
    }));
    methods.set("remove", bind(a -> {
      circuits.remove(a.subargs(2).checkjstring(1));
      return LuaValue.NONE;
    }));
    methods.set("rename", bind(a -> {
      final var rest = a.subargs(2);
      circuits.rename(rest.checkjstring(1), rest.checkjstring(2));
      return LuaValue.NONE;
    }));
    methods.set("setMain", bind(a -> {
      circuits.setMain(a.subargs(2).checkjstring(1));
      return LuaValue.NONE;
    }));
    methods.set("setEverywhere", bind(a -> {
      final var rest = a.subargs(2);
      final var kind = rest.arg(3);
      return LuaValue.valueOf(circuits.setEverywhere(
          rest.checkjstring(1), rest.arg(2).tojstring(), kind.isnil() ? null : kind.checkjstring()));
    }));
    final var meta = new LuaTable();
    meta.set("__index", methods);
    return new LuaUserdata(circuits, meta);
  }

  static LuaValue wrap(Appearance appearance) {
    final var methods = new LuaTable();
    methods.set("style", bind(a -> LuaValue.valueOf(appearance.style())));
    methods.set("setStyle", bind(a -> {
      appearance.setStyle(a.subargs(2).checkjstring(1));
      return LuaValue.NONE;
    }));
    methods.set("list", bind(a -> {
      final var t = new LuaTable();
      var i = 1;
      for (final var s : appearance.list()) {
        final var row = new LuaTable();
        row.set("index", s.index());
        row.set("kind", s.kind());
        row.set("x", s.x());
        row.set("y", s.y());
        row.set("width", s.width());
        row.set("height", s.height());
        if (s.text() != null) row.set("text", s.text());
        if (s.pin() != null) row.set("pin", s.pin());
        if (s.stroke() != null) {
          row.set("stroke", s.stroke());
          row.set("strokeWidth", s.strokeWidth());
        }
        if (s.fill() != null) row.set("fill", s.fill());
        t.set(i++, row);
      }
      return t;
    }));
    methods.set("addRect", bind(a -> {
      final var r = a.subargs(2);
      return LuaValue.valueOf(appearance.addRect(
          r.checkint(1), r.checkint(2), r.checkint(3), r.checkint(4), optionsFromTable(r.arg(5))));
    }));
    methods.set("addRoundRect", bind(a -> {
      final var r = a.subargs(2);
      return LuaValue.valueOf(appearance.addRoundRect(
          r.checkint(1), r.checkint(2), r.checkint(3), r.checkint(4), r.checkint(5),
          optionsFromTable(r.arg(6))));
    }));
    methods.set("addOval", bind(a -> {
      final var r = a.subargs(2);
      return LuaValue.valueOf(appearance.addOval(
          r.checkint(1), r.checkint(2), r.checkint(3), r.checkint(4), optionsFromTable(r.arg(5))));
    }));
    methods.set("addLine", bind(a -> {
      final var r = a.subargs(2);
      return LuaValue.valueOf(appearance.addLine(
          r.checkint(1), r.checkint(2), r.checkint(3), r.checkint(4), optionsFromTable(r.arg(5))));
    }));
    methods.set("addPoly", bind(a -> {
      final var r = a.subargs(2);
      final var points = new java.util.ArrayList<int[]>();
      final var table = r.checktable(1);
      for (var i = 1; i <= table.length(); i++) {
        final var pt = table.get(i).checktable();
        points.add(new int[] {pt.get(1).checkint(), pt.get(2).checkint()});
      }
      final var closed = r.arg(2).isnil() || r.arg(2).toboolean();
      return LuaValue.valueOf(appearance.addPoly(points, closed, optionsFromTable(r.arg(3))));
    }));
    methods.set("addText", bind(a -> {
      final var r = a.subargs(2);
      return LuaValue.valueOf(appearance.addText(
          r.checkint(1), r.checkint(2), r.checkjstring(3), optionsFromTable(r.arg(4))));
    }));
    methods.set("remove", bind(a -> {
      appearance.remove(a.subargs(2).checkint(1));
      return LuaValue.NONE;
    }));
    methods.set("clear", bind(a -> LuaValue.valueOf(appearance.clear())));
    methods.set("move", bind(a -> {
      final var r = a.subargs(2);
      appearance.move(r.checkint(1), r.checkint(2), r.checkint(3));
      return LuaValue.NONE;
    }));
    methods.set("setAnchorFacing", bind(a -> {
      appearance.setAnchorFacing(a.subargs(2).checkjstring(1));
      return LuaValue.NONE;
    }));
    methods.set("reorder", bind(a -> {
      final var r = a.subargs(2);
      appearance.reorder(r.checkint(1), r.checkjstring(2));
      return LuaValue.NONE;
    }));
    methods.set("resetDefault", bind(a -> {
      appearance.resetDefault();
      return LuaValue.NONE;
    }));
    methods.set("loadLogisimDefault", bind(a -> {
      appearance.loadLogisimDefault();
      return LuaValue.NONE;
    }));
    final var meta = new LuaTable();
    meta.set("__index", methods);
    return new LuaUserdata(appearance, meta);
  }

  private static java.util.Map<String, Object> optionsFromTable(LuaValue value) {
    final var out = new java.util.LinkedHashMap<String, Object>();
    if (value == null || value.isnil()) return out;
    final var table = value.checktable();
    var key = LuaValue.NIL;
    while (true) {
      final var next = table.next(key);
      key = next.arg1();
      if (key.isnil()) break;
      final var v = next.arg(2);
      out.put(key.tojstring(), v.isnumber() ? (Object) v.toint() : v.tojstring());
    }
    return out;
  }

  static LuaValue wrap(Memory memory) {
    final var methods = new LuaTable();
    methods.set("info", bind(a -> {
      final var i = memory.info(unwrapComp(a.subargs(2), 1));
      final var row = new LuaTable();
      row.set("kind", i.kind());
      row.set("addressBits", i.addressBits());
      row.set("dataBits", i.dataBits());
      row.set("words", LuaValue.valueOf((double) i.words()));
      row.set("live", LuaValue.valueOf(i.live()));
      return row;
    }));
    methods.set("read", bind(a -> {
      final var r = a.subargs(2);
      return LuaValue.valueOf((double) memory.read(unwrapComp(r, 1), r.checklong(2)));
    }));
    methods.set("readRange", bind(a -> {
      final var r = a.subargs(2);
      final var values = memory.readRange(unwrapComp(r, 1), r.checklong(2), r.checkint(3));
      final var t = new LuaTable();
      for (var i = 0; i < values.length; i++) t.set(i + 1, LuaValue.valueOf((double) values[i]));
      return t;
    }));
    methods.set("write", bind(a -> {
      final var r = a.subargs(2);
      memory.write(unwrapComp(r, 1), r.checklong(2), r.checklong(3));
      return LuaValue.NONE;
    }));
    methods.set("writeRange", bind(a -> {
      final var r = a.subargs(2);
      final var table = r.checktable(3);
      final var values = new long[table.length()];
      for (var i = 0; i < values.length; i++) values[i] = table.get(i + 1).checklong();
      memory.writeRange(unwrapComp(r, 1), r.checklong(2), values);
      return LuaValue.NONE;
    }));
    methods.set("fill", bind(a -> {
      final var r = a.subargs(2);
      memory.fill(unwrapComp(r, 1), r.checklong(2), r.checkint(3), r.checklong(4));
      return LuaValue.NONE;
    }));
    methods.set("clear", bind(a -> {
      memory.clear(unwrapComp(a.subargs(2), 1));
      return LuaValue.NONE;
    }));
    methods.set("dump", bind(a -> LuaValue.valueOf(memory.dump(unwrapComp(a.subargs(2), 1)))));
    methods.set("load", bind(a -> {
      final var r = a.subargs(2);
      memory.load(unwrapComp(r, 1), r.checkjstring(2));
      return LuaValue.NONE;
    }));
    methods.set("loadFile", bind(a -> {
      final var r = a.subargs(2);
      memory.loadFile(unwrapComp(r, 1), r.checkjstring(2));
      return LuaValue.NONE;
    }));
    methods.set("saveFile", bind(a -> {
      final var r = a.subargs(2);
      memory.saveFile(unwrapComp(r, 1), r.checkjstring(2));
      return LuaValue.NONE;
    }));
    final var meta = new LuaTable();
    meta.set("__index", methods);
    return new LuaUserdata(memory, meta);
  }

  static LuaValue wrap(PlaTables pla) {
    final var methods = new LuaTable();
    methods.set("getTable", bind(a -> LuaValue.valueOf(pla.getTable(unwrapComp(a.subargs(2), 1)))));
    methods.set("setTable", bind(a -> {
      final var r = a.subargs(2);
      pla.setTable(unwrapComp(r, 1), r.checkjstring(2));
      return LuaValue.NONE;
    }));
    final var meta = new LuaTable();
    meta.set("__index", methods);
    return new LuaUserdata(pla, meta);
  }

  static LuaValue wrap(Analysis analysis) {
    final var methods = new LuaTable();
    methods.set("truthTable", bind(a -> {
      final var table = analysis.truthTable();
      final var t = new LuaTable();
      t.set("inputs", toLua(table.inputs()));
      t.set("outputs", toLua(table.outputs()));
      final var rows = new LuaTable();
      var i = 1;
      for (final var r : table.rows()) {
        final var row = new LuaTable();
        row.set("inputs", r.inputs());
        row.set("outputs", r.outputs());
        rows.set(i++, row);
      }
      t.set("rows", rows);
      return t;
    }));
    methods.set("expressions", bind(a -> {
      final var r = a.subargs(2);
      return mapToLua(analysis.expressions(r.arg(1).isnil() ? null : r.checkjstring(1)));
    }));
    methods.set("minimized", bind(a -> {
      final var r = a.subargs(2);
      return mapToLua(analysis.minimized(
          r.arg(1).isnil() ? "sop" : r.checkjstring(1), r.arg(2).isnil() ? null : r.checkjstring(2)));
    }));
    methods.set("exportTable", bind(a -> {
      analysis.exportTable(a.subargs(2).checkjstring(1));
      return LuaValue.NONE;
    }));
    methods.set("exportLatex", bind(a -> {
      analysis.exportLatex(a.subargs(2).checkjstring(1));
      return LuaValue.NONE;
    }));
    final var meta = new LuaTable();
    meta.set("__index", methods);
    return new LuaUserdata(analysis, meta);
  }

  private static LuaValue mapToLua(java.util.Map<String, String> map) {
    final var t = new LuaTable();
    for (final var e : map.entrySet()) t.set(e.getKey(), e.getValue());
    return t;
  }

  static LuaValue wrap(TestVectors tests) {
    final var methods = new LuaTable();
    methods.set("run", bind(a -> resultToLua(tests.run(a.subargs(2).checkjstring(1)))));
    methods.set("runFile", bind(a -> resultToLua(tests.runFile(a.subargs(2).checkjstring(1)))));
    final var meta = new LuaTable();
    meta.set("__index", methods);
    return new LuaUserdata(tests, meta);
  }

  private static LuaValue resultToLua(TestVectors.Result result) {
    final var t = new LuaTable();
    t.set("passed", result.passed());
    t.set("failed", result.failed());
    final var failures = new LuaTable();
    var i = 1;
    for (final var f : result.failures()) {
      final var row = new LuaTable();
      row.set("row", f.row());
      final var mismatches = new LuaTable();
      var j = 1;
      for (final var m : f.mismatches()) {
        final var mm = new LuaTable();
        mm.set("column", m.column());
        mm.set("expected", m.expected());
        mm.set("computed", m.computed());
        mm.set("oscillating", LuaValue.valueOf(m.oscillating()));
        mismatches.set(j++, mm);
      }
      row.set("mismatches", mismatches);
      failures.set(i++, row);
    }
    t.set("failures", failures);
    return t;
  }

  static LuaValue wrap(Libraries libraries) {
    final var methods = new LuaTable();
    methods.set("list", bind(a -> toLua(libraries.list())));
    methods.set("loadCircuit", bind(a -> LuaValue.valueOf(libraries.loadCircuit(a.subargs(2).checkjstring(1)))));
    methods.set("loadJar", bind(a -> {
      final var rest = a.subargs(2);
      return LuaValue.valueOf(libraries.loadJar(rest.checkjstring(1), rest.checkjstring(2)));
    }));
    methods.set("loadPcomp", bind(a -> LuaValue.valueOf(libraries.loadPcomp(a.subargs(2).checkjstring(1)))));
    methods.set("createPcomp", bind(a -> {
      final var rest = a.subargs(2);
      return LuaValue.valueOf(libraries.createPcomp(rest.checkjstring(1), rest.checkjstring(2)));
    }));
    methods.set("exportPcomp", bind(a -> {
      final var rest = a.subargs(2);
      libraries.exportPcomp(rest.checkjstring(1), rest.checkjstring(2));
      return LuaValue.NONE;
    }));
    methods.set("unload", bind(a -> {
      libraries.unload(a.subargs(2).checkjstring(1));
      return LuaValue.NONE;
    }));
    methods.set("reload", bind(a -> {
      libraries.reload(a.subargs(2).checkjstring(1));
      return LuaValue.NONE;
    }));
    final var meta = new LuaTable();
    meta.set("__index", methods);
    return new LuaUserdata(libraries, meta);
  }

  // ---- Pcomp ----------------------------------------------------------------

  static LuaValue wrap(Pcomp pcomp) {
    final var methods = new LuaTable();
    methods.set("list", bind(a -> {
      final var t = new LuaTable();
      final var installed = pcomp.list(a.subargs(2).checkjstring(1));
      for (var i = 0; i < installed.size(); i++) t.set(i + 1, wrap(installed.get(i)));
      return t;
    }));
    methods.set("saveAsComponent", bind(a -> {
      final var rest = a.subargs(2);
      return wrap(pcomp.saveAsComponent(rest.checkjstring(1), rest.checkjstring(2), rest.checkjstring(3)));
    }));
    methods.set("importFile", bind(a -> {
      final var rest = a.subargs(2);
      return wrap(pcomp.importFile(rest.checkjstring(1), rest.checkjstring(2)));
    }));
    methods.set("delete", bind(a -> {
      final var rest = a.subargs(2);
      pcomp.delete(rest.checkjstring(1), rest.checkjstring(2), rest.checkint(3));
      return LuaValue.NONE;
    }));
    methods.set("replace", bind(a -> {
      final var rest = a.subargs(2);
      return wrap(pcomp.replace(rest.checkjstring(1), rest.checkjstring(2), rest.checkint(3), rest.checkint(4)));
    }));
    final var meta = new LuaTable();
    meta.set("__index", methods);
    return new LuaUserdata(pcomp, meta);
  }

  private static LuaValue wrap(Pcomp.Installed installed) {
    final var t = new LuaTable();
    t.set("id", LuaValue.valueOf(installed.id()));
    t.set("version", LuaValue.valueOf(installed.version()));
    t.set("name", LuaValue.valueOf(installed.name()));
    t.set("mainCircuit", LuaValue.valueOf(installed.mainCircuit()));
    t.set("locked", LuaValue.valueOf(installed.locked()));
    return t;
  }

  private static LuaValue wrap(Pcomp.Saved saved) {
    final var t = new LuaTable();
    t.set("id", LuaValue.valueOf(saved.id()));
    t.set("version", LuaValue.valueOf(saved.version()));
    t.set("name", LuaValue.valueOf(saved.name()));
    t.set("mainCircuit", LuaValue.valueOf(saved.mainCircuit()));
    t.set("path", LuaValue.valueOf(saved.path()));
    t.set("libraryName", LuaValue.valueOf(saved.libraryName()));
    return t;
  }

  private static LuaValue wrap(Pcomp.Replaced replaced) {
    final var t = new LuaTable();
    t.set("uses", LuaValue.valueOf(replaced.uses()));
    t.set("replaced", LuaValue.valueOf(replaced.replaced()));
    return t;
  }

  static LuaValue wrap(VhdlEntities vhdlEntities) {
    final var methods = new LuaTable();
    methods.set("list", bind(a -> toLua(vhdlEntities.list())));
    methods.set("create", bind(a -> {
      vhdlEntities.create(a.subargs(2).checkjstring(1));
      return LuaValue.NONE;
    }));
    methods.set("importFile", bind(a -> LuaValue.valueOf(vhdlEntities.importFile(a.subargs(2).checkjstring(1)))));
    methods.set("remove", bind(a -> {
      vhdlEntities.remove(a.subargs(2).checkjstring(1));
      return LuaValue.NONE;
    }));
    methods.set("rename", bind(a -> {
      final var rest = a.subargs(2);
      vhdlEntities.rename(rest.checkjstring(1), rest.checkjstring(2));
      return LuaValue.NONE;
    }));
    methods.set("getSource", bind(a -> LuaValue.valueOf(vhdlEntities.getSource(a.subargs(2).checkjstring(1)))));
    methods.set("setSource", bind(a -> {
      final var rest = a.subargs(2);
      vhdlEntities.setSource(rest.checkjstring(1), rest.checkjstring(2));
      return LuaValue.NONE;
    }));
    methods.set("ports", bind(a -> {
      final var t = new LuaTable();
      var i = 1;
      for (final var p : vhdlEntities.ports(a.subargs(2).checkjstring(1))) {
        final var row = new LuaTable();
        row.set("name", p.name());
        row.set("direction", p.direction());
        row.set("width", p.width());
        t.set(i++, row);
      }
      return t;
    }));
    methods.set("exportFile", bind(a -> {
      final var rest = a.subargs(2);
      vhdlEntities.exportFile(rest.checkjstring(1), rest.checkjstring(2));
      return LuaValue.NONE;
    }));
    final var meta = new LuaTable();
    meta.set("__index", methods);
    return new LuaUserdata(vhdlEntities, meta);
  }

  static LuaValue wrap(CircuitStatistics circuitStatistics) {
    final var methods = new LuaTable();
    methods.set("compute", bind(a -> wrap(circuitStatistics.compute(a.subargs(2).checkjstring(1)))));
    final var meta = new LuaTable();
    meta.set("__index", methods);
    return new LuaUserdata(circuitStatistics, meta);
  }

  private static LuaValue wrap(CircuitStatistics.Report report) {
    final var t = new LuaTable();
    final var rows = new LuaTable();
    for (var i = 0; i < report.rows().size(); i++) rows.set(i + 1, wrap(report.rows().get(i)));
    t.set("rows", rows);
    t.set("totalWithoutSubcircuits", wrap(report.totalWithoutSubcircuits()));
    t.set("totalWithSubcircuits", wrap(report.totalWithSubcircuits()));
    return t;
  }

  private static LuaValue wrap(CircuitStatistics.Row row) {
    final var t = new LuaTable();
    t.set("component", LuaValue.valueOf(row.component()));
    t.set("library", toLua(row.library()));
    t.set("simpleCount", LuaValue.valueOf(row.simpleCount()));
    t.set("uniqueCount", LuaValue.valueOf(row.uniqueCount()));
    t.set("recursiveCount", LuaValue.valueOf(row.recursiveCount()));
    return t;
  }

  private static LuaValue wrap(CircuitStatistics.Totals totals) {
    final var t = new LuaTable();
    t.set("simpleCount", LuaValue.valueOf(totals.simpleCount()));
    t.set("uniqueCount", LuaValue.valueOf(totals.uniqueCount()));
    t.set("recursiveCount", LuaValue.valueOf(totals.recursiveCount()));
    return t;
  }

  static LuaValue wrap(History history) {
    final var methods = new LuaTable();
    methods.set("canUndo", bind(a -> LuaValue.valueOf(history.canUndo())));
    methods.set("canRedo", bind(a -> LuaValue.valueOf(history.canRedo())));
    methods.set("nextUndoDescription", bind(a -> toLua(history.nextUndoDescription())));
    methods.set("nextRedoDescription", bind(a -> toLua(history.nextRedoDescription())));
    methods.set("undo", bind(a -> LuaValue.valueOf(history.undo())));
    methods.set("redo", bind(a -> LuaValue.valueOf(history.redo())));
    final var meta = new LuaTable();
    meta.set("__index", methods);
    return new LuaUserdata(history, meta);
  }

  static LuaValue wrap(Simulation simulation) {
    final var methods = new LuaTable();
    methods.set("reset", bind(a -> {
      simulation.reset();
      return LuaValue.NONE;
    }));
    methods.set("step", bind(a -> {
      simulation.step();
      return LuaValue.NONE;
    }));
    methods.set("tick", bind(a -> {
      simulation.tick(a.subargs(2).checkint(1));
      return LuaValue.NONE;
    }));
    methods.set("isAutoTicking", bind(a -> LuaValue.valueOf(simulation.isAutoTicking())));
    methods.set("setAutoTicking", bind(a -> {
      simulation.setAutoTicking(a.subargs(2).checkboolean(1));
      return LuaValue.NONE;
    }));
    methods.set("isAutoPropagating", bind(a -> LuaValue.valueOf(simulation.isAutoPropagating())));
    methods.set("setAutoPropagation", bind(a -> {
      simulation.setAutoPropagation(a.subargs(2).checkboolean(1));
      return LuaValue.NONE;
    }));
    methods.set("getTickFrequency", bind(a -> LuaValue.valueOf(simulation.getTickFrequency())));
    methods.set("setTickFrequency", bind(a -> {
      simulation.setTickFrequency(a.subargs(2).checkdouble(1));
      return LuaValue.NONE;
    }));
    methods.set("isOscillating", bind(a -> LuaValue.valueOf(simulation.isOscillating())));
    methods.set("isExceptionEncountered",
        bind(a -> LuaValue.valueOf(simulation.isExceptionEncountered())));
    methods.set("isVhdlSimulationAvailable",
        bind(a -> LuaValue.valueOf(simulation.isVhdlSimulationAvailable())));
    methods.set("isVhdlSimulationEnabled",
        bind(a -> LuaValue.valueOf(simulation.isVhdlSimulationEnabled())));
    methods.set("setVhdlSimulationEnabled", bind(a -> {
      simulation.setVhdlSimulationEnabled(a.subargs(2).checkboolean(1));
      return LuaValue.NONE;
    }));
    methods.set("generateVhdlSimulationFiles", bind(a -> {
      simulation.generateVhdlSimulationFiles();
      return LuaValue.NONE;
    }));
    methods.set("readPin", bind(a -> wrap(simulation.readPin(a.subargs(2).checkjstring(1)))));
    methods.set("trace", bind(a -> {
      final var r = a.subargs(2);
      final var labels = new java.util.ArrayList<String>();
      final var table = r.checktable(1);
      for (var k = 1; k <= table.length(); k++) labels.add(table.get(k).checkjstring());
      final var trace = simulation.trace(
          labels,
          r.arg(2).isnil() ? 1 : r.checkint(2),
          r.arg(3).isnil() ? 2 : r.checkint(3),
          r.arg(4).isnil() ? null : r.checkjstring(4));
      final var out = new LuaTable();
      out.set("signals", toLua(trace.signals()));
      final var rows = new LuaTable();
      var n = 1;
      for (final var row : trace.rows()) rows.set(n++, toLua(row));
      out.set("rows", rows);
      return out;
    }));
    methods.set("writePin", bind(a -> {
      final var rest = a.subargs(2);
      simulation.writePin(rest.checkjstring(1), rest.checklong(2));
      return LuaValue.NONE;
    }));
    final var meta = new LuaTable();
    meta.set("__index", methods);
    return new LuaUserdata(simulation, meta);
  }

  private static LuaValue wrap(Simulation.PinValue pinValue) {
    final var t = new LuaTable();
    t.set("value", LuaValue.valueOf(pinValue.value()));
    t.set("known", LuaValue.valueOf(pinValue.known()));
    t.set("error", LuaValue.valueOf(pinValue.error()));
    return t;
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
