/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.PcompReplacement;
import com.cburch.logisim.file.PcompWriter;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Action;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.util.StringUtil;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Peler Edition. Swapping one version of a component for another inside a project.
 *
 * <p>The action has to reach every circuit in the project, not only the one on screen, and it has to
 * carry over what the user set on each instance -- a component that came back facing the other way,
 * or without the label they gave it, would be a worse outcome than not offering the swap at all.
 */
class PcompReplacementTest {

  @BeforeEach
  @AfterEach
  void forgetTheCatalog() {
    PcompCatalog.useDirectory(null);
  }

  private static final PortLayout PORTS =
      PcompLayouts.automatic(
          "Top",
          PcompLayouts.nth("A", PortSide.LEFT, 0),
          PcompLayouts.nth("B", PortSide.LEFT, 1),
          PcompLayouts.nth("S", PortSide.RIGHT, 0),
          PcompLayouts.nth("C", PortSide.RIGHT, 1));

  private static File publish(Path dir, PcompMetadata metadata) throws Exception {
    final var project = PcompProjects.read(PcompProjects.THREE_CIRCUITS);
    final var file = dir.resolve(metadata.mainCircuit() + PcompFile.EXTENSION).toFile();
    PcompWriter.write(file, project, project.getCircuit("Top"), metadata, new Loader(null));
    return file;
  }

  /** Places one instance of a component in a circuit, facing south and labelled. */
  private static void place(Circuit circuit, PcompLibrary component, int x, String label) {
    final var factory = component.getCircuit().getSubcircuitFactory();
    final var attributes = factory.createAttributeSet();
    attributes.setValue(StdAttr.FACING, Direction.SOUTH);
    attributes.setValue(StdAttr.LABEL, label);
    final var mutation = new CircuitMutation(circuit);
    mutation.add(factory.createComponent(Location.create(x, 200, true), attributes));
    mutation.execute();
  }

  private record TwoVersions(Project host, PcompLibrary v1, PcompLibrary v2) {}

  /** A project with one instance of v1 in each of two circuits, and both versions installed. */
  private static TwoVersions setUp(Path dir) throws Exception {
    final var first = PcompMetadata.firstVersion("Top", PORTS);
    PcompCatalog.useDirectory(dir.toFile());
    final var v1 = PcompCatalog.install(publish(dir, first), new Loader(null));
    final var v2 =
        PcompCatalog.install(publish(dir, first.nextVersion(PORTS)), new Loader(null));

    // A real Project rather than the LogisimFile on its own: removing a component asks the
    // circuit for its simulation state, which only a project owns, so a replacement cannot be
    // exercised against a file nobody has open.
    final var host = new Project(PcompProjects.read(PcompProjects.THREE_CIRCUITS));
    // What ProjectActions.doOpen does after reading a file. A circuit that does not know its
    // project cannot be removed from -- removal asks the project for the circuit's simulation
    // state -- so without this the replacement throws where the real program would not.
    for (final var circuit : host.getLogisimFile().getCircuits()) circuit.setProject(host);
    place(host.getLogisimFile().getCircuit("Unrelated"), v1, 200, "one");
    place(host.getLogisimFile().getCircuit("Half"), v1, 400, "two");
    return new TwoVersions(host, v1, v2);
  }

  private static Action swap(TwoVersions set) {
    return PcompReplacement.replace(
        set.host().getLogisimFile(), set.v1(), set.v2(), StringUtil.constantGetter("swap"));
  }

  /** Every instance is counted, wherever in the project it sits. */
  @Test
  public void usesAreCountedAcrossEveryCircuit(@TempDir Path dir) throws Exception {
    final var set = setUp(dir);

    assertEquals(2, PcompReplacement.countUses(set.host().getLogisimFile(), set.v1()));
    assertEquals(0, PcompReplacement.countUses(set.host().getLogisimFile(), set.v2()));
  }

  /** The swap reaches both circuits and keeps what the user had set on each instance. */
  @Test
  public void replacingCarriesTheFacingAndTheLabelOver(@TempDir Path dir) throws Exception {
    final var set = setUp(dir);

    final var action = swap(set);
    assertNotNull(action, "there were instances to replace");
    set.host().doAction(action);

    assertEquals(0, PcompReplacement.countUses(set.host().getLogisimFile(), set.v1()));
    assertEquals(2, PcompReplacement.countUses(set.host().getLogisimFile(), set.v2()));
    final var replaced = onlySubcircuitIn(set.host().getLogisimFile().getCircuit("Unrelated"));
    assertEquals(Direction.SOUTH, replaced.getAttributeSet().getValue(StdAttr.FACING));
    assertEquals("one", replaced.getAttributeSet().getValue(StdAttr.LABEL));
    assertEquals(Location.create(200, 200, true), replaced.getLocation());
  }

  /** Undo puts the old version back, so a replacement is not a one-way door. */
  @Test
  public void theSwapCanBeUndone(@TempDir Path dir) throws Exception {
    final var set = setUp(dir);
    final var action = swap(set);

    set.host().doAction(action);
    set.host().undoAction();

    assertEquals(2, PcompReplacement.countUses(set.host().getLogisimFile(), set.v1()));
    assertEquals(0, PcompReplacement.countUses(set.host().getLogisimFile(), set.v2()));
  }

  /** Nothing to replace means no action at all, rather than an empty one on the undo stack. */
  @Test
  public void projectsThatDoNotUseTheVersionGetNoAction(@TempDir Path dir) throws Exception {
    final var set = setUp(dir);

    assertNull(
        PcompReplacement.replace(
            set.host().getLogisimFile(), set.v2(), set.v1(), StringUtil.constantGetter("swap")));
  }

  private static com.cburch.logisim.comp.Component onlySubcircuitIn(Circuit circuit) {
    for (final var candidate : circuit.getNonWires()) {
      if (candidate.getFactory() instanceof com.cburch.logisim.circuit.SubcircuitFactory) {
        return candidate;
      }
    }
    throw new IllegalStateException("no subcircuit in " + circuit.getName());
  }
}
