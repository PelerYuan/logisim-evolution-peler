/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.pcomp;

import com.cburch.draw.shapes.DrawAttr;
import com.cburch.logisim.pcomp.PortLayout;
import com.cburch.logisim.pcomp.PortLayoutDraft;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Peler Edition. The picture in the confirmation window, and the place the component is actually
 * laid out.
 *
 * <p>Everything on it can be dragged: a port to anywhere on the box, the box's right and bottom
 * edges to resize it, the caption to anywhere inside. Ports snap to the drawing grid because wires
 * do; the caption does not, because nothing attaches to it.
 *
 * <p><b>The top-left corner does not move.</b> It is where the component's anchor sits, and every
 * port coordinate is measured from it, so a box that could be dragged out to the left would move
 * every port in the file without the user having touched one. Widening therefore always happens to
 * the right and downwards, which is also how the drawing is read.
 *
 * <p>The geometry is the draft's, not this class's: what is drawn here is exactly what
 * {@link PortLayout} will be handed when the component is saved. What this class owns is the
 * mapping from model units to the panel -- a scale and an origin, both held still for the length of
 * a drag so that resizing the box does not move it out from under the pointer.
 */
public class PcompLayoutCanvas extends JPanel {
  private static final long serialVersionUID = 1L;

  private static final int PADDING = 48;
  private static final int MIN_SCALE = 1;
  private static final int MAX_SCALE = 3;
  private static final int GRAB = 7;
  private static final int STUB = 10;
  private static final int HANDLE = 4;

  private static final Color INPUT_COLOR = new Color(0x1a, 0x6b, 0x2f);
  private static final Color OUTPUT_COLOR = new Color(0x8a, 0x1f, 0x1f);
  private static final Color HANDLE_COLOR = new Color(0x2f, 0x6f, 0xd0);
  private static final Color GRID_COLOR = new Color(0xcc, 0xcc, 0xcc);

  /** What the pointer is on top of, which is also what a press would start dragging. */
  private enum Grip {
    NOTHING,
    PORT,
    CAPTION,
    RIGHT_EDGE,
    BOTTOM_EDGE,
    CORNER
  }

  private final PortLayoutDraft draft;
  private final Runnable onChange;

  private Grip grip = Grip.NOTHING;
  private PortLayoutDraft.Entry dragging;
  private Point pressedAt;
  private int heldScale;
  private Point heldOrigin;

  public PcompLayoutCanvas(PortLayoutDraft draft, Runnable onChange) {
    this.draft = draft;
    this.onChange = onChange;
    setBackground(Color.WHITE);
    setPreferredSize(new Dimension(460, 360));
    final var mouse = new Mouse();
    addMouseListener(mouse);
    addMouseMotionListener(mouse);
  }

  private int scale() {
    if (heldScale > 0) return heldScale;
    final var byWidth = (getWidth() - 2 * PADDING) / Math.max(1, draft.width());
    final var byHeight = (getHeight() - 2 * PADDING) / Math.max(1, draft.height());
    return Math.max(MIN_SCALE, Math.min(MAX_SCALE, Math.min(byWidth, byHeight)));
  }

  private Point origin() {
    if (heldOrigin != null) return heldOrigin;
    final var scale = scale();
    return new Point(
        (getWidth() - draft.width() * scale) / 2, (getHeight() - draft.height() * scale) / 2);
  }

  private Point toScreen(int x, int y) {
    final var origin = origin();
    final var scale = scale();
    return new Point(origin.x + x * scale, origin.y + y * scale);
  }

  private Point toModel(Point at) {
    final var origin = origin();
    final var scale = scale();
    return new Point(
        Math.floorDiv(at.x - origin.x + scale / 2, scale),
        Math.floorDiv(at.y - origin.y + scale / 2, scale));
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    final var gfx = (Graphics2D) g.create();
    gfx.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    gfx.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    final var scale = scale();
    final var origin = origin();
    final var width = draft.width() * scale;
    final var height = draft.height() * scale;

    gfx.setColor(GRID_COLOR);
    for (var x = 0; x <= draft.width(); x += PortLayout.GRID) {
      for (var y = 0; y <= draft.height(); y += PortLayout.GRID) {
        gfx.fillRect(origin.x + x * scale, origin.y + y * scale, 1, 1);
      }
    }

    gfx.setColor(Color.BLACK);
    gfx.setStroke(new BasicStroke(2f));
    gfx.drawRect(origin.x, origin.y, width, height);

    gfx.setStroke(new BasicStroke(1f));
    for (final var entry : draft.all()) paintPort(gfx, entry, scale);
    paintCaption(gfx, scale);

    gfx.setColor(HANDLE_COLOR);
    for (final var handle : new Grip[] {Grip.RIGHT_EDGE, Grip.BOTTOM_EDGE, Grip.CORNER}) {
      final var at = handleAt(handle);
      gfx.fillRect(at.x - HANDLE, at.y - HANDLE, 2 * HANDLE, 2 * HANDLE);
    }
    gfx.dispose();
  }

  /** Where one of the three resize handles sits on screen. */
  private Point handleAt(Grip handle) {
    return switch (handle) {
      case RIGHT_EDGE -> toScreen(draft.width(), draft.height() / 2);
      case BOTTOM_EDGE -> toScreen(draft.width() / 2, draft.height());
      default -> toScreen(draft.width(), draft.height());
    };
  }

  private void paintCaption(Graphics2D gfx, int scale) {
    final var text = draft.caption().isBlank() ? "?" : draft.caption();
    final var at = toScreen(draft.captionX(), draft.captionY());
    gfx.setFont(DrawAttr.DEFAULT_NAME_FONT.deriveFont(14f * scale));
    final var metrics = gfx.getFontMetrics();
    gfx.setColor(draft.caption().isBlank() ? Color.RED : Color.BLACK);
    gfx.drawString(text, at.x - metrics.stringWidth(text) / 2, baseline(at.y, metrics));
  }

  /**
   * One port: a stub sticking out of the side it belongs to, a dot on the box, and its name written
   * inside the way {@link com.cburch.logisim.pcomp.PcompAppearance} will write it.
   */
  private void paintPort(Graphics2D gfx, PortLayoutDraft.Entry entry, int scale) {
    final var at = toScreen(entry.atX(), entry.atY());
    gfx.setColor(entry.isInput() ? INPUT_COLOR : OUTPUT_COLOR);
    switch (entry.side()) {
      case LEFT -> gfx.drawLine(at.x - STUB, at.y, at.x, at.y);
      case RIGHT -> gfx.drawLine(at.x, at.y, at.x + STUB, at.y);
      case TOP -> gfx.drawLine(at.x, at.y - STUB, at.x, at.y);
      case BOTTOM -> gfx.drawLine(at.x, at.y, at.x, at.y + STUB);
    }
    gfx.fillOval(at.x - 4, at.y - 4, 8, 8);

    final var text = entry.name().isBlank() ? "?" : entry.name();
    gfx.setFont(DrawAttr.DEFAULT_FIXED_PICH_FONT.deriveFont(12f * scale));
    final var metrics = gfx.getFontMetrics();
    final var inset = PortLayout.LABEL_INSET * scale;
    final var lift = baseline(at.y, metrics);
    gfx.setColor(entry.name().isBlank() ? Color.RED : Color.DARK_GRAY);
    switch (entry.side()) {
      case LEFT -> gfx.drawString(text, at.x + inset, lift);
      case RIGHT -> gfx.drawString(text, at.x - inset - metrics.stringWidth(text), lift);
      case TOP -> gfx.drawString(
          text, at.x - metrics.stringWidth(text) / 2, at.y + inset + metrics.getAscent());
      case BOTTOM -> gfx.drawString(
          text, at.x - metrics.stringWidth(text) / 2, at.y - inset - metrics.getDescent());
    }
  }

  /**
   * The baseline that puts a line of text's middle on {@code y}, which is where the appearance
   * writes both the caption and a port's name.
   */
  private static int baseline(int y, FontMetrics metrics) {
    return y + (metrics.getAscent() - metrics.getDescent()) / 2;
  }

  /** What is under the pointer, resize handles first: they sit on top of the box's corner. */
  private Grip gripAt(Point at) {
    for (final var handle : new Grip[] {Grip.CORNER, Grip.RIGHT_EDGE, Grip.BOTTOM_EDGE}) {
      final var handleAt = handleAt(handle);
      if (Math.abs(handleAt.x - at.x) <= GRAB && Math.abs(handleAt.y - at.y) <= GRAB) return handle;
    }
    return entryAt(at) != null
        ? Grip.PORT
        : onCaption(at) ? Grip.CAPTION : Grip.NOTHING;
  }

  private PortLayoutDraft.Entry entryAt(Point at) {
    for (final var entry : draft.all()) {
      final var screen = toScreen(entry.atX(), entry.atY());
      if (Math.abs(screen.x - at.x) <= GRAB && Math.abs(screen.y - at.y) <= GRAB) return entry;
    }
    return null;
  }

  /**
   * Whether a point is on the caption. Measured against the panel's own font rather than the drawn
   * one, which is close enough for a grab box and does not need a {@code Graphics} to ask.
   */
  private boolean onCaption(Point at) {
    final var scale = scale();
    final var text = draft.caption().isBlank() ? "?" : draft.caption();
    final var centre = toScreen(draft.captionX(), draft.captionY());
    final var half = text.length() * PortLayout.CAPTION_CHAR_WIDTH * scale / 2;
    return Math.abs(at.x - centre.x) <= half
        && Math.abs(at.y - centre.y) <= PortLayout.CAPTION_HEIGHT * scale / 2;
  }

  private static Cursor cursorFor(Grip grip) {
    return switch (grip) {
      case PORT, CAPTION -> Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
      case RIGHT_EDGE -> Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR);
      case BOTTOM_EDGE -> Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR);
      case CORNER -> Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR);
      default -> Cursor.getDefaultCursor();
    };
  }

  private class Mouse extends MouseAdapter {
    @Override
    public void mouseMoved(MouseEvent event) {
      setCursor(cursorFor(gripAt(event.getPoint())));
    }

    @Override
    public void mousePressed(MouseEvent event) {
      if (!SwingUtilities.isLeftMouseButton(event)) return;
      // Held for the length of the drag. Both follow the box's size, so letting them move while the
      // box is being resized would slide the drawing out from under the pointer that is resizing it.
      heldScale = scale();
      heldOrigin = origin();
      grip = gripAt(event.getPoint());
      dragging = grip == Grip.PORT ? entryAt(event.getPoint()) : null;
      pressedAt = event.getPoint();
      repaint();
    }

    @Override
    public void mouseDragged(MouseEvent event) {
      if (pressedAt == null) return;
      final var at = toModel(event.getPoint());
      switch (grip) {
        case PORT -> {
          if (dragging != null) draft.moveTo(dragging, at.x, at.y);
        }
        case CAPTION -> draft.moveCaption(at.x, at.y);
        case RIGHT_EDGE -> draft.resize(at.x, draft.height());
        case BOTTOM_EDGE -> draft.resize(draft.width(), at.y);
        case CORNER -> draft.resize(at.x, at.y);
        default -> {
          return;
        }
      }
      repaint();
    }

    @Override
    public void mouseReleased(MouseEvent event) {
      final var moved = grip != Grip.NOTHING && pressedAt != null;
      grip = Grip.NOTHING;
      dragging = null;
      pressedAt = null;
      heldScale = 0;
      heldOrigin = null;
      if (moved) onChange.run();
      repaint();
    }

    @Override
    public void mouseClicked(MouseEvent event) {
      if (event.getClickCount() < 2) return;
      final var entry = entryAt(event.getPoint());
      if (entry != null) PcompSaveDialog.askForName(PcompLayoutCanvas.this, draft, entry, onChange);
    }
  }
}
