/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import static com.cburch.logisim.gui.Strings.S;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.gui.generic.OptionPane;
import com.cburch.logisim.proj.Project;
import java.awt.Component;

/**
 * Peler Edition. The one place that answers "may the user get inside this component".
 *
 * <p>The question is asked from five unrelated places -- a double-click in the explorer, a
 * double-click on a placed instance, the instance's context menu, the explorer's context menu and
 * the Project menu -- and every one of them is a door into the same room. Spreading the rule across
 * five call sites is how one of them ends up not having it.
 *
 * <p><b>Locked is not the same as unreachable.</b> A component's file is an ordinary project file,
 * so the way in is to open it: the component manager's "open" action does exactly that, and the
 * user then has the whole editor, because at that point they are editing the component rather than
 * a project that uses it. What locking stops is wandering into the internals from a project that
 * merely places the component, where any edit would apply to a file the user did not think they
 * had open.
 */
public final class PcompLock {
  private PcompLock() {}

  /** The locked component {@code circuit} belongs to, or null if it is not closed to the user. */
  public static PcompLibrary lockedOwnerOf(Circuit circuit) {
    final var component = PcompCatalog.componentOf(circuit);
    return component != null && component.isLocked() ? component : null;
  }

  /** Whether entering {@code circuit} from a project that uses it should be refused. */
  public static boolean blocksEntryInto(Circuit circuit) {
    return lockedOwnerOf(circuit) != null;
  }

  /**
   * Whether the appearance editor should be closed to {@code circuit}.
   *
   * <p>Wider than {@link #blocksEntryInto} on purpose, and in two directions. It covers an unlocked
   * component too, and it covers the component's own file while the user has that file open for
   * editing -- the case where every other restriction is deliberately lifted. The reason is that
   * the drawing is not stored anywhere: it is derived from the port layout every time the component
   * loads. An edit made in the appearance editor would be discarded on the next load, so offering
   * it would be offering an editor whose work does not survive being saved.
   */
  public static boolean blocksAppearanceEditOf(Project project, Circuit circuit) {
    if (circuit == null) return false;
    if (PcompCatalog.componentOf(circuit) != null) return true;
    if (project == null) return false;
    final var file = project.getLogisimFile();
    final var loader = file == null ? null : file.getLoader();
    final var source = loader == null ? null : loader.getMainFile();
    return PcompFile.isPcompFile(source) && file.getMainCircuit() == circuit;
  }

  /** Tells the user why a door did not open, naming the component so the message is actionable. */
  public static void explainLocked(Component parent, PcompLibrary component) {
    OptionPane.showMessageDialog(
        parent,
        S.get("pcompLockedMessage", component.getMetadata().displayName()),
        S.get("pcompLockedTitle"),
        OptionPane.INFORMATION_MESSAGE);
  }
}
