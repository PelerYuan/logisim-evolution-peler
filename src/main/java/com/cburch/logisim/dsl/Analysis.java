/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import com.cburch.logisim.analyze.file.AnalyzerTexWriter;
import com.cburch.logisim.analyze.file.TruthtableCsvFile;
import com.cburch.logisim.analyze.file.TruthtableTextFile;
import com.cburch.logisim.analyze.model.AnalyzerModel;
import com.cburch.logisim.analyze.model.Expression;
import com.cburch.logisim.analyze.model.Var;
import com.cburch.logisim.circuit.Analyze;
import com.cburch.logisim.circuit.AnalyzeException;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The GUI's Combinational Analysis window, run backwards from a circuit: read what the circuit
 * computes as a truth table, as expressions, or minimized, and export it as text, CSV or LaTeX.
 *
 * <p>Inputs and outputs are the circuit's pins, named by their labels (unlabeled pins get the
 * analyzer's default names); a multi-bit pin contributes one variable per bit, {@code name[i]}. The
 * circuit must be committed first, since analysis reads the circuit itself, not staged components.
 * The analyzer window is never opened.
 */
public final class Analysis {
  /** Above this many input bits a script is told to narrow the circuit rather than wait for
   * 2^n propagations; the GUI allows up to {@link AnalyzerModel#MAX_INPUTS}. */
  static final int MAX_SCRIPT_INPUTS = 16;

  /** One row of a truth table: input bits then output bits, as "0"/"1"/"x"/"E" characters. */
  public record Row(String inputs, String outputs) {}

  public record Table(List<String> inputs, List<String> outputs, List<Row> rows) {}

  private final Space space;
  private final Project proj;

  private Analysis(Space space) {
    this.space = space;
    this.proj = space.project();
  }

  public static Analysis of(Space space) {
    return new Analysis(space);
  }

  public Table truthTable() {
    final var model = tableModel();
    final var t = model.getTruthTable();
    final var inputs = names(model.getInputs().vars);
    final var outputs = names(model.getOutputs().vars);
    final var inBits = model.getInputs().bits;
    final var outBits = model.getOutputs().bits;
    final var rows = new ArrayList<Row>();
    final var rowCount = 1 << inBits.size();
    for (var row = 0; row < rowCount; row++) {
      final var in = new StringBuilder();
      for (var c = 0; c < inBits.size(); c++) in.append(t.getInputEntry(row, c).getDescription());
      final var out = new StringBuilder();
      for (var c = 0; c < outBits.size(); c++) out.append(t.getOutputEntry(row, c).getDescription());
      rows.add(new Row(in.toString(), out.toString()));
    }
    return new Table(bitNames(inBits), bitNames(outBits), rows);
  }

  /** Bit-level names, in the order the rows' characters appear. */
  private static List<String> bitNames(List<String> bits) {
    return List.copyOf(bits);
  }

  private static List<String> names(List<Var> vars) {
    final var out = new ArrayList<String>();
    for (final var v : vars) out.add(v.name);
    return out;
  }

  /** Each output bit's expression, read structurally off the circuit's gates. */
  public Map<String, String> expressions(String notation) {
    final var model = expressionModel();
    return render(model, notation, false);
  }

  /** Each output bit's minimal expression, {@code format} "sop" (sum of products) or "pos". */
  public Map<String, String> minimized(String format, String notation) {
    final var fmt = switch (String.valueOf(format).toLowerCase(Locale.ROOT)) {
      case "sop" -> AnalyzerModel.FORMAT_SUM_OF_PRODUCTS;
      case "pos" -> AnalyzerModel.FORMAT_PRODUCT_OF_SUMS;
      default ->
          throw new AnalysisFailedException(
              "unknown minimization format \"" + format + "\"",
              Map.of("format", String.valueOf(format)),
              "use \"sop\" or \"pos\"");
    };
    final var model = tableModel();
    for (final var out : model.getOutputs().bits) model.getOutputExpressions().setMinimizedFormat(out, fmt);
    return render(model, notation, true);
  }

  /** Writes the truth table; the extension picks the format, {@code .txt} or {@code .csv}. */
  public void exportTable(String path) {
    final var model = tableModel();
    final var lower = path.toLowerCase(Locale.ROOT);
    try {
      if (lower.endsWith(".txt")) TruthtableTextFile.doSave(new File(path), model);
      else if (lower.endsWith(".csv")) TruthtableCsvFile.doSave(new File(path), model);
      else {
        throw new InvalidExportFormatException(path);
      }
    } catch (IOException e) {
      throw new ExportFailedException(path, e.getMessage());
    }
  }

  /** Writes the analysis as a LaTeX document, as the window's "Export as LaTeX" button does. */
  public void exportLatex(String path) {
    final var model = tableModel();
    try {
      AnalyzerTexWriter.doSave(new File(path), model);
    } catch (IOException e) {
      throw new ExportFailedException(path, e.getMessage());
    }
  }

  // ---- internals -----------------------------------------------------------------------------

  private Map<String, String> render(AnalyzerModel model, String notation, boolean minimal) {
    final var style = notationOf(notation);
    final var out = new LinkedHashMap<String, String>();
    for (final var name : model.getOutputs().bits) {
      final Expression expr =
          minimal
              ? model.getOutputExpressions().getMinimalExpression(name)
              : model.getOutputExpressions().getExpression(name);
      out.put(name, expr == null ? "" : expr.toString(style));
    }
    return out;
  }

  private static Expression.Notation notationOf(String notation) {
    final var key = notation == null ? "progbits" : notation.toLowerCase(Locale.ROOT);
    for (final var n : Expression.Notation.values()) {
      if (n.name().toLowerCase(Locale.ROOT).equals(key)) return n;
    }
    throw new AnalysisFailedException(
        "unknown notation \"" + notation + "\"",
        Map.of("notation", String.valueOf(notation)),
        "use mathematical, logic, altlogic, progbools, progbits or latex");
  }

  private AnalyzerModel expressionModel() {
    final var ctx = context();
    try {
      Analyze.computeExpression(ctx.model, space.circuit(), ctx.pinNames);
    } catch (AnalyzeException e) {
      throw new AnalysisFailedException(
          e.getMessage(),
          Map.of("circuit", space.circuit().getName()),
          "use truthTable() or minimized(), which simulate the circuit instead of reading its gates");
    }
    return ctx.model;
  }

  private AnalyzerModel tableModel() {
    final var ctx = context();
    Analyze.computeTable(ctx.model, proj, space.circuit(), ctx.pinNames);
    return ctx.model;
  }

  private record Context(AnalyzerModel model, java.util.SortedMap<com.cburch.logisim.instance.Instance, String> pinNames) {}

  private Context context() {
    if (space.isDirty()) throw new UncommittedChangesException(space.pendingCount());
    final var circuit = space.circuit();
    final var pinNames = Analyze.getPinLabels(circuit);
    final var inputVars = new ArrayList<Var>();
    final var outputVars = new ArrayList<Var>();
    var inBits = 0;
    var outBits = 0;
    for (final var entry : pinNames.entrySet()) {
      final var pin = entry.getKey();
      final var width = pin.getAttributeValue(StdAttr.WIDTH).getWidth();
      final var v = new Var(entry.getValue(), width);
      if (Pin.FACTORY.isInputPin(pin)) {
        inputVars.add(v);
        inBits += width;
      } else {
        outputVars.add(v);
        outBits += width;
      }
    }
    if (inputVars.isEmpty() || outputVars.isEmpty()) {
      throw new AnalysisFailedException(
          "the circuit needs at least one input pin and one output pin to analyze",
          Map.of("inputs", inputVars.size(), "outputs", outputVars.size()),
          "add labeled wiring/pin components and commit");
    }
    if (inBits > MAX_SCRIPT_INPUTS) {
      throw new AnalysisFailedException(
          "the circuit has " + inBits + " input bits; a script analyzes at most " + MAX_SCRIPT_INPUTS,
          Map.of("inputBits", inBits, "limit", MAX_SCRIPT_INPUTS),
          "analyze a smaller circuit (2^n circuit evaluations are needed)");
    }
    if (outBits > AnalyzerModel.MAX_OUTPUTS) {
      throw new AnalysisFailedException(
          "the circuit has " + outBits + " output bits; the analyzer handles at most " + AnalyzerModel.MAX_OUTPUTS,
          Map.of("outputBits", outBits, "limit", AnalyzerModel.MAX_OUTPUTS),
          "analyze a smaller circuit");
    }
    final var model = new AnalyzerModel();
    model.setCurrentCircuit(proj, circuit);
    model.setVariables(inputVars, outputVars);
    return new Context(model, pinNames);
  }
}
