/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.proj;

import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.tools.SetAttributeAction;
import com.cburch.logisim.util.StringGetter;
import java.util.Objects;

/**
 * Peler Edition Feature 14: setting one attribute on every component of a project that carries it.
 *
 * <p>The properties panel can already do this to a selection, but only to a selection: its
 * attribute list is the intersection over the selected components, so a Ctrl+A on a mixed circuit
 * offers nothing to edit, and a selection never reaches into another circuit at all. This is the
 * same edit with the project as its scope.
 *
 * <p>Picking the components by attribute rather than by class is deliberate. A sweep that matched
 * on {@code AbstractTtlGate} would quietly skip any future chip that did not inherit from it;
 * matching on the attribute reaches exactly the components whose properties panel would have
 * offered the same edit by hand, which is the promise the menu item is making.
 */
public final class ProjectWideAttribute {
  private ProjectWideAttribute() {
    // Utility class.
  }

  /**
   * Whether any component anywhere in the file carries {@code attr}.
   *
   * <p>Meant for menu enablement: a command that would touch nothing should say so by being greyed
   * out rather than by doing nothing when clicked.
   */
  public static boolean isCarriedByAnyComponent(LogisimFile file, Attribute<?> attr) {
    for (final var circuit : file.getCircuits()) {
      for (final var comp : circuit.getNonWires()) {
        final var attrs = comp.getAttributeSet();
        if (attrs != null && attrs.containsAttribute(attr)) return true;
      }
    }
    return false;
  }

  /**
   * One undoable action setting {@code attr} to {@code value} on every component of the file that
   * carries it, or null when they all hold that value already.
   *
   * <p>Returning null rather than an empty action matters: {@link Project#doAction} ignores null,
   * so a command that changes nothing leaves the undo history alone instead of parking a no-op
   * entry the user has to step back over.
   *
   * <p>One {@link SetAttributeAction} per circuit, because that class works through a {@code
   * CircuitMutation} and a mutation belongs to a single circuit. Joining them with {@link
   * Action#append} is what makes the whole sweep a single undo entry -- {@code JoinedAction} runs
   * them forward to redo and backward to undo. It also reports the first one's name as the name of
   * the join, so {@code name} should read as the whole command rather than as one circuit's share
   * of it.
   */
  public static <V> Action setEverywhere(
      LogisimFile file, Attribute<V> attr, V value, StringGetter name) {
    Action joined = null;
    for (final var circuit : file.getCircuits()) {
      final var perCircuit = new SetAttributeAction(circuit, name);
      for (final var comp : circuit.getNonWires()) {
        final var attrs = comp.getAttributeSet();
        if (attrs == null || !attrs.containsAttribute(attr)) continue;
        if (Objects.equals(attrs.getValue(attr), value)) continue;
        perCircuit.set(comp, attr, value);
      }
      if (perCircuit.isEmpty()) continue;
      joined = (joined == null) ? perCircuit : joined.append(perCircuit);
    }
    return joined;
  }
}
