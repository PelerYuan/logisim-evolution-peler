/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.pcomp;

import com.cburch.logisim.pcomp.PortLayout;
import com.cburch.logisim.pcomp.PortLayoutDraft;
import com.cburch.logisim.pcomp.PortSide;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Peler Edition. The picture in the confirmation window: the component as it will be drawn, with
 * its ports draggable to any of the four sides.
 *
 * <p>The geometry is never computed here. Every coordinate comes from {@link PortLayout}, the same
 * class that will place the ports in the saved file, so what the user drags is what the component
 * becomes. This class only scales it to the panel and decides which side a drop landed on.
 */
public class PcompLayoutCanvas extends JPanel {
  private static final long serialVersionUID = 1L;

  private static final int PADDING = 44;
  private static final int MIN_SCALE = 1;
  private static final int MAX_SCALE = 3;
  private static final int GRAB_RADIUS = 9;
  private static final int STUB = 10;

  private final PortLayoutDraft draft;
  private final Runnable onChange;
  private final List<Hit> hits = new ArrayList<>();

  private PortLayoutDraft.Entry dragging;
  private Point pressedAt;
  private Point pointer;

  /** One port as it currently sits on screen, so a click can find it again. */
  private record Hit(PortLayoutDraft.Entry entry, int x, int y) {}

  public PcompLayoutCanvas(PortLayoutDraft draft, Runnable onChange) {
    this.draft = draft;
    this.onChange = onChange;
    setBackground(Color.WHITE);
    setPreferredSize(new Dimension(420, 320));
    final var mouse = new Mouse();
    addMouseListener(mouse);
    addMouseMotionListener(mouse);
  }

  private int scale(PortLayout layout) {
    final var byWidth = (getWidth() - 2 * PADDING) / Math.max(1, layout.width());
    final var byHeight = (getHeight() - 2 * PADDING) / Math.max(1, layout.height());
    return Math.max(MIN_SCALE, Math.min(MAX_SCALE, Math.min(byWidth, byHeight)));
  }

  private Point origin(PortLayout layout, int scale) {
    return new Point(
        (getWidth() - layout.width() * scale) / 2, (getHeight() - layout.height() * scale) / 2);
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    final var gfx = (Graphics2D) g.create();
    gfx.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    gfx.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

    final var layout = draft.previewLayout();
    final var scale = scale(layout);
    final var origin = origin(layout, scale);
    hits.clear();

    gfx.setColor(Color.BLACK);
    gfx.setStroke(new BasicStroke(2f));
    gfx.drawRect(origin.x, origin.y, layout.width() * scale, layout.height() * scale);

    final var metrics = gfx.getFontMetrics();
    final var caption = draft.caption();
    gfx.drawString(caption,
        origin.x + (layout.width() * scale - metrics.stringWidth(caption)) / 2,
        origin.y + layout.height() * scale / 2 + metrics.getAscent() / 2);

    gfx.setStroke(new BasicStroke(1f));
    for (final var side : PortSide.values()) {
      for (final var entry : draft.on(side)) {
        final var offset = layout.offsetOf(draft.previewNameOf(entry));
        if (offset == null) continue;
        final var x = origin.x + offset.getX() * scale;
        final var y = origin.y + offset.getY() * scale;
        hits.add(new Hit(entry, x, y));
        if (entry == dragging) continue;
        paintPort(gfx, side, x, y, entry.name(), entry.isInput());
      }
    }

    if (dragging != null && pointer != null) {
      gfx.setColor(new Color(0, 0, 0, 90));
      final var target = sideFor(layout, origin, scale, pointer);
      gfx.drawString(labelOf(dragging), pointer.x + 8, pointer.y - 6);
      gfx.fillOval(pointer.x - 4, pointer.y - 4, 8, 8);
      paintTargetEdge(gfx, layout, origin, scale, target);
    }
    gfx.dispose();
  }

  private String labelOf(PortLayoutDraft.Entry entry) {
    return entry.name().isBlank() ? "?" : entry.name();
  }

  private void paintPort(Graphics2D gfx, PortSide side, int x, int y, String name, boolean input) {
    gfx.setColor(input ? new Color(0x1a, 0x6b, 0x2f) : new Color(0x8a, 0x1f, 0x1f));
    switch (side) {
      case LEFT -> gfx.drawLine(x - STUB, y, x, y);
      case RIGHT -> gfx.drawLine(x, y, x + STUB, y);
      case TOP -> gfx.drawLine(x, y - STUB, x, y);
      case BOTTOM -> gfx.drawLine(x, y, x, y + STUB);
    }
    gfx.fillOval(x - 4, y - 4, 8, 8);

    final var text = name.isBlank() ? "?" : name;
    final var metrics = gfx.getFontMetrics();
    gfx.setColor(name.isBlank() ? Color.RED : Color.BLACK);
    switch (side) {
      case LEFT -> gfx.drawString(text, x + 6, y + metrics.getAscent() / 2 - 1);
      case RIGHT -> gfx.drawString(text, x - 6 - metrics.stringWidth(text),
          y + metrics.getAscent() / 2 - 1);
      case TOP -> gfx.drawString(text, x - metrics.stringWidth(text) / 2, y + metrics.getAscent());
      case BOTTOM -> gfx.drawString(text, x - metrics.stringWidth(text) / 2, y - 4);
    }
  }

  private void paintTargetEdge(Graphics2D gfx, PortLayout layout, Point origin, int scale,
      PortSide side) {
    final var width = layout.width() * scale;
    final var height = layout.height() * scale;
    gfx.setStroke(new BasicStroke(3f));
    gfx.setColor(new Color(0x2f, 0x6f, 0xd0));
    switch (side) {
      case LEFT -> gfx.drawLine(origin.x, origin.y, origin.x, origin.y + height);
      case RIGHT -> gfx.drawLine(origin.x + width, origin.y, origin.x + width, origin.y + height);
      case TOP -> gfx.drawLine(origin.x, origin.y, origin.x + width, origin.y);
      case BOTTOM -> gfx.drawLine(origin.x, origin.y + height, origin.x + width, origin.y + height);
    }
    gfx.setStroke(new BasicStroke(1f));
  }

  /**
   * Which side a drop belongs to.
   *
   * <p>Compared as fractions of the box rather than in pixels, so a wide flat component does not
   * hand its whole area to the left and right edges.
   */
  private PortSide sideFor(PortLayout layout, Point origin, int scale, Point at) {
    final var width = Math.max(1, layout.width() * scale);
    final var height = Math.max(1, layout.height() * scale);
    final var across = (at.x - origin.x - width / 2.0) / width;
    final var down = (at.y - origin.y - height / 2.0) / height;
    if (Math.abs(across) >= Math.abs(down)) {
      return across < 0 ? PortSide.LEFT : PortSide.RIGHT;
    }
    return down < 0 ? PortSide.TOP : PortSide.BOTTOM;
  }

  /** Where along an edge a drop lands, counting the ports already ahead of it. */
  private int slotFor(PortSide side, Point at) {
    var slot = 0;
    for (final var entry : draft.on(side)) {
      if (entry == dragging) continue;
      final var hit = hitFor(entry);
      if (hit == null) continue;
      if (side.stacked() ? hit.y() < at.y : hit.x() < at.x) slot++;
    }
    return slot;
  }

  private Hit hitFor(PortLayoutDraft.Entry entry) {
    for (final var hit : hits) {
      if (hit.entry() == entry) return hit;
    }
    return null;
  }

  private PortLayoutDraft.Entry entryAt(Point at) {
    for (final var hit : hits) {
      if (Math.abs(hit.x() - at.x) <= GRAB_RADIUS && Math.abs(hit.y() - at.y) <= GRAB_RADIUS) {
        return hit.entry();
      }
    }
    return null;
  }

  private class Mouse extends MouseAdapter {
    @Override
    public void mousePressed(MouseEvent event) {
      if (!SwingUtilities.isLeftMouseButton(event)) return;
      dragging = entryAt(event.getPoint());
      pressedAt = event.getPoint();
      pointer = event.getPoint();
      repaint();
    }

    @Override
    public void mouseDragged(MouseEvent event) {
      if (dragging == null) return;
      pointer = event.getPoint();
      repaint();
    }

    @Override
    public void mouseReleased(MouseEvent event) {
      if (dragging == null) return;
      // A double click arrives as press-release-press-release, so a release that did not travel is
      // left alone -- otherwise renaming a port would also reshuffle its side on the way in.
      if (pressedAt != null && pressedAt.distance(event.getPoint()) < 5) {
        dragging = null;
        pointer = null;
        repaint();
        return;
      }
      final var layout = draft.previewLayout();
      final var scale = scale(layout);
      final var side = sideFor(layout, origin(layout, scale), scale, event.getPoint());
      draft.moveTo(dragging, side, slotFor(side, event.getPoint()));
      dragging = null;
      pointer = null;
      onChange.run();
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
