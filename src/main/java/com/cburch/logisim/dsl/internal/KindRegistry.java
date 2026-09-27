/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl.internal;

import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.dsl.UnknownKindException;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.vhdl.base.VhdlEntity;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Not part of the DSL's public surface -- see {@link com.cburch.logisim.dsl.Kind}, which is.
 *
 * <p>Resolves a stable {@code "<library>/<factory>"} key to a {@link ComponentFactory}. Both
 * halves come from {@code Library.getName()} / {@code ComponentFactory.getName()} (the
 * {@code _ID}-backed name every built-in class carries), never from a display string -- those are
 * localized (design doc, 十二.2 and 3.5).
 *
 * <p>{@link #TABLE} is the small, hand-curated set of friendly aliases copied verbatim into the
 * {@code eval} MCP tool's static description (design doc, 13.2) -- that one is a fixed, one-time
 * cost and must stay hand-curated, not a runtime walk. Everything {@link #TABLE} does not name is
 * reached instead through {@link #mechanicalKinds}, a runtime walk over the project's own file
 * (its circuits and whatever libraries it has loaded) plus every built-in tool, keyed by the raw
 * {@code "<library _ID>/<factory _ID>"} pair. That walk only runs on a lookup miss -- resolving a
 * {@link #TABLE} key, or a successful {@code eval} call in general, never pays for it -- so it
 * does not reintroduce the per-call cost 13.2 warns against; it exists so that the other ~230
 * built-in tools, any loaded external/pcomp library, and any subcircuit living inside one of those
 * (not just the project's own top-level circuits) are placeable at all, discoverable via the
 * existing "did you mean" suggestions on {@link UnknownKindException} rather than a dedicated
 * listing tool.
 */
public final class KindRegistry {
  private static final String CIRCUIT_PREFIX = "circuit/";
  private static final String VHDL_PREFIX = "vhdl/";

  private record Entry(String libraryId, String factoryName) {}

  /** {@code key -> (library _ID, factory name)}, both non-localized. Extend as new component
   * families are wired up; nothing about the resolution algorithm below needs to change. */
  private static final Map<String, Entry> TABLE = new LinkedHashMap<>();

  static {
    TABLE.put("gates/and_gate", new Entry("Gates", "AND Gate"));
    TABLE.put("gates/or_gate", new Entry("Gates", "OR Gate"));
    TABLE.put("gates/xor_gate", new Entry("Gates", "XOR Gate"));
    TABLE.put("gates/xnor_gate", new Entry("Gates", "XNOR Gate"));
    TABLE.put("gates/nand_gate", new Entry("Gates", "NAND Gate"));
    TABLE.put("gates/nor_gate", new Entry("Gates", "NOR Gate"));
    TABLE.put("gates/not_gate", new Entry("Gates", "NOT Gate"));
    TABLE.put("gates/buffer", new Entry("Gates", "Buffer"));
    TABLE.put("wiring/pin", new Entry("Wiring", "Pin"));
    TABLE.put("memory/register", new Entry("Memory", "Register"));
  }

  private KindRegistry() {}

  public record Resolved(String key, ComponentFactory factory, boolean subcircuit) {}

  public static Resolved resolve(Project proj, String key) {
    final var entry = TABLE.get(key);
    if (entry != null) {
      final var library = proj.getLogisimFile().getLoader().getBuiltin().getLibrary(entry.libraryId());
      final var tool = library == null ? null : library.getTool(entry.factoryName());
      if (tool instanceof AddTool addTool) return new Resolved(key, addTool.getFactory(), false);
      throw unknown(proj, key);
    }

    final var found = mechanicalKinds(proj).get(key);
    if (found == null) throw unknown(proj, key);
    return found;
  }

  /** The reverse of {@link #resolve}, for P4 (design doc, section 十一): a hand-drawn circuit's
   * components arrive as raw {@link Component}s, not keys, so reading one back needs to go from
   * factory to key instead of key to factory. Empty for anything neither {@link #TABLE} nor
   * {@link #mechanicalKinds} can name (splitters, tunnels, probes, ...) -- P4 only has to read
   * circuits built from component families the DSL can also place, not arbitrary Logisim
   * content. */
  public static Optional<Resolved> resolveExisting(Project proj, Component component) {
    final var factory = component.getFactory();
    if (factory instanceof SubcircuitFactory subcircuitFactory) {
      final var key = CIRCUIT_PREFIX + subcircuitFactory.getSubcircuit().getName();
      return Optional.of(new Resolved(key, factory, true));
    }
    if (factory instanceof VhdlEntity vhdlEntity) {
      final var key = VHDL_PREFIX + vhdlEntity.getContent().getName();
      return Optional.of(new Resolved(key, factory, false));
    }
    for (final var key : TABLE.keySet()) {
      final Resolved candidate;
      try {
        candidate = resolve(proj, key);
      } catch (UnknownKindException e) {
        continue;
      }
      if (candidate.factory() == factory) return Optional.of(candidate);
    }
    for (final var candidate : mechanicalKinds(proj).values()) {
      if (candidate.factory() == factory) return Optional.of(candidate);
    }
    return Optional.empty();
  }

  public static List<String> curatedKeys() {
    return List.copyOf(TABLE.keySet());
  }

  public static List<String> availableKeys(Project proj) {
    final var keys = new ArrayList<>(curatedKeys());
    keys.addAll(mechanicalKinds(proj).keySet());
    return keys;
  }

  /**
   * Every kind reachable by a raw {@code "<library _ID>/<factory _ID>"} key (or, for a
   * subcircuit, {@code "circuit/<name>"}, or for a VHDL entity, {@code "vhdl/<name>"} --
   * regardless of which library owns either), beyond the small hand-curated {@link #TABLE}. A
   * {@link VhdlEntity}'s own {@code ComponentFactory} name is always empty (it delegates {@code
   * getName()} to its {@link com.cburch.logisim.vhdl.base.VhdlContent} instead), so without this
   * special case it would fall into the generic {@code "<library>/<factory>"} branch keyed on
   * whatever the owning file's own name happens to be -- unstable and not what {@link
   * com.cburch.logisim.dsl.VhdlEntities} promises callers. Walks the project's own file --
   * covering its own top-level
   * circuits and, recursively, whatever libraries it has loaded (external {@code .circ}/JAR
   * libraries, pcomp libraries, subcircuits nested inside them) -- and separately
   * {@code loader.getBuiltin()}, since a bare {@code LogisimFile.createNew(loader, null)} (as
   * every existing DSL test fixture builds) never loads {@code default.templ} and so has no
   * libraries of its own even though the built-in tools must still resolve.
   */
  private static Map<String, Resolved> mechanicalKinds(Project proj) {
    final var out = new LinkedHashMap<String, Resolved>();
    final var seen = new IdentityHashMap<Library, Boolean>();
    collect(proj.getLogisimFile(), out, seen);
    collect(proj.getLogisimFile().getLoader().getBuiltin(), out, seen);
    return out;
  }

  private static void collect(Library library, Map<String, Resolved> out, Map<Library, Boolean> seen) {
    if (seen.putIfAbsent(library, Boolean.TRUE) != null) return;
    for (final var tool : library.getTools()) {
      if (!(tool instanceof AddTool addTool)) continue;
      final var factory = addTool.getFactory();
      final String key;
      final boolean subcircuit;
      if (factory instanceof SubcircuitFactory subcircuitFactory) {
        key = CIRCUIT_PREFIX + subcircuitFactory.getSubcircuit().getName();
        subcircuit = true;
      } else if (factory instanceof VhdlEntity vhdlEntity) {
        key = VHDL_PREFIX + vhdlEntity.getContent().getName();
        subcircuit = false;
      } else {
        key = library.getName() + "/" + factory.getName();
        subcircuit = false;
      }
      out.putIfAbsent(key, new Resolved(key, factory, subcircuit));
    }
    for (final var nested : library.getLibraries()) {
      collect(nested, out, seen);
    }
  }

  private static UnknownKindException unknown(Project proj, String key) {
    return new UnknownKindException(key, nearest(proj, key));
  }

  private static List<String> nearest(Project proj, String key) {
    final var candidates = availableKeys(proj);
    candidates.sort((a, b) -> distance(key, a) - distance(key, b));
    final var top = new ArrayList<String>();
    for (final var candidate : candidates) {
      if (distance(key, candidate) <= Math.max(3, key.length() / 2)) top.add(candidate);
      if (top.size() == 3) break;
    }
    return top;
  }

  /** Plain Levenshtein distance -- good enough for "did you mean" suggestions, not for anything
   * that needs to be fast at scale. */
  private static int distance(String a, String b) {
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
}
