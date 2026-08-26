/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import com.cburch.draw.model.CanvasObject;
import com.cburch.draw.shapes.DrawAttr;
import com.cburch.draw.shapes.Rectangle;
import com.cburch.draw.shapes.Text;
import com.cburch.draw.util.EditableLabel;
import com.cburch.logisim.circuit.appear.AppearanceAnchor;
import com.cburch.logisim.circuit.appear.AppearancePort;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.instance.Instance;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Peler Edition. Turns a {@link PortLayout} into the shapes that draw it.
 *
 * <p>This is the join between the two halves of the feature. The layout is the model the user
 * drags around -- a box, a caption and a coordinate per port -- and is what a {@code .pcomp} file
 * stores. The appearance is what Logisim already knows how to draw, place, rotate and wire up, and
 * is derived here rather than stored. Deriving it is what keeps the layout the single description
 * of the component: were the shapes the stored form, the ordinary appearance editor could move a
 * port and the layout would no longer describe it.
 *
 * <p><b>The anchor sits on the box's top-left corner.</b> Port offsets are taken relative to it, so
 * each one comes out exactly the coordinate {@link PortLayout} computed, and the offset bounds come
 * out exactly the box -- appearance elements contribute only their location to the bounds, not
 * their drawn size, so an anchor inside the rectangle adds nothing. Both facts are what let a
 * project using custom components be written to official {@code .circ} as plain subcircuits with
 * no wire moving: the subcircuit carries this same appearance, so its ports are in the same places.
 */
public final class PcompAppearance {
  private PcompAppearance() {}

  /**
   * Where the drawing is placed on the appearance canvas. Arbitrary, and cancelled out by the
   * anchor; it exists so the shapes do not sit in the canvas's top-left corner where they are
   * awkward to look at.
   */
  private static final int OFFS = 50;

  /** Lifts a name's baseline so the text is centred on its port rather than sitting under it. */
  private static final int BASELINE_LIFT =
      (DrawAttr.FIXED_FONT_ASCENT - DrawAttr.FIXED_FONT_DESCENT) / 2;

  /**
   * Builds the shapes for one custom component.
   *
   * @param layout the box and its ports
   * @param pins the component circuit's pins, by name; every port in the layout must be in here
   * @throws IllegalArgumentException if a port has no pin of that name
   */
  public static List<CanvasObject> build(PortLayout layout, Map<String, Instance> pins) {
    final var shapes = new ArrayList<CanvasObject>();

    final var box = new Rectangle(OFFS, OFFS, layout.width(), layout.height());
    box.setValue(DrawAttr.STROKE_WIDTH, 2);
    shapes.add(box);

    final var caption =
        new Text(
            OFFS + layout.captionX(), OFFS + layout.captionY() + BASELINE_LIFT, layout.caption());
    caption.getLabel().setHorizontalAlignment(EditableLabel.CENTER);
    caption.getLabel().setColor(Color.BLACK);
    caption.getLabel().setFont(DrawAttr.DEFAULT_NAME_FONT);
    shapes.add(caption);

    for (final var placement : layout.placements()) {
      final var pin = pins.get(placement.name());
      if (pin == null) {
        throw new IllegalArgumentException(
            layout.caption() + " has no pin named " + placement.name());
      }
      final var offset = layout.offsetOf(placement.name());
      final var x = OFFS + offset.getX();
      final var y = OFFS + offset.getY();
      shapes.add(nameFor(placement, x, y));
      shapes.add(new AppearancePort(Location.create(x, y, false), pin));
    }

    shapes.add(new AppearanceAnchor(Location.create(OFFS, OFFS, false)));
    return shapes;
  }

  /**
   * A port's name, written inside the box against the edge the port is on. Every name is written
   * the same way up -- the appearance model's text carries no angle -- which is why a laid-out
   * side's pitch has to be wide enough for its names rather than its band being deep enough.
   */
  private static Text nameFor(PortPlacement placement, int x, int y) {
    final int textX;
    final int textY;
    final int alignment;
    switch (placement.side()) {
      case LEFT -> {
        textX = x + PortLayout.LABEL_INSET;
        textY = y + BASELINE_LIFT;
        alignment = EditableLabel.LEFT;
      }
      case RIGHT -> {
        textX = x - PortLayout.LABEL_INSET;
        textY = y + BASELINE_LIFT;
        alignment = EditableLabel.RIGHT;
      }
      case TOP -> {
        textX = x;
        textY = y + PortLayout.LABEL_INSET + DrawAttr.FIXED_FONT_ASCENT;
        alignment = EditableLabel.CENTER;
      }
      default -> {
        textX = x;
        textY = y - PortLayout.LABEL_INSET - DrawAttr.FIXED_FONT_DESCENT;
        alignment = EditableLabel.CENTER;
      }
    }
    final var text = new Text(textX, textY, placement.name());
    text.getLabel().setHorizontalAlignment(alignment);
    text.getLabel().setColor(Color.DARK_GRAY);
    text.getLabel().setFont(DrawAttr.DEFAULT_FIXED_PICH_FONT);
    return text;
  }
}
