/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.instance.StdAttr;

/** Builder returned by {@link Space#place(Kind)}. Nothing happens until {@link #place()} -- the
 * builder just accumulates where and how, so {@code at}/{@code anchorAt}/{@code rightOf}/{@code
 * below} may be called at most once each and are mutually exclusive. */
public final class Placement {
  private final Space space;
  private final Kind kind;
  private Attrs overrides = Attrs.of();
  private Direction facing;
  private Integer col;
  private Integer row;
  private boolean anchorGiven;
  private Comp rightOf;
  private int rightOfGap;
  private Comp below;
  private int belowGap;

  Placement(Space space, Kind kind) {
    this.space = space;
    this.kind = kind;
  }

  public Placement at(int col, int row) {
    this.col = col;
    this.row = row;
    this.anchorGiven = false;
    return this;
  }

  public Placement anchorAt(int col, int row) {
    this.col = col;
    this.row = row;
    this.anchorGiven = true;
    return this;
  }

  public Placement rightOf(Comp other, int gap) {
    this.rightOf = other;
    this.rightOfGap = gap;
    return this;
  }

  public Placement below(Comp other, int gap) {
    this.below = other;
    this.belowGap = gap;
    return this;
  }

  public Placement with(Attrs attrs) {
    this.overrides = attrs;
    return this;
  }

  public Placement facing(Direction d) {
    this.facing = d;
    return this;
  }

  public Comp place() {
    final var attrs = kind.factory().createAttributeSet();
    applyOverrides(attrs, overrides);
    if (facing != null) {
      final var facingAttr = attrs.getAttribute(StdAttr.FACING.getName());
      if (facingAttr != null) {
        @SuppressWarnings("unchecked")
        final var typed = (Attribute<Object>) facingAttr;
        attrs.setValue(typed, facing);
      }
    }

    final var offsetBounds = kind.factory().getOffsetBounds(attrs);
    final Location anchor = resolveAnchor(offsetBounds);
    final var component = kind.factory().createComponent(anchor, attrs);
    final var absoluteBounds = component.getBounds();

    for (final var existing : space.pendingComponents()) {
      if (!absoluteBounds.intersect(existing.bounds()).equals(Bounds.EMPTY_BOUNDS)) {
        throw new PlacementException("bounding box overlaps an existing placement", existing);
      }
      for (final var end : component.getEnds()) {
        for (final var otherEnd : existing.rawComponent().getEnds()) {
          if (end.getLocation().equals(otherEnd.getLocation())) {
            throw new PlacementException("a pin would land on an existing pin", existing);
          }
        }
      }
    }

    return space.register(kind, component);
  }

  private Location resolveAnchor(Bounds offsetBounds) {
    if (col != null && anchorGiven) {
      return Location.create(col * 10, row * 10, false);
    }
    if (col != null) {
      return Location.create(col * 10 - offsetBounds.getX(), row * 10 - offsetBounds.getY(), false);
    }
    if (rightOf != null) {
      final var otherBounds = rightOf.bounds();
      final var targetCol = (otherBounds.getX() + otherBounds.getWidth()) / 10 + rightOfGap;
      final var targetRow = otherBounds.getY() / 10;
      return Location.create(targetCol * 10 - offsetBounds.getX(), targetRow * 10 - offsetBounds.getY(), false);
    }
    if (below != null) {
      final var otherBounds = below.bounds();
      final var targetCol = otherBounds.getX() / 10;
      final var targetRow = (otherBounds.getY() + otherBounds.getHeight()) / 10 + belowGap;
      return Location.create(targetCol * 10 - offsetBounds.getX(), targetRow * 10 - offsetBounds.getY(), false);
    }
    final var auto = space.nextAutoLayoutDot();
    return Location.create(auto[0] * 10 - offsetBounds.getX(), auto[1] * 10 - offsetBounds.getY(), false);
  }

  /** Shared with {@link Kind#portsFor(Attrs)}, which needs the same override application to build a
   * throwaway component without going through a full {@code place()}. */
  static void applyOverrides(AttributeSet attrs, Attrs overrides) {
    for (final var name : overrides.names()) {
      final var attr = attrs.getAttribute(name);
      if (attr == null) {
        throw new IllegalArgumentException(name + " is not a recognized attribute here");
      }
      @SuppressWarnings("unchecked")
      final var typed = (Attribute<Object>) attr;
      final var value = overrides.get(name);
      attrs.setValue(typed, value instanceof String s ? typed.parse(s) : value);
    }
  }
}
