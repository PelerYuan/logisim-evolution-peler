/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.PcompWriter;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Peler Edition. Two versions of one component, side by side.
 *
 * <p>This is the part of the feature that everything else was arranged around. The layout is fixed
 * once published, so changing it means publishing again -- and the two publications have to be able
 * to sit in one catalog and one project at the same time, or the older one disappears out from under
 * the projects that already use it.
 *
 * <p>The catalog is a process-wide registry, so each test points it at its own folder and puts it
 * back afterwards. Left alone it would read whatever the person running the tests has installed.
 */
class PcompVersionTest {

  @BeforeEach
  @AfterEach
  void forgetTheCatalog() {
    PcompCatalog.useDirectory(null);
  }

  private static final PortLayout ORIGINAL =
      PcompLayouts.automatic(
          "Top",
          PcompLayouts.nth("A", PortSide.LEFT, 0),
          PcompLayouts.nth("B", PortSide.LEFT, 1),
          PcompLayouts.nth("S", PortSide.RIGHT, 0),
          PcompLayouts.nth("C", PortSide.RIGHT, 1));

  /** The same ports under a name with a space in it, for the labelling test. */
  private static final PortLayout HALF_ADDER =
      PcompLayouts.automatic(
          "Half Adder",
          PcompLayouts.nth("A", PortSide.LEFT, 0),
          PcompLayouts.nth("B", PortSide.LEFT, 1),
          PcompLayouts.nth("S", PortSide.RIGHT, 0),
          PcompLayouts.nth("C", PortSide.RIGHT, 1));

  /** {@code C} dragged to the bottom, which is a signature change and so a new version. */
  private static final PortLayout REARRANGED =
      PcompLayouts.moving(
          ORIGINAL, "C", PortSide.BOTTOM, ORIGINAL.width() / 2, ORIGINAL.height());

  /** Writes one version of the Top circuit into {@code dir} and hands back the file. */
  private static File publish(Path dir, PcompMetadata metadata) throws Exception {
    final var project = PcompProjects.read(PcompProjects.THREE_CIRCUITS);
    final var file = dir.resolve(metadata.mainCircuit() + PcompFile.EXTENSION).toFile();
    PcompWriter.write(file, project, project.getCircuit("Top"), metadata, new Loader(null));
    return file;
  }

  /**
   * Both versions install, and they are two different things to place.
   *
   * <p>The names are the point. A project file records a placed component as its library plus the
   * factory name, so if both versions offered a tool called {@code Top} the catalog would keep one
   * of them and every project that used the other would come back holding the wrong one.
   */
  @Test
  public void twoVersionsOfOneComponentBothInstall(@TempDir Path dir) throws Exception {
    final var first = PcompMetadata.firstVersion("Top", ORIGINAL);
    PcompCatalog.useDirectory(dir.toFile());
    PcompCatalog.install(publish(dir, first), new Loader(null));
    PcompCatalog.install(publish(dir, first.nextVersion(REARRANGED)), new Loader(null));

    final var versions = PcompCatalog.versionsOf(first.id());

    assertEquals(2, versions.size(), "both versions should be installed");
    assertEquals(1, versions.get(0).getMetadata().version());
    assertEquals(2, versions.get(1).getMetadata().version());
    assertEquals("Top v1", versions.get(0).getDisplayName());
    assertEquals("Top v2", versions.get(1).getDisplayName());

    final var tools = PcompCatalogLibrary.toolsOf(versions);
    assertEquals(2, tools.size(), "one version was dropped for sharing a name with the other");
    assertNotEquals(tools.get(0).getName(), tools.get(1).getName());
  }

  /**
   * The toolbox says what the user called the component; the file records the circuit.
   *
   * <p>Two different names for two different jobs. A project file resolves a placed component
   * through the tool's {@code getName}, which has to be the circuit's -- version suffix, spaces
   * folded to underscores and all -- while the label a person reads should be neither.
   */
  @Test
  public void theToolboxLabelIsTheNameTheUserGaveIt(@TempDir Path dir) throws Exception {
    final var first = PcompMetadata.firstVersion("Half Adder", HALF_ADDER);
    PcompCatalog.useDirectory(dir.toFile());
    final var component = PcompCatalog.install(publish(dir, first), new Loader(null));

    final var tool = PcompCatalogLibrary.toolsOf(List.of(component)).get(0);

    assertEquals("Half Adder v1", tool.getDisplayName());
    assertEquals("Half_Adder_v1", tool.getName(), "the tool's name is how a project file finds it");
    assertEquals("Half_Adder_v1", component.getCircuit().getName());
  }

  /** A new version draws its box the same size, because the caption never carried the number. */
  @Test
  public void newVersionsAreDrawnUnderTheSameName(@TempDir Path dir) throws Exception {
    final var first = PcompMetadata.firstVersion("Top", ORIGINAL);
    PcompCatalog.useDirectory(dir.toFile());
    final var v1 = PcompCatalog.install(publish(dir, first), new Loader(null));
    final var v2 =
        PcompCatalog.install(publish(dir, first.nextVersion(ORIGINAL)), new Loader(null));

    assertEquals("Top", v1.getMetadata().name());
    assertEquals("Top", v2.getMetadata().name());
    assertEquals(
        v1.getCircuit().getAppearance().getOffsetBounds(),
        v2.getCircuit().getAppearance().getOffsetBounds(),
        "the box changed size between two versions with the same ports");
  }

  /** What the layout window compares before deciding whether a save may overwrite. */
  @Test
  public void changedLayoutsAreChangedSignatures(@TempDir Path dir) throws Exception {
    final var first = PcompMetadata.firstVersion("Top", ORIGINAL);
    PcompCatalog.useDirectory(dir.toFile());
    final var v1 = PcompCatalog.install(publish(dir, first), new Loader(null));
    final var v2 =
        PcompCatalog.install(publish(dir, first.nextVersion(REARRANGED)), new Loader(null));

    final var changes = PortSignature.differences(PortSignature.of(v1), PortSignature.of(v2));

    assertEquals(1, changes.size());
    assertEquals(PortSignature.Kind.MOVED, changes.get(0).kind());
    assertEquals("C", changes.get(0).name());
  }

  /** Installing the same file again replaces its entry rather than adding a second one. */
  @Test
  public void reinstallingOneVersionDoesNotDuplicateIt(@TempDir Path dir) throws Exception {
    final var first = PcompMetadata.firstVersion("Top", ORIGINAL);
    PcompCatalog.useDirectory(dir.toFile());
    final var file = publish(dir, first);
    PcompCatalog.install(file, new Loader(null));
    PcompCatalog.install(file, new Loader(null));

    assertEquals(1, PcompCatalog.versionsOf(first.id()).size());
  }

  /** Removing a component takes its file with it, because keeping it would bring it back. */
  @Test
  public void uninstallingRemovesTheFileAndTheEntry(@TempDir Path dir) throws Exception {
    final var first = PcompMetadata.firstVersion("Top", ORIGINAL);
    PcompCatalog.useDirectory(dir.toFile());
    final var file = publish(dir, first);
    final var component = PcompCatalog.install(file, new Loader(null));

    PcompCatalog.uninstall(component);

    assertFalse(file.exists(), "the file was left behind for the next scan to find");
    assertTrue(PcompCatalog.versionsOf(first.id()).isEmpty());
  }

  /**
   * The catalog can say which component a circuit belongs to, dependencies included.
   *
   * <p>Locking rests on this. If it only knew the component's own circuit, a user stopped at the
   * front door would still get in through the circuit the component is built from.
   */
  @Test
  public void theCatalogRecognisesACircuitAndTheOnesItIsBuiltFrom(@TempDir Path dir)
      throws Exception {
    PcompCatalog.useDirectory(dir.toFile());
    final var component =
        PcompCatalog.install(
            publish(dir, PcompMetadata.firstVersion("Top", ORIGINAL)), new Loader(null));

    assertSame(component, PcompCatalog.componentOf(component.getCircuit()));
    final var dependency = dependencyOf(component);
    assertNotNull(dependency, "the fixture component should have been built from another circuit");
    assertSame(component, PcompCatalog.componentOf(dependency),
        "the circuit the component is built from is not recognised as part of it");
    assertTrue(component.isLocked(), "a published component is locked");

    final var stranger = PcompProjects.read(PcompProjects.THREE_CIRCUITS);
    assertNull(PcompCatalog.componentOf(stranger.getCircuit("Top")),
        "a circuit that merely shares a name was taken for the component itself");
  }

  /** The circuit the component is built from: the one subcircuit placed inside it. */
  private static Circuit dependencyOf(PcompLibrary component) {
    for (final var inside : component.getCircuit().getNonWires()) {
      if (inside.getFactory() instanceof SubcircuitFactory factory) return factory.getSubcircuit();
    }
    return null;
  }
}
