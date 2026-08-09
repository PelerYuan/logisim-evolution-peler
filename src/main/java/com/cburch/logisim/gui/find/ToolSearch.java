/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.find;

import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.tools.Tool;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Peler Edition. The index and the matching behind the component finder -- kept apart from the
 * dialog so the scoring can be reasoned about (and changed) without touching any Swing code.
 */
public final class ToolSearch {
  private ToolSearch() {}

  /**
   * One searchable tool.
   *
   * @param tool the tool itself, ready to hand to {@code Project.setTool}
   * @param library where it lives, shown as context and searched as well ("gates and" works)
   * @param displayName what the toolbox calls it, in the current language
   * @param idName the stable English identifier ({@code Tool.getName()}, e.g. "AND Gate")
   */
  public record Entry(Tool tool, String library, String displayName, String idName) {}

  /** An {@link Entry} that matched, with the score that decides its place in the list. */
  public record Hit(Entry entry, int score) {}

  /**
   * Every tool reachable from this project: the circuits and VHDL entities it defines itself, plus
   * every library it loads, recursively -- a loaded {@code .circ} library brings its own libraries
   * with it. Rebuilt each time the finder opens rather than cached: it costs a few hundred cheap
   * calls, and it means loading a library, adding a circuit or switching language can never leave
   * a stale index behind.
   *
   * <p>Nothing is filtered out. Searching finds the Poke and Wiring tools alongside the components
   * -- they are things you pick from the toolbox too, and typing narrows the list anyway.
   */
  public static List<Entry> index(LogisimFile file) {
    final var entries = new ArrayList<Entry>();
    collect(file, file.getDisplayName(), entries, new HashSet<>());
    return entries;
  }

  private static void collect(
      Library library, String label, List<Entry> out, Set<Library> visited) {
    // Libraries can reference one another, so a plain recursive walk is not guaranteed to be a
    // tree. Track what has been seen rather than trusting it to be one.
    if (!visited.add(library)) return;
    for (final var tool : library.getTools()) {
      if (tool == null) continue;
      out.add(new Entry(tool, label, tool.getDisplayName(), tool.getName()));
    }
    for (final var sub : library.getLibraries()) {
      collect(sub, sub.getDisplayName(), out, visited);
    }
  }

  /**
   * The entries matching {@code query}, best first. An empty query returns the first {@code limit}
   * entries in index order, so opening the finder shows something rather than a blank box.
   */
  public static List<Entry> search(List<Entry> entries, String query, int limit) {
    final var trimmed = query.trim();
    if (trimmed.isEmpty()) {
      return entries.size() <= limit ? List.copyOf(entries) : List.copyOf(entries.subList(0, limit));
    }

    final var needle = trimmed.toLowerCase(Locale.ROOT);
    final var hits = new ArrayList<Hit>();
    for (final var entry : entries) {
      final var score = scoreEntry(entry, needle);
      if (score > Integer.MIN_VALUE) hits.add(new Hit(entry, score));
    }
    hits.sort(
        Comparator.comparingInt(Hit::score)
            .reversed()
            // Same score: prefer the shorter name, then settle it alphabetically so the order is
            // stable between keystrokes instead of shuffling as the sort sees fit.
            .thenComparingInt((Hit h) -> h.entry().displayName().length())
            .thenComparing(h -> h.entry().displayName()));

    final var result = new ArrayList<Entry>(Math.min(limit, hits.size()));
    for (final var hit : hits) {
      if (result.size() >= limit) break;
      result.add(hit.entry());
    }
    return result;
  }

  /**
   * Best of the three things worth matching against. The English identifier is searched even when
   * the interface is in another language, which is the point: in a Chinese interface an AND gate
   * reads 与门, and typing "and" should still find it. The library name matches too but scores
   * lower, so "gates" narrows to that library without outranking a component actually called that.
   */
  private static int scoreEntry(Entry entry, String needle) {
    var best = Integer.MIN_VALUE;
    best = Math.max(best, score(entry.displayName(), needle));
    final var byId = score(entry.idName(), needle);
    if (byId > Integer.MIN_VALUE) best = Math.max(best, byId - 1);
    final var byLib = score(entry.library(), needle);
    if (byLib > Integer.MIN_VALUE) best = Math.max(best, byLib - 40);
    return best;
  }

  /**
   * Subsequence scoring: every character of {@code needle} must appear in {@code haystack} in
   * order, and where they land decides how good the match is.
   *
   * <p>Deliberately simple and predictable rather than clever. A match at the start of a word is
   * worth much more than one in the middle, and a run of consecutive characters is worth more than
   * the same characters scattered -- so "andg" ranks "AND Gate" above "Random Generator", and a
   * plain prefix always wins. Works unchanged for CJK, where each character is its own word.
   *
   * @return the score, or {@link Integer#MIN_VALUE} when the needle is not a subsequence at all
   */
  static int score(String haystack, String needle) {
    if (haystack == null || haystack.isEmpty()) return Integer.MIN_VALUE;
    final var target = haystack.toLowerCase(Locale.ROOT);

    var score = 0;
    var searchFrom = 0;
    var previousIndex = -2;
    for (var i = 0; i < needle.length(); i++) {
      final var found = target.indexOf(needle.charAt(i), searchFrom);
      if (found < 0) return Integer.MIN_VALUE;

      score += 1;
      if (found == 0 || isSeparator(target.charAt(found - 1))) {
        score += 12; // start of a word
      }
      if (found == previousIndex + 1) {
        score += 6; // consecutive with the previous match
      } else if (previousIndex >= 0) {
        score -= Math.min(found - previousIndex - 1, 6); // gap, but never a runaway penalty
      }
      previousIndex = found;
      searchFrom = found + 1;
    }

    if (target.equals(needle)) score += 60;
    else if (target.startsWith(needle)) score += 30;
    // Among equally good matches, a short name is more likely to be the one meant.
    score -= target.length() / 12;
    return score;
  }

  private static boolean isSeparator(char c) {
    return c == ' ' || c == '-' || c == '_' || c == '/' || c == '(' || c == '.';
  }
}
