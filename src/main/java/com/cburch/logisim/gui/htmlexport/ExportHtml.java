/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.htmlexport;

import static com.cburch.logisim.gui.Strings.S;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.gui.generic.OptionPane;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.util.JFileChoosers;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Peler Edition, experimental. Menu entry point for the interactive HTML export.
 *
 * <p>Refuses rather than exports a page that would quietly compute the wrong answer: anything
 * outside {@link HtmlExporter#supportedKinds()} stops the export and is named in the dialog.
 */
public final class ExportHtml {

  private ExportHtml() {}

  /**
   * Names every component the page could not simulate, looking inside subcircuits too, since a
   * subcircuit is flattened on export and its contents end up in the page just the same.
   */
  private static void collectUnsupported(Circuit circuit, Set<String> into, Set<Circuit> seen) {
    if (!seen.add(circuit)) return;
    final var supported = HtmlExporter.supportedKinds();
    for (final var component : circuit.getNonWires()) {
      if (component.getFactory() instanceof SubcircuitFactory factory) {
        collectUnsupported(factory.getSubcircuit(), into, seen);
        continue;
      }
      final var kind = component.getFactory().getName();
      if (!supported.contains(kind)) into.add(kind);
    }
  }

  public static void doExport(Project project) {
    final var circuit = project.getCurrentCircuit();
    if (circuit == null) return;

    final var unsupported = new TreeSet<String>();
    collectUnsupported(circuit, unsupported, new HashSet<>());
    if (!unsupported.isEmpty()) {
      OptionPane.showMessageDialog(
          project.getFrame(),
          S.get("pelerExportHtmlUnsupportedMessage") + "\n\n  " + String.join("\n  ", unsupported),
          S.get("pelerExportHtmlUnsupportedTitle"),
          OptionPane.WARNING_MESSAGE);
      return;
    }

    final var chooser = JFileChoosers.createSelected(new File(circuit.getName() + ".html"));
    chooser.setFileFilter(new FileNameExtensionFilter("HTML", "html", "htm"));
    if (chooser.showSaveDialog(project.getFrame()) != javax.swing.JFileChooser.APPROVE_OPTION) {
      return;
    }
    var target = chooser.getSelectedFile();
    if (!target.getName().toLowerCase().endsWith(".html")
        && !target.getName().toLowerCase().endsWith(".htm")) {
      target = new File(target.getParentFile(), target.getName() + ".html");
    }

    try {
      new HtmlExporter(project, circuit).writeTo(target);
    } catch (Exception e) {
      OptionPane.showMessageDialog(
          project.getFrame(),
          S.get("pelerExportHtmlFailed") + "\n" + e.getMessage(),
          S.get("pelerExportHtmlUnsupportedTitle"),
          OptionPane.ERROR_MESSAGE);
    }
  }
}
