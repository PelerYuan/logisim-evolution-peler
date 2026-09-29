/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitAttributes;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.dsl.internal.KindRegistry;
import com.cburch.logisim.file.LogisimFileActions;
import com.cburch.logisim.fpga.designrulecheck.CorrectLabel;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.tools.SetAttributeAction;
import com.cburch.logisim.util.StringUtil;
import com.cburch.logisim.util.SyntaxChecker;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Project-level circuit management -- create/remove/rename a circuit, set which one is main --
 * the counterpart to {@link Space}, which is deliberately scoped to a single circuit's content
 * and has no business doing any of this itself. Every method here is one immediate,
 * undo-integrated {@link com.cburch.logisim.proj.Action}, matching how the GUI's own
 * {@code Project} menu items behave (one menu click, one undo entry) rather than {@link
 * Space#commit(String)}'s batch-since-last-commit model, since there is no equivalent notion of
 * "staging" a circuit rename.
 *
 * <p>Reuses the exact validation {@code gui.menu.ProjectCircuitActions}'s dialogs apply before
 * they let a name through, but never the mutation path a GUI dialog itself uses: {@link
 * CircuitAttributes}'s own name-attribute listener pops up a Swing dialog on an invalid name
 * (it is written for a human at a keyboard), so a bad name here is rejected up front, as a
 * structured {@link DslException}, before {@link CircuitMutation#setForCircuit} is ever called
 * with it.
 */
public final class Circuits {
  private final Project proj;

  private Circuits(Project proj) {
    this.proj = proj;
  }

  public static Circuits of(Space space) {
    return new Circuits(space.project());
  }

  /** Circuit names in this project's own file order (not alphabetical). */
  public List<String> list() {
    final var names = new ArrayList<String>();
    for (final var circuit : proj.getLogisimFile().getCircuits()) names.add(circuit.getName());
    return names;
  }

  public String mainName() {
    final var main = proj.getLogisimFile().getMainCircuit();
    return main == null ? null : main.getName();
  }

  public void create(String name) {
    validateNewName(name, null);
    final var circuit = new Circuit(name, proj.getLogisimFile(), proj);
    proj.doAction(LogisimFileActions.addCircuit(circuit));
  }

  public void remove(String name) {
    final var circuit = require(name);
    if (proj.getLogisimFile().getCircuits().size() == 1) {
      throw new CircuitInUseException(name, "it is this project's only circuit");
    }
    if (!proj.getDependencies().canRemove(circuit)) {
      throw new CircuitInUseException(
          name, "it is placed as a subcircuit by another circuit in this project");
    }
    proj.doAction(LogisimFileActions.removeCircuit(circuit));
  }

  public void rename(String oldName, String newName) {
    final var circuit = require(oldName);
    validateNewName(newName, oldName);
    final var mutation = new CircuitMutation(circuit);
    mutation.setForCircuit(CircuitAttributes.NAME_ATTR, newName);
    proj.doAction(mutation.toAction(StringUtil.constantGetter("rename circuit")));
  }

  public void setMain(String name) {
    proj.doAction(LogisimFileActions.setMainCircuit(require(name)));
  }

  /** Sets one attribute on every component of the whole project that carries an attribute of that
   * name -- every circuit, subcircuits included -- as a single undo entry, the scripting
   * counterpart of the Project menu's "TTL chip drawing" commands (which are this with the
   * attribute {@code ShowInternalStructure}). {@code value} is read the way {@link Comp#set} reads
   * one. {@code kindKey}, when non-null, restricts the sweep to components of that one kind (a key
   * from {@code kinds}). Components already holding the value are left alone; when none change,
   * no undo entry is made. Returns how many components changed. */
  public int setEverywhere(String attrName, String value, String kindKey) {
    final var factory = kindKey == null ? null : KindRegistry.resolve(proj, kindKey).factory();
    final var name = StringUtil.constantGetter("set " + attrName + " everywhere");
    com.cburch.logisim.proj.Action joined = null;
    var changed = 0;
    for (final var circuit : proj.getLogisimFile().getCircuits()) {
      final var perCircuit = new SetAttributeAction(circuit, name);
      for (final var comp : circuit.getNonWires()) {
        if (factory != null && comp.getFactory() != factory) continue;
        final var attr = comp.getAttributeSet().getAttribute(attrName);
        if (attr == null) continue;
        @SuppressWarnings("unchecked")
        final var typed = (Attribute<Object>) attr;
        final Object parsed;
        try {
          parsed = typed.parse(value);
        } catch (RuntimeException e) {
          throw new InvalidAttributeValueException(
              attrName, value, e.getMessage() == null ? e.toString() : e.getMessage());
        }
        if (Objects.equals(comp.getAttributeSet().getValue(typed), parsed)) continue;
        perCircuit.set(comp, typed, parsed);
        changed++;
      }
      if (perCircuit.isEmpty()) continue;
      joined = joined == null ? perCircuit : joined.append(perCircuit);
    }
    if (joined != null) proj.doAction(joined);
    return changed;
  }

  private Circuit require(String name) {
    final var circuit = proj.getLogisimFile().getCircuit(name);
    if (circuit == null) throw new UnknownCircuitException(name, nearest(name));
    return circuit;
  }

  /** {@code exemptFrom}, when non-null, is the name being renamed away from: a case-only rename
   * ("Foo" -> "foo") must not trip the duplicate check on the very entry it is renaming. */
  private void validateNewName(String name, String exemptFrom) {
    if (name == null || name.isEmpty()) {
      throw new InvalidCircuitNameException(name, "name must not be empty");
    }
    if (CorrectLabel.isKeyword(name, false)) {
      throw new InvalidCircuitNameException(name, "\"" + name + "\" is a reserved VHDL/Verilog keyword");
    }
    final var inUse = exemptFrom == null || !name.equalsIgnoreCase(exemptFrom);
    if (inUse && nameIsInUse(name)) {
      throw new DuplicateCircuitNameException(name);
    }
    final var message = SyntaxChecker.getErrorMessage(name);
    if (message != null) {
      throw new InvalidCircuitNameException(name, message);
    }
  }

  private boolean nameIsInUse(String name) {
    for (final var lib : proj.getLogisimFile().getLibraries()) {
      if (nameIsInLibraries(lib, name)) return true;
    }
    for (final var tool : proj.getLogisimFile().getTools()) {
      if (name.equalsIgnoreCase(tool.getName())) return true;
    }
    return false;
  }

  private boolean nameIsInLibraries(Library lib, String name) {
    for (final var nested : lib.getLibraries()) {
      if (nameIsInLibraries(nested, name)) return true;
    }
    for (final var tool : lib.getTools()) {
      if (name.equalsIgnoreCase(tool.getName())) return true;
    }
    return false;
  }

  private List<String> nearest(String name) {
    final var candidates = list();
    candidates.sort((a, b) -> distance(name, a) - distance(name, b));
    final var top = new ArrayList<String>();
    for (final var candidate : candidates) {
      if (distance(name, candidate) <= Math.max(3, name.length() / 2)) top.add(candidate);
      if (top.size() == 3) break;
    }
    return top;
  }

  /** Plain Levenshtein distance -- same purpose and shape as {@code KindRegistry}'s copy, kept
   * separate rather than shared since both are small, private, and belong to unrelated key
   * spaces (kinds vs. circuit names). */
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
