/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/**
 * Peler Edition. The {@code .pcomp} file format: where the component metadata sits inside one, and
 * how it is read back.
 *
 * <p>A {@code .pcomp} file <b>is</b> an ordinary project file. It holds the circuit that is the
 * component, every circuit that one depends on, and one extra {@code <pcomp>} element under
 * {@code <project>} carrying {@link PcompMetadata}. Everything about loading, saving, simulating
 * and drawing a circuit therefore works on it unchanged, and the component itself is a subcircuit
 * with an appearance derived from its layout rather than a new kind of component.
 *
 * <p><b>Official Logisim-evolution cannot open a {@code .pcomp} file, by construction.</b> Its
 * reader throws on any unrecognised element directly under {@code <project>}, so the {@code <pcomp>}
 * element makes the whole file unreadable there rather than merely losing the metadata. That is
 * accepted: this is this edition's own component format and was never going to open over there. The
 * case that does have to keep working is a <em>project</em> that uses custom components being saved
 * to official {@code .circ}, and that goes down a different path entirely -- each component is
 * written out as a plain subcircuit carrying the same appearance, so the ports land on the same
 * coordinates and no wire moves.
 *
 * <p>The metadata is read by parsing the file a second time rather than by threading it through
 * {@code XmlReader}. It keeps {@code LogisimFile} free of a field that means nothing to all but a
 * handful of files, and a component file is small enough that the second parse does not show.
 */
public final class PcompFile {
  private PcompFile() {}

  /** This edition's custom-component extension. Fixed, like {@code .pcirc}. */
  public static final String EXTENSION = ".pcomp";

  static final String ELEMENT = "pcomp";
  static final String PORT_ELEMENT = "port";
  static final String ATTR_ID = "id";
  static final String ATTR_VERSION = "version";
  static final String ATTR_MAIN = "main";
  static final String ATTR_COMPONENT_NAME = "name";
  static final String ATTR_LOCKED = "locked";
  static final String ATTR_NAME = "name";
  static final String ATTR_SIDE = "side";
  static final String ATTR_SLOT = "slot";

  public static boolean isPcompFile(File file) {
    return file != null && file.getName().endsWith(EXTENSION);
  }

  /**
   * Reads the metadata out of a component file.
   *
   * @return the metadata, or null if this is a well-formed project file that simply is not a
   *     component -- the caller wants to skip those, not to fail on them
   * @throws IOException if the file cannot be read or is not XML at all
   */
  public static PcompMetadata read(File file) throws IOException {
    final var document = parse(file);
    final var root = document.getDocumentElement();
    if (root == null) return null;
    for (var child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element element && ELEMENT.equals(element.getTagName())) {
        return readElement(element, file.getName());
      }
    }
    return null;
  }

  /** Builds the {@code <pcomp>} element for a document being written. */
  public static Element toElement(Document document, PcompMetadata metadata) {
    final var element = document.createElement(ELEMENT);
    element.setAttribute(ATTR_ID, metadata.id());
    element.setAttribute(ATTR_VERSION, Integer.toString(metadata.version()));
    element.setAttribute(ATTR_COMPONENT_NAME, metadata.name());
    element.setAttribute(ATTR_MAIN, metadata.mainCircuit());
    element.setAttribute(ATTR_LOCKED, Boolean.toString(metadata.locked()));
    for (final var port : metadata.ports()) {
      final var child = document.createElement(PORT_ELEMENT);
      child.setAttribute(ATTR_NAME, port.name());
      child.setAttribute(ATTR_SIDE, port.side().toXmlValue());
      child.setAttribute(ATTR_SLOT, Integer.toString(port.slot()));
      element.appendChild(child);
    }
    return element;
  }

  static PcompMetadata readElement(Element element, String where) throws IOException {
    final var ports = new ArrayList<PortPlacement>();
    for (var child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (!(child instanceof Element port) || !PORT_ELEMENT.equals(port.getTagName())) continue;
      final var side = PortSide.fromXmlValue(port.getAttribute(ATTR_SIDE));
      if (side == null) {
        throw new IOException(
            where + ": port " + port.getAttribute(ATTR_NAME) + " has no usable side");
      }
      final int slot;
      try {
        slot = Integer.parseInt(port.getAttribute(ATTR_SLOT).trim());
      } catch (NumberFormatException e) {
        throw new IOException(
            where + ": port " + port.getAttribute(ATTR_NAME) + " has no usable slot");
      }
      ports.add(new PortPlacement(port.getAttribute(ATTR_NAME), side, slot));
    }
    final int version;
    try {
      version = Integer.parseInt(element.getAttribute(ATTR_VERSION).trim());
    } catch (NumberFormatException e) {
      throw new IOException(where + ": no usable version number");
    }
    final var mainCircuit = element.getAttribute(ATTR_MAIN);
    // A file written by this program always carries the name. Letting it default keeps a
    // hand-written component file -- the way the format is easiest to try out -- from needing two
    // attributes that say almost the same thing.
    final var name = element.getAttribute(ATTR_COMPONENT_NAME);
    try {
      return new PcompMetadata(
          element.getAttribute(ATTR_ID),
          version,
          name.isBlank() ? mainCircuit : name,
          mainCircuit,
          !"false".equalsIgnoreCase(element.getAttribute(ATTR_LOCKED).trim()),
          ports);
    } catch (IllegalArgumentException e) {
      throw new IOException(where + ": " + e.getMessage(), e);
    }
  }

  private static Document parse(File file) throws IOException {
    try {
      final var factory = DocumentBuilderFactory.newInstance();
      // A component file arrives from wherever the user got it. Nothing in the format needs an
      // external entity, so refusing them outright costs nothing and closes the usual door.
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setExpandEntityReferences(false);
      return factory.newDocumentBuilder().parse(file);
    } catch (ParserConfigurationException | SAXException e) {
      throw new IOException(file.getName() + " is not a readable component file", e);
    }
  }
}
