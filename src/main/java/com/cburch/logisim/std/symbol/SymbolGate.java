/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.symbol;

import static com.cburch.logisim.std.Strings.S;

import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.InstanceFactory;
import com.cburch.logisim.instance.InstancePainter;
import com.cburch.logisim.instance.Port;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.util.GraphicsUtil;
import com.cburch.logisim.util.StringGetter;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Peler Edition. A component drawn as a logic symbol -- a rectangle with the inputs down the left
 * and the outputs down the right, the way a datasheet's logic diagram and a lecture slide draw it
 * -- rather than in whatever arrangement its physical form dictates.
 *
 * <p>This class owns the picture and the port placement, and nothing else. What the symbol is a
 * symbol <em>of</em> belongs to a subclass: it supplies a {@link SymbolLayout} for a given
 * attribute set and implements {@code propagate}, which should hand straight to the component being
 * redrawn rather than restate its logic.
 *
 * <p>The layout is asked for per attribute set rather than fixed at construction, because a
 * component whose port count follows an attribute has a different symbol for each value of it.
 * {@link #layoutKey} says what the layout depends on, so the common case -- a symbol that never
 * changes -- is still built once.
 */
public abstract class SymbolGate extends InstanceFactory {

  private final Map<Object, SymbolLayout> layouts = new ConcurrentHashMap<>();

  protected SymbolGate(String name, StringGetter displayName) {
    super(name, displayName);
  }

  /**
   * What this symbol's shape depends on. Two attribute sets giving equal keys get the same layout,
   * so a symbol that never changes should return a constant.
   */
  protected abstract Object layoutKey(AttributeSet attrs);

  /** Builds the symbol for one attribute set. Called once per distinct {@link #layoutKey}. */
  protected abstract SymbolLayout buildLayout(AttributeSet attrs);

  /**
   * Whether a change to {@code attr} can change the symbol. Facing is always handled; override for
   * an attribute that decides the port count or the labels.
   */
  protected boolean affectsLayout(Attribute<?> attr) {
    return false;
  }

  /** The symbol for these attributes, built once per distinct key. */
  public final SymbolLayout layoutFor(AttributeSet attrs) {
    return layouts.computeIfAbsent(layoutKey(attrs), key -> buildLayout(attrs));
  }

  @Override
  public Bounds getOffsetBounds(AttributeSet attrs) {
    final var layout = layoutFor(attrs);
    final var dir = attrs.getValue(StdAttr.FACING);
    return Bounds.create(0, 0, layout.width(), layout.height()).rotate(Direction.EAST, dir, 0, 0);
  }

  /**
   * Where a port sits once the component is turned. The anchor is the top-left corner facing east,
   * and turning rotates every offset about it -- the same convention {@code Bounds.rotate} above
   * uses, so the ports stay on the edges of the bounds for all four facings.
   */
  private static int[] rotate(int x, int y, Direction dir) {
    if (dir == Direction.WEST) return new int[] {-x, -y};
    if (dir == Direction.NORTH) return new int[] {y, -x};
    if (dir == Direction.SOUTH) return new int[] {-y, x};
    return new int[] {x, y};
  }

  @Override
  protected void configureNewInstance(Instance instance) {
    instance.addAttributeListener();
    updatePorts(instance);
    computeTextField(instance);
  }

  @Override
  protected void instanceAttributeChanged(Instance instance, Attribute<?> attr) {
    if (attr == StdAttr.FACING || affectsLayout(attr)) {
      instance.recomputeBounds();
      updatePorts(instance);
      computeTextField(instance);
    }
  }

  private void computeTextField(Instance instance) {
    final var bds = instance.getBounds();
    instance.setTextField(
        StdAttr.LABEL,
        StdAttr.LABEL_FONT,
        bds.getX() + bds.getWidth() / 2,
        bds.getY() - 3,
        GraphicsUtil.H_CENTER,
        GraphicsUtil.V_BASELINE);
  }

  /**
   * Builds the port array in index order, not in drawing order. The layout places index {@code n}
   * somewhere on the symbol; this walks both columns to find where, so that {@code ps[n]} is still
   * the port the delegate's propagate means by {@code n}.
   */
  private void updatePorts(Instance instance) {
    final var layout = layoutFor(instance.getAttributeSet());
    final var dir = instance.getAttributeValue(StdAttr.FACING);
    final var ports = new ArrayList<Port>();
    for (var i = 0; i < layout.portCount(); i++) ports.add(null);

    for (var side = 0; side < 2; side++) {
      final var leftSide = side == 0;
      final var rows = layout.side(leftSide);
      final var x = leftSide ? 0 : layout.width();
      for (var row = 0; row < rows.size(); row++) {
        final var entry = rows.get(row);
        if (entry.isGap()) continue;
        final var offset = rotate(x, SymbolLayout.TOP_MARGIN + row * SymbolLayout.PITCH, dir);
        final var kind = layout.kind(entry.index());
        final var type =
            kind == SymbolLayout.INOUT
                ? Port.INOUT
                : kind == SymbolLayout.OUTPUT ? Port.OUTPUT : Port.INPUT;
        final var port = new Port(offset[0], offset[1], type, portWidth(instance, entry.index()));
        final var tip =
            kind == SymbolLayout.INOUT
                ? "ttlInOutTip"
                : kind == SymbolLayout.OUTPUT ? "demultiplexerOutTip" : "multiplexerInTip";
        port.setToolTip(S.getter(tip, ": " + layout.label(entry.index())));
        ports.set(entry.index(), port);
      }
    }
    for (var i = 0; i < ports.size(); i++) {
      if (ports.get(i) == null) {
        throw new IllegalStateException(
            getName() + " leaves port index " + i + " off the symbol; every port must be placed");
      }
    }
    instance.setPorts(ports.toArray(new Port[0]));
  }

  /**
   * How many bits port {@code index} carries. One unless a subclass says otherwise, which is right
   * for a chip whose pins are single wires and wrong for anything carrying a bus.
   */
  protected int portWidth(Instance instance, int index) {
    return 1;
  }

  @Override
  public void paintInstance(InstancePainter painter) {
    paintSymbol(painter, false);
    painter.drawPorts();
    painter.drawLabel();
  }

  @Override
  public void paintGhost(InstancePainter painter) {
    paintSymbol(painter, true);
  }

  /**
   * Draws the symbol as if it faced east and turns the whole drawing instead, which is the same
   * transform {@code getOffsetBounds} applies to the bounds and {@link #rotate} to the ports: a
   * turn about the component's own location.
   *
   * <p>Doing it this way rather than placing each label in absolute coordinates is what keeps a
   * turned symbol legible. Labels drawn horizontally would have to fit across a box that is now as
   * wide as the symbol is tall, and eleven rows of them landed on top of each other. Turned with
   * the box they read along it, the way upstream's DIP package already behaves.
   */
  private void paintSymbol(InstancePainter painter, boolean ghost) {
    final var layout = layoutFor(painter.getAttributeSet());
    final var dir = painter.getAttributeValue(StdAttr.FACING);
    final var loc = painter.getLocation();
    final var g = (Graphics2D) painter.getGraphics().create();
    try {
      g.rotate(Math.toRadians(-dir.toDegrees()), loc.getX(), loc.getY());
      final var x = loc.getX();
      final var y = loc.getY();

      if (!ghost) g.setColor(new Color(AppPreferences.COMPONENT_COLOR.get()));
      GraphicsUtil.switchToWidth(g, 2);
      g.drawRect(x, y, layout.width(), layout.height());
      GraphicsUtil.switchToWidth(g, 1);

      g.setFont(new Font(Font.DIALOG_INPUT, Font.BOLD, 11));
      GraphicsUtil.drawCenteredText(
          g, layout.caption(), x + layout.width() / 2, y + SymbolLayout.TOP_MARGIN / 2);
      if (ghost) return;

      g.setFont(new Font(Font.DIALOG_INPUT, Font.PLAIN, 8));
      for (var side = 0; side < 2; side++) {
        final var leftSide = side == 0;
        final var rows = layout.side(leftSide);
        for (var row = 0; row < rows.size(); row++) {
          final var entry = rows.get(row);
          if (entry.isGap()) continue;
          final var py = y + SymbolLayout.TOP_MARGIN + row * SymbolLayout.PITCH;
          final var inward = leftSide ? 1 : -1;
          final var edge = leftSide ? x : x + layout.width();
          var textAt = edge + inward * SymbolLayout.LABEL_INSET;
          if (layout.isInverted(entry.index())) {
            g.drawOval(
                edge - SymbolLayout.BUBBLE / 2,
                py - SymbolLayout.BUBBLE / 2,
                SymbolLayout.BUBBLE,
                SymbolLayout.BUBBLE);
            textAt = edge + inward * (SymbolLayout.LABEL_INSET + SymbolLayout.BUBBLE / 2);
          }
          if (entry.clock()) {
            g.drawPolyline(
                new int[] {edge, edge + inward * SymbolLayout.WEDGE, edge},
                new int[] {py - SymbolLayout.WEDGE, py, py + SymbolLayout.WEDGE},
                3);
            textAt = edge + inward * (SymbolLayout.LABEL_INSET + SymbolLayout.WEDGE);
          }
          GraphicsUtil.drawText(
              g,
              layout.label(entry.index()),
              textAt,
              py,
              leftSide ? GraphicsUtil.H_LEFT : GraphicsUtil.H_RIGHT,
              GraphicsUtil.V_CENTER);
        }
      }
    } finally {
      g.dispose();
    }
  }
}
