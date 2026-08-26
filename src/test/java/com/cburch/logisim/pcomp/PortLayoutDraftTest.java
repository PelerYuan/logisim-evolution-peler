/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import static com.cburch.logisim.pcomp.PcompLayouts.automatic;
import static com.cburch.logisim.pcomp.PcompLayouts.nth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * sharing one, a port halfway across the box -- so the rules that decide when the user may press
 * Save are checked here rather than by clicking, and so is everything a drag can do.
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

  private static PortLayoutDraft.Entry named(PortLayoutDraft draft, String name) {
    for (final var entry : draft.all()) {
      if (entry.name().equals(name)) return entry;
    }
    return null;
  }

  /**
   * A circuit opens with its inputs on the left and its outputs on the right, top to bottom, spaced
   * out by the automatic layout. The side comes from each pin's <em>type</em>, not its facing: an
   * output pin placed the ordinary way still faces east, so facing would open every circuit with
   * all of its ports stacked on the left.
   */
  @Test
  public void inputsStartOnTheLeftAndOutputsOnTheRight() throws Exception {
    final var draft = topOf(PcompProjects.THREE_CIRCUITS, "Top");

    assertEquals(List.of("A", "B"), namesOn(draft, PortSide.LEFT));
    assertEquals(List.of("S", "C"), namesOn(draft, PortSide.RIGHT));
    assertEquals(List.of(), namesOn(draft, PortSide.TOP));
    assertEquals(List.of(), namesOn(draft, PortSide.BOTTOM));
    assertEquals("Top", draft.caption());
    assertEquals(automatic("Top", nth("A", PortSide.LEFT, 0), nth("B", PortSide.LEFT, 1),
        nth("S", PortSide.RIGHT, 0), nth("C", PortSide.RIGHT, 1)), draft.layout());
  }

  /** A port dragged across the box changes the edge it belongs to, and its name turns round. */
  @Test
  public void draggingAPortToAnotherEdgeMovesItThere() throws Exception {
    final var draft = topOf(PcompProjects.THREE_CIRCUITS, "Top");
    final var b = named(draft, "B");

    draft.moveTo(b, draft.width() / 2, draft.height());

    assertEquals(List.of("A"), namesOn(draft, PortSide.LEFT));
    assertEquals(List.of("B"), namesOn(draft, PortSide.BOTTOM));
    assertEquals(PortSide.BOTTOM, b.side());
    assertEquals(draft.height(), b.atY());
    assertEquals(4, draft.portCount(), "the port should have moved, not been copied");
  }

  /** A port is snapped to the grid on the way down, because a port off it takes no wires. */
  @Test
  public void portsAreSnappedToTheGrid() throws Exception {
    final var draft = topOf(PcompProjects.THREE_CIRCUITS, "Top");
    final var a = named(draft, "A");

    draft.moveTo(a, 3, 24);

    assertEquals(0, a.atX());
    assertEquals(20, a.atY());
  }

  /** A drag past the edge of the box stops at it rather than leaving the port floating beside it. */
  @Test
  public void dragsPastTheBoxAreClampedToIt() throws Exception {
    final var draft = topOf(PcompProjects.THREE_CIRCUITS, "Top");
    final var a = named(draft, "A");

    draft.moveTo(a, 5000, -40);

    assertEquals(draft.width(), a.atX());
    assertEquals(0, a.atY());
  }

  /**
   * Dragging an edge takes the ports sitting on it along, which is what makes resizing feel like
   * widening the component rather than leaving its outputs behind. The caption keeps its place in
   * the box proportionally, so a centred name stays centred.
   */
  @Test
  public void resizingCarriesThePortsOnTheEdgesThatMoved() throws Exception {
    final var draft = topOf(PcompProjects.THREE_CIRCUITS, "Top");
    final var right = draft.width();
    final var s = named(draft, "S");
    final var a = named(draft, "A");
    final var wasCentred = draft.captionX() * 2 == draft.width();

    draft.resize(right + 100, draft.height());

    assertEquals(right + 100, draft.width());
    assertEquals(right + 100, s.atX(), "the output was left behind by the edge it was on");
    assertEquals(0, a.atX(), "the input moved although its edge did not");
    assertTrue(wasCentred, "the automatic layout should have centred the caption");
    assertEquals(draft.width() / 2, draft.captionX(), "the caption did not stay centred");
  }

  /** The box has a floor, so an over-enthusiastic drag cannot collapse it to nothing. */
  @Test
  public void theBoxCannotBeDraggedAwayEntirely() throws Exception {
    final var draft = topOf(PcompProjects.THREE_CIRCUITS, "Top");

    draft.resize(0, -50);

    assertEquals(PortLayoutDraft.MIN_SIZE, draft.width());
    assertEquals(PortLayoutDraft.MIN_SIZE, draft.height());
  }

  /**
   * The arrange button puts the spacing back without shuffling the ports. A user who has put four
   * inputs in the order they want and then finds them crowded wants the crowding fixed, not their
   * order thrown away.
   */
  @Test
  public void arrangingFixesTheSpacingAndKeepsTheOrder() throws Exception {
    final var draft = topOf(PcompProjects.THREE_CIRCUITS, "Top");
    final var before = draft.layout();
    draft.moveTo(named(draft, "A"), 0, draft.height());
    draft.moveTo(named(draft, "B"), 0, 10);
    draft.resize(400, 300);
    assertNotEquals(before, draft.layout());

    draft.arrange();

    assertEquals(List.of("B", "A"), namesOn(draft, PortSide.LEFT), "the order was not kept");
    assertEquals(
        automatic("Top", nth("B", PortSide.LEFT, 0), nth("A", PortSide.LEFT, 1),
            nth("S", PortSide.RIGHT, 0), nth("C", PortSide.RIGHT, 1)),
        draft.layout());
  }

  /**
   * Retyping the component's name makes the box big enough for it, until the user has dragged
   * something. After that the box is theirs and only the arrange button moves it.
   */
  @Test
  public void theNameWidensTheBoxOnlyUntilSomethingIsDragged() throws Exception {
    final var draft = topOf(PcompProjects.THREE_CIRCUITS, "Top");
    final var opened = draft.width();

    draft.setCaption("A very long component name");
    final var widened = draft.width();
    assertTrue(widened > opened, "the box did not grow for the longer name");

    draft.moveTo(named(draft, "A"), 0, 20);
    draft.setCaption("Tiny");

    assertEquals(widened, draft.width(), "the box moved after the user had laid it out");
  }

  /** Nothing is wrong with the circuit the fixture describes, so Save should be reachable. */
  @Test
  public void theFullyNamedDraftHasNoProblem() throws Exception {
    final var draft = topOf(PcompProjects.THREE_CIRCUITS, "Top");

    assertNull(draft.problem());
    assertEquals(4, draft.placements().size());
    assertNotNull(draft.layout());
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
   * Two ports may not sit in the same place either. That one is reachable by dragging, which is why
   * it is a problem the window reports rather than a move it refuses: a port has to be allowed to
   * pass over another on its way somewhere.
   */
  @Test
  public void twoPortsMayNotShareOnePlace() throws Exception {
    final var draft = topOf(PcompProjects.THREE_CIRCUITS, "Top");
    final var a = named(draft, "A");
    final var b = named(draft, "B");

    draft.moveTo(b, a.atX(), a.atY());

    assertEquals(PortLayoutDraft.Problem.OVERLAP, draft.problem());
    draft.arrange();
    assertNull(draft.problem(), "arranging should have pulled them apart again");
  }

  /** The drawing survives a draft that could not be saved, because that is most of the time. */
  @Test
  public void theDraftIsDrawableEvenWhileItIsUnsaveable() throws Exception {
    final var draft = topOf(PcompProjects.UNNAMED_PINS, "Bare");

    assertEquals(2, draft.all().size());
    assertTrue(draft.width() > 0 && draft.height() > 0);
    for (final var entry : draft.all()) {
      assertTrue(entry.atX() >= 0 && entry.atX() <= draft.width());
      assertTrue(entry.atY() >= 0 && entry.atY() <= draft.height());
    }
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
   * A published component comes back exactly as published -- box, caption and every port -- not as
   * the pin types would have arranged it.
   *
   * <p>This is what makes editing a component safe to open. Re-deriving the layout would propose
   * undoing the user's own work before they had touched anything, and since a changed layout is
   * what forces a new version, they would be pushed into publishing one just for opening the file.
   */
  @Test
  public void publishedLayoutsComeBackAsPublished() throws Exception {
    final var circuit = PcompProjects.read(PcompProjects.THREE_CIRCUITS).getCircuit("Top");
    final var published =
        new PortLayout(
            "Adder",
            200,
            80,
            100,
            40,
            List.of(
                new PortPlacement("S", PortSide.TOP, 60, 0),
                new PortPlacement("C", PortSide.LEFT, 0, 20),
                new PortPlacement("A", PortSide.LEFT, 0, 60),
                new PortPlacement("B", PortSide.BOTTOM, 140, 80)));

    final var draft = PortLayoutDraft.of(circuit, "Adder", published);

    assertEquals(published, draft.layout(), "the published box did not come back");
    assertEquals(List.of("C", "A"), namesOn(draft, PortSide.LEFT));
    assertEquals(List.of("S"), namesOn(draft, PortSide.TOP));
    assertEquals(List.of("B"), namesOn(draft, PortSide.BOTTOM));
    assertEquals(List.of(), namesOn(draft, PortSide.RIGHT));
  }

  /** The caption is the component's name, which is not the name of the circuit inside the file. */
  @Test
  public void theCaptionIsTheNameItWasGiven() throws Exception {
    final var circuit = PcompProjects.read(PcompProjects.THREE_CIRCUITS).getCircuit("Top");
    final var published =
        automatic(
            "Adder",
            nth("A", PortSide.LEFT, 0),
            nth("B", PortSide.LEFT, 1),
            nth("S", PortSide.RIGHT, 0),
            nth("C", PortSide.RIGHT, 1));

    final var draft = PortLayoutDraft.of(circuit, "Adder", published);

    assertEquals("Adder", draft.caption());
    assertEquals(published, draft.layout(), "naming it should not have rearranged it");
  }

  /**
   * A pin added since the component was published is parked on the default edge for its type, past
   * whatever is already there.
   *
   * <p>It has to land somewhere, and this is where a new pin would have gone anyway. Putting it
   * last rather than first keeps every published port exactly where it was published, so the only
   * difference the signature reports is the port that is genuinely new.
   */
  @Test
  public void pinsThePublishedLayoutNeverMentionedGoLast() throws Exception {
    final var circuit = PcompProjects.read(PcompProjects.THREE_CIRCUITS).getCircuit("Top");
    final var published =
        automatic(
            "Adder",
            nth("A", PortSide.LEFT, 0),
            nth("S", PortSide.RIGHT, 0),
            nth("C", PortSide.RIGHT, 1));

    final var draft = PortLayoutDraft.of(circuit, "Adder", published);

    assertEquals(List.of("A", "B"), namesOn(draft, PortSide.LEFT), "B is the new one");
    assertEquals(List.of("S", "C"), namesOn(draft, PortSide.RIGHT));
    assertNull(draft.problem(), "the new pin landed on top of something");
    for (final var name : List.of("A", "S", "C")) {
      assertEquals(
          published.offsetOf(name), draft.layout().offsetOf(name), name + " was moved by the newcomer");
    }
  }
}
