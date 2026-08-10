/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.htmlexport;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentDrawContext;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.gui.generic.TikZWriter;
import com.cburch.logisim.proj.Project;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Element;

/**
 * Peler Edition, experimental. Writes a circuit out as one self-contained HTML page that still
 * simulates: inputs can be clicked, values propagate, nothing can be moved or edited.
 *
 * <p>The split is deliberate. Component bodies are drawn by Logisim's own paint code through
 * {@link TikZWriter}, so gates, plexers and everything else look exactly as they do in the editor
 * without a line of drawing code in JavaScript. Only what changes with the simulation -- wires and
 * their colours, and the three components that display or accept a value -- is drawn by the page.
 *
 * <p>Each component is rendered through a writer of its own. Grouping a shared writer's output by
 * index would not survive {@code TikZInfo.optimize()}, which merges and reorders as it writes.
 *
 * <p>Phase 1 covers combinational circuits. {@link HtmlExportSupport} decides what is in scope and
 * the caller is expected to have asked it first.
 */
public final class HtmlExporter {

  /** Drawn by the page, not by Logisim: they change with the simulation or accept clicks. */
  private static final Set<String> RUNTIME_DRAWN = Set.of("Pin", "LED", "Probe");

  private static final int BORDER = 10;

  private final Project project;
  private final Circuit circuit;

  public HtmlExporter(Project project, Circuit circuit) {
    this.project = project;
    this.circuit = circuit;
  }

  public void writeTo(File target) throws IOException {
    final var model = HtmlCircuitModel.of(circuit);
    final var probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
    final var probeGraphics = probe.getGraphics();
    final var bounds = circuit.getBounds(probeGraphics).expand(BORDER);
    probeGraphics.dispose();

    final var bodies = renderComponents(model, bounds.getX(), bounds.getY());
    final var json = new HtmlJson()
        .put("name", circuit.getName())
        .put("width", bounds.getWidth())
        .put("height", bounds.getHeight())
        .put("originX", bounds.getX())
        .put("originY", bounds.getY())
        .put("nets", netsJson(model))
        .put("components", componentsJson(model, bodies))
        .put("colors", colorsJson())
        .toString();

    final var page = template()
        .replace("/*__CIRCUIT__*/null", json)
        .replace("__TITLE__", HtmlJson.escapeText(circuit.getName()));
    Files.writeString(target.toPath(), page, StandardCharsets.UTF_8);
  }

  private List<Object> netsJson(HtmlCircuitModel model) {
    final var out = new ArrayList<Object>();
    for (final var net : model.getNets()) {
      out.add(new HtmlJson()
          .put("id", net.id)
          .put("width", net.width)
          .put("segments", net.segments)
          .put("pins", net.pins));
    }
    return out;
  }

  private List<Object> componentsJson(HtmlCircuitModel model, List<String> bodies) {
    final var out = new ArrayList<Object>();
    for (final var comp : model.getComponents()) {
      final var entry = new HtmlJson()
          .put("id", comp.id)
          .put("kind", comp.kind)
          .put("label", comp.label)
          .put("ports", comp.ports)
          .put("portDirs", comp.portDirs)
          .put("portWidths", comp.portWidths)
          .put("portLocs", comp.portLocs)
          .put("bounds", boundsOf(model.getSourceComponents().get(comp.id)))
          .put("svg", bodies.get(comp.id));
      for (final Map.Entry<String, Object> attr : comp.attrs.entrySet()) {
        entry.putAttribute(attr.getKey(), attr.getValue());
      }
      out.add(entry);
    }
    return out;
  }

  /**
   * The editor's own wire colours travel with the page. They are preferences, not constants, so an
   * export made by someone who has retuned them would otherwise come out looking like a different
   * program's.
   */
  private static HtmlJson colorsJson() {
    return new HtmlJson()
        .put("false", css(Value.falseColor))
        .put("true", css(Value.trueColor))
        .put("unknown", css(Value.unknownColor))
        .put("error", css(Value.errorColor))
        .put("nil", css(Value.nilColor))
        .put("multi", css(Value.multiColor));
  }

  private static String css(java.awt.Color color) {
    return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
  }

  private static List<Integer> boundsOf(Component component) {
    final var bounds = component.getBounds();
    return List.of(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
  }

  /**
   * One SVG fragment per component, already translated into page coordinates. Components the page
   * draws itself get an empty fragment rather than being skipped, so indexes stay aligned with the
   * model.
   */
  private List<String> renderComponents(HtmlCircuitModel model, int originX, int originY) {
    final var state = project.getCircuitState(circuit);
    final var out = new ArrayList<String>();
    for (final var component : model.getSourceComponents()) {
      if (RUNTIME_DRAWN.contains(component.getFactory().getName())) {
        out.add("");
        continue;
      }
      out.add(renderOne(component, state, originX, originY));
    }
    return out;
  }

  private String renderOne(Component component, CircuitState state, int originX, int originY) {
    final var writer = new TikZWriter();
    final Graphics g = writer.create();
    g.translate(-originX, -originY);
    final var context = new ComponentDrawContext(null, circuit, state, writer, g, true);
    try {
      component.draw(context);
    } catch (RuntimeException e) {
      // A component that cannot paint outside a real canvas should cost its own picture, not the
      // whole export. The netlist entry survives, so it still simulates.
      return "";
    } finally {
      g.dispose();
    }
    try {
      final var document = writer.buildSvgDocument(1, 1);
      final var root = document.getDocumentElement();
      final var body = new StringBuilder();
      final var children = root.getChildNodes();
      final var transformer = TransformerFactory.newInstance().newTransformer();
      transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
      for (var i = 0; i < children.getLength(); i++) {
        if (!(children.item(i) instanceof Element element)) continue;
        final var buffer = new StringWriter();
        transformer.transform(new DOMSource(element), new StreamResult(buffer));
        body.append(buffer);
      }
      return body.toString();
    } catch (Exception e) {
      return "";
    }
  }

  private static String template() throws IOException {
    try (final var in = HtmlExporter.class.getResourceAsStream(
        "/resources/logisim/html/export-template.html")) {
      if (in == null) throw new IOException("export template missing from the jar");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** Kinds this phase can simulate, kept next to the exporter that depends on it. */
  public static Set<String> supportedKinds() {
    return new LinkedHashSet<>(List.of(
        "Pin", "LED", "Probe", "Constant", "Tunnel",
        "AND Gate", "OR Gate", "NAND Gate", "NOR Gate", "XOR Gate", "XNOR Gate",
        "NOT Gate", "Buffer"));
  }
}
