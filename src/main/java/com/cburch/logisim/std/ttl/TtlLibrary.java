/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.ttl;

import static com.cburch.logisim.std.Strings.S;

import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.Attributes;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.tools.FactoryDescription;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.tools.Tool;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import javax.swing.SwingUtilities;

public class TtlLibrary extends Library {
  /**
   * Unique identifier of the library, used as reference in project files. Do NOT change as it will
   * prevent project files from loading.
   *
   * <p>Identifier value must MUST be unique string among all libraries.
   */
  public static final String _ID = "TTL";

  private static final FactoryDescription[] DESCRIPTIONS = {
      new FactoryDescription(Ttl7400.class, S.getter("TTL7400"), "ttl.gif"),
      new FactoryDescription(Ttl7402.class, S.getter("TTL7402"), "ttl.gif"),
      new FactoryDescription(Ttl7404.class, S.getter("TTL7404"), "ttl.gif"),
      new FactoryDescription(Ttl7408.class, S.getter("TTL7408"), "ttl.gif"),
      new FactoryDescription(Ttl7410.class, S.getter("TTL7410"), "ttl.gif"),
      new FactoryDescription(Ttl7411.class, S.getter("TTL7411"), "ttl.gif"),
      new FactoryDescription(Ttl7413.class, S.getter("TTL7413"), "ttl.gif"),
      new FactoryDescription(Ttl7414.class, S.getter("TTL7414"), "ttl.gif"),
      new FactoryDescription(Ttl7418.class, S.getter("TTL7418"), "ttl.gif"),
      new FactoryDescription(Ttl7419.class, S.getter("TTL7419"), "ttl.gif"),
      new FactoryDescription(Ttl7420.class, S.getter("TTL7420"), "ttl.gif"),
      new FactoryDescription(Ttl7421.class, S.getter("TTL7421"), "ttl.gif"),
      new FactoryDescription(Ttl7424.class, S.getter("TTL7424"), "ttl.gif"),
      new FactoryDescription(Ttl7427.class, S.getter("TTL7427"), "ttl.gif"),
      new FactoryDescription(Ttl7430.class, S.getter("TTL7430"), "ttl.gif"),
      new FactoryDescription(Ttl7432.class, S.getter("TTL7432"), "ttl.gif"),
      new FactoryDescription(Ttl7434.class, S.getter("TTL7434"), "ttl.gif"),
      new FactoryDescription(Ttl7436.class, S.getter("TTL7436"), "ttl.gif"),
      new FactoryDescription(Ttl7442.class, S.getter("TTL7442"), "ttl.gif"),
      new FactoryDescription(Ttl7443.class, S.getter("TTL7443"), "ttl.gif"),
      new FactoryDescription(Ttl7444.class, S.getter("TTL7444"), "ttl.gif"),
      new FactoryDescription(Ttl7447.class, S.getter("TTL7447"), "ttl.gif"),
      new FactoryDescription(Ttl7451.class, S.getter("TTL7451"), "ttl.gif"),
      new FactoryDescription(Ttl7454.class, S.getter("TTL7454"), "ttl.gif"),
      new FactoryDescription(Ttl7458.class, S.getter("TTL7458"), "ttl.gif"),
      new FactoryDescription(Ttl7464.class, S.getter("TTL7464"), "ttl.gif"),
      new FactoryDescription(Ttl7474.class, S.getter("TTL7474"), "ttl.gif"),
      new FactoryDescription(Ttl7485.class, S.getter("TTL7485"), "ttl.gif"),
      new FactoryDescription(Ttl7486.class, S.getter("TTL7486"), "ttl.gif"),
      new FactoryDescription(Ttl7487.class, S.getter("TTL7487"), "ttl.gif"),
      new FactoryDescription(Ttl74125.class, S.getter("TTL74125"), "ttl.gif"),
      new FactoryDescription(Ttl74138.class, S.getter("TTL74138"), "ttl.gif"),
      new FactoryDescription(Ttl74139.class, S.getter("TTL74139"), "ttl.gif"),
      new FactoryDescription(Ttl74151.class, S.getter("TTL74151"), "ttl.gif"),
      new FactoryDescription(Ttl74153.class, S.getter("TTL74153"), "ttl.gif"),
      new FactoryDescription(Ttl74157.class, S.getter("TTL74157"), "ttl.gif"),
      new FactoryDescription(Ttl74158.class, S.getter("TTL74158"), "ttl.gif"),
      new FactoryDescription(Ttl74161.class, S.getter("TTL74161"), "ttl.gif"),
      new FactoryDescription(Ttl74163.class, S.getter("TTL74163"), "ttl.gif"),
      new FactoryDescription(Ttl74164.class, S.getter("TTL74164"), "ttl.gif"),
      new FactoryDescription(Ttl74165.class, S.getter("TTL74165"), "ttl.gif"),
      new FactoryDescription(Ttl74166.class, S.getter("TTL74166"), "ttl.gif"),
      new FactoryDescription(Ttl74175.class, S.getter("TTL74175"), "ttl.gif"),
      new FactoryDescription(Ttl74181.class, S.getter("TTL74181"), "ttl.gif"),
      new FactoryDescription(Ttl74182.class, S.getter("TTL74182"), "ttl.gif"),
      new FactoryDescription(Ttl74192.class, S.getter("TTL74192"), "ttl.gif"),
      new FactoryDescription(Ttl74193.class, S.getter("TTL74193"), "ttl.gif"),
      new FactoryDescription(Ttl74194.class, S.getter("TTL74194"), "ttl.gif"),
      new FactoryDescription(Ttl74240.class, S.getter("TTL74240"), "ttl.gif"),
      new FactoryDescription(Ttl74241.class, S.getter("TTL74241"), "ttl.gif"),
      new FactoryDescription(Ttl74244.class, S.getter("TTL74244"), "ttl.gif"),
      new FactoryDescription(Ttl74245.class, S.getter("TTL74245"), "ttl.gif"),
      new FactoryDescription(Ttl74266.class, S.getter("TTL74266"), "ttl.gif"),
      new FactoryDescription(Ttl74273.class, S.getter("TTL74273"), "ttl.gif"),
      new FactoryDescription(Ttl74283.class, S.getter("TTL74283"), "ttl.gif"),
      new FactoryDescription(Ttl74299.class, S.getter("TTL74299"), "ttl.gif"),
      new FactoryDescription(Ttl74377.class, S.getter("TTL74377"), "ttl.gif"),
      new FactoryDescription(Ttl74381.class, S.getter("TTL74381"), "ttl.gif"),
      new FactoryDescription(Ttl74541.class, S.getter("TTL74541"), "ttl.gif"),
      new FactoryDescription(Ttl74670.class, S.getter("TTL74670"), "ttl.gif"),
      new FactoryDescription(Ttl747266.class, S.getter("TTL747266"), "ttl.gif"),
  };

  static final Attribute<Boolean> VCC_GND =
      Attributes.forBoolean("VccGndPorts", S.getter("VccGndPorts"));
  static final Attribute<Boolean> DRAW_INTERNAL_STRUCTURE =
      Attributes.forBoolean("ShowInternalStructure", S.getter("ShowInternalStructure"));

  private List<Tool> tools = null;

  /**
   * Peler Edition Feature 12: every library instance that might have tools to update.
   *
   * <p>Weak, because there is one of these per open project -- {@code Loader} builds its own
   * {@code Builtin} -- and the listener below outlives them all.
   */
  private static final Set<TtlLibrary> INSTANCES =
      Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

  static {
    AppPreferences.TTL_DRAW_INTERNAL_STRUCTURE.addPropertyChangeListener(
        event -> {
          if (!AppPreferences.TTL_DRAW_INTERNAL_STRUCTURE.isSource(event)) return;
          final List<TtlLibrary> live;
          synchronized (INSTANCES) {
            live = new ArrayList<>(INSTANCES);
          }
          // The preference change arrives on java.util.prefs' own thread, and what this touches is
          // watched by the toolbox. invokeLater, never invokeAndWait: nothing here is waiting on
          // the result, and a settings change must not be able to block on the event thread.
          SwingUtilities.invokeLater(() -> {
            for (final var library : live) library.applyDefaultDrawing();
          });
        });
  }

  public TtlLibrary() {
    INSTANCES.add(this);
  }

  /**
   * Pushes the settings-page default into the tools the toolbox is already holding.
   *
   * <p>{@link AbstractTtlGate#createAttributeSet()} is enough at startup but not afterwards:
   * {@code AddTool}'s constructor asks whether its attribute set contains {@code StdAttr.APPEARANCE}
   * and that question builds the set, so all sixty-one are fixed before the user has seen the
   * window. Without this the setting would appear to do nothing until the next launch, which is
   * indistinguishable from a broken checkbox.
   */
  private void applyDefaultDrawing() {
    final var current = tools;
    if (current == null) return; // Not built yet, so createAttributeSet will supply the value.
    final var wanted = AppPreferences.TTL_DRAW_INTERNAL_STRUCTURE.getBoolean();
    for (final var tool : current) {
      final var attrs = tool.getAttributeSet();
      if (attrs != null && attrs.containsAttribute(DRAW_INTERNAL_STRUCTURE)) {
        attrs.setValue(DRAW_INTERNAL_STRUCTURE, wanted);
      }
    }
  }

  @Override
  public List<? extends Tool> getTools() {
    if (tools == null) {
      tools = FactoryDescription.getTools(TtlLibrary.class, DESCRIPTIONS);
    }
    return tools;
  }
}
