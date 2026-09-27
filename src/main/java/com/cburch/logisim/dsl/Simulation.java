/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.dsl;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.circuit.Simulator;
import com.cburch.logisim.circuit.SubcircuitFactory;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Clock;
import com.cburch.logisim.std.wiring.Pin;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs the project's own, always-live {@link Simulator} (design doc, section 五 -- every {@link
 * Project} has one from construction, headless or not) against the circuit a {@link Space} wraps.
 * Unlike every other façade in this package, {@code Simulator}'s own API is asynchronous: a call
 * like {@link Simulator#tick(int)} only sets a flag and returns, with the real work happening later
 * on the project's background {@code SimThread}. Every method here waits for that work to actually
 * finish (a temporary {@link Simulator.Listener} counting down a {@link CountDownLatch}) before
 * returning, so a script sees each step complete before making the next call -- exactly like a human
 * clicking a menu item and seeing the result before clicking the next one.
 *
 * <p>Deliberately never touches {@link com.cburch.logisim.gui.log.ComponentSelector} or {@link
 * com.cburch.logisim.gui.log.ClockSource}: both construct real Swing components, and the latter can
 * pop a modal "choose a clock" dialog that would freeze the event dispatch thread waiting for a
 * click an unattended script will never make (see {@code CLAUDE.md}, "Before hopping to the event
 * dispatch thread"). {@link #tick(int)} and {@link #setAutoTicking(boolean)} (true case) instead
 * replicate {@code Simulator}'s own clock presence check directly -- see {@link #ensureClock()} --
 * and refuse with {@link NoClockException} rather than ever reaching that dialog.
 */
public final class Simulation {
  private static final long TIMEOUT_SECONDS = 5;

  private final Space space;
  private final Project proj;

  private Simulation(Space space) {
    this.space = space;
    this.proj = space.project();
  }

  public static Simulation of(Space space) {
    return new Simulation(space);
  }

  /** A pin's current value. {@code known} is false (and {@code value} meaningless, always -1) for
   * a pin with any unknown ("floating") bit; {@code error} is true for a pin in a width- or
   * conflict-error state. */
  public record PinValue(long value, boolean known, boolean error) {}

  /** Resets the simulation to its initial state -- mirrors the GUI's Simulate -> Reset Simulation.
   * Unlike {@link #tick(int)}/{@link #step()}, this never needs a clock, so it never throws {@link
   * NoClockException}. */
  public void reset() {
    final var sim = proj.getSimulator();
    circuitState();
    final var latch = new CountDownLatch(1);
    final Simulator.Listener listener = new Simulator.Listener() {
      @Override
      public void simulatorReset(Simulator.Event e) {
        latch.countDown();
      }

      @Override
      public void simulatorStateChanged(Simulator.Event e) {}

      @Override
      public void propagationCompleted(Simulator.Event e) {}
    };
    sim.addSimulatorListener(listener);
    try {
      sim.reset();
      await(latch, "reset");
    } finally {
      sim.removeSimulatorListener(listener);
    }
  }

  /** Propagates one step by hand -- mirrors the GUI's Simulate -> Single Step. Meaningful whether
   * or not auto-propagation is on. */
  public void step() {
    runAndAwaitPropagation(Simulator::step);
  }

  /** Advances every clock by {@code halfCycles} half-periods -- mirrors the GUI's Simulate -> Tick
   * Half/Full Period (1 and 2 respectively). Throws {@link NoClockException} if this circuit (or
   * any subcircuit it contains) has no {@link Clock} component to advance. */
  public void tick(int halfCycles) {
    ensureClock();
    runAndAwaitPropagation(sim -> sim.tick(halfCycles));
  }

  public boolean isAutoTicking() {
    return proj.getSimulator().isAutoTicking();
  }

  /** Mirrors the GUI's Simulate -> Ticks Enabled. Turning ticking on throws {@link
   * NoClockException} under the same condition as {@link #tick(int)}. */
  public void setAutoTicking(boolean value) {
    if (value) ensureClock();
    proj.getSimulator().setAutoTicking(value);
  }

  public boolean isAutoPropagating() {
    return proj.getSimulator().isAutoPropagating();
  }

  /** Mirrors the GUI's Simulate -> Run/Stop Simulation toggle. */
  public void setAutoPropagation(boolean value) {
    proj.getSimulator().setAutoPropagation(value);
  }

  public double getTickFrequency() {
    return proj.getSimulator().getTickFrequency();
  }

  public void setTickFrequency(double hz) {
    proj.getSimulator().setTickFrequency(hz);
  }

  public boolean isOscillating() {
    return proj.getSimulator().isOscillating();
  }

  public boolean isExceptionEncountered() {
    return proj.getSimulator().isExceptionEncountered();
  }

  /** Reads the current value of the {@code wiring/pin} component labeled {@code label} -- works
   * for both input and output pins. Throws {@link UnknownPinException} if no such labeled pin
   * exists in this circuit. */
  public PinValue readPin(String label) {
    final var state = circuitState();
    final var comp = requirePin(label);
    final var instanceState = state.getInstanceState(comp.rawComponent());
    return toPinValue(Pin.FACTORY.getValue(instanceState));
  }

  /** Drives an input pin to {@code value} and propagates the change -- mirrors clicking the GUI's
   * poke tool on an input pin. Throws {@link UnknownPinException} if no such labeled pin exists, or
   * {@link PinNotWritableException} if {@code label} names an output pin instead. */
  public void writePin(String label, long value) {
    final var state = circuitState();
    final var comp = requirePin(label);
    final var instanceState = state.getInstanceState(comp.rawComponent());
    final var instance = instanceState.getInstance();
    if (!Pin.FACTORY.isInputPin(instance)) {
      throw new PinNotWritableException(label);
    }
    final var width = Pin.FACTORY.getWidth(instance);
    Pin.FACTORY.driveInputPin(instanceState, Value.createKnown(width, value));
    instanceState.fireInvalidated();
    runAndAwaitPropagation(Simulator::step);
  }

  /** Points the project's shared {@link Simulator} at this {@link Space}'s circuit -- {@link
   * Simulator#setCircuitState} rather than {@link Project#setCircuitState}, since the latter carries
   * GUI side effects (canvas selection, tab switching, tool deselection) this headless façade has no
   * business causing, and is a no-op when already pointed here. Using the project's own shared
   * {@link CircuitState} (not a private one) is not just convenient but required: {@link
   * com.cburch.logisim.circuit.Propagator#propagate()} throws if called from any thread but the one
   * that constructed it, and a {@link CircuitState} reached this way binds that to the project's
   * real {@code SimThread} -- the only thread {@code Simulator}'s own async API ever calls it from. */
  private CircuitState circuitState() {
    final var state = proj.getCircuitState(space.circuit());
    proj.getSimulator().setCircuitState(state);
    return state;
  }

  /** Replicates {@code Simulator.ensureClocks()}'s own clock-presence check (recursing into
   * subcircuits exactly as {@link com.cburch.logisim.gui.log.ComponentSelector#findClocks} does for
   * its {@code ACTUAL_CLOCKS} mode) without ever constructing a {@code ComponentSelector} -- it
   * extends {@code JTable} and does real Swing setup in its constructor, so it is not safe to build
   * off the event dispatch thread even just to call a static method on it. Marks the state's known-
   * clocks flag on success so {@code Simulator}'s own re-check inside {@link Simulator#tick}/{@link
   * Simulator#setAutoTicking} short-circuits before ever reaching its dialog fallback. */
  private void ensureClock() {
    final var state = circuitState();
    if (state.hasKnownClocks()) return;
    if (hasClock(space.circuit(), new HashSet<>())) {
      state.markKnownClocks();
      return;
    }
    throw new NoClockException(space.circuitName());
  }

  private static boolean hasClock(Circuit circuit, Set<Circuit> visited) {
    if (!visited.add(circuit)) return false;
    for (final var comp : circuit.getNonWires()) {
      if (comp.getFactory() instanceof Clock) return true;
      if (comp.getFactory() instanceof SubcircuitFactory sub && hasClock(sub.getSubcircuit(), visited)) {
        return true;
      }
    }
    return false;
  }

  private Comp requirePin(String label) {
    final var found = space.byLabel(label);
    if (found.isEmpty()) {
      throw new UnknownPinException(label, nearest(label), null);
    }
    final var comp = found.get();
    if (!comp.kind().key().equals("wiring/pin")) {
      throw new UnknownPinException(label, List.of(), comp.kind().key());
    }
    return comp;
  }

  private List<String> nearest(String label) {
    final var candidates = new ArrayList<String>();
    for (final var c : space.components()) c.label().ifPresent(candidates::add);
    candidates.sort((a, b) -> distance(label, a) - distance(label, b));
    final var top = new ArrayList<String>();
    for (final var candidate : candidates) {
      if (distance(label, candidate) <= Math.max(3, label.length() / 2)) top.add(candidate);
      if (top.size() == 3) break;
    }
    return top;
  }

  private static int distance(String a, String b) {
    final var dp = new int[a.length() + 1][b.length() + 1];
    for (var i = 0; i <= a.length(); i++) dp[i][0] = i;
    for (var j = 0; j <= b.length(); j++) dp[0][j] = j;
    for (var i = 1; i <= a.length(); i++) {
      for (var j = 1; j <= b.length(); j++) {
        final var cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
      }
    }
    return dp[a.length()][b.length()];
  }

  private void runAndAwaitPropagation(Consumer<Simulator> request) {
    final var sim = proj.getSimulator();
    circuitState();
    final var latch = new CountDownLatch(1);
    final Simulator.Listener listener = new Simulator.Listener() {
      @Override
      public void simulatorReset(Simulator.Event e) {}

      @Override
      public void simulatorStateChanged(Simulator.Event e) {}

      @Override
      public void propagationCompleted(Simulator.Event e) {
        latch.countDown();
      }
    };
    sim.addSimulatorListener(listener);
    try {
      request.accept(sim);
      await(latch, "propagation");
    } finally {
      sim.removeSimulatorListener(listener);
    }
  }

  private void await(CountDownLatch latch, String operation) {
    try {
      if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new SimulationTimedOutException(operation);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SimulationTimedOutException(operation);
    }
  }

  private static PinValue toPinValue(Value v) {
    if (v.isErrorValue()) return new PinValue(-1L, false, true);
    if (!v.isFullyDefined()) return new PinValue(-1L, false, false);
    return new PinValue(v.toLongValue(), true, false);
  }
}
