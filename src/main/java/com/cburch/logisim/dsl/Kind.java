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
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeOptionInterface;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.dsl.internal.KindRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What can be placed, addressed by a stable key such as {@code "gates/and_gate"} (design doc,
 * section 五, resolving open question 十二.2 -- see {@link KindRegistry} for the key table and why
 * it is curated by hand rather than computed).
 *
 * <p>Deviates from the design doc's literal {@code Kind.of(String)} signature by taking a {@link
 * Space}: resolving a key needs a {@code Project} to look libraries up in, and a static global
 * would just be that dependency hidden instead of removed.
 */
public final class Kind {
  private final String key;
  private final ComponentFactory factory;
  private final boolean subcircuit;

  Kind(String key, ComponentFactory factory, boolean subcircuit) {
    this.key = key;
    this.factory = factory;
    this.subcircuit = subcircuit;
  }

  public static Kind of(Space space, String key) {
    final var resolved = KindRegistry.resolve(space.project(), key);
    return new Kind(resolved.key(), resolved.factory(), resolved.subcircuit());
  }

  public static List<Kind> available(Space space) {
    final var kinds = new ArrayList<Kind>();
    for (final var key : KindRegistry.availableKeys(space.project())) {
      kinds.add(of(space, key));
    }
    return kinds;
  }

  public String key() {
    return key;
  }

  public String displayName() {
    return factory.getDisplayName();
  }

  public List<AttrSpec> attributes() {
    return AttrTable.forKey(key, factory);
  }

  /** What placing this {@code Kind} with these attribute overrides would expose as ports --
   * queryable before anything is placed (design doc, 五: "不写名字表的底气"). */
  public List<PortSpec> portsFor(Attrs overrides) {
    final var attrs = factory.createAttributeSet();
    Placement.applyOverrides(attrs, overrides);
    final Component throwaway = factory.createComponent(Location.create(0, 0, false), attrs);
    final var specs = new ArrayList<PortSpec>();
    final var ends = throwaway.getEnds();
    for (var i = 0; i < ends.size(); i++) {
      final var end = ends.get(i);
      final Port.Dir dir;
      if (end.isInput() && end.isOutput()) dir = Port.Dir.INOUT;
      else dir = end.isOutput() ? Port.Dir.OUT : Port.Dir.IN;
      specs.add(new PortSpec(i, dir, end.getWidth().getWidth(), end.isExclusive()));
    }
    return specs;
  }

  boolean isSubcircuit() {
    return subcircuit;
  }

  ComponentFactory factory() {
    return factory;
  }

  @Override
  public String toString() {
    return key;
  }

  public record AttrSpec(String name, String kind, List<String> options) {}

  public record PortSpec(int index, Port.Dir dir, int width, boolean exclusive) {}

  /** Curated, hand-written for the small set of kinds worth spelling out precisely -- see the
   * caveat in the P1 research notes: option-attributes don't expose their legal values
   * reflectively (the candidate array is a private field on a package-private class in {@code
   * com.cburch.logisim.data.Attributes}), so a fully accurate spec for one of these has to name
   * the public constants directly rather than introspect. Everything else falls through to
   * {@link #reflect(ComponentFactory)}, a best-effort walk of the factory's own {@code
   * AttributeSet} -- real attribute names and a type guessed from the default value's runtime
   * type, which is honest (an empty {@code options} list where the legal values genuinely cannot
   * be enumerated this way) rather than the previous behavior of silently reporting only {@code
   * facing} for any kind this table did not happen to name. */
  private static final class AttrTable {
    private static final List<AttrSpec> FACING = List.of(new AttrSpec("facing", "direction",
        List.of(Direction.EAST.toString(), Direction.NORTH.toString(), Direction.WEST.toString(),
            Direction.SOUTH.toString())));
    private static final List<AttrSpec> GATE =
        concat(FACING, new AttrSpec("size", "option", List.of("30", "50", "70")),
            new AttrSpec("width", "bitWidth", List.of()));
    private static final List<AttrSpec> VARIADIC_GATE =
        concat(GATE, new AttrSpec("inputs", "intRange[2,64]", List.of()));
    private static final List<AttrSpec> PIN =
        List.of(new AttrSpec("type", "option", List.of("input", "output")),
            new AttrSpec("width", "bitWidth", List.of()));

    private static final Map<String, List<AttrSpec>> BY_KEY = Map.ofEntries(
        Map.entry("gates/and_gate", VARIADIC_GATE),
        Map.entry("gates/or_gate", VARIADIC_GATE),
        Map.entry("gates/xor_gate", GATE),
        Map.entry("gates/xnor_gate", GATE),
        Map.entry("gates/nand_gate", VARIADIC_GATE),
        Map.entry("gates/nor_gate", VARIADIC_GATE),
        Map.entry("gates/not_gate", FACING),
        Map.entry("gates/buffer", FACING),
        Map.entry("wiring/pin", PIN));

    static List<AttrSpec> forKey(String key, ComponentFactory factory) {
      final var curated = BY_KEY.get(key);
      return curated != null ? curated : reflect(factory);
    }

    private static List<AttrSpec> concat(List<AttrSpec> base, AttrSpec... more) {
      final var list = new ArrayList<>(base);
      list.addAll(List.of(more));
      return List.copyOf(list);
    }

    private static List<AttrSpec> reflect(ComponentFactory factory) {
      final var attrs = factory.createAttributeSet();
      final var declared = attrs.getAttributes();
      if (declared == null) return List.of();
      final var specs = new ArrayList<AttrSpec>();
      for (final var attr : declared) specs.add(specFor(attr, attrs));
      return List.copyOf(specs);
    }

    /** Guesses a spec from the default value's runtime type -- the only thing reachable without
     * reflecting into {@code Attributes}' private fields (see the class javadoc). {@code
     * AttributeOptionInterface} covers every component-specific enum-like option (gate size,
     * appearance, ...media/std-lib factories declare dozens of these), correctly identified as an
     * "option" attribute but without its candidate list, which is exactly the honest gap this
     * class accepts rather than papering over. */
    private static AttrSpec specFor(Attribute<?> attr, com.cburch.logisim.data.AttributeSet attrs) {
      final var name = attr.getName();
      final var value = attrs.getValue(attr);
      if (value instanceof Direction) return new AttrSpec(name, "direction", DIRECTIONS);
      if (value instanceof Boolean) return new AttrSpec(name, "boolean", List.of("true", "false"));
      if (value instanceof BitWidth) return new AttrSpec(name, "bitWidth", List.of());
      if (value instanceof Integer) return new AttrSpec(name, "int", List.of());
      if (value instanceof AttributeOptionInterface) return new AttrSpec(name, "option", List.of());
      return new AttrSpec(name, "string", List.of());
    }

    private static final List<String> DIRECTIONS = List.of(Direction.EAST.toString(),
        Direction.NORTH.toString(), Direction.WEST.toString(), Direction.SOUTH.toString());
  }
}
