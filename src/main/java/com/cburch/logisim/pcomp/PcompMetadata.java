/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import java.util.List;
import java.util.UUID;

/**
 * Peler Edition. What a {@code .pcomp} file says about itself, over and above the circuit it holds.
 *
 * <p>Identity is {@code id} plus {@code version}, not the name. The id is generated once, when the
 * component is first saved, and never again, so two versions of one component are recognisably
 * related and two components that happen to share a name are not. Two versions of one id are two
 * separate component types that can sit in one project at the same time -- which is the whole
 * point, since the layout is fixed once published and a changed layout has to arrive as something a
 * user opts into rather than as a silent substitution under their wires.
 *
 * <p><b>{@code name} and {@code mainCircuit} are deliberately two things.</b> {@code name} is what
 * the user called the component and what is drawn in the box; {@code mainCircuit} is the name of
 * the circuit inside the file, which carries the version -- {@code MyAdder_v2}. They have to
 * differ, because a project file records a placed component as its library plus the factory name,
 * and a factory name is a circuit name: if both versions called their circuit {@code MyAdder}, a
 * project that used v1 would silently bind to v2 the next time it was opened, moving every port it
 * had wires on. The suffix is what makes the two versions distinct references. The drawn caption
 * stays the bare name, so a version bump does not change the shape of the box.
 *
 * <p>The whole drawing lives in one {@link PortLayout} rather than in a handful of loose numbers.
 * The box's size, the caption's place and every port's coordinate are one description of one
 * picture, and a file that could state them apart from each other is a file that could state them
 * inconsistently.
 *
 * @param id stable across renames and version bumps; a UUID string
 * @param version 1 for the first publication, incremented for each one after
 * @param name what the user called the component; also the caption drawn in the box
 * @param mainCircuit the name of the circuit in this file that <em>is</em> the component; the rest
 *     are its dependencies
 * @param locked whether the component's internals are closed to a project that uses it
 * @param layout the box as the user laid it out, ports and all
 */
public record PcompMetadata(
    String id,
    int version,
    String name,
    String mainCircuit,
    boolean locked,
    PortLayout layout) {

  public PcompMetadata {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("a component needs an id");
    if (version < 1) throw new IllegalArgumentException("version must be 1 or more: " + version);
    if (name == null || name.isBlank()) throw new IllegalArgumentException("a component needs a name");
    if (mainCircuit == null || mainCircuit.isBlank()) {
      throw new IllegalArgumentException("a component needs a main circuit");
    }
    if (layout == null) throw new IllegalArgumentException(name + " has no layout");
    id = id.trim();
    name = name.trim();
    mainCircuit = mainCircuit.trim();
    if (!layout.caption().equals(name)) {
      throw new IllegalArgumentException(
          name + " is drawn with the caption " + layout.caption());
    }
  }

  /** Every port, with the place the layout window settled on. */
  public List<PortPlacement> ports() {
    return layout.placements();
  }

  /**
   * The name the circuit inside a component file carries.
   *
   * <p>Not the name the user typed. A circuit name goes through {@code
   * SyntaxChecker.isVariableNameAcceptable}, which rejects a space -- and a rejected name cannot be
   * set on a live circuit at all, because the {@code NAME_ATTR} listener puts the old name back and
   * <em>opens a modal dialog</em> while doing it. So the spaces are folded out here and the user's
   * own name is kept, spaces and all, in {@link #name}: it is what the box is captioned with, what
   * the manager panel lists and what {@link #displayName} reads.
   *
   * <p>A run of spaces folds to one underscore rather than one each, because {@code __} is rejected
   * too. What this does not do is rescue a name that is unusable for some other reason -- a hyphen,
   * a leading digit, a trailing underscore -- so those are still refused, at the point where the
   * user can see why.
   *
   * <p>The version suffix is an underscore for the same reason. A name ending in one would make
   * {@code __v1}, but such a name is refused before it gets here.
   */
  public static String circuitNameFor(String name, int version) {
    return withoutSpaces(name) + "_v" + version;
  }

  /** The user's name with every run of whitespace folded into a single underscore. */
  private static String withoutSpaces(String name) {
    return name.trim().replaceAll("\\s+", "_");
  }

  /** A first publication of a component that has never been saved before. */
  public static PcompMetadata firstVersion(String name, PortLayout layout) {
    return new PcompMetadata(
        UUID.randomUUID().toString(), 1, name, circuitNameFor(name, 1), true, layout);
  }

  /**
   * The next version of this component. Same id, so the two are recognisably related; a new version
   * number, so they are two types and nothing a user already drew moves under them.
   */
  public PcompMetadata nextVersion(PortLayout newLayout) {
    return new PcompMetadata(
        id, version + 1, name, circuitNameFor(name, version + 1), locked, newLayout);
  }

  /** The same component, published again under the same version number. */
  public PcompMetadata withLayout(PortLayout newLayout) {
    return new PcompMetadata(id, version, name, mainCircuit, locked, newLayout);
  }

  /** How this component is named in the toolbox: the name the user gave it, and its version. */
  public String displayName() {
    return name + " v" + version;
  }
}
