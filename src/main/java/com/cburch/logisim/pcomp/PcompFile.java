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
import java.util.List;
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
  static final String ATTR_WIDTH = "width";
  static final String ATTR_HEIGHT = "height";
  static final String ATTR_CAPTION_X = "caption-x";
  static final String ATTR_CAPTION_Y = "caption-y";
  static final String ATTR_X = "x";
  static final String ATTR_Y = "y";

  /**
   * How the first version of this format said where a port went: an edge and a position in that
   * edge's order, with the coordinate worked out from the port names at load time. Files written
   * that way are still read -- see {@link #readElement} -- but nothing writes it any more.
   */
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
    final var layout = metadata.layout();
    final var element = document.createElement(ELEMENT);
    element.setAttribute(ATTR_ID, metadata.id());
    element.setAttribute(ATTR_VERSION, Integer.toString(metadata.version()));
    element.setAttribute(ATTR_COMPONENT_NAME, metadata.name());
    element.setAttribute(ATTR_MAIN, metadata.mainCircuit());
    element.setAttribute(ATTR_LOCKED, Boolean.toString(metadata.locked()));
    element.setAttribute(ATTR_WIDTH, Integer.toString(layout.width()));
    element.setAttribute(ATTR_HEIGHT, Integer.toString(layout.height()));
    element.setAttribute(ATTR_CAPTION_X, Integer.toString(layout.captionX()));
    element.setAttribute(ATTR_CAPTION_Y, Integer.toString(layout.captionY()));
    for (final var port : layout.placements()) {
      final var child = document.createElement(PORT_ELEMENT);
      child.setAttribute(ATTR_NAME, port.name());
      child.setAttribute(ATTR_SIDE, port.side().toXmlValue());
      child.setAttribute(ATTR_X, Integer.toString(port.x()));
      child.setAttribute(ATTR_Y, Integer.toString(port.y()));
      element.appendChild(child);
    }
    return element;
  }

  static PcompMetadata readElement(Element element, String where) throws IOException {
    final var laidOut = new ArrayList<PortPlacement>();
    final var inSlots = new ArrayList<PortPlacement>();
    for (var child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (!(child instanceof Element port) || !PORT_ELEMENT.equals(port.getTagName())) continue;
      final var name = port.getAttribute(ATTR_NAME);
      final var side = PortSide.fromXmlValue(port.getAttribute(ATTR_SIDE));
      if (side == null) {
        throw new IOException(where + ": port " + name + " has no usable side");
      }
      final var x = number(port, ATTR_X);
      final var y = number(port, ATTR_Y);
      final var slot = number(port, ATTR_SLOT);
      try {
        if (x != null && y != null) {
          laidOut.add(new PortPlacement(name, side, x, y));
        } else if (slot != null && slot >= 0) {
          // The order along the side is all the automatic layout reads back out of these, so a
          // coordinate that merely sorts the way the slots did is enough to stand in for one.
          inSlots.add(
              new PortPlacement(
                  name, side, side.stacked() ? 0 : slot, side.stacked() ? slot : 0));
        } else {
          throw new IOException(where + ": port " + name + " says nowhere in particular");
        }
      } catch (IllegalArgumentException e) {
        throw new IOException(where + ": " + e.getMessage(), e);
      }
    }
    final var version = number(element, ATTR_VERSION);
    if (version == null) throw new IOException(where + ": no usable version number");
    final var mainCircuit = element.getAttribute(ATTR_MAIN);
    // A file written by this program always carries the name. Letting it default keeps a
    // hand-written component file -- the way the format is easiest to try out -- from needing two
    // attributes that say almost the same thing.
    final var declared = element.getAttribute(ATTR_COMPONENT_NAME);
    final var name = declared.isBlank() ? mainCircuit : declared;
    try {
      return new PcompMetadata(
          element.getAttribute(ATTR_ID),
          version,
          name,
          mainCircuit,
          !"false".equalsIgnoreCase(element.getAttribute(ATTR_LOCKED).trim()),
          layoutOf(element, name.trim(), laidOut, inSlots));
    } catch (IllegalArgumentException e) {
      throw new IOException(where + ": " + e.getMessage(), e);
    }
  }

  /**
   * The box this element describes.
   *
   * <p>Two shapes of file end up here. One states the geometry outright -- a size for the box and a
   * coordinate for every port -- and is what this program writes. The other is the first version of
   * the format, which gave each port an edge and a place in that edge's order and left the
   * arithmetic to the reader; those are laid out by {@link PortLayout#automatic}, which is the same
   * arithmetic in the same place it always was. A file of the older shape therefore still opens,
   * still places, and still wires up -- though not always to the pixel it once did, the automatic
   * layout having been corrected since.
   *
   * <p>A file that mixes the two is read as the older shape. Half a geometry is not one, and
   * arranging the whole thing is the answer that at least produces a component.
   */
  private static PortLayout layoutOf(
      Element element, String caption, List<PortPlacement> laidOut, List<PortPlacement> inSlots) {
    final var width = number(element, ATTR_WIDTH);
    final var height = number(element, ATTR_HEIGHT);
    if (inSlots.isEmpty() && width != null && height != null) {
      final var captionX = number(element, ATTR_CAPTION_X);
      final var captionY = number(element, ATTR_CAPTION_Y);
      return new PortLayout(
          caption,
          width,
          height,
          captionX == null ? width / 2 : captionX,
          captionY == null ? height / 2 : captionY,
          laidOut);
    }
    final var everything = new ArrayList<>(inSlots);
    everything.addAll(laidOut);
    return PortLayout.automatic(caption, everything);
  }

  /** One integer attribute, or null when it is absent or is not a number. */
  private static Integer number(Element element, String attribute) {
    final var text = element.getAttribute(attribute).trim();
    if (text.isEmpty()) return null;
    try {
      return Integer.valueOf(text);
    } catch (NumberFormatException e) {
      return null;
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
