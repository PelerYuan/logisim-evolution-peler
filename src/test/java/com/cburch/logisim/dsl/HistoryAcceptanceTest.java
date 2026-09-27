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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.proj.Project;
import org.junit.jupiter.api.Test;

/** Covers the "撤销/重做已提交的动作" (undo/redo of already-committed actions) addition: {@link
 * History} is a thin wrapper over {@link com.cburch.logisim.proj.Project#undoAction()}/{@link
 * com.cburch.logisim.proj.Project#redoAction()} -- exactly as headless-safe on a {@code Frame}-less
 * {@link Project} as {@link Project#doAction} already is, which every {@code dsl} mutation class
 * calls throughout this test suite. */
class HistoryAcceptanceTest {

  private static Project blankProject() {
    final var loader = new Loader(null);
    final var file = LogisimFile.createNew(loader, null);
    final var project = new Project(file);
    for (final var circuit : file.getCircuits()) circuit.setProject(project);
    return project;
  }

  @Test
  void freshProjectHasNothingToUndoOrRedo() {
    final var history = History.of(Space.of(blankProject()));

    assertFalse(history.canUndo());
    assertFalse(history.canRedo());
    assertNull(history.nextUndoDescription());
    assertNull(history.nextRedoDescription());
    assertThrows(NothingToUndoException.class, history::undo);
    assertThrows(NothingToRedoException.class, history::redo);
  }

  @Test
  void undoRevertsTheMostRecentCommittedActionAndEnablesRedo() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var circuits = Circuits.of(space);
    final var history = History.of(space);

    circuits.create("Helper");
    assertTrue(circuits.list().contains("Helper"));
    assertTrue(history.canUndo());
    assertNotNull(history.nextUndoDescription());

    final var undone = history.undo();

    assertNotNull(undone);
    assertFalse(circuits.list().contains("Helper"));
    assertFalse(history.canUndo());
    assertTrue(history.canRedo());
    assertEquals(undone, history.nextRedoDescription());
  }

  @Test
  void redoReappliesTheUndoneAction() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var circuits = Circuits.of(space);
    final var history = History.of(space);

    circuits.create("Helper");
    history.undo();

    final var redone = history.redo();

    assertNotNull(redone);
    assertTrue(circuits.list().contains("Helper"));
    assertFalse(history.canRedo());
    assertTrue(history.canUndo());
  }

  @Test
  void newActionAfterUndoClearsTheRedoLog() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var circuits = Circuits.of(space);
    final var history = History.of(space);

    circuits.create("Helper");
    history.undo();
    assertTrue(history.canRedo());

    circuits.create("Other");

    assertFalse(history.canRedo());
    assertThrows(NothingToRedoException.class, history::redo);
  }

  @Test
  void oneSpaceCommitIsOneUndoUnitCoveringEverythingPlacedSinceTheLastCommit() {
    final var project = blankProject();
    final var space = Space.of(project);
    final var history = History.of(space);

    final var pin = Kind.of(space, "wiring/pin");
    space.place(pin).anchorAt(0, 0).place();
    space.place(pin).anchorAt(0, 8).place();
    space.commit("place two pins");

    assertEquals(2, space.components().size());

    // History.undo() mutates the underlying Circuit directly; a Space caches what it has already
    // discovered/committed (see Space's own class javadoc on existingComponents), so re-reading
    // through a fresh Space -- exactly how SpaceExistingCircuitAcceptanceTest reads back
    // hand-drawn content -- is what proves the circuit itself reverted, not just this handle.
    history.undo();
    assertTrue(Space.of(project).components().isEmpty());
  }
}
