/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import com.cburch.logisim.proj.Project;

/**
 * Project-level undo/redo of already-committed actions -- the counterpart to {@link Circuits} for
 * history navigation rather than circuit management, independent of whichever circuit {@link
 * Space} is currently open on.
 *
 * <p>One "unit" of undo/redo here is exactly one {@link com.cburch.logisim.proj.Project}
 * undo-log entry -- the same unit every mutation in this package already produces: one {@link
 * Space#commit(String)} call (which batches every {@code place()}/{@code connect()} made since the
 * last commit into a single entry, per {@link Space}'s own class javadoc), or one direct {@link
 * Circuits}/{@link Libraries}/{@link VhdlEntities}/{@link Space#tidyWires()}/{@link
 * Space#synthesize(Synthesis)} call. There is no separate "session" concept to undo by: this is a
 * thin wrapper over {@link Project#undoAction()}/{@link Project#redoAction()}, the same calls the
 * GUI's own Edit menu makes.
 *
 * <p>Calling these on a headless {@link Project} (no {@link com.cburch.logisim.gui.main.Frame}
 * ever attached, exactly how every {@code dsl} test fixture and every real MCP-driven project is
 * constructed) is no riskier than {@link Project#doAction}, which this package already calls
 * throughout: {@code undoAction}/{@code redoAction} only reach {@link
 * com.cburch.logisim.gui.main.Canvas}/other Swing listeners through {@code Project}'s
 * {@code ProjectListener} list, which stays empty until a {@code Frame} registers on it.
 */
public final class History {
  private final Project proj;

  private History(Project proj) {
    this.proj = proj;
  }

  public static History of(Space space) {
    return new History(space.project());
  }

  public boolean canUndo() {
    return proj.getLastAction() != null;
  }

  public boolean canRedo() {
    return proj.getCanRedo();
  }

  /** The description of what {@link #undo()} would revert, or {@code null} if {@link #canUndo()}
   * is false. */
  public String nextUndoDescription() {
    final var action = proj.getLastAction();
    return action == null ? null : action.getName();
  }

  /** The description of what {@link #redo()} would reapply, or {@code null} if {@link #canRedo()}
   * is false. */
  public String nextRedoDescription() {
    final var action = proj.getLastRedoAction();
    return action == null ? null : action.getName();
  }

  /** Undoes the most recent undo-log entry, returning its description. */
  public String undo() {
    final var action = proj.getLastAction();
    if (action == null) throw new NothingToUndoException();
    proj.undoAction();
    return action.getName();
  }

  /** Redoes the most recently undone entry, returning its description. */
  public String redo() {
    final var action = proj.getLastRedoAction();
    if (action == null) throw new NothingToRedoException();
    proj.redoAction();
    return action.getName();
  }
}
