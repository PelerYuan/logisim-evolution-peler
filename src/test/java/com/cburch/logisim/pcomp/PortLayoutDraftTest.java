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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Peler Edition. The layout while it is still being edited.
 *
 * <p>{@link PortLayout} is the finished thing and refuses anything less. This is the state the
 * confirmation window is actually in most of the time -- a port with no name yet, two ports briefly
 * sharing one, a side being emptied by a drag -- so the rules that decide when the user may press
 * Save are checked here rather than by clicking.
 */
class PortLayoutDraftTest {

  private static PortLayoutDraft topOf(String xml, String circuit) throws Exception {
    return PortLayoutDraft.of(PcompProjects.read(xml).getCircuit(circuit));
  }

  private static List<String> namesOn(PortLayoutDraft draft, PortSide side) {
    final var names = new ArrayList<String>();
    for (final var entry : draft.on(side)) names.add(entry.name());
    return names;
  }

  /**
   * A circuit opens with its inputs on the left and its outputs on the right, top to bottom. The
   * side comes from each pin's <em>type</em>, not its facing: an output pin placed the ordinary way
   * still faces east, so facing would open every circuit with all of its ports stacked on the left.
   */
  @Test
  public void inputsStartOnTheLeftAndOutputsOnTheRight() throws Exception {
    final var draft = topOf(PcompProjects.THREE_CIRCUITS, "Top");

    assertEquals(List.of("A", "B"), namesOn(draft, PortSide.LEFT));
    assertEquals(List.of("S", "C"), namesOn(draft, PortSide.RIGHT));
    assertEquals(List.of(), namesOn(draft, PortSide.TOP));
    assertEquals(List.of(), namesOn(draft, PortSide.BOTTOM));
    assertEquals("Top", draft.caption());
  }

  /** A port dragged to another side leaves the one it was on. */
  @Test
  public void movingAPortTakesItOffTheSideItWasOn() throws Exception {
    final var draft = topOf(PcompProjects.THREE_CIRCUITS, "Top");
    final var b = draft.on(PortSide.LEFT).get(1);

    draft.moveTo(b, PortSide.BOTTOM, 0);

    assertEquals(List.of("A"), namesOn(draft, PortSide.LEFT));
    assertEquals(List.of("B"), namesOn(draft, PortSide.BOTTOM));
    assertEquals(PortSide.BOTTOM, draft.sideOf(b));
    assertEquals(4, draft.portCount(), "the port should have moved, not been copied");
  }

  /** A drop past the end of a side lands at the end rather than throwing. */
  @Test
  public void slotsBeyondTheEndClampToIt() throws Exception {
    final var draft = topOf(PcompProjects.THREE_CIRCUITS, "Top");
    final var a = draft.on(PortSide.LEFT).get(0);

    draft.moveTo(a, PortSide.RIGHT, 99);

    assertEquals(List.of("S", "C", "A"), namesOn(draft, PortSide.RIGHT));
  }

  /** Reordering within one side, which is the other half of what a drag can do. */
  @Test
  public void portsCanBeMovedUpTheSideTheyAreAlreadyOn() throws Exception {
    final var draft = topOf(PcompProjects.THREE_CIRCUITS, "Top");
    final var c = draft.on(PortSide.RIGHT).get(1);

    draft.moveTo(c, PortSide.RIGHT, 0);

    assertEquals(List.of("C", "S"), namesOn(draft, PortSide.RIGHT));
  }

  /** Nothing is wrong with the circuit the fixture describes, so Save should be reachable. */
  @Test
  public void theFullyNamedDraftHasNoProblem() throws Exception {
    final var draft = topOf(PcompProjects.THREE_CIRCUITS, "Top");

    assertNull(draft.problem());
    assertEquals(4, draft.placements().size());
    assertNotNull(draft.previewLayout());
  }

  /** The rule the user asked for: no name, no save. */
  @Test
  public void anUnnamedPortStopsTheSave() throws Exception {
    final var draft = topOf(PcompProjects.UNNAMED_PINS, "Bare");

    assertEquals(PortLayoutDraft.Problem.UNNAMED, draft.problem());

    draft.rename(draft.on(PortSide.LEFT).get(0), "In");
    assertEquals(PortLayoutDraft.Problem.UNNAMED, draft.problem(), "one of two is not all of them");

    draft.rename(draft.on(PortSide.RIGHT).get(0), "Out");
    assertNull(draft.problem());
  }

  /** Whitespace is not a name. */
  @Test
  public void namesOfNothingButSpacesAreNoNames() throws Exception {
    final var draft = topOf(PcompProjects.THREE_CIRCUITS, "Top");

    draft.rename(draft.on(PortSide.LEFT).get(0), "   ");

    assertEquals(PortLayoutDraft.Problem.UNNAMED, draft.problem());
  }

  /**
   * Two ports may not share a name. The appearance binds a port to the pin carrying its name, and
   * a name that answers to two pins has no such binding.
   */
  @Test
  public void twoPortsMayNotShareOneName() throws Exception {
    final var draft = topOf(PcompProjects.THREE_CIRCUITS, "Top");

    draft.rename(draft.on(PortSide.RIGHT).get(0), "A");

    assertEquals(PortLayoutDraft.Problem.DUPLICATE_NAME, draft.problem());
  }

  /**
   * The preview draws even while the draft is unsaveable, which is most of the time the window is
   * open. Blank and repeated names are stood in for rather than crashing the panel.
   */
  @Test
  public void thePreviewSurvivesNamesThatAreNotGoodEnoughToSave() throws Exception {
    final var draft = topOf(PcompProjects.UNNAMED_PINS, "Bare");

    final var layout = draft.previewLayout();

    assertNotNull(layout);
    assertEquals(2, layout.portCount());
    for (final var entry : draft.on(PortSide.LEFT)) {
      assertNotNull(layout.offsetOf(draft.previewNameOf(entry)));
    }
    for (final var entry : draft.on(PortSide.RIGHT)) {
      assertNotNull(layout.offsetOf(draft.previewNameOf(entry)));
    }
  }

  /** Two ports called the same thing still draw, in different places. */
  @Test
  public void thePreviewSeparatesTwoPortsWithOneName() throws Exception {
    final var draft = topOf(PcompProjects.THREE_CIRCUITS, "Top");
    draft.rename(draft.on(PortSide.RIGHT).get(0), "A");

    final var layout = draft.previewLayout();
    final var left = draft.on(PortSide.LEFT).get(0);
    final var right = draft.on(PortSide.RIGHT).get(0);

    assertEquals(4, layout.portCount());
    assertNotNull(layout.offsetOf(draft.previewNameOf(left)));
    assertNotNull(layout.offsetOf(draft.previewNameOf(right)));
    assertTrue(
        !draft.previewNameOf(left).equals(draft.previewNameOf(right)),
        "the two ports should not be drawn on top of each other");
  }

  /** The pins the names point at, which is what binds the drawing to the circuit. */
  @Test
  public void everyPortStillKnowsItsPin() throws Exception {
    final var draft = topOf(PcompProjects.THREE_CIRCUITS, "Top");

    final var pins = draft.pinsByName();

    assertEquals(4, pins.size());
    for (final var placement : draft.placements()) {
      assertNotNull(pins.get(placement.name()), placement.name() + " lost its pin");
    }
  }

  /** Which way a port points, so the window can say so and the drawing can colour it. */
  @Test
  public void everyPortKnowsWhetherItIsAnInput() throws Exception {
    final var draft = topOf(PcompProjects.THREE_CIRCUITS, "Top");

    assertTrue(draft.on(PortSide.LEFT).get(0).isInput());
    assertTrue(!draft.on(PortSide.RIGHT).get(0).isInput());
  }

  /**
   * A published component comes back with its ports where they were published, not where the pin
   * types would have put them.
   *
   * <p>This is what makes editing a component safe to open. Re-deriving the sides would propose
   * undoing the user's own layout before they had touched anything, and since a changed layout is
   * what forces a new version, they would be pushed into publishing one just for opening the file.
   */
  @Test
  public void publishedLayoutsComeBackAsPublished() throws Exception {
    final var circuit = PcompProjects.read(PcompProjects.THREE_CIRCUITS).getCircuit("Top");
    final var published =
        List.of(
            new PortPlacement("S", PortSide.TOP, 0),
            new PortPlacement("C", PortSide.LEFT, 0),
            new PortPlacement("A", PortSide.LEFT, 1),
            new PortPlacement("B", PortSide.BOTTOM, 0));

    final var draft = PortLayoutDraft.of(circuit, "Adder", published);

    assertEquals(List.of("C", "A"), namesOn(draft, PortSide.LEFT));
    assertEquals(List.of("S"), namesOn(draft, PortSide.TOP));
    assertEquals(List.of("B"), namesOn(draft, PortSide.BOTTOM));
    assertEquals(List.of(), namesOn(draft, PortSide.RIGHT));
  }

  /** The caption is the component's name, which is not the name of the circuit inside the file. */
  @Test
  public void theCaptionIsTheNameItWasGiven() throws Exception {
    final var circuit = PcompProjects.read(PcompProjects.THREE_CIRCUITS).getCircuit("Top");

    final var draft = PortLayoutDraft.of(circuit, "Adder", List.of());

    assertEquals("Adder", draft.caption());
  }

  /**
   * A pin added since the component was published lands on the default side for its type, last.
   *
   * <p>It has to land somewhere, and this is where a new pin would have gone anyway. Putting it
   * last rather than first keeps every published port at the slot it was published at, so the only
   * difference the signature reports is the port that is genuinely new.
   */
  @Test
  public void pinsThePublishedLayoutNeverMentionedGoLast() throws Exception {
    final var circuit = PcompProjects.read(PcompProjects.THREE_CIRCUITS).getCircuit("Top");
    final var published =
        List.of(
            new PortPlacement("A", PortSide.LEFT, 0),
            new PortPlacement("S", PortSide.RIGHT, 0),
            new PortPlacement("C", PortSide.RIGHT, 1));

    final var draft = PortLayoutDraft.of(circuit, "Adder", published);

    assertEquals(List.of("A", "B"), namesOn(draft, PortSide.LEFT), "B is the new one");
    assertEquals(List.of("S", "C"), namesOn(draft, PortSide.RIGHT));
  }
}
