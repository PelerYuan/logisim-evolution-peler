/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.cburch.draw.model.CanvasObject;
import com.cburch.logisim.circuit.appear.AppearanceAnchor;
import com.cburch.logisim.circuit.appear.AppearanceElement;
import com.cburch.logisim.circuit.appear.AppearancePort;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.instance.Instance;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Peler Edition. The appearance derived from a layout has to agree with the layout exactly.
 *
 * <p>This is the load-bearing property of the whole feature, and it is load-bearing twice. In a
 * project it decides where wires attach to a custom component; when that project is written to
 * official {@code .circ}, each component goes out as a plain subcircuit carrying this same
 * appearance, and the only reason no wire moves is that these coordinates are the ones the layout
 * computed. A drift of one grid square here would not fail anything until somebody opened a saved
 * file in the official build and found every wire beside its port.
 */
class PcompAppearanceTest {

  private static Map<String, Instance> pinsFor(PortLayout layout) {
    final var pins = new LinkedHashMap<String, Instance>();
    for (final var port : layout.placements()) pins.put(port.name(), mock(Instance.class));
    return pins;
  }

  private static PortLayout layoutOf(int left, int right, int top, int bottom) {
    final var ports = new ArrayList<PortPlacement>();
    for (var i = 0; i < left; i++) ports.add(PcompLayouts.nth("IN" + i, PortSide.LEFT, i));
    for (var i = 0; i < right; i++) ports.add(PcompLayouts.nth("OUT" + i, PortSide.RIGHT, i));
    for (var i = 0; i < top; i++) ports.add(PcompLayouts.nth("CLK" + i, PortSide.TOP, i));
    for (var i = 0; i < bottom; i++) ports.add(PcompLayouts.nth("CARRY" + i, PortSide.BOTTOM, i));
    return PcompLayouts.automatic("Component", ports);
  }

  static Stream<Arguments> portCounts() {
    final var counts = new ArrayList<Arguments>();
    for (final var left : List.of(0, 1, 5)) {
      for (final var right : List.of(0, 2)) {
        for (final var top : List.of(0, 1, 2)) {
          for (final var bottom : List.of(0, 1)) {
            if (left + right + top + bottom == 0) continue;
            counts.add(Arguments.of(left, right, top, bottom));
          }
        }
      }
    }
    return counts.stream();
  }

  /** The anchor's location, which every port offset is taken relative to. */
  private static Location anchorOf(List<CanvasObject> shapes) {
    for (final var shape : shapes) {
      if (shape instanceof AppearanceAnchor anchor) return anchor.getLocation();
    }
    return null;
  }

  /**
   * The port offsets the appearance reports, worked out the same way {@code
   * CircuitAppearance.getPortOffsets} does: a port's location, less the anchor's.
   */
  private static Map<Location, AppearancePort> portOffsets(List<CanvasObject> shapes) {
    final var anchor = anchorOf(shapes);
    final var offsets = new HashMap<Location, AppearancePort>();
    for (final var shape : shapes) {
      if (shape instanceof AppearancePort port) {
        offsets.put(
            port.getLocation().translate(-anchor.getX(), -anchor.getY()), port);
      }
    }
    return offsets;
  }

  /**
   * The bounds the appearance reports, worked out the same way {@code CircuitAppearance.getBounds}
   * does: an appearance element counts as its location alone, everything else as its drawn bounds.
   *
   * <p>Text is left out, deliberately. {@code Text.getBounds} measures with a real
   * {@code FontMetrics}, so counting it would make this test's answer depend on which fonts the
   * machine running it happens to have -- the exact dependency the layout arithmetic exists to
   * avoid, and one that would have this fail on a build machine with no Courier rather than on a
   * genuine regression. What the box is, and where the anchor sits in it, do not depend on fonts.
   */
  private static Bounds boundsOf(List<CanvasObject> shapes) {
    Bounds bounds = null;
    for (final var shape : shapes) {
      if (shape instanceof com.cburch.draw.shapes.Text) continue;
      final var own =
          (shape instanceof AppearanceElement element)
              ? Bounds.create(element.getLocation())
              : shape.getBounds();
      bounds = (bounds == null) ? own : bounds.add(own);
    }
    final var anchor = anchorOf(shapes);
    return bounds.translate(-anchor.getX(), -anchor.getY());
  }

  /** Every port comes out on the coordinate the layout put it on, and no port is missing. */
  @ParameterizedTest
  @MethodSource("portCounts")
  public void portsLandExactlyWhereTheLayoutPutThem(int left, int right, int top, int bottom) {
    final var layout = layoutOf(left, right, top, bottom);
    final var shapes = PcompAppearance.build(layout, pinsFor(layout));

    final var offsets = portOffsets(shapes);
    assertEquals(
        layout.portCount(), offsets.size(), "the appearance has a different number of ports");
    for (final var port : layout.placements()) {
      final var expected = layout.offsetOf(port.name());
      assertTrue(
          offsets.containsKey(expected),
          port.name() + " should be at " + expected + " but the appearance has " + offsets.keySet());
    }
  }

  /**
   * The box is the bounds, give or take the stroke. The anchor sits on the box's top-left corner
   * and appearance elements contribute only their location, so no port or anchor pushes the bounds
   * outwards; all that is left is the outline's two-pixel stroke, half of it either side.
   *
   * <p>What this rules out is the component believing it is a different size from the box drawn on
   * it, which is how a click lands on nothing or a wire refuses a port that is plainly there.
   */
  @ParameterizedTest
  @MethodSource("portCounts")
  public void theBoundsAreTheBoxPlusItsStroke(int left, int right, int top, int bottom) {
    final var layout = layoutOf(left, right, top, bottom);
    final var shapes = PcompAppearance.build(layout, pinsFor(layout));

    assertEquals(
        Bounds.create(-1, -1, layout.width() + 2, layout.height() + 2), boundsOf(shapes));
  }

  /** Each port anchor is bound to the pin of the same name, not to whichever pin came first. */
  @Test
  public void eachPortIsBoundToItsOwnPin() {
    final var layout = layoutOf(2, 2, 1, 1);
    final var pins = pinsFor(layout);
    final var shapes = PcompAppearance.build(layout, pins);

    final var offsets = portOffsets(shapes);
    for (final var port : layout.placements()) {
      final var at = layout.offsetOf(port.name());
      assertEquals(
          pins.get(port.name()),
          offsets.get(at).getPin(),
          port.name() + " is drawn at the right place but wired to the wrong pin");
    }
  }

  /**
   * A layout naming a port the circuit does not have is refused. Dropping it instead would leave a
   * component that draws correctly and has one fewer port than its pins, which cannot be wired and
   * gives nothing to point at.
   */
  @Test
  public void portsWithNoPinAreRefused() {
    final var layout = layoutOf(2, 1, 0, 0);
    final var pins = pinsFor(layout);
    pins.remove("IN1");

    final var failure =
        assertThrows(
            IllegalArgumentException.class, () -> PcompAppearance.build(layout, pins));
    assertTrue(failure.getMessage().contains("IN1"), "the message should name the missing pin");
  }

  /** The drawing carries the component's name and one label per port. */
  @Test
  public void theBoxIsLabelled() {
    final var layout = layoutOf(1, 1, 1, 1);
    final var shapes = PcompAppearance.build(layout, pinsFor(layout));

    final var texts =
        shapes.stream().filter(s -> s instanceof com.cburch.draw.shapes.Text).count();
    assertEquals(1 + layout.portCount(), texts, "expected a caption plus one name per port");
    assertNotNull(anchorOf(shapes), "the appearance has no anchor");
  }
}
