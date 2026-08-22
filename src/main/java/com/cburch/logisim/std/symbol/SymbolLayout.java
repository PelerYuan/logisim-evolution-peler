/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.symbol;

import java.util.List;

/**
 * Peler Edition. One logic symbol, resolved: the two columns of rows, what is written beside each
 * port, which ports carry an inversion circle, which are inputs, and how big the box has to be.
 *
 * <p>A component whose symbol never changes builds one of these once; one whose port count follows
 * an attribute builds one per value of it. Everything {@link SymbolGate} draws or places comes from
 * here, so the two cases go down the same path.
 */
public final class SymbolLayout {

  /** Vertical distance between two neighbouring ports, and the height of a blank row. */
  public static final int PITCH = 10;

  /** Room above the first port, where the caption goes. */
  public static final int TOP_MARGIN = 20;

  /** Room below the last port, so the box does not end on a pin. */
  public static final int BOTTOM_MARGIN = 10;

  /** Narrowest box drawn, whatever the labels ask for. */
  static final int MIN_WIDTH = 60;

  /** Gap between the edge of the box and the text of a port's name. */
  static final int LABEL_INSET = 3;

  /** Clear space kept between the left column of names and the right one. */
  static final int LABEL_GUTTER = 12;

  /** Diameter of the inversion circle on an active-low port. */
  static final int BUBBLE = 6;

  /** Half-height of the wedge on a clock port. */
  static final int WEDGE = 4;

  /**
   * Width allowed per character of a port name, and per character of the caption. These are
   * deliberately fixed numbers rather than a measurement from {@code FontMetrics}: the box width
   * decides where the right-hand ports sit, wire endpoints in a project file are absolute
   * coordinates, and a width that came out of the local font would put a circuit's wires in a
   * different place on a machine whose fonts differ. Both are set above the advance of the logical
   * font actually used, so the text fits.
   */
  static final int LABEL_CHAR_WIDTH = 6;

  static final int CAPTION_CHAR_WIDTH = 8;

  /** Port kinds, as {@link #kind} reports them. */
  public static final int INPUT = 0;

  public static final int OUTPUT = 1;
  public static final int INOUT = 2;

  private final String caption;
  private final List<SymbolRow> left;
  private final List<SymbolRow> right;
  private final String[] labels;
  private final boolean[] bubbles;
  private final int[] kinds;
  private final int width;
  private final int height;

  /**
   * @param caption the name written across the top of the box
   * @param left rows down the left-hand side, top first
   * @param right rows down the right-hand side, top first
   * @param labels what to write beside each port, indexed by port index
   * @param bubbles which ports carry an inversion circle, indexed by port index
   * @param kinds {@link #INPUT}, {@link #OUTPUT} or {@link #INOUT} per port index
   */
  public SymbolLayout(
      String caption,
      List<SymbolRow> left,
      List<SymbolRow> right,
      String[] labels,
      boolean[] bubbles,
      int[] kinds) {
    this.caption = caption;
    this.left = List.copyOf(left);
    this.right = List.copyOf(right);
    this.labels = labels.clone();
    this.bubbles = bubbles.clone();
    this.kinds = kinds.clone();
    this.width = measureWidth();
    this.height = TOP_MARGIN + rows() * PITCH + BOTTOM_MARGIN;
  }

  public String caption() {
    return caption;
  }

  public List<SymbolRow> left() {
    return left;
  }

  public List<SymbolRow> right() {
    return right;
  }

  /** The rows down one side. {@code leftSide} picks which. */
  public List<SymbolRow> side(boolean leftSide) {
    return leftSide ? left : right;
  }

  public String label(int index) {
    return labels[index];
  }

  public boolean isInverted(int index) {
    return bubbles[index];
  }

  public int kind(int index) {
    return kinds[index];
  }

  public int portCount() {
    return kinds.length;
  }

  public int width() {
    return width;
  }

  public int height() {
    return height;
  }

  /** Number of rows the symbol is tall, blanks included. */
  public int rows() {
    return Math.max(left.size(), right.size());
  }

  /**
   * How wide the box has to be for the two columns of names not to run into each other, rounded up
   * to the grid so the right-hand ports stay on it.
   */
  private int measureWidth() {
    var widest = MIN_WIDTH;
    for (var row = 0; row < rows(); row++) {
      final var span = columnWidth(left, row) + LABEL_GUTTER + columnWidth(right, row);
      widest = Math.max(widest, span);
    }
    widest = Math.max(widest, caption.length() * CAPTION_CHAR_WIDTH + 2 * LABEL_INSET);
    return (widest + 9) / 10 * 10;
  }

  private int columnWidth(List<SymbolRow> column, int row) {
    if (row >= column.size()) return 0;
    final var entry = column.get(row);
    if (entry.isGap()) return 0;
    final var ornament = bubbles[entry.index()] ? BUBBLE : entry.clock() ? WEDGE : 0;
    return LABEL_INSET + ornament + labels[entry.index()].length() * LABEL_CHAR_WIDTH;
  }
}
