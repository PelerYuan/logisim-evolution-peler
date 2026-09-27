/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.StdAttr;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** A placed component. Wraps a live Logisim {@link Component} that already has real ports (design
 * doc, 3.11) even though it may not be part of any {@link com.cburch.logisim.circuit.Circuit} yet
 * -- placement is immediate, commit is deferred (design doc, section 六). */
public final class Comp {
  private final Space space;
  private final Kind kind;
  private final String id;
  private Component component;
  private int generation;
  private Port[] portCache;

  Comp(Space space, Kind kind, String id, Component component) {
    this.space = space;
    this.kind = kind;
    this.id = id;
    this.component = component;
  }

  public Kind kind() {
    return kind;
  }

  public String id() {
    return id;
  }

  public Optional<String> label() {
    final var attr = component.getAttributeSet().getAttribute("label");
    if (attr == null) return Optional.empty();
    @SuppressWarnings("unchecked")
    final var value = (String) component.getAttributeSet().getValue((Attribute<Object>) attr);
    return (value == null || value.isEmpty()) ? Optional.empty() : Optional.of(value);
  }

  public Comp label(String text) {
    return set(StdAttr.LABEL.getName(), text);
  }

  public List<Port> ports() {
    ensurePortCache();
    return List.of(portCache);
  }

  public Port port(int index) {
    ensurePortCache();
    if (index < 0 || index >= portCache.length) {
      throw new UnknownPortException(this, index, List.of(portCache), null);
    }
    return portCache[index];
  }

  public Port port(String pinLabel) {
    ensurePortCache();
    for (final var p : portCache) {
      if (p.name().isPresent() && p.name().get().equals(pinLabel)) return p;
    }
    final var suggestion = ports().stream()
        .map(p -> p.name().orElse(null))
        .filter(n -> n != null)
        .min((a, b) -> Integer.compare(editDistance(pinLabel, a), editDistance(pinLabel, b)))
        .map(n -> "did you mean \"" + n + "\"?")
        .orElse(null);
    throw new UnknownPortException(this, pinLabel, List.of(portCache), suggestion);
  }

  public List<Port> inputs() {
    return ports().stream().filter(p -> p.dir() == Port.Dir.IN || p.dir() == Port.Dir.INOUT)
        .collect(Collectors.toList());
  }

  public List<Port> outputs() {
    return ports().stream().filter(p -> p.dir() == Port.Dir.OUT || p.dir() == Port.Dir.INOUT)
        .collect(Collectors.toList());
  }

  public Attrs attrs() {
    final var attributes = component.getAttributeSet().getAttributes();
    if (attributes == null) return Attrs.of();
    final var map = new java.util.LinkedHashMap<String, Object>();
    for (final var attr : attributes) {
      @SuppressWarnings("unchecked")
      final var typed = (Attribute<Object>) attr;
      map.put(attr.getName(), component.getAttributeSet().getValue(typed));
    }
    return Attrs.fromMap(map);
  }

  /** Sets a single attribute by Logisim's stable name and invalidates every {@link Port} vended
   * before this call (design doc, 3.8 -- attribute changes can change the port count itself). */
  @SuppressWarnings("unchecked")
  public Comp set(String attrName, Object value) {
    final var attr = component.getAttributeSet().getAttribute(attrName);
    if (attr == null) {
      throw new IllegalArgumentException(attrName + " is not an attribute of " + kind.key());
    }
    final var typed = (Attribute<Object>) attr;
    final Object parsed = value instanceof String s ? typed.parse(s) : value;
    component.getAttributeSet().setValue(typed, parsed);
    generation++;
    portCache = null;
    return this;
  }

  public Comp facing(Direction d) {
    return set(StdAttr.FACING.getName(), d);
  }

  public Dot origin() {
    final var loc = component.getLocation();
    return new Dot(loc.getX(), loc.getY());
  }

  public Bounds bounds() {
    return component.getBounds();
  }

  Space space() {
    return space;
  }

  int generation() {
    return generation;
  }

  Component rawComponent() {
    return component;
  }

  /** Rebinds this {@link Comp} onto a fresh {@link Component} the circuit now actually holds --
   * used by {@link Space#ensureAwayFromOrigin} after a bulk translate replaces every component in
   * the circuit, so the same {@code Comp} object (same id, same identity a caller may already be
   * holding) keeps working rather than going stale. */
  void rebind(Component newComponent) {
    this.component = newComponent;
    generation++;
    portCache = null;
  }

  Optional<String> toolTipFor(int index) {
    final var instance = Instance.getInstanceFor(component);
    if (instance == null) return Optional.empty();
    final var ports = instance.getPorts();
    if (index < 0 || index >= ports.size()) return Optional.empty();
    return Optional.ofNullable(ports.get(index).getToolTip());
  }

  private void ensurePortCache() {
    if (portCache != null) return;
    final var ends = component.getEnds();
    final var built = new Port[ends.size()];
    final var gen = generation;
    for (var i = 0; i < ends.size(); i++) {
      final String subcircuitLabel = kind.isSubcircuit() ? toolTipFor(i).orElse(null) : null;
      built[i] = new Port(this, i, ends.get(i), gen, subcircuitLabel);
    }
    portCache = built;
  }

  private static int editDistance(String a, String b) {
    final var dp = new int[a.length() + 1][b.length() + 1];
    for (var i = 0; i <= a.length(); i++) dp[i][0] = i;
    for (var j = 0; j <= b.length(); j++) dp[0][j] = j;
    for (var i = 1; i <= a.length(); i++) {
      for (var j = 1; j <= b.length(); j++) {
        final var cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
      }
    }
    return dp[a.length()][b.length()];
  }

  @Override
  public String toString() {
    return id;
  }
}
