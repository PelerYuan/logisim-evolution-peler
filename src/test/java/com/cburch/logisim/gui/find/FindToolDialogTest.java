/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.find;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.TestBase;
import java.util.Arrays;
import javax.swing.Action;
import org.junit.jupiter.api.Test;

/** Peler Edition. Guards the finder against a method-resolution trap that silently disabled Enter. */
public class FindToolDialogTest extends TestBase {

  /**
   * {@link Action} has carried a {@code default boolean accept(Object)} since JDK 9. A nested
   * {@code AbstractAction} inside the dialog inherits it, so an unqualified {@code accept(flag)}
   * written in that nested class resolves to the default method -- boxing the flag, returning true
   * and never reaching the enclosing dialog. It compiles clean and does nothing, which is what Enter
   * and Shift+Enter did until 2026-08-10.
   *
   * <p>Any enclosing method whose name a nested {@code Action} can inherit is exposed to this. The
   * dialog therefore must not declare one called {@code accept}; renaming it back would reintroduce
   * the bug without a single warning.
   */
  @Test
  public void testNoMethodShadowedByActionsInheritedAccept() {
    assertTrue(
        Arrays.stream(Action.class.getMethods()).anyMatch(m -> "accept".equals(m.getName())),
        "premise of this test: Action still declares accept(Object)");

    final var offenders =
        Arrays.stream(FindToolDialog.class.getDeclaredMethods())
            .map(java.lang.reflect.Method::getName)
            .filter("accept"::equals)
            .toList();
    assertTrue(
        offenders.isEmpty(),
        "FindToolDialog must not declare accept(...): a nested Action calling it unqualified would "
            + "silently bind to Action.accept(Object) instead. Found: " + offenders);
  }
}
