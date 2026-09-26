/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl.internal;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.dsl.UnknownKindException;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.tools.AddTool;
import java.util.ArrayList;
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
 * localized (design doc, 十二.2 and 3.5). This table is meant to be curated by hand and copied
 * verbatim into the {@code eval} MCP tool's static description once P3 exists (design doc, 13.2):
 * do not turn it into a runtime reflection walk, or that one-time cost becomes a per-call one.
 */
public final class KindRegistry {
  private static final String CIRCUIT_PREFIX = "circuit/";

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
    if (key.startsWith(CIRCUIT_PREFIX)) {
      final var circuitName = key.substring(CIRCUIT_PREFIX.length());
      final var circuit = proj.getLogisimFile().getCircuit(circuitName);
      if (circuit == null) throw unknown(proj, key);
      return new Resolved(key, circuit.getSubcircuitFactory(), true);
    }

    final var entry = TABLE.get(key);
    if (entry == null) throw unknown(proj, key);
    final var library = proj.getLogisimFile().getLoader().getBuiltin().getLibrary(entry.libraryId());
    if (library == null) throw unknown(proj, key);
    final var tool = library.getTool(entry.factoryName());
    if (!(tool instanceof AddTool addTool)) throw unknown(proj, key);
    return new Resolved(key, addTool.getFactory(), false);
  }

  /** The reverse of {@link #resolve}, for P4 (design doc, section 十一): a hand-drawn circuit's
   * components arrive as raw {@link Component}s, not keys, so reading one back needs to go from
   * factory to key instead of key to factory. Empty for anything this table does not curate
   * (splitters, tunnels, probes, ...) -- P4 only has to read circuits built from component
   * families the DSL can also place, not arbitrary Logisim content. */
  public static Optional<Resolved> resolveExisting(Project proj, Component component) {
    final var factory = component.getFactory();
    if (factory instanceof SubcircuitFactory subcircuitFactory) {
      final var key = CIRCUIT_PREFIX + subcircuitFactory.getSubcircuit().getName();
      return Optional.of(new Resolved(key, factory, true));
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
    return Optional.empty();
  }

  public static List<String> curatedKeys() {
    return List.copyOf(TABLE.keySet());
  }

  public static List<String> availableKeys(Project proj) {
    final var keys = new ArrayList<>(curatedKeys());
    for (final Circuit circuit : proj.getLogisimFile().getCircuits()) {
      keys.add(CIRCUIT_PREFIX + circuit.getName());
    }
    return keys;
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
