/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.proj.Project;
import org.junit.jupiter.api.Test;

/**
 * Covers the "先做元器件" expansion of {@link com.cburch.logisim.dsl.internal.KindRegistry}: a
 * component the small hand-curated table does not name (anything beyond the original ten gate/
 * pin/register keys) must still be placeable via its raw {@code "<library>/<factory>"} key, and a
 * subcircuit read back from a library loaded into the project (not just the project's own
 * top-level circuits) must be re-placeable using the exact key it was read back as -- the
 * round-trip that previously threw {@link UnknownKindException} even though {@code
 * componentsOf}/{@code byLabel} could already see the component.
 */
public class KindRegistryExpansionAcceptanceTest {

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  @Test
  public void testAllTenCuratedKeysStillResolve() {
    final var project = blankProject();
    final var space = Space.of(project);
    for (final var key : new String[] {
        "gates/and_gate", "gates/or_gate", "gates/xor_gate", "gates/xnor_gate",
        "gates/nand_gate", "gates/nor_gate", "gates/not_gate", "gates/buffer",
        "wiring/pin", "memory/register",
    }) {
      final var kind = Kind.of(space, key);
      assertEquals(key, kind.key());
    }
  }

  @Test
  public void testNonCuratedBuiltinComponentIsPlaceableByItsMechanicalKey() {
    final var project = blankProject();
    final var space = Space.of(project);

    final var mux = Kind.of(space, "Plexers/Multiplexer");
    final var placed = space.place(mux).anchorAt(0, 0).place();
    space.commit("place a multiplexer via its mechanical key");

    assertEquals(1, space.componentsOf(mux).size());
    assertTrue(space.componentsOf(mux).contains(placed));
  }

  @Test
  public void testNonCuratedBuiltinComponentReportsItsRealAttributesNotJustFacing() {
    final var project = blankProject();
    final var space = Space.of(project);

    final var mux = Kind.of(space, "Plexers/Multiplexer");
    final var specs = mux.attributes();

    assertTrue(specs.size() > 1,
        "a Multiplexer has more than just a facing attribute: " + specs);
    final var width = specs.stream().filter(s -> s.name().equals("width")).findFirst();
    assertTrue(width.isPresent(), "expected a \"width\" attribute among: " + specs);
    assertEquals("bitWidth", width.get().kind());
  }

  @Test
  public void testUnknownKeySuggestsMechanicalKeysToo() {
    final var project = blankProject();
    final var space = Space.of(project);

    final var thrown = org.junit.jupiter.api.Assertions.assertThrows(UnknownKindException.class,
        () -> Kind.of(space, "Plexers/Multiplexr"));
    @SuppressWarnings("unchecked")
    final var nearKeys = (java.util.List<String>) thrown.details().get("nearKeys");
    assertTrue(nearKeys.contains("Plexers/Multiplexer"),
        "a near-miss on a mechanical key should still get a \"did you mean\" suggestion: " + nearKeys);
  }

  @Test
  public void testSubcircuitFromALoadedLibraryCanBeReadBackAndRePlacedByItsKey() {
    final var project = blankProject();
    final var libraryFile = LogisimFile.createNew(new Loader(null), null);
    final var helper = new Circuit("Helper", libraryFile, null);
    libraryFile.addCircuit(helper);
    project.getLogisimFile().addLibrary(libraryFile);

    final var space = Space.of(project);
    final var helperKind = Kind.of(space, "circuit/Helper");
    final var placed = space.place(helperKind).anchorAt(0, 0).place();
    space.commit("place a subcircuit from a loaded library");

    // Read it back the way an AI client would: from the placed component, not from the key
    // used to place it.
    final var reopened = Space.of(project);
    final var readBackKind = reopened.components().get(0).kind();
    assertEquals("circuit/Helper", readBackKind.key());

    // The bug: re-placing using exactly the key just read back used to throw
    // UnknownKindException, because resolve() only ever searched the project's own top-level
    // circuits for a "circuit/<name>" key, never a loaded library's.
    final var second = reopened.place(readBackKind).anchorAt(20, 0).place();
    reopened.commit("place a second instance using the read-back key");

    assertEquals(2, reopened.componentsOf(readBackKind).size());
  }
}
