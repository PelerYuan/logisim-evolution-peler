/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import com.cburch.logisim.file.LogisimFileActions;
import com.cburch.logisim.fpga.hdlgenerator.Vhdl;
import com.cburch.logisim.proj.Action;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.vhdl.base.VhdlContent;
import com.cburch.logisim.vhdl.base.VhdlParser;
import com.cburch.logisim.vhdl.file.HdlFile;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Project-level VHDL entity management -- create a blank entity from the same template the GUI's
 * "Add VHDL Entity" menu item uses, import one from an existing {@code .vhd} file, remove one, or
 * rename one -- the counterpart to {@link Circuits} for VHDL entities rather than circuits. A VHDL
 * entity is a {@link VhdlContent} placeable into a circuit exactly like a subcircuit, via {@link
 * Kind}'s {@code "vhdl/<name>"} key (see {@code dsl.internal.KindRegistry}).
 *
 * <p>Deliberately never lets {@link VhdlContent#create}/{@link VhdlContent#parse} reach either of
 * the two dialogs they can otherwise pop on failure, since both would come from whatever thread
 * called this, not the event dispatch thread, with nobody to click them (the same class of hang
 * {@link Libraries}'s class javadoc explains at greater length):
 *
 * <ul>
 *   <li>{@link VhdlContent#setContent(String)} itself calls {@link
 *       VhdlContent#labelVHDLInvalidNotify} on an invalid or duplicate entity name -- not routed
 *       through the overridable {@code showErrors()} method, so subclassing cannot intercept it.
 *       The fix is to validate the name ourselves, with the exact same checks, before ever calling
 *       {@code create}/{@code parse} with it: a name that already passes those checks is guaranteed
 *       to pass them again inside {@code setContent} (this class always passes a {@code null} file
 *       there when the name is unchanged from what was already validated, which is what skips the
 *       duplicate re-check), so the notify path is never reached.
 *   <li>{@code create}/{@code parse} themselves call {@code showErrors()} unconditionally whenever
 *       {@code setContent} returns false, for any reason -- including malformed VHDL on import.
 *       {@link #importFile(String)} avoids this by running its own {@link VhdlParser} over the file
 *       first and refusing (with a structured exception) before ever calling {@link
 *       VhdlContent#parse}, so the internal parse -- given the exact same input -- is guaranteed to
 *       succeed too.
 * </ul>
 *
 * <p>One risk is not closed by any of the above: {@link VhdlContent#setContent(String)} also calls
 * {@code Softwares.validateVhdl}, which shells out to an external QuestaSim binary and can itself
 * fail -- but only if the user has opted into {@code AppPreferences.QUESTA_VALIDATION} (off by
 * default) and configured a QuestaSim path. That is an existing risk in the GUI's own VHDL add/
 * import flows, not one this class introduces, and is accepted rather than worked around here.
 *
 * <p>Renaming reuses {@link VhdlContent#setName(String)}, wrapped in a small custom {@link Action}
 * rather than going through {@link com.cburch.logisim.circuit.CircuitMutation} the way {@link
 * Circuits#rename} does -- VHDL content has no equivalent generic attribute-mutation helper. This
 * also makes renaming reachable independent of whether the entity has any placed instance: the GUI
 * only exposes renaming through a placed instance's attribute table, so an entity with zero
 * instances currently has no GUI path to be renamed at all.
 */
public final class VhdlEntities {
  private final Project proj;

  private VhdlEntities(Project proj) {
    this.proj = proj;
  }

  public static VhdlEntities of(Space space) {
    return new VhdlEntities(space.project());
  }

  /** Entity names in this project's own file order (not alphabetical). */
  public List<String> list() {
    final var names = new ArrayList<String>();
    for (final var content : proj.getLogisimFile().getVhdlContents()) names.add(content.getName());
    return List.copyOf(names);
  }

  /** Creates a new entity from the same blank template the GUI's "Add VHDL Entity" menu item
   * uses, in one undo-logged action. */
  public void create(String name) {
    validateNewName(name, null);
    final var content = VhdlContent.create(name, proj.getLogisimFile());
    proj.doAction(LogisimFileActions.addVhdl(content));
  }

  /** Reads {@code path} as a VHDL source file, adds it as a new entity named after its own
   * {@code entity ... is} declaration (which need not match the file name), and returns that name.
   * One undo-logged action. */
  public String importFile(String path) {
    final var text = readVhdlFile(path);
    final var parser = new VhdlParser(text);
    try {
      parser.parse();
    } catch (VhdlParser.IllegalVhdlContentException e) {
      final var reason = e.getMessage() == null || e.getMessage().isBlank()
          ? "file does not contain valid VHDL" : e.getMessage();
      throw new VhdlImportFailedException(path, reason);
    }
    final var name = parser.getName();
    validateNewName(name, null);
    final var content = VhdlContent.parse(name, text, proj.getLogisimFile());
    proj.doAction(LogisimFileActions.addVhdl(content));
    return name;
  }

  private String readVhdlFile(String path) {
    try {
      return HdlFile.load(new File(path));
    } catch (IOException e) {
      final var reason = e.getMessage() == null || e.getMessage().isBlank()
          ? "could not read file" : e.getMessage();
      throw new VhdlImportFailedException(path, reason);
    }
  }

  /** Removes an entity by name, refusing (with a structured reason) if anything in the project
   * still places it. */
  public void remove(String name) {
    final var content = require(name);
    if (!proj.getDependencies().canRemove(content)) {
      throw new VhdlEntityInUseException(
          name, "it is placed as a component by another circuit in this project");
    }
    proj.doAction(LogisimFileActions.removeVhdl(content));
  }

  /** An entity's full VHDL source text, exactly as the GUI's VHDL editor shows it. */
  public String getSource(String name) {
    return require(name).getContent();
  }

  /** One port of an entity: its name, direction ({@code "input"}, {@code "output"}, {@code
   * "inout"}) and bit width. */
  public record EntityPort(String name, String direction, int width) {}

  /** The entity's ports as its current source declares them, inputs first. */
  public List<EntityPort> ports(String name) {
    final var out = new ArrayList<EntityPort>();
    for (final var p : require(name).getPorts()) {
      out.add(new EntityPort(p.getName(), p.getType(), p.getWidth().getWidth()));
    }
    return List.copyOf(out);
  }

  /**
   * Replaces an entity's VHDL source, the way typing in the GUI's VHDL editor and pressing "Validate
   * and Save" does -- as one undo-logged action. The new text must parse and must still declare an
   * entity of the same name (use {@link #rename} to change a name); anything else is refused with a
   * structured {@link InvalidVhdlSourceException} before the entity is touched, for the same
   * no-dialog reason as the rest of this class. Placed instances pick up port changes exactly as
   * they do after a GUI edit.
   */
  public void setSource(String name, String source) {
    final var content = require(name);
    final var parser = new VhdlParser(source);
    try {
      parser.parse();
    } catch (VhdlParser.IllegalVhdlContentException e) {
      final var reason = e.getMessage() == null || e.getMessage().isBlank()
          ? "the source does not contain valid VHDL" : e.getMessage();
      throw new InvalidVhdlSourceException(name, reason);
    }
    if (!parser.getName().equals(name)) {
      throw new InvalidVhdlSourceException(
          name, "the source declares entity \"" + parser.getName() + "\" instead");
    }
    if (content.getContent().equals(source)) return;
    final var oldSource = content.getContent();
    proj.doAction(new SetVhdlSource(content, name, oldSource, source));
  }

  /** Writes an entity's source to a {@code .vhd} file -- the GUI editor's "Save" button. */
  public void exportFile(String name, String path) {
    final var content = require(name);
    try {
      HdlFile.save(new File(path), content.getContent());
    } catch (IOException e) {
      final var reason = e.getMessage() == null || e.getMessage().isBlank()
          ? "could not write file" : e.getMessage();
      throw new ExportFailedException(path, reason);
    }
  }

  public void rename(String oldName, String newName) {
    final var content = require(oldName);
    validateNewName(newName, oldName);
    proj.doAction(new RenameVhdl(content, oldName, newName));
  }

  private VhdlContent require(String name) {
    final var content = proj.getLogisimFile().getVhdlContent(name);
    if (content == null) throw new UnknownVhdlEntityException(name, nearest(name));
    return content;
  }

  /** {@code exemptFrom}, when non-null, is the name being renamed away from: renaming an entity to
   * its own current name must not trip the duplicate check on the very entry it is renaming.
   * Mirrors, check for check, what {@link VhdlContent#labelVHDLInvalidNotify} itself applies -- see
   * the class javadoc for why that matters. */
  private void validateNewName(String name, String exemptFrom) {
    if (name == null || name.isEmpty()) {
      throw new InvalidVhdlNameException(name, "name must not be empty");
    }
    if (!name.matches("^[A-Za-z]\\w*") || name.endsWith("_") || name.matches(".*__.*")) {
      throw new InvalidVhdlNameException(name, "not a valid VHDL identifier");
    }
    if (Vhdl.VHDL_KEYWORDS.contains(name.toLowerCase())) {
      throw new InvalidVhdlNameException(name, "\"" + name + "\" is a reserved VHDL keyword");
    }
    final var inUse = exemptFrom == null || !name.equals(exemptFrom);
    if (inUse && proj.getLogisimFile().containsFactory(name)) {
      throw new DuplicateVhdlNameException(name);
    }
  }

  private List<String> nearest(String name) {
    final var candidates = new ArrayList<>(list());
    candidates.sort((a, b) -> distance(name, a) - distance(name, b));
    final var top = new ArrayList<String>();
    for (final var candidate : candidates) {
      if (distance(name, candidate) <= Math.max(3, name.length() / 2)) top.add(candidate);
      if (top.size() == 3) break;
    }
    return top;
  }

  /** Plain Levenshtein distance -- same purpose and shape as {@code Circuits}'/{@code
   * Libraries}'/{@code KindRegistry}'s own copies, kept separate rather than shared since all are
   * small, private, and belong to unrelated key spaces. */
  private static int distance(String a, String b) {
    final var dp = new int[a.length() + 1][b.length() + 1];
    for (var i = 0; i <= a.length(); i++) dp[i][0] = i;
    for (var j = 0; j <= b.length(); j++) dp[0][j] = j;
    for (var i = 1; i <= a.length(); i++) {
      for (var j = 1; j <= b.length(); j++) {
        final var cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
      }
    }
    return dp[a.length()][b.length()];
  }

  private static final class SetVhdlSource extends Action {
    private final VhdlContent content;
    private final String entity;
    private final String oldSource;
    private final String newSource;

    SetVhdlSource(VhdlContent content, String entity, String oldSource, String newSource) {
      this.content = content;
      this.entity = entity;
      this.oldSource = oldSource;
      this.newSource = newSource;
    }

    @Override
    public void doIt(Project proj) {
      apply(newSource);
    }

    @Override
    public void undo(Project proj) {
      apply(oldSource);
    }

    private void apply(String text) {
      if (!content.setContent(text)) {
        throw new InvalidVhdlSourceException(entity, "the entity's validation rejected the source");
      }
    }

    @Override
    public String getName() {
      return "edit VHDL entity source";
    }
  }

  /** See the class javadoc: makes {@link VhdlContent#setName(String)} reachable as a plain
   * undo-logged action, independent of whether the entity has any placed instance. */
  private static final class RenameVhdl extends Action {
    private final VhdlContent content;
    private final String oldName;
    private final String newName;

    RenameVhdl(VhdlContent content, String oldName, String newName) {
      this.content = content;
      this.oldName = oldName;
      this.newName = newName;
    }

    @Override
    public void doIt(Project proj) {
      content.setName(newName);
    }

    @Override
    public void undo(Project proj) {
      content.setName(oldName);
    }

    @Override
    public String getName() {
      return "rename VHDL entity";
    }
  }
}
