/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.gates.PlaTable;
import com.cburch.logisim.util.StringUtil;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What the GUI's PLA program editor does: read and replace a {@code gates/pla} component's table.
 *
 * <p>The table is text, one row per line, the form {@code PlaTable.toStandardString} writes: input
 * bits over {@code 0 1 x}, a space, output bits over {@code 0 1}, most significant bit first, with
 * an optional {@code # comment}. Setting a table resizes the component's input and output widths to
 * match, as the editor does. The GUI's own parser answers a bad row with a modal dialog, which a
 * script cannot dismiss, so the text is validated here first.
 */
public final class PlaTables {
  private static final Pattern ROW = Pattern.compile("^([01x]+)\\s+([01]+)\\s*(#.*)?$");

  private final Space space;
  private final Project proj;

  private PlaTables(Space space) {
    this.space = space;
    this.proj = space.project();
  }

  public static PlaTables of(Space space) {
    return new PlaTables(space);
  }

  public String getTable(Comp comp) {
    return table(comp).toStandardString();
  }

  public void setTable(Comp comp, String text) {
    final var raw = comp.rawComponent();
    final var attr = attribute(comp);
    final var parsed = PlaTable.parse(validated(text));
    if (space.circuit().contains(raw)) {
      final var mutation = new CircuitMutation(space.circuit());
      mutation.set(raw, attr, parsed);
      proj.doAction(mutation.toAction(StringUtil.constantGetter("set PLA table")));
    } else {
      raw.getAttributeSet().setValue(attr, parsed);
    }
    comp.invalidatePorts();
  }

  private PlaTable table(Comp comp) {
    return comp.rawComponent().getAttributeSet().getValue(attribute(comp));
  }

  @SuppressWarnings("unchecked")
  private static Attribute<PlaTable> attribute(Comp comp) {
    final var raw = comp.rawComponent();
    if (!"PLA".equals(raw.getFactory().getName())) {
      throw new NotAMemoryException(
          comp.kind().key() + " is not a PLA", comp.kind().key(), "use a gates/pla component");
    }
    return (Attribute<PlaTable>) raw.getAttributeSet().getAttribute("table");
  }

  private static String validated(String text) {
    var inWidth = -1;
    var outWidth = -1;
    var lineNo = 0;
    final var kept = new StringBuilder();
    for (final var line : text.split("\n")) {
      lineNo++;
      final var trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
      final var m = ROW.matcher(trimmed);
      if (!m.matches()) {
        throw new InvalidPlaTableException(
            "line " + lineNo + " is not a PLA row: \"" + trimmed + "\"",
            Map.of("line", lineNo, "text", trimmed),
            "write inputs over 0/1/x, a space, then outputs over 0/1, e.g. \"1x0 01\"");
      }
      final var in = m.group(1).length();
      final var out = m.group(2).length();
      if (inWidth < 0) {
        inWidth = in;
        outWidth = out;
      } else if (in != inWidth || out != outWidth) {
        throw new InvalidPlaTableException(
            "line " + lineNo + " is " + in + "/" + out + " bits wide, earlier rows are " + inWidth + "/" + outWidth,
            Map.of("line", lineNo, "inputs", in, "outputs", out, "expectedInputs", inWidth, "expectedOutputs", outWidth),
            "every row needs the same number of input bits and output bits");
      }
      kept.append(trimmed).append('\n');
    }
    if (inWidth < 0) {
      throw new InvalidPlaTableException(
          "the table has no rows", Map.of(), "give at least one row, e.g. \"00 1\"");
    }
    return kept.toString();
  }
}
