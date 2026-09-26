/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import java.util.ArrayList;
import java.util.List;

/**
 * The declarative spec {@link Space#synthesize(Synthesis)} takes (design doc, section 十一): named
 * 1-bit inputs, and named 1-bit outputs each given as a boolean expression over those input names,
 * in Logisim's own expression syntax -- {@code and}/{@code or}/{@code xor}/{@code not} or the
 * symbolic operators the Combinational Analysis window accepts. Multi-bit variables are out of
 * scope for this first version: {@link com.cburch.logisim.analyze.model.OutputExpressions} keys an
 * expression by individual bit name, so a bus would need one expression per bit, which is more
 * machinery than anything using this so far has needed.
 *
 * <p>Building this generates an entire new circuit's gate-level layout via {@code CircuitBuilder}
 * -- it is a convenience layered on top of the Java operation layer ({@link Space#place}/{@link
 * Space#connect}), not a replacement, and it only ever targets a brand new, empty circuit (design
 * doc, closing discussion of 十一).
 */
public final class Synthesis {
  record InputSpec(String name) {}

  record OutputSpec(String name, String expression) {}

  private final List<InputSpec> inputs = new ArrayList<>();
  private final List<OutputSpec> outputs = new ArrayList<>();
  private boolean twoInputGatesOnly;
  private boolean nandOnly;

  private Synthesis() {}

  public static Synthesis of() {
    return new Synthesis();
  }

  public Synthesis input(String name) {
    inputs.add(new InputSpec(name));
    return this;
  }

  public Synthesis output(String name, String expression) {
    outputs.add(new OutputSpec(name, expression));
    return this;
  }

  /** Every gate becomes 2-input, splitting any wider AND/OR into a cascade -- matches the
   * Combinational Analysis window's "Two-input gates only" checkbox. */
  public Synthesis twoInputGatesOnly() {
    this.twoInputGatesOnly = true;
    return this;
  }

  /** Builds exclusively from NAND gates -- matches the window's "NANDs only" checkbox. */
  public Synthesis nandOnly() {
    this.nandOnly = true;
    return this;
  }

  List<InputSpec> inputs() {
    return List.copyOf(inputs);
  }

  List<OutputSpec> outputs() {
    return List.copyOf(outputs);
  }

  boolean isTwoInputGatesOnly() {
    return twoInputGatesOnly;
  }

  boolean isNandOnly() {
    return nandOnly;
  }
}
