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
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 * Peler Edition. The manifest file at the root of a component library directory, and how it is read
 * and written.
 *
 * <p>A component library is a plain directory: this one small manifest ({@link
 * #MANIFEST_FILE_NAME}) plus zero or more {@code .pcomp} files, each read exactly the way {@link
 * PcompCatalog#scan} already reads them. That is deliberate -- see {@code
 * docs/peler-edition/design/pcomp-libraries.md} section six -- the directory itself is the
 * shareable unit; zipping it and handing it to someone, or pointing "Load Library" at a folder
 * someone sent, needs no format this program does not already read and write.
 */
public final class PcompLibraryFile {
  private PcompLibraryFile() {}

  /** The manifest's file name, fixed like {@code .pcomp} itself. */
  public static final String MANIFEST_FILE_NAME = "library.pcomplib";

  static final String ELEMENT = "pcomplib";
  static final String ATTR_ID = "id";
  static final String ATTR_NAME = "name";

  public static boolean isPcompLibraryDirectory(File directory) {
    return directory != null && directory.isDirectory() && manifestFile(directory).isFile();
  }

  static File manifestFile(File directory) {
    return new File(directory, MANIFEST_FILE_NAME);
  }

  /**
   * Reads the manifest out of a library directory.
   *
   * @throws IOException if the directory has no manifest, or the manifest cannot be parsed
   */
  public static PcompLibraryManifest read(File directory) throws IOException {
    final var file = manifestFile(directory);
    final var document = parse(file);
    final var root = document.getDocumentElement();
    if (root == null || !ELEMENT.equals(root.getTagName())) {
      throw new IOException(file.getPath() + " is not a component library manifest");
    }
    try {
      return new PcompLibraryManifest(root.getAttribute(ATTR_ID), root.getAttribute(ATTR_NAME));
    } catch (IllegalArgumentException e) {
      throw new IOException(file.getPath() + ": " + e.getMessage(), e);
    }
  }

  /**
   * Writes the manifest into a directory, creating the directory if it does not exist yet.
   *
   * @throws IOException if the directory could not be created, or the manifest could not be written
   */
  public static void write(File directory, PcompLibraryManifest manifest) throws IOException {
    if (!directory.isDirectory() && !directory.mkdirs()) {
      throw new IOException(directory.getPath() + " could not be created");
    }
    try {
      final var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
      final var root = document.createElement(ELEMENT);
      root.setAttribute(ATTR_ID, manifest.id());
      root.setAttribute(ATTR_NAME, manifest.name());
      document.appendChild(root);
      final var transformer = TransformerFactory.newInstance().newTransformer();
      transformer.setOutputProperty(OutputKeys.INDENT, "yes");
      transformer.transform(new DOMSource(document), new StreamResult(manifestFile(directory)));
    } catch (ParserConfigurationException | TransformerException e) {
      throw new IOException(directory.getPath() + ": manifest could not be written", e);
    }
  }

  /**
   * Turns a directory into a new, empty component library: a freshly generated id, written as its
   * manifest.
   *
   * @throws IOException if the directory is already a component library, or the manifest could not
   *     be written
   */
  public static PcompLibraryManifest create(File directory, String name) throws IOException {
    if (isPcompLibraryDirectory(directory)) {
      throw new IOException(directory.getPath() + " is already a component library");
    }
    final var manifest = PcompLibraryManifest.create(name);
    write(directory, manifest);
    return manifest;
  }

  private static Document parse(File file) throws IOException {
    try {
      final var factory = DocumentBuilderFactory.newInstance();
      // A manifest arrives with whatever directory the user pointed "Load Library" at. Nothing in
      // the format needs an external entity, so refusing them outright costs nothing.
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setExpandEntityReferences(false);
      return factory.newDocumentBuilder().parse(file);
    } catch (ParserConfigurationException | SAXException e) {
      throw new IOException(file.getPath() + " is not a readable manifest", e);
    }
  }
}
