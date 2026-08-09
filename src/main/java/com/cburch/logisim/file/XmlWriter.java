/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.file;

import com.cburch.draw.model.AbstractCanvasObject;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitAttributes;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeDefaultProvider;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.fpga.data.MapComponent;
import com.cburch.logisim.generated.BuildInfo;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.std.annotate.Annotation;
import com.cburch.logisim.std.annotate.AnnotationAttributes;
import com.cburch.logisim.std.annotate.AnnotationLibrary;
import com.cburch.logisim.std.base.BaseLibrary;
import com.cburch.logisim.std.base.Text;
import com.cburch.logisim.std.wiring.ProbeAttributes;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.tools.Tool;
import com.cburch.logisim.util.InputEventUtil;
import com.cburch.logisim.util.LineBuffer;
import com.cburch.logisim.util.StringUtil;
import com.cburch.logisim.util.XmlUtil;
import com.cburch.logisim.vhdl.base.VhdlContent;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.regex.Pattern;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

final class XmlWriter {

  private final LogisimFile file;
  private final Document doc;
  /**
   * Path of the file which is being written on disk -- used to relativize components stored in it.
   */
  private final String outFilePath;
  private final boolean isProjectExport;
  private final LibraryLoader loader;
  private final HashMap<Library, String> libs = new HashMap<>();
  private final boolean isRecursiveCall;

  /**
   * Peler Edition compatibility mode: on when the destination is an official {@code .circ} rather
   * than this edition's own {@code .pcirc}. It lowers the project to what official
   * Logisim-evolution v4.1.0 can actually read -- annotations become plain {@code Text}
   * components, and the tools upstream has never heard of drop out of the mouse mappings and the
   * toolbar. Without it, upstream greets the file with "The built-in library Annotation is not
   * available" plus one error per annotation, then discards them; and since upstream regenerates
   * the whole XML from its in-memory model when it saves, anything it did not understand is gone
   * for good the first time someone saves over there.
   *
   * <p>Set after construction rather than through the constructors so all three of them, and every
   * caller, stay as upstream wrote them.
   */
  private boolean compatMode;

  private XmlWriter(LogisimFile file, Document doc, LibraryLoader loader) {
    this(file, doc, loader, null, null, false);
  }

  private XmlWriter(LogisimFile file, Document doc, LibraryLoader loader, String outFilePath) {
    this(file, doc, loader, outFilePath, null, false);
  }

  private XmlWriter(LogisimFile file, Document doc, LibraryLoader loader, String outFilePath, 
      String mainCircFile, boolean recursiveCall) {
    this.file = file;
    this.doc = doc;
    this.loader = loader;
    this.outFilePath = outFilePath;
    isProjectExport = StringUtil.isNotEmpty(mainCircFile);
    isRecursiveCall = recursiveCall;
  }



  /* We sort some parts of the xml tree, to help with reproducibility and to
   * ease testing (e.g. diff a circuit file). Attribute name=value pairs seem
   * to be sorted already, so we don't worry about those. The code below sorts
   * the nodes, but only in best-effort fashion (some nodes are identical
   * except for their child contents, which seems overkill to bother sorting).
   * Parts of the tree where node order matters (top-level "project", the
   * libraries, and the toolbar, for example) are not sorted.
   */

  static String attrToString(Attr a) {
    String n = a.getName();
    String v = a.getValue().replaceAll("&", "&amp;").replaceAll("\"", "&quot;");
    return n + "=\"" + v + "\"";
  }

  static String attrsToString(NamedNodeMap a) {
    final var n = a.getLength();
    if (n == 0) return "";
    else if (n == 1) return attrToString((Attr) a.item(0));
    final var lst = new ArrayList<String>();
    for (var i = 0; i < n; i++) {
      lst.add(attrToString((Attr) a.item(i)));
    }
    Collections.sort(lst);
    return String.join(" ", lst);
  }

  private static int stringCompare(String stringA, String stringB) {
    if (stringA == null) return -1;
    if (stringB == null) return 1;
    return stringA.compareTo(stringB);
  }

  private static final Comparator<Node> nodeComparator =
      (nodeA, nodeB) -> {
        var compareResult = stringCompare(nodeA.getNodeName(), nodeB.getNodeName());
        if (compareResult != 0) return compareResult;
        compareResult = stringCompare(attrsToString(nodeA.getAttributes()), attrsToString(nodeB.getAttributes()));
        if (compareResult != 0) return compareResult;
        return stringCompare(nodeA.getNodeValue(), nodeB.getNodeValue());
      };

  static void sort(Node top) {
    final var children = top.getChildNodes();
    final var childrenCount = children.getLength();
    final var name = top.getNodeName();
    // project (contains ordered elements, do not sort)
    // - main
    // - toolbar (contains ordered elements, do not sort)
    //   - tool(s)
    //     - a(s)
    // - lib(s) (contains orderd elements, do not sort)
    //   - tool(s)
    //     - a(s)
    // - options
    //   - a(s)
    // - circuit(s)
    //   - a(s)
    //   - comp(s)
    //   - wire(s)
    if ("appear".equals(name)) {
      // the appearance section only has to sort the circuit ports, the rest is static.
      final var circuitPortIndexes = new ArrayList<Integer>();
      for (var nodeIndex = 0; nodeIndex < childrenCount; nodeIndex++)
        if ("circ-port".equals(children.item(nodeIndex).getNodeName())) circuitPortIndexes.add(nodeIndex);
      if (circuitPortIndexes.isEmpty()) return;
      final var numberOfPorts = circuitPortIndexes.size();
      final var nodeSet = new Node[numberOfPorts];
      for (var portIndex = 0; portIndex < numberOfPorts; portIndex++)
        nodeSet[portIndex] = children.item(circuitPortIndexes.get(portIndex));
      Arrays.sort(nodeSet, nodeComparator);
      for (var portIndex = 0; portIndex < numberOfPorts; portIndex++) top.insertBefore(nodeSet[portIndex], null);
      return;
    }
    if (childrenCount > 1 && !name.equals("project") && !name.equals("lib") && !name.equals("toolbar")
          && !name.equals("appear")) {
      final var nodeSet = new Node[childrenCount];
      for (var nodeIndex = 0; nodeIndex < childrenCount; nodeIndex++) nodeSet[nodeIndex] = children.item(nodeIndex);
      Arrays.sort(nodeSet, nodeComparator);
      for (var nodeIndex = 0; nodeIndex < childrenCount; nodeIndex++) top.insertBefore(nodeSet[nodeIndex], null);
    }
    for (var childId = 0; childId < childrenCount; childId++) {
      sort(children.item(childId));
    }
  }

  static void write(LogisimFile file, OutputStream out, LibraryLoader loader, File destFile, String mainCircFile, boolean recurse)
      throws ParserConfigurationException, TransformerException, IOException, LoadFailedException {

    final var docFactory = XmlUtil.getHardenedBuilderFactory();
    final var docBuilder = docFactory.newDocumentBuilder();

    final var doc = docBuilder.newDocument();
    XmlWriter context;
    if (destFile != null) {
      var dstFilePath = destFile.getAbsolutePath();
      dstFilePath = dstFilePath.substring(0, dstFilePath.lastIndexOf(File.separator));
      context = new XmlWriter(file, doc, loader, dstFilePath);
    } else if (mainCircFile != null) {
      context = new XmlWriter(file, doc, loader, null, mainCircFile, recurse);
    } else context = new XmlWriter(file, doc, loader);

    // Peler Edition: the destination's extension picks the dialect. Only the plain save path
    // supplies destFile; a project bundle keeps full fidelity, since the bundle is ours too.
    context.compatMode =
        PelerCompat.isCompatTarget(destFile);

    context.fromLogisimFile();

    final var tfFactory = TransformerFactory.newInstance();
    try {
      tfFactory.setAttribute("indent-number", 2);
    } catch (IllegalArgumentException ignored) {
      // Do nothing
    }
    final var tf = tfFactory.newTransformer();
    tf.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
    tf.setOutputProperty(OutputKeys.INDENT, "yes");
    try {
      tf.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
    } catch (IllegalArgumentException ignored) {
      // Do nothing
    }

    if ((mainCircFile != null) && (out instanceof ZipOutputStream zipFile)) {
      zipFile.putNextEntry(new ZipEntry(mainCircFile));
    }
    doc.normalize();
    sort(doc);
    Source src = new DOMSource(doc);
    Result dest = new StreamResult(out);
    tf.transform(src, dest);
  }

  void addAttributeSetContent(Element elt, AttributeSet attrs, AttributeDefaultProvider source, boolean userModifiedOnly) {
    if (attrs == null) return;
    if (source != null && source.isAllDefaultValues(attrs, BuildInfo.version)) return;
    for (final var attrBase : attrs.getAttributes()) {
      @SuppressWarnings("unchecked")
      final var attr = (Attribute<Object>) attrBase;
      final var val = attrs.getValue(attr);
      if (userModifiedOnly && (attrs.isReadOnly(attr) || attr.isHidden())) continue;
      if (attrs.isToSave(attr) && val != null) {
        final var dflt = source == null ? null : source.getDefaultAttributeValue(attr, BuildInfo.version);
        final var defaultValue = dflt == null ? "" : attr.toStandardString(dflt);
        var newValue = attr.toStandardString(val);
        if (dflt == null || (!dflt.equals(val) && !defaultValue.equals(newValue))
            || (attr.equals(StdAttr.APPEARANCE) && !userModifiedOnly)
            || (attr.equals(ProbeAttributes.PROBEAPPEARANCE) && !userModifiedOnly && val.equals(ProbeAttributes.APPEAR_EVOLUTION_NEW))) {
          final var a = doc.createElement("a");
          a.setAttribute("name", attr.getName());
          if ("filePath".equals(attr.getName()) && outFilePath != null) {
            final var outFP = Paths.get(outFilePath);
            final var attrValP = Paths.get(newValue);
            newValue = (outFP.relativize(attrValP)).toString();
            a.setAttribute("val", newValue);
          } else {
            if (newValue.contains("\n")) {
              a.appendChild(doc.createTextNode(newValue));
            } else {
              a.setAttribute("val", attr.toStandardString(val));
            }
          }
          elt.appendChild(a);
        }
      }
    }
  }

  Library findLibrary(ComponentFactory source) {
    if (file.contains(source)) return file;
    for (final var lib : file.getLibraries()) {
      if (lib.contains(source)) return lib;
    }
    return null;
  }

  Library findLibrary(Tool tool) {
    if (libraryContains(file, tool)) return file;
    for (final var lib : file.getLibraries()) {
      if (libraryContains(lib, tool)) return lib;
    }
    return null;
  }

  Element fromCircuit(Circuit circuit) {
    final var ret = doc.createElement("circuit");
    ret.setAttribute("name", circuit.getName());
    addAttributeSetContent(ret, circuit.getStaticAttributes(), CircuitAttributes.DEFAULT_STATIC_ATTRIBUTES, false);
    if (circuit.getAppearance().hasCustomAppearance()) {
      final var appear = doc.createElement("appear");
      for (Object obj : circuit.getAppearance().getCustomObjectsFromBottom()) {
        if (obj instanceof AbstractCanvasObject canvasObject) {
          final var elt = canvasObject.toSvgElement(doc);
          if (elt != null) {
            appear.appendChild(elt);
          }
        }
      }
      ret.appendChild(appear);
    }
    for (final var wire : circuit.getWires()) {
      ret.appendChild(fromWire(wire));
    }
    for (final var comp : circuit.getNonWires()) {
      final var elt = fromComponent(comp);
      if (elt != null) ret.appendChild(elt);
    }
    for (final var board : circuit.getBoardMapNamestoSave()) {
      final var elt = fromMap(circuit, board);
      if (elt != null) ret.appendChild(elt);
    }
    return ret;
  }

  Element fromVhdl(VhdlContent vhdl) {
    vhdl.aboutToSave();
    final var ret = doc.createElement("vhdl");
    ret.setAttribute("name", vhdl.getName());
    ret.setTextContent(vhdl.getContent());
    return ret;
  }

  Element fromMap(Circuit circ, String boardName) {
    final var ret = doc.createElement("boardmap");
    ret.setAttribute("boardname", boardName);
    for (String key : circ.getMapInfo(boardName).keySet()) {
      final var map = doc.createElement("mc");
      final var mapInfo = circ.getMapInfo(boardName).get(key);
      if (mapInfo.isOldFormat()) {
        map.setAttribute("key", key);
        if (mapInfo.isOpen()) {
          map.setAttribute(MapComponent.OPEN_KEY, MapComponent.OPEN_KEY);
        } else if (mapInfo.isConst()) {
          map.setAttribute(MapComponent.CONSTANT_KEY, Long.toString(mapInfo.getConstValue()));
        } else {
          final var rect = mapInfo.getRectangle();
          map.setAttribute("valx", Integer.toString(rect.getXpos()));
          map.setAttribute("valy", Integer.toString(rect.getYpos()));
          map.setAttribute("valw", Integer.toString(rect.getWidth()));
          map.setAttribute("valh", Integer.toString(rect.getHeight()));
        }
      } else {
        final var nmap = mapInfo.getMap();
        if (nmap != null)
          nmap.getMapElement(map);
        else {
          map.setAttribute("key", key);
          MapComponent.getComplexMap(map, mapInfo);
        }
      }
      ret.appendChild(map);
    }
    return ret;
  }

  /**
   * Peler Edition compatibility mode: rewrite an annotation as the closest thing upstream owns, a
   * plain {@code Text} component at the same place, so the note is still legible over there
   * instead of vanishing. Deliberately one-way -- what upstream cannot model (which component the
   * note is attached to, and how) is dropped rather than smuggled into attributes it would
   * silently discard the next time it saved.
   *
   * <p>Multiple lines are joined with a space rather than kept: upstream's {@code Text} paints via
   * a single {@code drawString}, which has no notion of a line break.
   *
   * @return the substitute element, or null if it should not be written at all
   */
  private Element fromAnnotationAsText(Component comp) {
    final var baseLib = baseLibraryName();
    if (baseLib == null) return null;

    final var attrs = comp.getAttributeSet();
    final var raw = attrs.getValue(AnnotationAttributes.ATTR_TEXT);
    if (StringUtil.isNullOrEmpty(raw)) return null;
    // Base Attribute.toStandardString strips the remaining control characters for us.
    final var text = Text.ATTR_TEXT.toStandardString(raw.replace('\n', ' '));
    if (text.isEmpty()) return null;

    final var ret = doc.createElement("comp");
    ret.setAttribute("lib", baseLib);
    ret.setAttribute("name", Text._ID);
    ret.setAttribute("loc", comp.getLocation().toString());
    addPlainAttribute(ret, Text.ATTR_TEXT.getName(), text);
    addPlainAttribute(
        ret, Text.ATTR_FONT.getName(), Text.ATTR_FONT.toStandardString(attrs.getValue(Text.ATTR_FONT)));
    addPlainAttribute(
        ret,
        Text.ATTR_COLOR.getName(),
        Text.ATTR_COLOR.toStandardString(attrs.getValue(Text.ATTR_COLOR)));
    addPlainAttribute(
        ret,
        Text.ATTR_HALIGN.getName(),
        Text.ATTR_HALIGN.toStandardString(attrs.getValue(Text.ATTR_HALIGN)));
    addPlainAttribute(
        ret,
        Text.ATTR_VALIGN.getName(),
        Text.ATTR_VALIGN.toStandardString(attrs.getValue(Text.ATTR_VALIGN)));
    return ret;
  }

  private void addPlainAttribute(Element parent, String name, String value) {
    final var elt = doc.createElement("a");
    elt.setAttribute("name", name);
    elt.setAttribute("val", value);
    parent.appendChild(elt);
  }

  /**
   * Index of the {@code #Base} library in the file being written, or null if it somehow is not
   * there. Always written out -- {@code fromLibrary} keeps {@code #Base} even when unused -- and
   * populated before any circuit is, since {@code fromLogisimFile} emits the libraries first.
   */
  private String baseLibraryName() {
    for (final var lib : file.getLibraries()) {
      if (lib instanceof BaseLibrary) return libs.get(lib);
    }
    return null;
  }

  Element fromComponent(Component comp) {
    final var source = comp.getFactory();
    if (compatMode && source instanceof Annotation) return fromAnnotationAsText(comp);

    final var lib = findLibrary(source);
    String libName;
    if (lib == null) {
      loader.showError(source.getName() + " component not found");
      return null;
    } else if (lib == file) {
      libName = null;
    } else {
      libName = libs.get(lib);
      if (libName == null) {
        loader.showError("unknown library within file");
        return null;
      }
    }
    if ("Text".equals(source.getName())) {
      /* check if the text element is empty, in this case we do not save */
      final var value = comp.getAttributeSet().getValue(Text.ATTR_TEXT);
      if (value.isEmpty()) return null;
    }

    final var ret = doc.createElement("comp");
    if (libName != null) ret.setAttribute("lib", libName);
    ret.setAttribute("name", source.getName());
    ret.setAttribute("loc", comp.getLocation().toString());
    addAttributeSetContent(ret, comp.getAttributeSet(), comp.getFactory(), false);
    return ret;
  }

  Element fromLibrary(Library lib) throws IOException, LoadFailedException {
    // Peler Edition compatibility mode: leave the Annotation library out entirely, and out of the
    // `libs` map too, so nothing downstream can emit a reference to it. Note this is the reason a
    // compatible file is clean even when it holds no annotations at all: the library entry comes
    // from the new-project template and `removeUnusedLibs` is off by default, so every file this
    // edition saved used to carry it, and upstream complained about every single one.
    if (compatMode && lib instanceof AnnotationLibrary) return null;

    final var ret = doc.createElement("lib");
    if (libs.containsKey(lib)) return null;
    final var name = Integer.toString(libs.size());
    var desc = loader.getDescriptor(lib);
    if (desc == null) {
      loader.showError("library location unknown: " + lib.getName());
      return null;
    }
    libs.put(lib, name);
    if (AppPreferences.REMOVE_UNUSED_LIBRARIES.getBoolean()) {
      // first we check if the library is used and if this is not the case we do not add it
      var isUsed = false;
      final var tools = lib.getTools();
      for (final var circuit : file.getCircuits()) {
        for (final var tool : circuit.getNonWires()) {
          isUsed |= lib.contains(tool.getFactory());
        }
      }
      for (final var tool : file.getOptions().getToolbarData().getContents()) {
        isUsed |= tools.contains(tool);
      }
      for (final var entry : file.getOptions().getMouseMappings().getMappings().entrySet()) {
        isUsed |= tools.contains(entry.getValue());
      }
      if (!isUsed && !"#Base".equals(desc)) {
        return null;
      }
    }
    if (isProjectExport) {
      if (lib instanceof LoadedLibrary) {
        final var origFile = LibraryManager.getLibraryFilePath(file.getLoader(), desc);
        final var isJarLibrary = LibraryManager.isJarLibrary(file.getLoader(), desc);
        if (origFile != null) {
          final var names = origFile.split(Pattern.quote(File.separator));
          final var filename = names[names.length - 1];
          final var newFile = LineBuffer.format("{{1}}{{2}}{{3}}", Loader.LOGISIM_LIBRARY_DIR, File.separator, filename);
          final var zipFile = file.getLoader().getZipFile();
          if (zipFile != null) {
            if (isJarLibrary) {
              writeJarToZip(zipFile, origFile, newFile);
            } else {
              writeLogisimFileToZip(zipFile, origFile, newFile);
            }
            desc = LibraryManager.getReplacementDescriptor(file.getLoader(), desc, isRecursiveCall 
                ? LineBuffer.format(".{{1}}{{2}}", File.separator, filename)
                : LineBuffer.format(".{{1}}{{2}}{{1}}{{3}}", File.separator, Loader.LOGISIM_LIBRARY_DIR, filename));
          }
        }
      }
    }
    ret.setAttribute("name", name);
    ret.setAttribute("desc", desc);
    for (Tool t : lib.getTools()) {
      final var attrs = t.getAttributeSet();
      if (attrs != null) {
        final var toAdd = doc.createElement("tool");
        toAdd.setAttribute("name", t.getName());
        addAttributeSetContent(toAdd, attrs, t, true);
        if (toAdd.getChildNodes().getLength() > 0) {
          ret.appendChild(toAdd);
        }
      }
    }
    return ret;
  }

  Element fromLogisimFile() throws IOException, LoadFailedException {
    final var ret = doc.createElement("project");
    doc.appendChild(ret);
    ret.appendChild(
        doc.createTextNode(
            "\nThis file is intended to be "
                + "loaded by "
                + BuildInfo.displayName
                + "("
                + BuildInfo.url
                + ").\n"));
    ret.setAttribute("version", "1.0");
    ret.setAttribute("source", BuildInfo.version.toString());

    for (final var lib : file.getLibraries()) {
      final var elt = fromLibrary(lib);
      if (elt != null) ret.appendChild(elt);
    }

    if (file.getMainCircuit() != null) {
      final var mainElt = doc.createElement("main");
      mainElt.setAttribute("name", file.getMainCircuit().getName());
      ret.appendChild(mainElt);
    }

    ret.appendChild(fromOptions());
    ret.appendChild(fromMouseMappings());
    ret.appendChild(fromToolbarData());

    for (final var circ : file.getCircuits()) {
      ret.appendChild(fromCircuit(circ));
    }
    for (final var vhdl : file.getVhdlContents()) {
      ret.appendChild(fromVhdl(vhdl));
    }
    return ret;
  }

  Element fromMouseMappings() {
    final var elt = doc.createElement("mappings");
    final var map = file.getOptions().getMouseMappings();
    for (final var entry : map.getMappings().entrySet()) {
      final var mods = entry.getKey();
      final var tool = entry.getValue();
      if (compatMode && PelerCompat.isPelerOnly(tool)) continue;
      final var toolElt = fromTool(tool);
      final var mapValue = InputEventUtil.toString(mods);
      toolElt.setAttribute("map", mapValue);
      elt.appendChild(toolElt);
    }
    return elt;
  }

  Element fromOptions() {
    final var elt = doc.createElement("options");
    addAttributeSetContent(elt, file.getOptions().getAttributeSet(), null, false);
    return elt;
  }

  Element fromTool(Tool tool) {
    final var lib = findLibrary(tool);
    String libName;
    if (lib == null) {
      loader.showError(String.format("tool `%s' not found", tool.getDisplayName()));
      return null;
    } else if (lib == file) {
      libName = null;
    } else {
      libName = libs.get(lib);
      if (libName == null) {
        loader.showError("unknown library within file");
        return null;
      }
    }

    final var elt = doc.createElement("tool");
    if (libName != null) elt.setAttribute("lib", libName);
    elt.setAttribute("name", tool.getName());
    addAttributeSetContent(elt, tool.getAttributeSet(), tool, true);
    return elt;
  }

  Element fromToolbarData() {
    final var elt = doc.createElement("toolbar");
    final var toolbar = file.getOptions().getToolbarData();
    for (final var tool : toolbar.getContents()) {
      if (tool == null) {
        elt.appendChild(doc.createElement("sep"));
      } else if (!(compatMode && PelerCompat.isPelerOnly(tool))) {
        elt.appendChild(fromTool(tool));
      }
    }
    return elt;
  }

  Element fromWire(Wire w) {
    final var ret = doc.createElement("wire");
    ret.setAttribute("from", w.getEnd0().toString());
    ret.setAttribute("to", w.getEnd1().toString());
    return ret;
  }

  boolean libraryContains(Library lib, Tool query) {
    for (final var tool : lib.getTools()) {
      if (tool.sharesSource(query)) return true;
    }
    return false;
  }

  private void writeJarToZip(ZipOutputStream zipFile, String inputFileName, String outputFileName) throws IOException {
    final var fileToRead = new File(inputFileName);
    final var fileReader = new FileInputStream(fileToRead);
    zipFile.putNextEntry(new ZipEntry(outputFileName));
    final var bytes = new byte[1024];
    var length = 0;
    while ((length = fileReader.read(bytes)) >= 0) {
      zipFile.write(bytes, 0, length);
    }
    fileReader.close();
  }
  
  private void writeLogisimFileToZip(ZipOutputStream zipFile, String inputFileName, String outputFileName) throws IOException, LoadFailedException {
    final var newLoader = new Loader(null);
    newLoader.setZipFile(zipFile);
    final var library = newLoader.openLogisimFile(new File(inputFileName).getCanonicalFile());
    library.write(zipFile, newLoader, outputFileName, true);
  }
}
