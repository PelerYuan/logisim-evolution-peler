/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import com.cburch.logisim.gui.hex.HexFile;
import com.cburch.logisim.proj.Action;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.memory.DualRam;
import com.cburch.logisim.std.memory.MemContents;
import com.cburch.logisim.std.memory.Ram;
import com.cburch.logisim.std.memory.Rom;
import com.cburch.logisim.util.StringUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What the GUI's hex editor, and the RAM/ROM popup's Clear / Load / Save, do to a memory.
 *
 * <p>A ROM's contents are an attribute of the component and are saved with the circuit; a script's
 * edit to a ROM in the circuit is one undo entry. A RAM's contents are simulation state, reached
 * through the project's circuit state as the hex editor reaches them, so they are not saved and not
 * undoable, and a RAM that is not in the circuit yet has none to edit.
 *
 * <p>Edits are made in place on the existing {@link MemContents}, never by replacing the attribute
 * value: the component's own listeners and its simulation state hold the original object, and a
 * replacement would leave them looking at stale data.
 *
 * <p>Images are text in Logisim's native "v2.0 raw" format (the format {@link #dump} writes and
 * {@link #load} reads, a hex word per token with {@code N*word} run-length repeats). The GUI's other
 * formats are chosen through a dialog, which a script cannot answer.
 */
public final class Memory {
  /** Geometry of a memory component. {@code live} is true for RAM, whose contents are not saved. */
  public record Info(String kind, int addressBits, int dataBits, long words, boolean live) {}

  private static final int MAX_RANGE = 1 << 20;

  private final Space space;
  private final Project proj;

  private Memory(Space space) {
    this.space = space;
    this.proj = space.project();
  }

  public static Memory of(Space space) {
    return new Memory(space);
  }

  public Info info(Comp comp) {
    final var target = target(comp);
    return new Info(
        target.kind,
        target.contents.getLogLength(),
        target.contents.getWidth(),
        target.contents.getLastOffset() + 1,
        target.live);
  }

  public long read(Comp comp, long address) {
    final var contents = target(comp).contents;
    checkAddress(contents, address, 1);
    return contents.get(address);
  }

  public long[] readRange(Comp comp, long start, int count) {
    final var contents = target(comp).contents;
    checkRange(contents, start, count);
    final var out = new long[count];
    for (var i = 0; i < count; i++) out[i] = contents.get(start + i);
    return out;
  }

  public void write(Comp comp, long address, long value) {
    writeRange(comp, address, new long[] {value});
  }

  public void writeRange(Comp comp, long start, long[] values) {
    final var target = target(comp);
    checkRange(target.contents, start, values.length);
    for (final var v : values) checkValue(target.contents, v);
    edit(comp, target, c -> {
      for (var i = 0; i < values.length; i++) c.set(start + i, values[i]);
    }, "write memory");
  }

  public void fill(Comp comp, long start, int count, long value) {
    final var target = target(comp);
    checkRange(target.contents, start, count);
    checkValue(target.contents, value);
    edit(comp, target, c -> c.fill(start, count, value), "fill memory");
  }

  public void clear(Comp comp) {
    final var target = target(comp);
    edit(comp, target, MemContents::clear, "clear memory");
  }

  /** The whole memory as a "v2.0 raw" image, without the file header. */
  public String dump(Comp comp) {
    return HexFile.saveToString(target(comp).contents);
  }

  /** Replaces the whole memory with a "v2.0 raw" image (the file header line is optional). */
  public void load(Comp comp, String image) {
    final var target = target(comp);
    final var loaded = parse(target.contents, image);
    edit(comp, target, c -> {
      c.clear();
      c.copyFrom(0, loaded, 0, (int) (loaded.getLastOffset() + 1));
    }, "load memory");
  }

  public void loadFile(Comp comp, String path) {
    final String text;
    try {
      text = Files.readString(Path.of(path), StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException e) {
      throw new InvalidMemoryAccessException(
          "cannot read \"" + path + "\": " + e.getMessage(),
          Map.of("path", path),
          "check the path; only \"v2.0 raw\" images are supported");
    }
    load(comp, text);
  }

  public void saveFile(Comp comp, String path) {
    final var image = "v2.0 raw\n" + dump(comp);
    try {
      Files.writeString(Path.of(path), image, StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException e) {
      throw new ExportFailedException(path, e.getMessage());
    }
  }

  // ---- internals -----------------------------------------------------------------------------

  private record Target(String kind, MemContents contents, boolean live, boolean inCircuit) {}

  private Target target(Comp comp) {
    final var raw = comp.rawComponent();
    final var factory = raw.getFactory();
    final var circuit = space.circuit();
    final var inCircuit = circuit.contains(raw);
    if (factory instanceof Rom) {
      return new Target("rom", raw.getAttributeSet().getValue(Rom.CONTENTS_ATTR), false, inCircuit);
    }
    if (factory instanceof Ram || factory instanceof DualRam) {
      if (!inCircuit) {
        throw new NotAMemoryException(
            "this RAM is not in the circuit yet, so it has no contents to edit",
            comp.kind().key(),
            "space:commit() first: a RAM's contents are simulation state");
      }
      final var state = proj.getCircuitState(circuit).getInstanceState(raw);
      final var contents =
          factory instanceof Ram ram ? ram.getContents(state) : ((DualRam) factory).getContents(state);
      return new Target(factory instanceof Ram ? "ram" : "dualram", contents, true, true);
    }
    throw new NotAMemoryException(
        comp.kind().key() + " is not a memory component",
        comp.kind().key(),
        "memory contents apply to memory/rom, memory/ram and the dual-port RAM");
  }

  private void edit(Comp comp, Target target, Consumer<MemContents> change, String name) {
    if (target.live || !target.inCircuit) {
      change.accept(target.contents);
      return;
    }
    final var before = target.contents.clone();
    final var after = target.contents.clone();
    change.accept(after);
    proj.doAction(new ContentsAction(target.contents, before, after, name));
  }

  private static MemContents parse(MemContents like, String image) {
    var body = image;
    final var newline = image.indexOf('\n');
    final var first = (newline < 0 ? image : image.substring(0, newline)).trim();
    if (first.startsWith("v2.0")) body = newline < 0 ? "" : image.substring(newline + 1);
    validateImage(like, body);
    try {
      return HexFile.parseFromCircFile(body, like.getLogLength(), like.getWidth());
    } catch (IOException | RuntimeException e) {
      throw badImage(e.getMessage());
    }
  }

  /** The decoder reports a bad token as a warning and carries on, which a script would never see,
   * so every token is checked here: a hex word, optionally {@code N*word}, that fits the data width
   * and (in total) the address range. */
  private static void validateImage(MemContents like, String body) {
    final var words = like.getLastOffset() + 1;
    final var width = like.getWidth();
    var total = 0L;
    var lineNo = 0;
    for (final var rawLine : body.split("\n")) {
      lineNo++;
      final var hash = rawLine.indexOf('#');
      final var line = (hash >= 0 ? rawLine.substring(0, hash) : rawLine).trim();
      if (line.isEmpty()) continue;
      for (final var token : line.split("\\s+")) {
        final var star = token.indexOf('*');
        final var countText = star < 0 ? "1" : token.substring(0, star);
        final var wordText = star < 0 ? token : token.substring(star + 1);
        if (!countText.matches("[0-9]{1,9}") || !wordText.matches("[0-9a-fA-F]{1,16}")) {
          throw badImage("line " + lineNo + ": \"" + token + "\" is not a hex word or N*word");
        }
        if (width < 64 && Long.parseUnsignedLong(wordText, 16) >= (1L << width)) {
          throw badImage("line " + lineNo + ": \"" + wordText + "\" does not fit in " + width + " data bits");
        }
        total += Long.parseLong(countText);
        if (total > words) {
          throw badImage("the image holds more than the memory's " + words + " words");
        }
      }
    }
  }

  private static InvalidMemoryAccessException badImage(String reason) {
    return new InvalidMemoryAccessException(
        "the memory image does not parse: " + reason,
        Map.of("format", "v2.0 raw"),
        "use hex words separated by whitespace, with N*word to repeat a word; see memory:dump()");
  }

  private static void checkAddress(MemContents contents, long address, long count) {
    checkRange(contents, address, count);
  }

  private static void checkRange(MemContents contents, long start, long count) {
    final var words = contents.getLastOffset() + 1;
    if (count < 0 || count > MAX_RANGE) {
      throw new InvalidMemoryAccessException(
          "count " + count + " is outside 0.." + MAX_RANGE,
          Map.of("count", count),
          "split the access into ranges of at most " + MAX_RANGE + " words");
    }
    if (start < 0 || start + count > words) {
      throw new InvalidMemoryAccessException(
          "address range " + start + ".." + (start + count - 1) + " is outside this memory (" + words + " words)",
          Map.of("start", start, "count", count, "words", words),
          "valid addresses are 0.." + (words - 1));
    }
  }

  private static void checkValue(MemContents contents, long value) {
    final var width = contents.getWidth();
    if (width < 64 && (value < 0 || value >= (1L << width))) {
      throw new InvalidMemoryAccessException(
          "value " + value + " does not fit in " + width + " data bits",
          Map.of("value", value, "dataBits", width),
          "use a value from 0 to " + ((1L << width) - 1));
    }
  }

  /** Copies a snapshot into the live contents in place, so listeners and state keep their object. */
  private static final class ContentsAction extends Action {
    private final MemContents live;
    private final MemContents before;
    private final MemContents after;
    private final String name;

    ContentsAction(MemContents live, MemContents before, MemContents after, String name) {
      this.live = live;
      this.before = before;
      this.after = after;
      this.name = name;
    }

    @Override
    public void doIt(Project proj) {
      copy(after);
    }

    @Override
    public void undo(Project proj) {
      copy(before);
    }

    @Override
    public String getName() {
      return StringUtil.constantGetter(name).toString();
    }

    private void copy(MemContents from) {
      live.copyFrom(0, from, 0, (int) (from.getLastOffset() + 1));
    }
  }
}
