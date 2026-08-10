/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.htmlexport;

import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.instance.InstanceDataSingleton;
import com.cburch.logisim.instance.InstanceFactory;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.std.io.Button;
import com.cburch.logisim.std.io.DipSwitch;
import com.cburch.logisim.std.wiring.Pin;
import java.awt.event.MouseEvent;
import javax.swing.JPanel;

/**
 * Peler Edition. The components whose value comes from being clicked rather than from a wire.
 *
 * <p>Their state does not live on any port, so the exporter cannot reach it by driving inputs. It
 * is set here the same way a user sets it: through the component's own {@code InstancePoker}, with
 * a click at the coordinate the poker itself would map to that switch. Nothing restates where the
 * switches are -- the coordinates are the component's own port locations, and the poker's mapping
 * back from a coordinate to a switch index is inverted, not reimplemented.
 *
 * <p>The page recomputes that same mapping when it is clicked, so a click there and a click in the
 * editor land on the same switch by construction.
 *
 * <p>Input pins belong here for the same reason, even though the page shows them differently: their
 * value is theirs, not something arriving on a wire, so the exporter has to set it to find out what
 * the pin looks like holding it.
 */
final class HtmlPoke {

  private HtmlPoke() {}

  /** Lazily made, and never shown: a MouseEvent refuses a null source. */
  private static JPanel eventSource;

  /** How many bits of clickable state this component has, or 0 when it has none. */
  static int bits(Component component) {
    final var attrs = component.getAttributeSet();
    return switch (component.getFactory().getName()) {
      case "Button" -> 1;
      case "DipSwitch" -> attrs.getValue(DipSwitch.ATTR_SIZE).getWidth();
      // An input pin holds its own value too, and clicking it is the whole point of the export.
      case "Pin" -> Pin.OUTPUT.equals(attrs.getValue(Pin.ATTR_TYPE))
          ? 0 : attrs.getValue(StdAttr.WIDTH).getWidth();
      default -> 0;
    };
  }

  /**
   * Puts the component into the given state, before its {@code propagate} is run.
   *
   * @return false when the state could not be set, so the caller drops the appearance rather than
   *     shipping one that does not match.
   */
  static boolean apply(Component component, InstanceState state, long value) {
    final var factory = (InstanceFactory) component.getFactory();
    switch (component.getFactory().getName()) {
      case "Button" -> {
        // Pressed is whatever the released value is not; which of the two is the "1" on the wire
        // is the ATTR_PRESS attribute's business, and propagate() applies it.
        final var passive =
            component.getAttributeSet().getValue(Button.ATTR_PRESS) == Button.BUTTON_PRESS_PASSIVE;
        final var pressed = (value & 1) != 0;
        final var level = pressed == passive ? Value.FALSE : Value.TRUE;
        state.setData(new InstanceDataSingleton(level));
        return true;
      }
      case "Pin" -> {
        final var width = component.getAttributeSet().getValue(StdAttr.WIDTH).getWidth();
        final var bits = new Value[width];
        for (var i = 0; i < width; i++) {
          bits[i] = ((value >> i) & 1) != 0 ? Value.TRUE : Value.FALSE;
        }
        Pin.FACTORY.driveInputPin(state, Value.create(bits));
        return true;
      }
      case "DipSwitch" -> {
        // The first propagate is what creates the switch state; the poker needs it to exist.
        factory.propagate(state);
        final var poker = new DipSwitch.Poker();
        final var count = bits(component);
        for (var i = 0; i < count; i++) {
          if (((value >> i) & 1) == 0) continue;
          final var at = component.getEnd(i).getLocation();
          poker.mousePressed(state, clickAt(at.getX(), at.getY()));
        }
        return true;
      }
      default -> {
        return false;
      }
    }
  }

  private static MouseEvent clickAt(int x, int y) {
    if (eventSource == null) eventSource = new JPanel();
    return new MouseEvent(
        eventSource, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, x, y, 1, false);
  }

  /**
   * What the page needs to route a click back to a switch, for the components where one click
   * region covers several switches.
   */
  static Object hitTestJson(Component component) {
    if (!"DipSwitch".equals(component.getFactory().getName())) return null;
    final var facing = component.getAttributeSet().getValue(StdAttr.FACING);
    return new HtmlJson()
        .put("kind", "dip")
        .put("facing", facing == null ? Direction.NORTH.toString() : facing.toString());
  }
}
