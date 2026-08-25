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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.data.Direction;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.PcompWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Peler Edition. Saving a circuit as a component, and getting it back.
 *
 * <p>The two halves that only meet here: the writer has to carry the circuits the component needs
 * and leave behind the ones it does not, and what it writes has to be exactly what {@link
 * PcompLibrary} can load. Everything else in this package tests one side of that.
 */
class PcompWriterTest {

  private static Path saveTop(Path dir, String name) throws Exception {
    final var project = PcompProjects.read(PcompProjects.THREE_CIRCUITS);
    final var top = project.getCircuit("Top");
    final var draft = PortLayoutDraft.of(top);
    final var metadata = PcompMetadata.firstVersion(top.getName(), draft.placements());
    final var destination = dir.resolve(name + PcompFile.EXTENSION);
    PcompWriter.write(destination.toFile(), project, top, metadata, new Loader(null));
    return destination;
  }

  /** What the component reaches comes along; what it does not is left in the project. */
  @Test
  public void onlyTheCircuitsTheComponentNeedsAreCarried(@TempDir Path dir) throws Exception {
    final var file = saveTop(dir, "Top");

    final var written = Files.readString(file, StandardCharsets.UTF_8);

    assertTrue(written.contains("<circuit name=\"Top_v1\""), "the component itself is missing");
    assertTrue(written.contains("<circuit name=\"Half\""), "a circuit it uses was left behind");
    assertFalse(written.contains("Unrelated"), "an unrelated circuit came along");
  }

  /**
   * The component's circuit is renamed to carry its version; the circuits it depends on are not.
   *
   * <p>Both halves matter. Without the suffix two versions would be one reference and a project
   * using the older one would silently bind to the newer. With it applied any wider, a dependency
   * would change its name every time the component that uses it was republished, and a second
   * component built on the same dependency would disagree with the first about what it is called.
   *
   * <p>The name is checked in both of the places it is written. The reader applies the {@code
   * circuit} static attribute over the element's own attribute, so renaming only the element would
   * produce a file whose circuit is not the one its metadata names -- and it would load, right up
   * until the component could not be found in it.
   */
  @Test
  public void onlyTheComponentsOwnCircuitTakesTheVersionSuffix(@TempDir Path dir) throws Exception {
    final var file = saveTop(dir, "Top");

    final var written = Files.readString(file, StandardCharsets.UTF_8);

    assertTrue(written.contains("<main name=\"Top_v1\""), "the main circuit was not renamed");
    assertTrue(written.contains("val=\"Top_v1\""), "the name attribute still says the old name");
    assertFalse(
        written.contains("<circuit name=\"Top\""), "the circuit kept the unversioned name");
    assertFalse(written.contains("<main name=\"Top\"/>"), "the main element was left behind");
    assertFalse(written.contains("Half_v1"), "a dependency was given a version it does not have");
  }

  /** Trimming happens on a copy: the project the user is still editing keeps all three circuits. */
  @Test
  public void theProjectItWasSavedFromIsUntouched(@TempDir Path dir) throws Exception {
    final var project = PcompProjects.read(PcompProjects.THREE_CIRCUITS);
    final var top = project.getCircuit("Top");
    final var draft = PortLayoutDraft.of(top);

    PcompWriter.write(
        dir.resolve("Top" + PcompFile.EXTENSION).toFile(),
        project,
        top,
        PcompMetadata.firstVersion(top.getName(), draft.placements()),
        new Loader(null));

    assertEquals(3, project.getCircuits().size(), "the project lost circuits it still needs");
    assertNotNull(project.getCircuit("Unrelated"));
  }

  /** The closure is over subcircuits, and it includes the circuit it starts from. */
  @Test
  public void theClosureIsTheComponentPlusWhatItUses() throws Exception {
    final var project = PcompProjects.read(PcompProjects.THREE_CIRCUITS);

    final var closure = PcompWriter.dependencyClosure(project.getCircuit("Top"));

    assertEquals(2, closure.size());
    assertTrue(closure.contains(project.getCircuit("Top")));
    assertTrue(closure.contains(project.getCircuit("Half")));
    assertFalse(closure.contains(project.getCircuit("Unrelated")));
  }

  /**
   * The whole trip: a circuit in a project becomes a file, and the file becomes a component whose
   * ports are where the layout said. This is the check that the two ends agree; each of them is
   * plausible on its own and they only have to match here.
   */
  @Test
  public void theSavedComponentLoadsBackWithItsPortsInPlace(@TempDir Path dir) throws Exception {
    final var file = saveTop(dir, "Top");

    final var component = PcompLibrary.load(file.toFile(), new Loader(null));

    assertEquals(1, component.getTools().size());
    assertEquals("Top v1", component.getDisplayName());
    assertEquals(1, component.getMetadata().version());
    assertFalse(component.getCircuit().getAppearance().isDefaultAppearance());

    final var expected = new PortLayout("Top",
        PortLayoutDraft.of(PcompProjects.read(PcompProjects.THREE_CIRCUITS).getCircuit("Top"))
            .placements());
    final var offsets = component.getCircuit().getAppearance().getPortOffsets(Direction.EAST);
    assertEquals(4, offsets.size());
    for (final var name : List.of("A", "B", "S", "C")) {
      assertTrue(
          offsets.containsKey(expected.offsetOf(name)),
          name + " should be at " + expected.offsetOf(name) + " but the ports are "
              + offsets.keySet());
    }
  }

  /** The subcircuit the component is built from comes back too, or it could not be simulated. */
  @Test
  public void theCircuitsItDependsOnAreLoadedWithIt(@TempDir Path dir) throws Exception {
    final var file = saveTop(dir, "Top");

    final var component = PcompLibrary.load(file.toFile(), new Loader(null));

    var found = false;
    for (final var inside : component.getCircuit().getNonWires()) {
      if ("Half".equals(inside.getFactory().getName())) found = true;
    }
    assertTrue(found, "the component no longer contains the circuit it was built from");
  }

  /** A component file is a project file, so official Logisim would read it -- except for one part. */
  @Test
  public void theMetadataIsWrittenWhereTheReaderLooksForIt(@TempDir Path dir) throws Exception {
    final var file = saveTop(dir, "Top");

    final var metadata = PcompFile.read(file.toFile());

    assertNotNull(metadata);
    assertEquals("Top", metadata.name(), "the name a user sees carries no version");
    assertEquals("Top_v1", metadata.mainCircuit());
    assertTrue(metadata.locked(), "a component is locked when it is created");
    assertEquals(4, metadata.ports().size());
    assertNull(new PortLayout(metadata.mainCircuit(), metadata.ports()).offsetOf("Nothing"));
  }
}
