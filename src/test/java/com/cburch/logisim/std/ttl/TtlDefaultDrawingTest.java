/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.ttl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.generated.BuildInfo;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.tools.AddTool;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Peler Edition Feature 12. The settings-page default for upstream's per-chip
 * {@code ShowInternalStructure} attribute.
 *
 * <p>Setting a preference from a test is safe here only because the {@code test} task in
 * {@code build.gradle.kts} points {@code java.util.prefs} at a directory under {@code build/}; the
 * same redirection is what keeps a headless run from writing a degraded {@code hotkeyMenuMask} into
 * the developer's real settings. Do not copy this pattern into a test run outside Gradle.
 *
 * <p>Three of the four checks below are about what the setting is deliberately kept away from.
 * That is where the risk is: the attribute it feeds sits next to {@code VccGndPorts}, which decides
 * how many pins a package has, and a settings-dependent default on that one would change the pin
 * count of chips in circuits that were drawn years ago.
 */
public class TtlDefaultDrawingTest {

  /** A spread of the library rather than one chip, since the behaviour is on the base class. */
  private static final List<Supplier<AbstractTtlGate>> CHIPS =
      List.of(Ttl7400::new, Ttl74283::new, Ttl74175::new, Ttl7447::new);

  @AfterEach
  public void restore() {
    setSetting(false);
  }

  /**
   * Writes the setting and waits for it to arrive.
   *
   * <p>{@code PrefMonitorBoolean.set} only writes the {@code java.util.prefs} node; the cached
   * value {@code getBoolean} returns is updated by the preference-change listener, which the
   * platform delivers on its own thread. Nothing in the application notices -- a user who ticks the
   * box has to move the mouse before placing anything -- but a test that sets and reads in the same
   * breath reads the old value often enough to fail.
   */
  private static void setSetting(boolean wanted) {
    AppPreferences.TTL_DRAW_INTERNAL_STRUCTURE.setBoolean(wanted);
    final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (AppPreferences.TTL_DRAW_INTERNAL_STRUCTURE.getBoolean() != wanted) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("the setting never became " + wanted);
      }
      try {
        Thread.sleep(1);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted waiting for the setting to become " + wanted, e);
      }
    }
  }

  @Test
  public void newlyPlacedChipsStartWithTheDrawingTheSettingAsksFor() {
    for (final var chip : CHIPS) {
      for (final var wanted : List.of(true, false)) {
        setSetting(wanted);
        final var factory = chip.get();
        assertEquals(
            wanted,
            factory.createAttributeSet().getValue(TtlLibrary.DRAW_INTERNAL_STRUCTURE),
            factory.getName() + " ignored the setting when its attribute set was built");
      }
    }
  }

  @Test
  public void theSettingDoesNotReachAChipThatAlreadyExists() {
    setSetting(false);
    final var placed = new Ttl7400().createAttributeSet();

    setSetting(true);

    assertFalse(
        placed.getValue(TtlLibrary.DRAW_INTERNAL_STRUCTURE),
        "changing the setting reached back into a chip that was already on the canvas");
  }

  /**
   * The registered factory default has to stay upstream's {@code false} whatever the setting says,
   * because that is the value {@code XmlWriter} compares against when it decides whether to write
   * an attribute out. Were it to follow the setting, a chip placed with the setting on would match
   * the default, save nothing, and come back as a plain package on a machine where the setting is
   * off -- the file would mean different things on different machines.
   */
  @Test
  public void chipsPlacedWithTheSettingOnStillWriteTheAttributeIntoTheFile() {
    setSetting(true);
    final var factory = new Ttl7400();
    final var attrs = factory.createAttributeSet();

    assertTrue(
        attrs.getValue(TtlLibrary.DRAW_INTERNAL_STRUCTURE),
        "premise of this test: the chip picked the setting up");
    assertEquals(
        Boolean.FALSE,
        factory.getDefaultAttributeValue(TtlLibrary.DRAW_INTERNAL_STRUCTURE, BuildInfo.version),
        "the registered default moved with the setting, so the saved file would no longer say "
            + "which drawing it meant");
  }

  /**
   * The check the whole feature turned on, and the one the unit tests above cannot make on their
   * own.
   *
   * <p>{@code AddTool}'s constructor asks its attribute set whether it contains
   * {@code StdAttr.APPEARANCE}, and that question builds the set -- so every chip in the toolbox
   * has its attributes fixed at startup, long before the settings window opens. Ticking the box
   * placed a plain DIP package anyway, in a build whose four other checks all passed, because
   * nothing here reached the tools that were already made.
   */
  @Test
  public void changingTheSettingReachesTheChipsAlreadyInTheToolbox() {
    setSetting(false);
    final var library = new TtlLibrary();
    final var tools = library.getTools();
    assertFalse(tools.isEmpty(), "premise of this test: the TTL library offers tools");
    for (final var tool : tools) {
      assertFalse(
          ((AddTool) tool).getAttributeSet().getValue(TtlLibrary.DRAW_INTERNAL_STRUCTURE),
          tool.getName() + " started out drawn with its gates showing");
    }

    setSetting(true);

    for (final var tool : tools) {
      awaitToolSetting((AddTool) tool, true);
    }
  }

  /**
   * The push runs on the event dispatch thread, so it lands a moment after the setting itself
   * does. Waiting for it here rather than asserting straight away keeps the test from depending on
   * how quickly two threads happen to be scheduled.
   */
  private static void awaitToolSetting(AddTool tool, boolean wanted) {
    final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!Boolean.valueOf(wanted).equals(tool.getAttributeSet().getValue(TtlLibrary.DRAW_INTERNAL_STRUCTURE))) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError(
            tool.getName() + " was still in the toolbox with the old drawing after the setting "
                + "changed, so the checkbox would look like it did nothing until the next launch");
      }
      try {
        Thread.sleep(1);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted waiting for " + tool.getName(), e);
      }
    }
  }

  /**
   * {@code VccGndPorts} is next to it in the attribute list and looks like the same kind of switch,
   * but it adds two ports to the package. There is deliberately no setting for it; this holds that
   * decision in place, since wiring one up is a two-line change away.
   */
  @Test
  public void theSupplyPinsAreLeftAlone() {
    setSetting(true);

    assertFalse(
        new Ttl7400().createAttributeSet().getValue(TtlLibrary.VCC_GND),
        "the drawing setting also switched the supply pins on, which changes the pin count");
  }
}
