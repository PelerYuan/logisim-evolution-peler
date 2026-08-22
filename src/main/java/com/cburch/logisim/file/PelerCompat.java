/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.file;

import com.cburch.logisim.std.annotate.Annotation;
import com.cburch.logisim.std.ttlsymbol.TtlSymbolGate;
import com.cburch.logisim.tools.AbstractAnnotateTool;
import com.cburch.logisim.tools.AddTool;
import com.cburch.logisim.tools.QuickRotateTool;
import com.cburch.logisim.tools.TidyWiresTool;
import com.cburch.logisim.tools.Tool;
import java.io.File;

/**
 * Peler Edition. What this edition can hold that official Logisim-evolution cannot, gathered in one
 * place so the writer and the save dialogs agree on it.
 *
 * <p>This edition saves {@code .pcirc} by default and keeps everything; saving to {@code .circ}
 * still works but lowers the project to what upstream reads, because upstream regenerates the whole
 * file from its in-memory model -- whatever it could not model is gone the first time it saves.
 * See {@code XmlWriter}'s compatibility mode for the lowering itself.
 */
public final class PelerCompat {
  private PelerCompat() {}

  /** True for a destination that should be written in the official, lowered dialect. */
  public static boolean isCompatTarget(File dest) {
    return dest != null && dest.getName().endsWith(Loader.LOGISIM_EXTENSION);
  }

  /**
   * Drops either project extension from a file name. Plain {@code replace(LOGISIM_EXTENSION, "")}
   * no longer does: ".pcirc" does not contain ".circ" as a substring, so a {@code .pcirc} name came
   * through such a call untouched.
   */
  public static String stripProjectExtension(String fileName) {
    if (fileName.endsWith(Loader.PELER_EXTENSION)) {
      return fileName.substring(0, fileName.length() - Loader.PELER_EXTENSION.length());
    }
    if (fileName.endsWith(Loader.LOGISIM_EXTENSION)) {
      return fileName.substring(0, fileName.length() - Loader.LOGISIM_EXTENSION.length());
    }
    return fileName;
  }

  /**
   * Tools this edition added. Upstream cannot resolve them, and a {@code <mappings>} or {@code
   * <toolbar>} entry naming one makes it report "Tool not found in library" while loading, so they
   * are left out of a compatible file.
   *
   * <p>Not treated as content loss: these are interface preferences stored in the project file,
   * not part of the circuit, and upstream simply falls back to its own defaults.
   */
  public static boolean isPelerOnly(Tool tool) {
    return tool instanceof QuickRotateTool
        || tool instanceof TidyWiresTool
        || tool instanceof AbstractAnnotateTool
        || (tool instanceof AddTool add && add.getFactory() instanceof TtlSymbolGate);
  }

  /**
   * True if this project holds annotations -- the one thing a compatible save actually degrades,
   * and therefore the only thing worth interrupting the user about. Annotations survive as plain
   * {@code Text} components, so the words are still readable over there, but which component each
   * note was attached to is not.
   */
  public static boolean hasAnnotations(LogisimFile file) {
    for (final var circuit : file.getCircuits()) {
      for (final var comp : circuit.getNonWires()) {
        if (comp.getFactory() instanceof Annotation) return true;
      }
    }
    return false;
  }

  /**
   * True if this project uses any of the TTL logic symbols (Feature 12). They are dropped outright
   * from a compatible file, components and library both, so this is content loss of a harsher kind
   * than an annotation's and is worth interrupting the user about.
   *
   * <p>Not lowered to the DIP chip they delegate to, tempting as that looks. The two draw the same
   * ports in different places, so every wire reaching a lowered chip would land on the wrong port
   * or on none -- a circuit that opens over there and quietly computes something else. Losing the
   * chip leaves visibly dangling wires instead, which is a worse-looking and much safer failure.
   */
  public static boolean hasSymbolChips(LogisimFile file) {
    for (final var circuit : file.getCircuits()) {
      for (final var comp : circuit.getNonWires()) {
        if (comp.getFactory() instanceof TtlSymbolGate) return true;
      }
    }
    return false;
  }

  /** True if a compatible save would drop any of the user's actual work. */
  public static boolean isLossy(LogisimFile file) {
    return hasAnnotations(file) || hasSymbolChips(file);
  }
}
