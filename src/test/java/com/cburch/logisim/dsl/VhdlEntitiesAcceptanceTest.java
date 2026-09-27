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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.proj.Project;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Covers the "VHDL 实体管理" expansion: {@link VhdlEntities} adds create/import/remove/rename of
 * VHDL entities on top of the existing single-circuit {@link Space}, taking care never to reach
 * either dialog {@link com.cburch.logisim.vhdl.base.VhdlContent#create}/{@code parse} can pop on
 * failure (see {@link VhdlEntities}'s class javadoc). */
class VhdlEntitiesAcceptanceTest {

  @TempDir File tempDir;

  private static final String ADDER_VHDL =
      """
      LIBRARY ieee;
      USE ieee.std_logic_1164.all;

      ENTITY Adder IS
        PORT (
          clock      : IN  std_logic;
          val        : IN  std_logic_vector(3 DOWNTO 0);
          max        : OUT std_logic;
          cpt        : OUT std_logic_vector(3 DOWNTO 0)
          );
      END Adder;

      ARCHITECTURE TypeArchitecture OF Adder IS
      BEGIN
      END TypeArchitecture;
      """;

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  private File writeFile(String fileName, String content) throws Exception {
    final var file = new File(tempDir, fileName);
    Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
    return file;
  }

  @Test
  void createAddsAnEntityUsableAsAKindSource() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var vhdlEntities = VhdlEntities.of(space);

    vhdlEntities.create("MyEntity");

    assertTrue(vhdlEntities.list().contains("MyEntity"));

    final var kind = Kind.of(space, "vhdl/MyEntity");
    space.place(kind).anchorAt(0, 0).place();
    space.commit("place a VHDL entity");
    assertEquals(1, space.componentsOf(kind).size());
  }

  @Test
  void createRejectsAnEmptyName() {
    final var vhdlEntities = VhdlEntities.of(Space.of(blankProject()));
    assertThrows(InvalidVhdlNameException.class, () -> vhdlEntities.create(""));
  }

  @Test
  void createRejectsAReservedKeyword() {
    final var vhdlEntities = VhdlEntities.of(Space.of(blankProject()));
    assertThrows(InvalidVhdlNameException.class, () -> vhdlEntities.create("entity"));
  }

  @Test
  void createRejectsAnInvalidIdentifier() {
    final var vhdlEntities = VhdlEntities.of(Space.of(blankProject()));
    assertThrows(InvalidVhdlNameException.class, () -> vhdlEntities.create("123Bad"));
  }

  @Test
  void createRejectsADuplicateName() {
    final var vhdlEntities = VhdlEntities.of(Space.of(blankProject()));
    vhdlEntities.create("MyEntity");
    assertThrows(DuplicateVhdlNameException.class, () -> vhdlEntities.create("MyEntity"));
  }

  @Test
  void createRejectsANameAlreadyUsedByACircuit() {
    final var project = blankProject();
    final var vhdlEntities = VhdlEntities.of(Space.of(project));
    final var circuitName = project.getLogisimFile().getMainCircuit().getName();
    assertThrows(DuplicateVhdlNameException.class, () -> vhdlEntities.create(circuitName));
  }

  @Test
  void importFileAddsAnEntityNamedAfterItsOwnDeclarationNotTheFileName() throws Exception {
    final var file = writeFile("something-else.vhd", ADDER_VHDL);
    final var vhdlEntities = VhdlEntities.of(Space.of(blankProject()));

    final var name = vhdlEntities.importFile(file.getAbsolutePath());

    assertEquals("Adder", name);
    assertTrue(vhdlEntities.list().contains("Adder"));
  }

  @Test
  void importFileRejectsAMissingFile() {
    final var vhdlEntities = VhdlEntities.of(Space.of(blankProject()));
    final var missing = new File(tempDir, "does-not-exist.vhd").getAbsolutePath();

    final var thrown =
        assertThrows(VhdlImportFailedException.class, () -> vhdlEntities.importFile(missing));
    assertEquals(missing, thrown.details().get("path"));
  }

  @Test
  void importFileRejectsInvalidVhdl() throws Exception {
    final var file = writeFile("garbage.vhd", "this is not valid VHDL at all");
    final var vhdlEntities = VhdlEntities.of(Space.of(blankProject()));

    assertThrows(VhdlImportFailedException.class,
        () -> vhdlEntities.importFile(file.getAbsolutePath()));
  }

  @Test
  void importFileRejectsADuplicateName() throws Exception {
    final var file = writeFile("adder.vhd", ADDER_VHDL);
    final var vhdlEntities = VhdlEntities.of(Space.of(blankProject()));
    vhdlEntities.importFile(file.getAbsolutePath());

    assertThrows(DuplicateVhdlNameException.class,
        () -> vhdlEntities.importFile(file.getAbsolutePath()));
  }

  @Test
  void removeDropsAnUnusedEntity() {
    final var vhdlEntities = VhdlEntities.of(Space.of(blankProject()));
    vhdlEntities.create("MyEntity");

    vhdlEntities.remove("MyEntity");

    assertFalse(vhdlEntities.list().contains("MyEntity"));
  }

  @Test
  void removeOfUnknownNameSuggestsTheNearestRealOne() {
    final var vhdlEntities = VhdlEntities.of(Space.of(blankProject()));
    vhdlEntities.create("MyEntity");

    final var thrown =
        assertThrows(UnknownVhdlEntityException.class, () -> vhdlEntities.remove("MyEntiti"));
    @SuppressWarnings("unchecked")
    final var nearNames = (java.util.List<String>) thrown.details().get("nearNames");
    assertTrue(nearNames.contains("MyEntity"));
  }

  @Test
  void removeRefusesAnEntityStillPlaced() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var vhdlEntities = VhdlEntities.of(space);
    vhdlEntities.create("MyEntity");

    final var kind = Kind.of(space, "vhdl/MyEntity");
    space.place(kind).anchorAt(0, 0).place();
    space.commit("place a VHDL entity");

    assertThrows(VhdlEntityInUseException.class, () -> vhdlEntities.remove("MyEntity"));
  }

  @Test
  void renameChangesTheNameWithNoInstancesPlaced() {
    final var vhdlEntities = VhdlEntities.of(Space.of(blankProject()));
    vhdlEntities.create("OldName");

    vhdlEntities.rename("OldName", "NewName");

    assertTrue(vhdlEntities.list().contains("NewName"));
    assertFalse(vhdlEntities.list().contains("OldName"));
  }

  @Test
  void renameRejectsADuplicateName() {
    final var vhdlEntities = VhdlEntities.of(Space.of(blankProject()));
    vhdlEntities.create("First");
    vhdlEntities.create("Second");

    assertThrows(DuplicateVhdlNameException.class, () -> vhdlEntities.rename("First", "Second"));
  }

  @Test
  void renameOfUnknownNameThrows() {
    final var vhdlEntities = VhdlEntities.of(Space.of(blankProject()));
    assertThrows(UnknownVhdlEntityException.class, () -> vhdlEntities.rename("NoSuchEntity", "New"));
  }
}
