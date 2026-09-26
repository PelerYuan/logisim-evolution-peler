/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.file;

import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.comp.Component;
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
                instance.getLocation(),
                LoadedLibrary.createAttributes(newFactory, instance.getAttributeSet())));
      }
      final var action = mutation.toAction(name);
      joined = joined == null ? action : joined.append(action);
    }
    return joined;
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
