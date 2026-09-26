/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import com.cburch.logisim.comp.EndData;
import java.util.Optional;

/**
 * One connection point on a placed {@link Comp}. Addressed by array index for built-in components,
 * or by label for subcircuits -- see design doc, 3.6/3.7 for why those are the only two schemes
 * that stay stable across rotation and attribute changes.
 */
public final class Port {
  /** Signal direction, derived from the underlying {@link EndData} type (design doc, 3.6). */
  public enum Dir {
    IN,
    OUT,
    INOUT
  }

  private final Comp owner;
  private final int index;
  private final EndData end;
  private final int vendGeneration;
  private final String subcircuitPinLabel;

  Port(Comp owner, int index, EndData end, int vendGeneration, String subcircuitPinLabel) {
    this.owner = owner;
    this.index = index;
    this.end = end;
    this.vendGeneration = vendGeneration;
    this.subcircuitPinLabel = subcircuitPinLabel;
  }

  public Comp owner() {
    checkFresh();
    return owner;
  }

  public int index() {
    checkFresh();
    return index;
  }

  public Dir dir() {
    checkFresh();
    final var input = end.isInput();
    final var output = end.isOutput();
    if (input && output) return Dir.INOUT;
    return output ? Dir.OUT : Dir.IN;
  }

  public int width() {
    checkFresh();
    return end.getWidth().getWidth();
  }

  public boolean exclusive() {
    checkFresh();
    return end.isExclusive();
  }

  /** The subcircuit pin's label, verbatim -- empty for every built-in component (design doc, 3.7). */
  public Optional<String> name() {
    checkFresh();
    return Optional.ofNullable(subcircuitPinLabel);
  }

  /** Logisim's own tooltip: localized, human-readable, never an identifier (design doc, 3.5). */
  public Optional<String> desc() {
    checkFresh();
    return owner.toolTipFor(index);
  }

  public Dot at() {
    checkFresh();
    return new Dot(end.getLocation().getX(), end.getLocation().getY());
  }

  public Optional<Net> net() {
    checkFresh();
    return owner.space().netOf(this);
  }

  public boolean isConnected() {
    return net().isPresent();
  }

  private void checkFresh() {
    if (owner.generation() != vendGeneration) throw new StaleReferenceException(owner);
  }

  @Override
  public String toString() {
    return owner.id() + ".port(" + index + ")";
  }
}
