/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import com.cburch.draw.actions.ModelAddAction;
import com.cburch.draw.actions.ModelChangeAttributeAction;
import com.cburch.draw.actions.ModelRemoveAction;
import com.cburch.draw.actions.ModelReorderAction;
import com.cburch.draw.actions.ModelTranslateAction;
import com.cburch.draw.model.AttributeMapKey;
import com.cburch.draw.model.CanvasObject;
import com.cburch.draw.shapes.DrawAttr;
import com.cburch.draw.shapes.Line;
import com.cburch.draw.shapes.Oval;
import com.cburch.draw.shapes.Poly;
import com.cburch.draw.shapes.Rectangle;
import com.cburch.draw.shapes.RoundRectangle;
import com.cburch.draw.shapes.Text;
import com.cburch.draw.undo.UndoAction;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitAttributes;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.CircuitMutator;
import com.cburch.logisim.circuit.CircuitTransaction;
import com.cburch.logisim.circuit.appear.AppearanceAnchor;
import com.cburch.logisim.circuit.appear.AppearancePort;
import com.cburch.logisim.data.AttributeOption;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.gui.appear.CanvasActionAdapter;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.pcomp.PcompLock;
import com.cburch.logisim.proj.Action;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.util.StringUtil;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What the GUI's appearance editor does, for the circuit a {@link Space} is bound to: choose the
 * symbol style, draw and delete shapes, move a shape or a pin's port, reorder, and reset.
 *
 * <p>Edits go through the same canvas actions and the same {@link CanvasActionAdapter} the editor
 * dispatches, so an edit here is one undo entry and updates every placed instance of the circuit
 * exactly as an edit in the editor does. A shape edit switches the circuit to the "custom" style if
 * it was not already, in the same undo entry -- an edit that could not be seen would be a trap.
 *
 * <p>Indices are into the circuit's custom shape list, bottom first, the same list the editor
 * draws; ports and the anchor are in it (the editor forces them to the top layers) and can be moved
 * but not removed. Coordinates are the appearance grid's, where the anchor is the point that lands
 * on the placed component's location.
 */
public final class Appearance {
  /** One shape of the custom appearance. Fields that do not apply to a kind are null or zero. */
  public record Shape(
      int index,
      String kind,
      int x,
      int y,
      int width,
      int height,
      String text,
      String pin,
      String stroke,
      String fill,
      int strokeWidth) {}

  private final Project proj;
  private final Circuit circuit;

  private Appearance(Project proj, Circuit circuit) {
    this.proj = proj;
    this.circuit = circuit;
  }

  public static Appearance of(Space space) {
    return new Appearance(space.project(), space.circuit());
  }

  /** "classic", "evolution", "fpga" or "custom": which symbol the circuit is drawn with. */
  public String style() {
    return String.valueOf(circuit.getStaticAttributes().getValue(CircuitAttributes.APPEARANCE_ATTR).getValue());
  }

  public void setStyle(String name) {
    guard();
    final var option = optionNamed(name);
    if (option == circuit.getStaticAttributes().getValue(CircuitAttributes.APPEARANCE_ATTR)) return;
    proj.doAction(styleAction(option));
  }

  public List<Shape> list() {
    final var out = new ArrayList<Shape>();
    final var shapes = shapes();
    for (var i = 0; i < shapes.size(); i++) out.add(describe(i, shapes.get(i)));
    return out;
  }

  public int addRect(int x, int y, int width, int height, Map<String, Object> options) {
    final var shape = new Rectangle(x, y, checkSize(width), checkSize(height));
    return add(shape, options);
  }

  public int addRoundRect(
      int x, int y, int width, int height, int radius, Map<String, Object> options) {
    final var shape = new RoundRectangle(x, y, checkSize(width), checkSize(height));
    shape.setValue(DrawAttr.CORNER_RADIUS, Math.max(0, radius));
    return add(shape, options);
  }

  public int addOval(int x, int y, int width, int height, Map<String, Object> options) {
    return add(new Oval(x, y, checkSize(width), checkSize(height)), options);
  }

  public int addLine(int x1, int y1, int x2, int y2, Map<String, Object> options) {
    return add(new Line(x1, y1, x2, y2), options);
  }

  /** {@code points} is a list of {x, y} pairs; a closed polygon needs three, an open one two. */
  public int addPoly(List<int[]> points, boolean closed, Map<String, Object> options) {
    if (points.size() < (closed ? 3 : 2)) {
      throw new InvalidAppearanceEditException(
          "a " + (closed ? "closed polygon needs at least 3 points" : "polyline needs at least 2 points"),
          Map.of("points", points.size(), "closed", closed),
          closed ? "pass {{x,y},{x,y},{x,y}}, or use closed=false for a polyline" : "pass {{x,y},{x,y}}");
    }
    final var locs = new ArrayList<Location>();
    for (final var p : points) locs.add(Location.create(p[0], p[1], false));
    return add(new Poly(closed, locs), options);
  }

  public int addText(int x, int y, String text, Map<String, Object> options) {
    return add(new Text(x, y, text), options);
  }

  public void remove(int index) {
    guard();
    final var shape = shape(index);
    if (!shape.canRemove()) {
      throw new InvalidAppearanceEditException(
          "shape " + index + " is a " + describe(index, shape).kind() + ", which follows the circuit's pins and cannot be removed",
          Map.of("index", index, "kind", describe(index, shape).kind()),
          "move it with appearance:move(), or add/remove the pin in the circuit itself");
    }
    run(new ModelRemoveAction(model(), shape));
  }

  /** Removes every shape that can be removed, keeping ports and the anchor. */
  public int clear() {
    guard();
    final var removable = new ArrayList<CanvasObject>();
    for (final var shape : shapes()) if (shape.canRemove()) removable.add(shape);
    if (removable.isEmpty()) return 0;
    run(new ModelRemoveAction(model(), removable));
    return removable.size();
  }

  /** Moves a shape, a pin's port or the anchor by (dx, dy) grid units. */
  public void move(int index, int dx, int dy) {
    guard();
    final var shape = shape(index);
    if (dx == 0 && dy == 0) return;
    run(new ModelTranslateAction(model(), List.of(shape), dx, dy));
  }

  /** Sets the direction the anchor faces ("east", "north", "west", "south"). */
  public void setAnchorFacing(String facing) {
    guard();
    final var direction = Direction.parse(facing);
    for (final var shape : shapes()) {
      if (shape instanceof AppearanceAnchor anchor) {
        final var oldValues = new HashMap<AttributeMapKey, Object>();
        final var newValues = new HashMap<AttributeMapKey, Object>();
        final var key = new AttributeMapKey(AppearanceAnchor.FACING, anchor);
        oldValues.put(key, anchor.getValue(AppearanceAnchor.FACING));
        newValues.put(key, direction);
        run(new ModelChangeAttributeAction(model(), oldValues, newValues));
        return;
      }
    }
    throw new InvalidAppearanceEditException(
        "this circuit's appearance has no anchor", Map.of(), "call appearance:resetDefault()");
  }

  /** One layer up ("up"), one down ("down"), or to the top / bottom ("top" / "bottom"). */
  public void reorder(int index, String where) {
    guard();
    final var shape = shape(index);
    final var one = List.of(shape);
    final ModelReorderAction action =
        switch (where) {
          case "up" -> ModelReorderAction.createRaise(model(), one);
          case "down" -> ModelReorderAction.createLower(model(), one);
          case "top" -> ModelReorderAction.createRaiseTop(model(), one);
          case "bottom" -> ModelReorderAction.createLowerBottom(model(), one);
          default ->
              throw new InvalidAppearanceEditException(
                  "unknown reorder target \"" + where + "\"",
                  Map.of("where", where),
                  "use \"up\", \"down\", \"top\" or \"bottom\"");
        };
    if (action != null) run(action);
  }

  /** The editor's "restore default custom appearance": the plain box with every pin's port. */
  public void resetDefault() {
    guard();
    replaceAll(a -> a.resetDefaultCustomAppearance(), "reset appearance");
  }

  /** The editor's "clear appearance and load logisim default": the built-in symbol as shapes. */
  public void loadLogisimDefault() {
    guard();
    replaceAll(a -> a.loadDefaultLogisimAppearance(), "load default appearance");
  }

  // ---- internals -----------------------------------------------------------------------------

  private List<CanvasObject> shapes() {
    return circuit.getAppearance().getCustomObjectsFromBottom();
  }

  private com.cburch.draw.model.CanvasModel model() {
    return circuit.getAppearance().getCustomAppearanceDrawing();
  }

  private CanvasObject shape(int index) {
    final var shapes = shapes();
    if (index < 0 || index >= shapes.size()) {
      throw new UnknownAppearanceShapeException(index, shapes.size());
    }
    return shapes.get(index);
  }

  private void guard() {
    if (PcompLock.blocksAppearanceEditOf(proj, circuit)) {
      throw new AppearanceLockedException(circuit.getName());
    }
  }

  private boolean isCustom() {
    return circuit.getStaticAttributes().getValue(CircuitAttributes.APPEARANCE_ATTR)
        == CircuitAttributes.APPEAR_CUSTOM;
  }

  private Action styleAction(AttributeOption option) {
    final var mutation = new CircuitMutation(circuit);
    mutation.setForCircuit(CircuitAttributes.APPEARANCE_ATTR, option);
    return mutation.toAction(StringUtil.constantGetter("set appearance style"));
  }

  private void run(UndoAction canvasAction) {
    Action action = new CanvasActionAdapter(circuit, canvasAction);
    if (!isCustom()) action = styleAction(CircuitAttributes.APPEAR_CUSTOM).append(action);
    proj.doAction(action);
  }

  private int add(CanvasObject shape, Map<String, Object> options) {
    guard();
    applyOptions(shape, options == null ? Map.of() : options);
    run(new ModelAddAction(model(), shape));
    final var shapes = shapes();
    for (var i = shapes.size() - 1; i >= 0; i--) if (shapes.get(i) == shape) return i;
    return shapes.size() - 1;
  }

  private static int checkSize(int size) {
    if (size <= 0) {
      throw new InvalidAppearanceEditException(
          "shape width and height must be positive, got " + size,
          Map.of("size", size),
          "pass a size of at least 1");
    }
    return size;
  }

  private static void applyOptions(CanvasObject shape, Map<String, Object> options) {
    for (final var key : options.keySet()) {
      if (!key.equals("stroke") && !key.equals("fill") && !key.equals("strokeWidth") && !key.equals("size")) {
        throw new InvalidAppearanceEditException(
            "unknown shape option \"" + key + "\"",
            Map.of("option", key),
            "valid options are stroke, fill (\"#rrggbb\"), strokeWidth (integer) and, for text, size");
      }
    }
    final var stroke = color(options, "stroke");
    final var fill = color(options, "fill");
    if (shape instanceof Text) {
      if (fill != null) shape.setValue(DrawAttr.FILL_COLOR, fill);
      final var size = options.get("size");
      if (size instanceof Number n) {
        shape.setValue(DrawAttr.FONT, DrawAttr.DEFAULT_FONT.deriveFont(n.floatValue()));
      }
      return;
    }
    if (stroke != null) shape.setValue(DrawAttr.STROKE_COLOR, stroke);
    if (options.get("strokeWidth") instanceof Number w) {
      shape.setValue(DrawAttr.STROKE_WIDTH, Math.max(1, w.intValue()));
    }
    final var fillable =
        shape instanceof Rectangle
            || shape instanceof RoundRectangle
            || shape instanceof Oval
            || (shape instanceof Poly p && p.isClosed());
    if (fillable) {
      if (fill != null) shape.setValue(DrawAttr.FILL_COLOR, fill);
      final var paint =
          fill != null && stroke != null
              ? DrawAttr.PAINT_STROKE_FILL
              : fill != null ? DrawAttr.PAINT_FILL : DrawAttr.PAINT_STROKE;
      shape.setValue(DrawAttr.PAINT_TYPE, paint);
    } else if (fill != null) {
      throw new InvalidAppearanceEditException(
          "this shape cannot be filled",
          Map.of("option", "fill"),
          "fill applies to rectangles, rounded rectangles, ovals and closed polygons");
    }
  }

  private static Color color(Map<String, Object> options, String key) {
    final var value = options.get(key);
    if (value == null) return null;
    try {
      return Color.decode(String.valueOf(value));
    } catch (NumberFormatException e) {
      throw new InvalidAppearanceEditException(
          "\"" + value + "\" is not a colour",
          Map.of("option", key, "value", String.valueOf(value)),
          "write it as \"#rrggbb\", for example \"#ff0000\"");
    }
  }

  private static AttributeOption optionNamed(String name) {
    for (final var option :
        new AttributeOption[] {
          CircuitAttributes.APPEAR_CLASSIC,
          CircuitAttributes.APPEAR_EVOLUTION,
          CircuitAttributes.APPEAR_FPGA,
          CircuitAttributes.APPEAR_CUSTOM
        }) {
      if (String.valueOf(option.getValue()).equals(name)) return option;
    }
    throw new InvalidAppearanceEditException(
        "unknown appearance style \"" + name + "\"",
        Map.of("style", String.valueOf(name)),
        "use \"classic\", \"evolution\", \"fpga\" or \"custom\"");
  }

  private static String hex(Color c) {
    return c == null ? null : String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
  }

  private static Shape describe(int index, CanvasObject shape) {
    final var bounds = shape.getBounds();
    var kind = "shape";
    String text = null;
    String pin = null;
    if (shape instanceof AppearancePort port) {
      kind = "port";
      final var label = port.getPin() == null ? null : port.getPin().getAttributeValue(StdAttr.LABEL);
      pin = label == null || label.isEmpty() ? null : label;
    } else if (shape instanceof AppearanceAnchor) {
      kind = "anchor";
    } else if (shape instanceof Text t) {
      kind = "text";
      text = t.getText();
    } else if (shape instanceof RoundRectangle) {
      kind = "roundrect";
    } else if (shape instanceof Rectangle) {
      kind = "rect";
    } else if (shape instanceof Oval) {
      kind = "oval";
    } else if (shape instanceof Line) {
      kind = "line";
    } else if (shape instanceof Poly p) {
      kind = p.isClosed() ? "polygon" : "polyline";
    }
    final var paint = shape.getValue(DrawAttr.PAINT_TYPE);
    final var hasFill = paint == DrawAttr.PAINT_FILL || paint == DrawAttr.PAINT_STROKE_FILL;
    final var hasStroke = paint != DrawAttr.PAINT_FILL;
    final Color fill;
    if (shape instanceof Text) fill = shape.getValue(DrawAttr.FILL_COLOR);
    else fill = hasFill ? shape.getValue(DrawAttr.FILL_COLOR) : null;
    final var strokeColor = hasStroke ? shape.getValue(DrawAttr.STROKE_COLOR) : null;
    final var width = shape.getValue(DrawAttr.STROKE_WIDTH);
    return new Shape(
        index,
        kind,
        bounds.getX(),
        bounds.getY(),
        bounds.getWidth(),
        bounds.getHeight(),
        text,
        pin,
        hex(strokeColor),
        hex(fill),
        width == null || strokeColor == null ? 0 : width);
  }

  private void replaceAll(Consumer<com.cburch.logisim.circuit.appear.CircuitAppearance> change, String name) {
    Action action = new Replace(circuit, change, name);
    if (!isCustom()) action = styleAction(CircuitAttributes.APPEAR_CUSTOM).append(action);
    proj.doAction(action);
  }

  /** Same shape as {@code RevertAppearanceAction}: lock every circuit that draws this one. */
  private static final class Replace extends Action {
    private final Circuit circuit;
    private final Consumer<com.cburch.logisim.circuit.appear.CircuitAppearance> change;
    private final String name;
    private List<CanvasObject> old;

    Replace(Circuit circuit, Consumer<com.cburch.logisim.circuit.appear.CircuitAppearance> change, String name) {
      this.circuit = circuit;
      this.change = change;
      this.name = name;
    }

    @Override
    public void doIt(Project proj) {
      new Transaction(true).execute();
    }

    @Override
    public void undo(Project proj) {
      new Transaction(false).execute();
    }

    @Override
    public String getName() {
      return name;
    }

    private final class Transaction extends CircuitTransaction {
      private final boolean forward;

      Transaction(boolean forward) {
        this.forward = forward;
      }

      @Override
      protected Map<Circuit, Integer> getAccessedCircuits() {
        final var access = new HashMap<Circuit, Integer>();
        for (final var user : circuit.getCircuitsUsingThis()) access.put(user, READ_WRITE);
        return access;
      }

      @Override
      protected void run(CircuitMutator mutator) {
        final var appearance = circuit.getAppearance();
        if (forward) {
          old = new ArrayList<>(appearance.getCustomObjectsFromBottom());
          change.accept(appearance);
        } else {
          appearance.setObjectsForce(old);
        }
      }
    }
  }
}
