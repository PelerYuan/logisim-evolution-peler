/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.TestBase;
import java.io.File;
import org.junit.jupiter.api.Test;

/** Peler Edition. Guards the boundaries that keep this edition off the official one's files. */
public class PelerPreferencesTest extends TestBase {

  /** Upstream's default, which this edition must no longer generate into. */
  private static final String SHARED_WORKSPACE =
      System.getProperty("user.home") + "/logisim_evolution_workspace";

  /**
   * Nothing here touches {@link AppPreferences}: its static initialiser reads the real preference
   * store, and reading it headless writes wrong defaults back (see CLAUDE.md).
   */
  @Test
  public void testDefaultFpgaWorkspaceIsNotTheSharedOne() {
    final var workspace = PelerPreferences.defaultFpgaWorkspace();
    assertNotEquals(SHARED_WORKSPACE, workspace);
    assertTrue(
        workspace.startsWith(System.getProperty("user.home")),
        "workspace should still live under the home directory, was: " + workspace);
    assertEquals(
        new File(SHARED_WORKSPACE).getParent(), new File(workspace).getParent(),
        "workspace should sit alongside the shared one, not inside it");
  }

  /** A sibling of the shared node, so neither edition sees the other walking sub-nodes. */
  @Test
  public void testPreferenceNodeIsNotTheSharedOne() {
    assertNotEquals("com/cburch/logisim", PelerPreferences.NODE);
  }
}
