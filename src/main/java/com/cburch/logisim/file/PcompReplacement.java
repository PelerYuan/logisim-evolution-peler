/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.file;

import com.cburch.logisim.circuit.CircuitAttributes;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.pcomp.PcompComponent;
import com.cburch.logisim.proj.Action;
import com.cburch.logisim.util.StringGetter;
import java.util.ArrayList;
import java.util.List;

/**
 * Peler Edition. Swapping one version of a custom component for another, throughout a project.
 *
 * <p>Two versions of a component are two component types, so a project that used v1 keeps using v1
 * until somebody says otherwise. This is that somebody: it finds every instance of one version and
 * puts the other in its place, at the same coordinates and with the same attributes.
 *
 * <p><b>Wires are not touched.</b> When the signatures match, none need to be: the ports are on the
 * same coordinates, so every wire still lands on the port it landed on before. When they do not,
 * the wires stay where the user drew them and some of them are now attached to nothing, which is
 * visible and fixable. Moving them would mean guessing which port each one had meant, and a wrong
 * guess is a circuit that opens, simulates, and computes something else -- the same reasoning
 * {@code PelerCompat} sets out for a dropped symbol chip.
 *
 * <p>Lives beside {@link LoadedLibrary} to share its attribute copy, which is the piece that keeps
 * a replaced component facing the way it was facing and keeping the label it had.
 */
public final class PcompReplacement {
  private PcompReplacement() {}

  /** How many instances of {@code component} the project holds, across all of its circuits. */
  public static int countUses(LogisimFile file, PcompComponent component) {
    var count = 0;
    for (final var circuit : file.getCircuits()) {
      count += usesIn(circuit.getNonWires(), component).size();
    }
    return count;
  }

  /**
   * An undoable action putting {@code to} everywhere {@code from} is, or null if it is nowhere.
   *
   * <p>One action for the whole project rather than one per circuit. A replacement that a user
   * could undo halfway would leave the project holding both versions of a component with no way to
   * tell which instances had been reached.
   */
  public static Action replace(
      LogisimFile file, PcompComponent from, PcompComponent to, StringGetter name) {
    final var newFactory = to.getCircuit().getSubcircuitFactory();
    Action joined = null;
    for (final var circuit : file.getCircuits()) {
      final var found = usesIn(circuit.getNonWires(), from);
      if (found.isEmpty()) continue;
      final var mutation = new CircuitMutation(circuit);
      for (final var instance : found) {
        mutation.replace(
            instance,
            newFactory.createComponent(
                instance.getLocation(), attributesForReplacement(newFactory, instance)));
      }
      final var action = mutation.toAction(name);
      joined = joined == null ? action : joined.append(action);
    }
    return joined;
  }

  /**
   * {@link LoadedLibrary#createAttributes} copies every same-named attribute from the old
   * instance onto the new one, {@code CircuitAttributes.NAME_ATTR} included. For a plain library
   * reload that attribute already holds the same value on both sides, so the copy is a no-op --
   * but a pcomp version bump gives each version's circuit a distinct name on purpose, and
   * {@code CircuitAttributes.setValue(NAME_ATTR, ...)} is not a per-instance label: it calls
   * {@code Circuit.setName} on the shared circuit the attribute set is bound to. Copying it here
   * would rename {@code to}'s circuit to {@code from}'s name as a side effect of the swap, which
   * silently collapses both versions onto one name. So this reads {@code newFactory}'s own name
   * before the copy runs and restores it afterward, undoing just that one side effect.
   */
  private static AttributeSet attributesForReplacement(ComponentFactory newFactory, Component instance) {
    final var ownName = newFactory.getName();
    final var dest = LoadedLibrary.createAttributes(newFactory, instance.getAttributeSet());
    dest.setValue(CircuitAttributes.NAME_ATTR, ownName);
    return dest;
  }

  private static List<Component> usesIn(
      Iterable<? extends Component> components, PcompComponent component) {
    final var factory = component.getCircuit().getSubcircuitFactory();
    final var found = new ArrayList<Component>();
    for (final var candidate : components) {
      if (candidate.getFactory() == factory) found.add(candidate);
    }
    return found;
  }
}
