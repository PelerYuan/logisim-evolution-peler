/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitAttributes;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.tools.Tool;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.xml.sax.SAXException;

/**
 * Peler Edition. One custom component, loaded and ready to place.
 *
 * <p>A component file holds a whole project -- the circuit that is the component, plus every
 * circuit it depends on -- and this exposes exactly one tool from it: the main circuit. The
 * dependencies are still loaded, because the component does not work without them, but they are
 * not offered in the toolbox: a user who places a custom adder wants the adder, not the full adder
 * it happens to be built from.
 *
 * <p>The appearance is applied here, at load time, rather than being stored in the file. That is
 * what makes the layout the single description of the component: there is no second copy of the
 * geometry for the appearance editor to change out from under it.
 */
public final class PcompComponent extends Library {

  private final PcompMetadata metadata;
  private final Circuit circuit;
  private final List<Tool> tools;
  private final File source;
  private final Set<Circuit> circuits;

  private PcompComponent(
      File source, PcompMetadata metadata, Circuit circuit, Tool tool, List<Circuit> circuits) {
    this.source = source;
    this.metadata = metadata;
    this.circuit = circuit;
    this.tools = List.of(tool);
    final var owned = Collections.newSetFromMap(new IdentityHashMap<Circuit, Boolean>());
    owned.addAll(circuits);
    this.circuits = Collections.unmodifiableSet(owned);
  }

  /**
   * Loads one component file.
   *
   * @throws IOException if the file is not a component, names a main circuit it does not contain,
   *     or describes ports its circuit has no pins for
   */
  public static PcompComponent load(File file, Loader loader) throws IOException {
    final var metadata = PcompFile.read(file);
    if (metadata == null) {
      throw new IOException(file.getName() + " is not a custom component");
    }
    final var project = readProject(file, loader);
    final var circuit = project.getCircuit(metadata.mainCircuit());
    if (circuit == null) {
      throw new IOException(
          file.getName() + " names " + metadata.mainCircuit() + ", which it does not contain");
    }
    applyAppearance(circuit, metadata, file.getName());
    if (project.getAddTool(circuit) == null) {
      throw new IOException(file.getName() + " has no tool for " + metadata.mainCircuit());
    }
    // Not the project's own tool: that one is labelled with the circuit's name, which carries the
    // version suffix and has the user's spaces folded into underscores. See PcompTool.
    final var tool = new PcompTool(circuit.getSubcircuitFactory(), metadata.displayName());
    return new PcompComponent(file, metadata, circuit, tool, project.getCircuits());
  }

  /**
   * Reads the project half of a component file.
   *
   * <p>Deliberately not {@code LogisimFile.load(File, Loader)}: that one is the one the Open dialog
   * uses, so it offers to recover an autosave and reports a parse failure through a modal dialog.
   * Neither belongs in a scan of the component directory at startup -- a component nobody asked to
   * open should not stop the launch with a question, and a broken one should come back as the
   * exception {@link PcompCatalog} already knows how to skip.
   */
  private static LogisimFile readProject(File file, Loader loader) throws IOException {
    try (final var in = new FileInputStream(file)) {
      final var project = LogisimFile.loadSub(in, loader, file);
      if (project == null) {
        throw new IOException(file.getName() + " could not be opened");
      }
      return project;
    } catch (SAXException e) {
      throw new IOException(file.getName() + " could not be read: " + e.getMessage(), e);
    }
  }

  /**
   * Derives the drawing from the layout and puts it on the circuit.
   *
   * <p>Setting the appearance attribute to custom is not decoration: {@code
   * CircuitAppearance.isDefaultAppearance} reads that attribute, and while it says otherwise the
   * shapes set here are ignored in favour of a regenerated default box -- the component would load
   * without error and be drawn as an ordinary subcircuit.
   */
  private static void applyAppearance(Circuit circuit, PcompMetadata metadata, String where)
      throws IOException {
    final var pins = new LinkedHashMap<String, com.cburch.logisim.instance.Instance>();
    for (final var pin : circuit.getAppearance().getCircuitPins().getPins()) {
      final var label = pin.getAttributeValue(StdAttr.LABEL);
      if (label != null && !label.isBlank()) pins.put(label.trim(), pin);
    }
    try {
      final var shapes = PcompAppearance.build(metadata.layout(), pins);
      circuit
          .getStaticAttributes()
          .setValue(CircuitAttributes.APPEARANCE_ATTR, CircuitAttributes.APPEAR_CUSTOM);
      circuit.getAppearance().setObjectsForce(shapes);
    } catch (IllegalArgumentException e) {
      throw new IOException(where + ": " + e.getMessage(), e);
    }
  }

  public PcompMetadata getMetadata() {
    return metadata;
  }

  public Circuit getCircuit() {
    return circuit;
  }

  public File getSource() {
    return source;
  }

  /** Whether this component's internals are closed to a project that merely uses it. */
  public boolean isLocked() {
    return metadata.locked();
  }

  /**
   * Whether {@code candidate} is one of the circuits this component is made of.
   *
   * <p>Every circuit in the file, not just the one that is the component. Locking has to cover the
   * dependencies too, or the second double-click gets in through the door the first one was
   * stopped at.
   *
   * <p>Compared by identity. Two components may hold circuits of the same name, and the question
   * being asked -- "is the circuit on screen right now part of this component" -- is about the
   * object, never about what it is called.
   */
  public boolean ownsCircuit(Circuit candidate) {
    return candidate != null && circuits.contains(candidate);
  }

  @Override
  public String getName() {
    return PcompFile.EXTENSION.substring(1) + ":" + metadata.id() + ":" + metadata.version();
  }

  @Override
  public String getDisplayName() {
    return metadata.displayName();
  }

  @Override
  public List<? extends Tool> getTools() {
    return tools;
  }
}
