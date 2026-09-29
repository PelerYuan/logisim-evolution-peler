/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.file.Loader;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import java.io.File;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ROM/RAM contents and PLA tables, the GUI's per-component content editors. */
class MemoryContentsAcceptanceTest {
  @TempDir File tempDir;

  private record Fixture(Space space, Memory memory, Comp rom, Comp ram, Comp pla) {}

  private static Fixture fixture() {
    final var file = LogisimFile.createNew(new Loader(null), null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    final var space = Space.of(project);
    final var rom = space.place(Kind.of(space, "Memory/ROM")).anchorAt(10, 10).place();
    final var ram = space.place(Kind.of(space, "Memory/RAM")).anchorAt(40, 10).place();
    final var pla = space.place(Kind.of(space, "Gates/PLA")).anchorAt(70, 10).place();
    space.commit("memories");
    return new Fixture(space, Memory.of(space), rom, ram, pla);
  }

  @Test
  void romReadWriteAndUndo() {
    final var f = fixture();
    final var info = f.memory().info(f.rom());
    assertEquals("rom", info.kind());
    assertEquals(8, info.dataBits());

    f.memory().write(f.rom(), 3, 0xAB);
    f.memory().writeRange(f.rom(), 10, new long[] {1, 2, 3});

    assertEquals(0xAB, f.memory().read(f.rom(), 3));
    assertArrayEquals(new long[] {1, 2, 3}, f.memory().readRange(f.rom(), 10, 3));

    History.of(f.space()).undo();
    assertEquals(0, f.memory().read(f.rom(), 10));
    assertEquals(0xAB, f.memory().read(f.rom(), 3));
    History.of(f.space()).undo();
    assertEquals(0, f.memory().read(f.rom(), 3));
  }

  @Test
  void romFillClearDumpAndLoadRoundTrip() {
    final var f = fixture();
    f.memory().fill(f.rom(), 0, 16, 7);
    final var image = f.memory().dump(f.rom());

    f.memory().clear(f.rom());
    assertEquals(0, f.memory().read(f.rom(), 5));

    f.memory().load(f.rom(), image);
    assertEquals(7, f.memory().read(f.rom(), 5));
    assertEquals(0, f.memory().read(f.rom(), 16));
  }

  @Test
  void romImageFilesRoundTrip() {
    final var f = fixture();
    f.memory().write(f.rom(), 1, 0x42);
    final var path = new File(tempDir, "rom.hex").getAbsolutePath();
    f.memory().saveFile(f.rom(), path);

    f.memory().clear(f.rom());
    f.memory().loadFile(f.rom(), path);
    assertEquals(0x42, f.memory().read(f.rom(), 1));
  }

  @Test
  void ramContentsAreLiveState() {
    final var f = fixture();
    assertEquals("ram", f.memory().info(f.ram()).kind());
    assertTrue(f.memory().info(f.ram()).live());

    f.memory().write(f.ram(), 2, 9);
    assertEquals(9, f.memory().read(f.ram(), 2));
    f.memory().clear(f.ram());
    assertEquals(0, f.memory().read(f.ram(), 2));
  }

  @Test
  void badAccessesAreStructuredErrors() {
    final var f = fixture();
    assertThrows(InvalidMemoryAccessException.class, () -> f.memory().read(f.rom(), 1L << 20));
    assertThrows(InvalidMemoryAccessException.class, () -> f.memory().read(f.rom(), -1));
    assertThrows(InvalidMemoryAccessException.class, () -> f.memory().write(f.rom(), 0, 256));
    assertThrows(InvalidMemoryAccessException.class, () -> f.memory().load(f.rom(), "not hex at all zz"));
    assertThrows(NotAMemoryException.class, () -> f.memory().read(f.pla(), 0));
  }

  @Test
  void plaTableCanBeReadAndReplacedWithUndo() {
    final var f = fixture();
    final var tables = PlaTables.of(f.space());
    final var original = tables.getTable(f.pla());

    tables.setTable(f.pla(), "0x1 10\n110 01 # and\n");

    assertTrue(tables.getTable(f.pla()).contains("0x1 10"));
    assertTrue(tables.getTable(f.pla()).contains("110 01"));

    History.of(f.space()).undo();
    assertEquals(original, tables.getTable(f.pla()));
  }

  @Test
  void plaRejectsBadTextWithoutADialog() {
    final var f = fixture();
    final var tables = PlaTables.of(f.space());
    assertThrows(InvalidPlaTableException.class, () -> tables.setTable(f.pla(), "abc def"));
    assertThrows(InvalidPlaTableException.class, () -> tables.setTable(f.pla(), "01 1\n011 1"));
    assertThrows(InvalidPlaTableException.class, () -> tables.setTable(f.pla(), "# nothing"));
    assertThrows(NotAMemoryException.class, () -> tables.getTable(f.rom()));
  }
}
