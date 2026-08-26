/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.file;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.pcomp.PcompFile;
import com.cburch.logisim.pcomp.PcompMetadata;
import com.cburch.logisim.util.XmlUtil;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Peler Edition. Writes one custom component out of an open project.
 *
 * <p>Lives here rather than beside the rest of {@code com.cburch.logisim.pcomp} because
 * {@code XmlWriter} and {@code LogisimFile.write} are package-private, and a component file is an
 * ordinary project file plus one element -- reimplementing the project half of it would be a second
 * writer to keep in step with the first.
 *
 * <p>What it does that a plain save does not: it works on a clone, and it throws away every circuit
 * the component does not reach. A project usually holds unrelated work, and a component that
 * carried all of it would grow with the project it happened to be built in.
 */
public final class PcompWriter {
  private PcompWriter() {}

  /**
   * Writes {@code main} and everything it depends on to {@code dest} as a custom component.
   *
   * <p>{@code source} is left untouched: the clone is what gets trimmed, so the project the user is
   * still editing does not lose the circuits this component happens not to use.
   *
   * @throws IOException if the project cannot be cloned, trimmed or written
   */
  public static void write(File dest, LogisimFile source, Circuit main, PcompMetadata metadata,
      Loader loader) throws IOException {
    final var clone = source.cloneLogisimFile(loader);
    if (clone == null) {
      throw new IOException("the project could not be copied");
    }
    final var mainClone = clone.getCircuit(main.getName());
    if (mainClone == null) {
      throw new IOException(main.getName() + " is not in this project");
    }
    clone.setMainCircuit(mainClone);
    clone.setName(metadata.name());
    trimTo(clone, dependencyClosure(mainClone));
    for (final var circuit : clone.getCircuits()) {
      if (circuit != mainClone && circuit.getName().equals(metadata.mainCircuit())) {
        throw new IOException(
            "this project already has a circuit called " + metadata.mainCircuit());
      }
    }

    final var project = new ByteArrayOutputStream();
    clone.write(project, loader, dest, null);
    if (project.size() == 0) {
      throw new IOException("the project could not be written");
    }
    writeWithMetadata(dest, project.toByteArray(), main.getName(), metadata);
  }

  /**
   * Every circuit reachable from this one, itself included.
   *
   * <p>Reachability is over {@link SubcircuitFactory} only. A circuit that appears in the project
   * but not in this walk is not part of the component, however closely related the user considers
   * it.
   */
  public static Set<Circuit> dependencyClosure(Circuit main) {
    final var found = new LinkedHashSet<Circuit>();
    final var pending = new ArrayDeque<Circuit>();
    pending.add(main);
    while (!pending.isEmpty()) {
      final var circuit = pending.removeFirst();
      if (!found.add(circuit)) continue;
      for (final var component : circuit.getNonWires()) {
        if (component.getFactory() instanceof SubcircuitFactory factory) {
          pending.add(factory.getSubcircuit());
        }
      }
    }
    return found;
  }

  /** Drops every circuit that is not in the closure, by identity rather than by name. */
  private static void trimTo(LogisimFile file, Set<Circuit> keep) {
    for (final var circuit : new ArrayList<>(file.getCircuits())) {
      if (!keep.contains(circuit)) file.removeCircuit(circuit);
    }
  }

  /** Drops the text nodes that are nothing but the previous pass's line breaks and spaces. */
  private static void stripIndentation(Node node) {
    final var children = node.getChildNodes();
    for (var index = children.getLength() - 1; index >= 0; index--) {
      final var child = children.item(index);
      if (child.getNodeType() == Node.TEXT_NODE && child.getTextContent().isBlank()) {
        node.removeChild(child);
      } else {
        stripIndentation(child);
      }
    }
  }

  /**
   * Renames the component's circuit to the versioned name the metadata declares.
   *
   * <p>Done on the written XML rather than on the clone, for two reasons. {@code Circuit.setName}
   * goes through {@code CircuitAttributes}, which validates the new name and reports a rejection by
   * opening a modal dialog -- a writer is no place for that. And the name appears twice in the
   * output, as the {@code <circuit>} element's attribute and again as the {@code circuit} static
   * attribute inside it; the reader applies the second over the first, so renaming only one of them
   * would produce a file whose circuit is not the one its metadata names.
   */
  private static void renameMainCircuit(Node root, String from, String to) {
    if (from.equals(to)) return;
    final var children = root.getChildNodes();
    for (var index = 0; index < children.getLength(); index++) {
      if (!(children.item(index) instanceof Element element)) continue;
      if ("main".equals(element.getTagName()) && from.equals(element.getAttribute("name"))) {
        element.setAttribute("name", to);
      }
      if (!"circuit".equals(element.getTagName())) continue;
      if (!from.equals(element.getAttribute("name"))) continue;
      element.setAttribute("name", to);
      final var attributes = element.getChildNodes();
      for (var inner = 0; inner < attributes.getLength(); inner++) {
        if (attributes.item(inner) instanceof Element attribute
            && "a".equals(attribute.getTagName())
            && "circuit".equals(attribute.getAttribute("name"))) {
          attribute.setAttribute("val", to);
        }
      }
    }
  }

  /**
   * Re-parses the written project, renames its main circuit and appends the {@code <pcomp>}
   * element to it.
   *
   * <p>A second pass rather than a hook in {@code XmlWriter}: the element belongs to this format
   * alone, and the writer that everything else in the program uses should not grow a parameter for
   * it.
   */
  private static void writeWithMetadata(
      File dest, byte[] project, String circuitName, PcompMetadata metadata) throws IOException {
    try {
      final var builder = XmlUtil.getHardenedBuilderFactory().newDocumentBuilder();
      final var document = builder.parse(new ByteArrayInputStream(project));
      // The bytes already carry the writer's own indentation as text nodes. Indenting again on the
      // way out would add a second layer of it around every element, so they go first.
      stripIndentation(document.getDocumentElement());
      renameMainCircuit(document.getDocumentElement(), circuitName, metadata.mainCircuit());
      document.getDocumentElement().appendChild(PcompFile.toElement(document, metadata));
      final var transformer = TransformerFactory.newInstance().newTransformer();
      transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      transformer.setOutputProperty(OutputKeys.INDENT, "yes");
      try (final var out = new FileOutputStream(dest)) {
        transformer.transform(new DOMSource(document), new StreamResult(out));
      }
    } catch (ParserConfigurationException | SAXException | TransformerException e) {
      throw new IOException("the component file could not be written: " + e.getMessage(), e);
    }
  }
}
