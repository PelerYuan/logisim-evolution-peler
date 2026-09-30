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
    applyOverrides(attrs, overrides, kind.key());
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
        throw new PlacementException(
            "bounding box overlaps an existing placement", existing, absoluteBounds);
      }
      for (final var end : component.getEnds()) {
        for (final var otherEnd : existing.rawComponent().getEnds()) {
          if (end.getLocation().equals(otherEnd.getLocation())) {
            throw new PlacementException(
                "a pin would land on an existing pin", existing, absoluteBounds);
          }
        }
      }
    }

    return space.register(kind, component);
  }

  private Location resolveAnchor(Bounds offsetBounds) {
    final var raw = resolveRawAnchor(offsetBounds);
    return Location.create(snap(raw.getX()), snap(raw.getY()), false);
  }

  /** A component whose bounds are not a whole number of cells (a subcircuit box is a pixel off)
   * would otherwise land with its pins between grid points, where no wire can reach them. */
  private static int snap(int value) {
    return Math.round(value / 10f) * 10;
  }

  private Location resolveRawAnchor(Bounds offsetBounds) {
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

  /** Applies the overrides so the outcome does not depend on the order the script wrote them in:
   * sizes first (a constant's value is reset by a later width change), then the rest, then any
   * attribute a later one knocked off its requested value is set again. */
  static void applyOverrides(AttributeSet attrs, Attrs overrides, String kindKey) {
    final var names = new java.util.ArrayList<>(overrides.names());
    names.sort((x, y) -> Boolean.compare(!isSizeLike(x), !isSizeLike(y)));
    for (final var name : names) apply(attrs, overrides, name, kindKey);
    for (final var name : names) {
      final var attr = attrs.getAttribute(name);
      @SuppressWarnings("unchecked")
      final var typed = (Attribute<Object>) attr;
      final var wanted = overrides.get(name);
      final var parsed = wanted instanceof String s ? typed.parse(s) : wanted;
      if (!java.util.Objects.equals(attrs.getValue(typed), parsed)) apply(attrs, overrides, name, kindKey);
    }
  }

  private static boolean isSizeLike(String name) {
    final var lower = name.toLowerCase(java.util.Locale.ROOT);
    return lower.contains("width") || lower.contains("bits") || lower.equals("inputs")
        || lower.equals("incoming") || lower.equals("fanout");
  }

  private static void apply(AttributeSet attrs, Attrs overrides, String name, String kindKey) {
    final var attr = attrs.getAttribute(name);
    if (attr == null) throw new UnknownAttributeException(name, kindKey, attributeNames(attrs));
    @SuppressWarnings("unchecked")
    final var typed = (Attribute<Object>) attr;
    final var value = overrides.get(name);
    final Object parsed;
    try {
      parsed = value instanceof String s ? typed.parse(s) : value;
    } catch (RuntimeException e) {
      throw invalidValue(name, String.valueOf(value), typed, e);
    }
    attrs.setValue(typed, parsed);
  }

  static java.util.List<String> attributeNames(AttributeSet attrs) {
    final var names = new java.util.ArrayList<String>();
    final var declared = attrs.getAttributes();
    if (declared != null) {
      for (final var a : declared) if (!a.isHidden()) names.add(a.getName());
    }
    return names;
  }

  static InvalidAttributeValueException invalidValue(
      String name, String value, Attribute<?> attr, RuntimeException cause) {
    final var choices = attr.getChoices();
    final var reason = choices.isEmpty()
        ? "could not be read (" + cause.getMessage() + ")"
        : "must be one of " + String.join(", ", choices);
    return new InvalidAttributeValueException(name, value, reason);
  }
}
