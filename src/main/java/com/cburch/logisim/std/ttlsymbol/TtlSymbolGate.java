/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.ttlsymbol;

import static com.cburch.logisim.std.Strings.S;

import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.InstanceFactory;
import com.cburch.logisim.instance.InstancePainter;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.instance.Port;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.std.ttl.AbstractTtlGate;
import com.cburch.logisim.std.ttlsymbol.TtlSymbolSpec.Row;
import com.cburch.logisim.util.GraphicsUtil;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Peler Edition. A 74xx chip drawn as a logic symbol -- a rectangle, inputs left, outputs right --
 * instead of as the DIP package upstream draws.
 *
 * <p>One class serves every chip: the differences are data, and they live in {@link TtlSymbolSpec}.
 * That is why these are built with {@code new AddTool(factory)} rather than through {@code
 * FactoryDescription}, which reflects on a class per tool and so could not give one class many
 * names.
 *
 * <p><b>The simulation is not reimplemented.</b> {@link #propagate} hands the state straight to the
 * DIP factory's {@code propagateTtl}. That is sound because {@code AbstractTtlGate} derives a
 * port's index from its pin number and never from its position, so a symbol built from the same
 * port array in a different arrangement answers to the same indices.
 * {@code TtlSymbolEquivalenceTest} holds that invariant down by sweeping both factories over every
 * input combination.
 *
 * <p>The identity of the tool is taken from the delegate too -- the id is {@code "Sym"} before the
 * chip's name, the toolbox entry reads from upstream's {@code TTL<name>} string and the caption on
 * the box is the chip's name. Sixty-one chips is enough for restating any of that per chip to be a
 * source of typos, and it would need sixty-one new strings in twelve locales to say what upstream
 * already says.
 *
 * <p>Upstream's {@code VccGndPorts} attribute is deliberately not offered. A logic symbol does not
 * draw power pins, and leaving it out keeps the port array the same one the delegate expects.
 */
public class TtlSymbolGate extends InstanceFactory {

  /** Vertical distance between two neighbouring ports, and the size of a blank row. */
  static final int PITCH = 10;

  /** Room above the first port, where the chip's name goes. */
  static final int TOP_MARGIN = 20;

  /** Room below the last port, so the box does not end on a pin. */
  static final int BOTTOM_MARGIN = 10;

  /** Narrowest box drawn, whatever the labels ask for. */
  private static final int MIN_WIDTH = 60;

  /** Gap between the edge of the box and the text of a port's name. */
  private static final int LABEL_INSET = 3;

  /** Clear space kept between the left column of names and the right one. */
  private static final int LABEL_GUTTER = 12;

  /** Diameter of the inversion circle on an active-low port. */
  private static final int BUBBLE = 6;

  /** Half-height of the wedge on a clock port. */
  private static final int WEDGE = 4;

  /**
   * Width allowed per character of a port name, and per character of the caption. These are
   * deliberately fixed numbers rather than a measurement from {@code FontMetrics}: the box width
   * decides where the right-hand ports sit, wire endpoints in a {@code .circ} file are absolute
   * coordinates, and a width that came out of the local font would put a circuit's wires in a
   * different place on a machine whose fonts differ. Both are set above the advance of the logical
   * font actually used, so the text fits.
   */
  private static final int LABEL_CHAR_WIDTH = 6;

  private static final int CAPTION_CHAR_WIDTH = 8;

  /**
   * How upstream spells an active-low pin: an {@code n} before the symbol, after a leading group
   * number if there is one. Splitting it in two groups is what lets the {@code n} be dropped --
   * {@code n1Y4} becomes {@code 1Y4} and {@code 1nY0} becomes {@code 1Y0}.
   */
  private static final Pattern ACTIVE_LOW_PREFIX = Pattern.compile("(\\d*)n([A-Z0-9].*)");

  /** The other way upstream marks one, in pin names that are descriptions. */
  private static final String ACTIVE_LOW_WORDS = "active LOW";

  private final TtlSymbolSpec spec;
  private final AbstractTtlGate delegate;
  private final String caption;
  private final int width;
  private final int height;
  private final String[] labels;
  private final boolean[] bubbles;
  private final int[] kinds;

  public TtlSymbolGate(TtlSymbolSpec spec) {
    this(spec, spec.delegate().get());
  }

  private TtlSymbolGate(TtlSymbolSpec spec, AbstractTtlGate delegate) {
    super("Sym" + delegate.getName(), S.getter("TTL" + delegate.getName()));
    this.spec = spec;
    this.delegate = delegate;
    this.caption = delegate.getName();
    this.kinds = portKinds(delegate);
    this.labels = new String[kinds.length];
    this.bubbles = new boolean[kinds.length];
    resolveLabels(spec, delegate, labels, bubbles);
    this.width = measureWidth(spec, labels, bubbles, caption);
    this.height = TOP_MARGIN + spec.rows() * PITCH + BOTTOM_MARGIN;
    setIconName("ttl.gif");
    setAttributes(
        new Attribute[] {StdAttr.FACING, StdAttr.LABEL, StdAttr.LABEL_FONT},
        new Object[] {Direction.EAST, "", StdAttr.DEFAULT_LABEL_FONT});
    setFacingAttribute(StdAttr.FACING);
  }

  public TtlSymbolSpec getSpec() {
    return spec;
  }

  /** The DIP factory this symbol is a second face of. Used by the tests to compare the two. */
  public AbstractTtlGate getDelegate() {
    return delegate;
  }

  /** What is written beside port {@code index}. */
  String label(int index) {
    return labels[index];
  }

  /** Whether port {@code index} is drawn with an inversion circle. */
  boolean isInverted(int index) {
    return bubbles[index];
  }

  int getSymbolWidth() {
    return width;
  }

  /**
   * Which of the delegate's ports are inputs, outputs or both, taken from the DIP factory rather
   * than restated here: one throwaway DIP component is built and its ends are read. Restating it
   * would be a second copy of the pinout to keep in step with upstream's, and the count matters as
   * much as the kinds -- it is what {@link #updatePorts} checks the spec against.
   */
  private static int[] portKinds(AbstractTtlGate delegate) {
    final var probe =
        delegate.createComponent(Location.create(0, 0, true), delegate.createAttributeSet());
    final var ends = probe.getEnds();
    final var kinds = new int[ends.size()];
    for (var i = 0; i < kinds.length; i++) {
      final var end = ends.get(i);
      kinds[i] = end.isInput() && end.isOutput() ? 2 : end.isOutput() ? 1 : 0;
    }
    return kinds;
  }

  /**
   * Works out, once at construction, what is written beside every port and which ports get an
   * inversion circle. A row that gives no label of its own takes the DIP factory's, which is what
   * makes an index typed wrong in the layout table show up as a wrong name.
   *
   * <p>Polarity is read out of upstream's own pin name rather than restated per chip. Upstream
   * writes an active-low pin as an {@code n} before the symbol -- {@code nCLR}, {@code nOE1},
   * {@code n1Y4}, and {@code 1nY0} where a group number comes first -- or spells out {@code
   * "active LOW"} in the description. Either spelling means a circle, and the {@code n} comes off
   * the name because the circle already says it. Deriving it here rather than ticking a box in
   * sixty-one layout tables means the symbol cannot end up claiming a polarity the chip it
   * delegates to does not have. A chip whose factory declares no names, or that upstream never
   * marked, still says so in its layout table.
   */
  private static void resolveLabels(
      TtlSymbolSpec spec, AbstractTtlGate delegate, String[] labels, boolean[] bubbles) {
    final var pinNames = delegate.getPortNames();
    for (final var row : spec.ports()) {
      if (row.index() >= labels.length) {
        throw new IllegalStateException(
            "Sym" + delegate.getName() + " places port index " + row.index()
                + ", but the chip has only " + labels.length + " ports");
      }
      final var upstream =
          pinNames != null && row.index() < pinNames.length ? pinNames[row.index()] : null;
      if (row.label() == null && upstream == null) {
        throw new IllegalStateException(
            "Sym" + delegate.getName() + " port " + row.index() + " has no name: the chip declares"
                + " none, so the layout table has to give one");
      }
      final var stripped = upstream == null ? null : ACTIVE_LOW_PREFIX.matcher(upstream);
      final var marked = stripped != null && stripped.matches();
      labels[row.index()] =
          row.label() != null ? row.label() : marked ? stripped.group(1) + stripped.group(2) : upstream;
      bubbles[row.index()] =
          row.bubble() || marked || (upstream != null && upstream.contains(ACTIVE_LOW_WORDS));
    }
  }

  /**
   * How wide the box has to be for the two columns of names not to run into each other, rounded up
   * to the grid so the right-hand ports stay on it.
   */
  private static int measureWidth(
      TtlSymbolSpec spec, String[] labels, boolean[] bubbles, String caption) {
    var widest = MIN_WIDTH;
    for (var row = 0; row < spec.rows(); row++) {
      final var span =
          columnWidth(spec.left(), row, labels, bubbles)
              + LABEL_GUTTER
              + columnWidth(spec.right(), row, labels, bubbles);
      widest = Math.max(widest, span);
    }
    widest = Math.max(widest, caption.length() * CAPTION_CHAR_WIDTH + 2 * LABEL_INSET);
    return (widest + 9) / 10 * 10;
  }

  private static int columnWidth(List<Row> column, int row, String[] labels, boolean[] bubbles) {
    if (row >= column.size()) return 0;
    final var entry = column.get(row);
    if (entry.isGap()) return 0;
    final var ornament = bubbles[entry.index()] ? BUBBLE : entry.clock() ? WEDGE : 0;
    return LABEL_INSET + ornament + labels[entry.index()].length() * LABEL_CHAR_WIDTH;
  }

  @Override
  public Bounds getOffsetBounds(AttributeSet attrs) {
    final var dir = attrs.getValue(StdAttr.FACING);
    return Bounds.create(0, 0, width, height).rotate(Direction.EAST, dir, 0, 0);
  }

  /**
   * Where a port sits once the component is turned. The anchor is the top-left corner facing east,
   * and turning rotates every offset about it -- the same convention {@code Bounds.rotate} above
   * uses, so the ports stay on the edges of the bounds for all four facings.
   * {@code TtlSymbolLayoutTest} asserts exactly that.
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
    if (attr == StdAttr.FACING) {
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
   * Builds the port array in index order, not in drawing order. The spec places index {@code n}
   * somewhere on the symbol; this walks both columns to find where, so that {@code ps[n]} is still
   * the port the delegate's {@code propagateTtl} means by {@code n}.
   */
  private void updatePorts(Instance instance) {
    final var dir = instance.getAttributeValue(StdAttr.FACING);
    final var ports = new ArrayList<Port>();
    for (var i = 0; i < kinds.length; i++) ports.add(null);

    for (var side = 0; side < 2; side++) {
      final var leftSide = side == 0;
      final var rows = leftSide ? spec.left() : spec.right();
      final var x = leftSide ? 0 : width;
      for (var row = 0; row < rows.size(); row++) {
        final var entry = rows.get(row);
        if (entry.isGap()) continue;
        final var offset = rotate(x, TOP_MARGIN + row * PITCH, dir);
        final var kind = kinds[entry.index()];
        final var type = kind == 2 ? Port.INOUT : kind == 1 ? Port.OUTPUT : Port.INPUT;
        final var port = new Port(offset[0], offset[1], type, 1);
        final var tip = kind == 2 ? "ttlInOutTip" : kind == 1 ? "demultiplexerOutTip" : "multiplexerInTip";
        port.setToolTip(S.getter(tip, ": " + labels[entry.index()]));
        ports.set(entry.index(), port);
      }
    }
    for (var i = 0; i < ports.size(); i++) {
      if (ports.get(i) == null) {
        throw new IllegalStateException(
            "Sym" + caption + " leaves port index " + i + " off the symbol; every port must be"
                + " placed");
      }
    }
    instance.setPorts(ports.toArray(new Port[0]));
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
    final var dir = painter.getAttributeValue(StdAttr.FACING);
    final var loc = painter.getLocation();
    final var g = (Graphics2D) painter.getGraphics().create();
    try {
      g.rotate(Math.toRadians(-dir.toDegrees()), loc.getX(), loc.getY());
      final var x = loc.getX();
      final var y = loc.getY();

      if (!ghost) g.setColor(new Color(AppPreferences.COMPONENT_COLOR.get()));
      GraphicsUtil.switchToWidth(g, 2);
      g.drawRect(x, y, width, height);
      GraphicsUtil.switchToWidth(g, 1);

      g.setFont(new Font(Font.DIALOG_INPUT, Font.BOLD, 11));
      GraphicsUtil.drawCenteredText(g, caption, x + width / 2, y + TOP_MARGIN / 2);
      if (ghost) return;

      g.setFont(new Font(Font.DIALOG_INPUT, Font.PLAIN, 8));
      for (var side = 0; side < 2; side++) {
        final var leftSide = side == 0;
        final var rows = leftSide ? spec.left() : spec.right();
        for (var row = 0; row < rows.size(); row++) {
          final var entry = rows.get(row);
          if (entry.isGap()) continue;
          final var py = y + TOP_MARGIN + row * PITCH;
          final var inward = leftSide ? 1 : -1;
          final var edge = leftSide ? x : x + width;
          var textAt = edge + inward * LABEL_INSET;
          if (bubbles[entry.index()]) {
            g.drawOval(edge - BUBBLE / 2, py - BUBBLE / 2, BUBBLE, BUBBLE);
            textAt = edge + inward * (LABEL_INSET + BUBBLE / 2);
          }
          if (entry.clock()) {
            g.drawPolyline(
                new int[] {edge, edge + inward * WEDGE, edge},
                new int[] {py - WEDGE, py, py + WEDGE},
                3);
            textAt = edge + inward * (LABEL_INSET + WEDGE);
          }
          GraphicsUtil.drawText(
              g,
              labels[entry.index()],
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

  @Override
  public void propagate(InstanceState state) {
    delegate.propagateTtl(state);
  }
}
