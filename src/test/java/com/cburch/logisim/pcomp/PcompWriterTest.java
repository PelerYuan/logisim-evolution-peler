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
import com.cburch.logisim.file.LogisimFileActions;
import com.cburch.logisim.file.PcompWriter;
import com.cburch.logisim.proj.Project;
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
 * PcompComponent} can load. Everything else in this package tests one side of that.
 */
class PcompWriterTest {

  private static Path saveTop(Path dir, String name) throws Exception {
    final var project = PcompProjects.read(PcompProjects.THREE_CIRCUITS);
    final var top = project.getCircuit("Top");
    final var draft = PortLayoutDraft.of(top);
    final var metadata = PcompMetadata.firstVersion(top.getName(), draft.layout());
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
        PcompMetadata.firstVersion(top.getName(), draft.layout()),
        new Loader(null));

    assertEquals(3, project.getCircuits().size(), "the project lost circuits it still needs");
    assertNotNull(project.getCircuit("Unrelated"));
  }

  /**
   * The source project is cloned whole, libraries included, so a component library the host
   * project happens to have loaded -- but the component itself does not place anything from --
   * must not ride along as a {@code <lib>} entry. Left in, saving a component into a library while
   * that same library is loaded (the ordinary case: the manager window's own library list is always
   * loaded into the project it manages) would write a {@code pcomplib#...} reference naming the
   * component's own containing directory, a dependency on itself. See {@code
   * PcompWriter.trimUnusedLibraries}.
   */
  @Test
  public void loadedLibraryTheComponentDoesNotUseIsNotCarriedAlong(@TempDir Path dir)
      throws Exception {
    final var project = PcompProjects.read(PcompProjects.THREE_CIRCUITS);
    final var top = project.getCircuit("Top");
    final var draft = PortLayoutDraft.of(top);
    final var metadata = PcompMetadata.firstVersion(top.getName(), draft.layout());

    final var loader = new Loader(null);
    final var libraryDir = dir.resolve("otherLib").toFile();
    PcompLibraryFile.create(libraryDir, "Other Library");
    final var otherLibrary = loader.loadPcompLibrary(libraryDir);
    final var proj = new Project(project);
    proj.doAction(LogisimFileActions.loadLibraryQuiet(otherLibrary, project));
    assertTrue(project.getLibraries().contains(otherLibrary), "the fixture did not load");

    final var destination = dir.resolve("Top" + PcompFile.EXTENSION);
    PcompWriter.write(destination.toFile(), project, top, metadata, loader);

    final var written = Files.readString(destination, StandardCharsets.UTF_8);
    assertFalse(written.contains("pcomplib#"), "an unused, unrelated library became a dependency");
  }

  /**
   * A toolbar separator is a {@code null} entry in {@code ToolbarData.getContents()} -- real
   * projects have them. {@code PcompComponentLibrary.getTools()} returns {@code List.copyOf(...)},
   * which throws on {@code contains(null)} rather than the plain {@code ArrayList} most {@code
   * Library} implementations use, so a separator must be skipped before checking toolbar usage,
   * not passed straight to {@code contains}.
   */
  @Test
  public void toolbarSeparatorDoesNotBreakTheUnusedLibraryCheck(@TempDir Path dir)
      throws Exception {
    final var project = PcompProjects.read(PcompProjects.THREE_CIRCUITS);
    project.getOptions().getToolbarData().addSeparator();
    final var top = project.getCircuit("Top");
    final var draft = PortLayoutDraft.of(top);
    final var metadata = PcompMetadata.firstVersion(top.getName(), draft.layout());

    final var loader = new Loader(null);
    final var libraryDir = dir.resolve("otherLib").toFile();
    PcompLibraryFile.create(libraryDir, "Other Library");
    final var otherLibrary = loader.loadPcompLibrary(libraryDir);
    final var proj = new Project(project);
    proj.doAction(LogisimFileActions.loadLibraryQuiet(otherLibrary, project));

    final var destination = dir.resolve("Top" + PcompFile.EXTENSION);
    PcompWriter.write(destination.toFile(), project, top, metadata, loader);

    final var written = Files.readString(destination, StandardCharsets.UTF_8);
    assertFalse(written.contains("pcomplib#"), "an unused, unrelated library became a dependency");
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

    final var component = PcompComponent.load(file.toFile(), new Loader(null));

    assertEquals(1, component.getTools().size());
    assertEquals("Top v1", component.getDisplayName());
    assertEquals(1, component.getMetadata().version());
    assertFalse(component.getCircuit().getAppearance().isDefaultAppearance());

    final var expected =
        PortLayoutDraft.of(PcompProjects.read(PcompProjects.THREE_CIRCUITS).getCircuit("Top"))
            .layout();
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

    final var component = PcompComponent.load(file.toFile(), new Loader(null));

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
    assertNull(metadata.layout().offsetOf("Nothing"));
    assertEquals("Top", metadata.layout().caption(), "the box is captioned with the bare name");
  }
}
