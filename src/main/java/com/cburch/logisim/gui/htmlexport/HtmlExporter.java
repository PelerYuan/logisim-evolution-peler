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
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.gui.generic.TikZWriter;
import com.cburch.logisim.instance.InstanceData;
import com.cburch.logisim.instance.InstanceFactory;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.proj.Project;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Peler Edition, experimental. Writes a circuit out as one self-contained HTML page that still
 * simulates: inputs can be clicked, values propagate, nothing can be moved or edited.
 *
 * <p>The split is deliberate. Component bodies are drawn by Logisim's own paint code through
 * {@link TikZWriter}, so gates, displays and everything else look exactly as they do in the editor
 * without a line of drawing code in JavaScript. Components whose picture changes with their value
 * are rendered once per state and shipped as differences -- see {@link HtmlAppearance} -- so they
 * keep that fidelity while moving. Only wires, port dots and the two components that show a bare
 * number are drawn by the page.
 *
 * <p>Each component is rendered through a writer of its own. Grouping a shared writer's output by
 * index would not survive {@code TikZInfo.optimize()}, which merges and reorders as it writes.
 *
 * <p>Rendering runs against a scratch circuit state rather than the editor's live one, so an export
 * is the same file whatever the editor happened to be showing when it was made.
 */
public final class HtmlExporter {

  /** Drawn by the page, not by Logisim: they show a value as text and accept clicks. */
  private static final Set<String> RUNTIME_DRAWN = Set.of("Pin", "Probe");

  private static final int BORDER = 10;

  private final Project project;
  private final Circuit circuit;
  private CircuitState scratch;

  public HtmlExporter(Project project, Circuit circuit) {
    this.project = project;
    this.circuit = circuit;
  }

  /**
   * One component ready to travel: its picture, how that picture changes with its value, and the
   * renderer that produced both. The renderer is kept so a test can ask Logisim for the same state
   * again and check the encoding against it.
   */
  record Prepared(
      Component component,
      int[][] stateBits,
      HtmlAppearance.Renderer renderer,
      String svg,
      HtmlAppearance appearance) {}

  public void writeTo(File target) throws IOException {
    final var model = HtmlCircuitModel.of(circuit);
    final var bounds = setUp();
    final var prepared = prepare(model, bounds.getX(), bounds.getY());

    final var json = new HtmlJson()
        .put("name", circuit.getName())
        .put("width", bounds.getWidth())
        .put("height", bounds.getHeight())
        .put("originX", bounds.getX())
        .put("originY", bounds.getY())
        .put("threadCount", model.getThreadCount())
        .put("pinDot", pinDotRadius())
        .put("nets", netsJson(model))
        .put("components", componentsJson(model, prepared))
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
          .put("threads", net.threads)
          .put("segments", net.segments)
          .put("pins", net.pins));
    }
    return out;
  }

  /** Visible for testing: everything the page would be built from, without writing a file. */
  List<Prepared> prepare() {
    final var bounds = setUp();
    return prepare(HtmlCircuitModel.of(circuit), bounds.getX(), bounds.getY());
  }

  private Bounds setUp() {
    final var probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
    final var probeGraphics = probe.getGraphics();
    final var bounds = circuit.getBounds(probeGraphics).expand(BORDER);
    probeGraphics.dispose();
    scratch = CircuitState.createRootState(project, circuit);
    return bounds;
  }

  private List<Object> componentsJson(HtmlCircuitModel model, List<Prepared> prepared) {
    final var out = new ArrayList<Object>();
    for (final var comp : model.getComponents()) {
      final var source = model.getSourceComponents().get(comp.id);
      final var entry = new HtmlJson()
          .put("id", comp.id)
          .put("kind", comp.kind)
          .put("label", comp.label)
          .put("ports", comp.ports)
          .put("portDirs", comp.portDirs)
          .put("portWidths", comp.portWidths)
          .put("portLocs", comp.portLocs)
          .put("loc", comp.loc)
          .put("bounds", boundsOf(source))
          .put("svg", prepared.get(comp.id).svg());
      final var appearance = prepared.get(comp.id).appearance();
      if (appearance != null) entry.put("dyn", appearance.toJson());
      final var pokeBits = HtmlPoke.bits(source);
      if (pokeBits > 0) {
        entry.put("poke", pokeBits);
        final var hit = HtmlPoke.hitTestJson(source);
        if (hit != null) entry.put("hit", hit);
      }
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

  /** Matches {@code ComponentDrawContext.drawPinMarker}, whose dot size is a preference too. */
  private static double pinDotRadius() {
    return switch (AppPreferences.PinAppearance.get()) {
      case AppPreferences.PIN_APPEAR_DOT_MEDIUM -> 3.0;
      case AppPreferences.PIN_APPEAR_DOT_BIG -> 4.0;
      case AppPreferences.PIN_APPEAR_DOT_BIGGER -> 5.0;
      default -> 2.0;
    };
  }

  private static String css(java.awt.Color color) {
    return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
  }

  private static List<Integer> boundsOf(Component component) {
    final var bounds = component.getBounds();
    return List.of(bounds.getX(), bounds.getY(), bounds.getWidth(), bounds.getHeight());
  }

  /**
   * One SVG fragment per component, already translated into page coordinates, plus the encoding of
   * how that picture changes with the component's value where it does. Components the page draws
   * itself get an empty fragment rather than being skipped, so indexes stay aligned with the model.
   */
  private List<Prepared> prepare(HtmlCircuitModel model, int originX, int originY) {
    final var out = new ArrayList<Prepared>();
    for (final var component : model.getSourceComponents()) {
      final var stateBits = stateBitsOf(component);
      final HtmlAppearance.Renderer renderer =
          state -> renderState(component, stateBits, state, originX, originY, null);
      if (RUNTIME_DRAWN.contains(component.getFactory().getName())) {
        out.add(new Prepared(component, stateBits, renderer, "", null));
        continue;
      }
      final var base = renderer.render(0);
      final var encodable = base != null && remembersNothing(component, stateBits, originX, originY);
      out.add(new Prepared(
          component,
          stateBits,
          renderer,
          base == null ? "" : base.svg(),
          encodable ? HtmlAppearance.of(stateBits, renderer) : null));
    }
    return out;
  }

  /**
   * Which bits make up the component's state, as {@code {port, bit}} pairs, or an empty array when
   * the state is the component's own rather than something arriving on a wire.
   *
   * <p>A pokeable component reports {@code {-1, bit}} pairs: there is no port to read them from,
   * and the page holds them itself.
   */
  private static int[][] stateBitsOf(Component component) {
    final var pokeBits = HtmlPoke.bits(component);
    if (pokeBits > 0) {
      final var bits = new int[pokeBits][];
      for (var i = 0; i < pokeBits; i++) bits[i] = new int[] {-1, i};
      return bits;
    }
    final var bits = new ArrayList<int[]>();
    final var ends = component.getEnds();
    for (var port = 0; port < ends.size(); port++) {
      final var end = ends.get(port);
      if (!end.isInput() || end.isOutput()) continue;
      for (var bit = 0; bit < end.getWidth().getWidth(); bit++) bits.add(new int[] {port, bit});
    }
    return bits.toArray(new int[0][]);
  }

  /**
   * Whether the component's picture is a function of its inputs alone.
   *
   * <p>An exported appearance is looked up by the value on the component's ports, so it can only
   * be right for a component that has nothing else to go on. A flip-flop's indicator shows what it
   * latched, not what is on D, and encoding it against the inputs would make the page show a
   * confidently wrong picture -- worse than the frozen one it gets instead.
   *
   * <p>The test is direct rather than a list of kinds: render a state cold, then render it again
   * after another state has gone by, carrying the component's own data across as a running
   * circuit would. Anything that remembers comes out different.
   *
   * <p>Components whose state is the page's own -- buttons and switches -- are exempt. Their value
   * does not come from a port, so there is nothing for history to contradict.
   */
  private boolean remembersNothing(
      Component component, int[][] stateBits, int originX, int originY) {
    if (stateBits.length == 0 || stateBits[0][0] < 0) return true;
    final var all = stateBits.length >= 63 ? -1L : (1L << stateBits.length) - 1;
    final var carried = new InstanceData[1];
    if (renderState(component, stateBits, all, originX, originY, carried) == null) return false;
    final var warm = renderState(component, stateBits, 0, originX, originY, carried);
    final var cold = renderState(component, stateBits, 0, originX, originY, null);
    return warm != null && cold != null && warm.svg().equals(cold.svg());
  }

  /**
   * The component's picture when its state bits hold the given value.
   *
   * <p>The value is put in place by the component's own {@code propagate}, run offline against
   * chosen port values, so nothing here has to know what a lit segment means.
   */
  private HtmlAppearance.Fragment renderState(
      Component component, int[][] stateBits, long state, int originX, int originY,
      InstanceData[] carried) {
    final var ends = component.getEnds();
    // Value.create indexes its array least significant bit first, so bit n sits at n. Filling it
    // the other way round is invisible on a one bit port and shows a hex display the reverse of
    // its input on a wide one.
    final var bits = new Value[ends.size()][];
    for (var port = 0; port < bits.length; port++) {
      bits[port] = new Value[ends.get(port).getWidth().getWidth()];
      Arrays.fill(bits[port], Value.FALSE);
    }
    for (var i = 0; i < stateBits.length; i++) {
      final var port = stateBits[i][0];
      if (port < 0 || ((state >> i) & 1) == 0) continue;
      bits[port][stateBits[i][1]] = Value.TRUE;
    }
    final var ports = new Value[bits.length];
    for (var port = 0; port < bits.length; port++) ports[port] = Value.create(bits[port]);

    final var offline = new HtmlOfflineState(component, ports);
    if (carried != null && carried[0] != null) offline.setData(carried[0]);
    try {
      if (HtmlPoke.bits(component) > 0 && !HtmlPoke.apply(component, offline, state)) return null;
      if (component.getFactory() instanceof InstanceFactory factory) factory.propagate(offline);
      if (carried != null) carried[0] = offline.data();
      scratch.setData(component, offline.data());
    } catch (RuntimeException e) {
      // A component that will not run without a live simulation keeps its default picture. The
      // netlist entry survives either way, so it still simulates.
      return null;
    }
    return render(component, originX, originY);
  }

  private HtmlAppearance.Fragment render(Component component, int originX, int originY) {
    final var writer = new TikZWriter();
    final Graphics g = writer.create();
    g.translate(-originX, -originY);
    // The last argument is printView, not showState. Passing true here suppressed both colour and
    // state for every component in the page, which is why an LED had to be drawn by hand before.
    final var context = new ComponentDrawContext(null, circuit, scratch, writer, g, false);
    try {
      component.draw(context);
    } catch (RuntimeException e) {
      // A component that cannot paint outside a real canvas should cost its own picture, not the
      // whole export.
      return null;
    } finally {
      g.dispose();
    }
    try {
      final var document = writer.buildSvgDocument(1, 1);
      final var root = document.getDocumentElement();
      final var body = new StringBuilder();
      final var flat = new ArrayList<Element>();
      final var children = root.getChildNodes();
      final var transformer = TransformerFactory.newInstance().newTransformer();
      transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
      for (var i = 0; i < children.getLength(); i++) {
        if (!(children.item(i) instanceof Element element)) continue;
        flat.add(element);
        flatten(element, flat);
        final var buffer = new StringWriter();
        transformer.transform(new DOMSource(element), new StreamResult(buffer));
        body.append(buffer);
      }
      return new HtmlAppearance.Fragment(flat, body.toString());
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Document order, depth first, which is the order {@code querySelectorAll("*")} returns in the
   * page. The two have to agree: a patch names its element by position in this list.
   */
  private static void flatten(Node node, List<Element> out) {
    final var children = node.getChildNodes();
    for (var i = 0; i < children.getLength(); i++) {
      if (children.item(i) instanceof Element element) {
        out.add(element);
        flatten(element, out);
      }
    }
  }

  private static String template() throws IOException {
    try (final var in = HtmlExporter.class.getResourceAsStream(
        "/resources/logisim/html/export-template.html")) {
      if (in == null) throw new IOException("export template missing from the jar");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** Kinds the page can simulate, kept next to the exporter that depends on it. */
  public static Set<String> supportedKinds() {
    return new LinkedHashSet<>(List.of(
        "Pin", "LED", "Probe", "Constant", "Tunnel",
        "AND Gate", "OR Gate", "NAND Gate", "NOR Gate", "XOR Gate", "XNOR Gate",
        "NOT Gate", "Buffer",
        "Splitter", "Bit Extender", "Power", "Ground",
        "Clock", "D Flip-Flop", "Register",
        "7-Segment Display", "Hex Digit Display", "RGBLED", "Button", "DipSwitch"));
  }
}
