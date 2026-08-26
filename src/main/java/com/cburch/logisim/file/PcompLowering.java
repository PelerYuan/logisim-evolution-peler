/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.file;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.pcomp.PcompCatalog;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Peler Edition. What a custom component turns into when the project is saved to official {@code
 * .circ}.
 *
 * <p>Over here a component is a circuit that lives in a file of its own and is offered by a library
 * upstream has never heard of. Over there neither exists, so the component is written into the
 * project as an ordinary circuit, and every place that used it becomes a plain subcircuit
 * reference. The appearance goes with it -- it is the circuit's own custom appearance, so {@code
 * XmlWriter} writes it out with no help from here -- which is what keeps the ports on the same
 * coordinates and every wire where the user left it.
 *
 * <p>This is a copy, not a move. The project the user is still editing keeps its components; only
 * the bytes on the way to the {@code .circ} see them inlined. Reopening that {@code .circ} here
 * gives back a project with a few more circuits in it and no components -- the lowering is one-way,
 * as every lowering in a compatible save is.
 *
 * <p><b>Dependencies come too.</b> A component may be built from circuits the user never placed
 * anywhere; those are in its file, and without them the inlined circuit refers to subcircuits the
 * {@code .circ} does not contain.
 */
public final class PcompLowering {
  private PcompLowering() {}

  /**
   * Decides which circuits have to be written into {@code file} and under what names.
   *
   * <p>The walk is over every circuit reachable from the project, not just the ones the project
   * places directly: a component placed inside another component is reached through the first, and
   * a component's own dependencies are reached through it.
   *
   * @return the circuits to inline and the name each takes, in the order they were found; empty if
   *     the project uses no custom components
   */
  public static Map<Circuit, String> plan(LogisimFile file) {
    final var planned = new LinkedHashMap<Circuit, String>();
    if (file == null) return planned;
    final var taken = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
    for (final var circuit : file.getCircuits()) taken.add(circuit.getName());
    for (final var vhdl : file.getVhdlContents()) taken.add(vhdl.getName());

    final Set<Circuit> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    final var pending = new ArrayDeque<>(file.getCircuits());
    while (!pending.isEmpty()) {
      final var circuit = pending.removeFirst();
      if (!visited.add(circuit)) continue;
      for (final var component : circuit.getNonWires()) {
        if (!(component.getFactory() instanceof SubcircuitFactory factory)) continue;
        final var inner = factory.getSubcircuit();
        pending.add(inner);
        if (planned.containsKey(inner)) continue;
        if (PcompCatalog.componentOf(inner) == null) continue;
        planned.put(inner, unusedName(taken, inner.getName()));
      }
    }
    return planned;
  }

  /**
   * The circuit's own name where that is free, and the same with a number after it where it is not.
   *
   * <p>A clash is unlikely -- component circuits carry a version suffix -- but it is not
   * impossible, and two {@code <circuit>} elements of one name in a {@code .circ} is a file whose
   * subcircuit references have two answers. Compared without case, because that is how {@code
   * LogisimFile} compares circuit names when it decides whether one is already there.
   */
  private static String unusedName(Set<String> taken, String name) {
    var candidate = name;
    var next = 1;
    while (!taken.add(candidate)) {
      candidate = name + "_" + next;
      next++;
    }
    return candidate;
  }
}
