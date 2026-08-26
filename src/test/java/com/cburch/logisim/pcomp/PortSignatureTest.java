/*
 * Logisim-evolution - digital logic design tool and simulator
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.pcomp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Peler Edition. What counts as the same component, and what a change to one looks like.
 *
 * <p>This is the rule that stops a published component being edited under the wires of the projects
 * already using it, so the tests here are about the ways a change can hide. A reordered port list is
 * not a change and must not be reported as one, or every save becomes a new version. A port that
 * kept its name and its place but changed direction or width <em>is</em> a change, and the one
 * hardest to see: the wire stays exactly where it was and starts carrying something else.
 */
class PortSignatureTest {

  private static final List<PortPlacement> TOP_PORTS =
      List.of(
          PcompLayouts.nth("A", PortSide.LEFT, 0),
          PcompLayouts.nth("B", PortSide.LEFT, 1),
          PcompLayouts.nth("S", PortSide.RIGHT, 0),
          PcompLayouts.nth("C", PortSide.RIGHT, 1));

  private static final PortLayout TOP_LAYOUT = PcompLayouts.automatic("Top", TOP_PORTS);

  private static PortLayoutDraft topDraft() throws Exception {
    return draftOf(PcompProjects.THREE_CIRCUITS);
  }

  /**
   * The same fixture with pin A described differently.
   *
   * <p>A second project rather than an edit to the first. A circuit holds its components under a
   * lock and an attribute may only be changed inside a transaction that has it, so reaching in from
   * a test throws -- and the situation being tested is two files anyway, one published and one about
   * to be, never one circuit changed underneath itself.
   */
  private static String withPinA(String attributes) {
    return PcompProjects.THREE_CIRCUITS.replace("<a name=\"label\" val=\"A\"/>", attributes);
  }

  private static PortLayoutDraft draftOf(String projectXml) throws Exception {
    final var project = PcompProjects.read(projectXml);
    return PortLayoutDraft.of(project.getCircuit("Top"), "Top", TOP_LAYOUT);
  }

  /** Direction and width come off the pins, not out of the placement list. */
  @Test
  public void theSignatureCarriesWhatTheLayoutDoesNotKnow() throws Exception {
    final var signature = PortSignature.of(topDraft());

    assertEquals(4, signature.size());
    final var first = signature.get(0);
    assertEquals("A", first.name());
    assertEquals(PortSide.LEFT, first.side());
    assertEquals(0, first.x(), "A is on the left edge, so it sits at x zero");
    assertTrue(first.input(), "A is an input pin");
    assertEquals(1, first.width());
    assertTrue(signature.stream().anyMatch(port -> port.name().equals("S") && !port.input()));
  }

  /** The order the ports were listed in is not part of the signature. */
  @Test
  public void listingThePortsInAnotherOrderIsNotAChange() throws Exception {
    final var project = PcompProjects.read(PcompProjects.THREE_CIRCUITS);
    final var top = project.getCircuit("Top");

    final var forwards = PortSignature.of(PortLayoutDraft.of(top, "Top", TOP_LAYOUT));
    final var backwards =
        PortSignature.of(
            PortLayoutDraft.of(
                top, "Top", PcompLayouts.automatic("Top", TOP_PORTS.reversed())));

    assertEquals(forwards, backwards);
    assertTrue(PortSignature.differences(forwards, backwards).isEmpty());
  }

  /**
   * A port dragged somewhere else is reported, and reported as having moved rather than replaced.
   *
   * <p>Moved on its own, with the box and the other three ports left exactly as they were. That is
   * what the layout window now makes possible, and it is what a comparison has to be able to see:
   * arranging the whole component again would move the other ports too, and every one of them would
   * come back as a change.
   */
  @Test
  public void movingAPortIsAChange() throws Exception {
    final var project = PcompProjects.read(PcompProjects.THREE_CIRCUITS);
    final var top = project.getCircuit("Top");
    final var moved =
        PcompLayouts.moving(
            TOP_LAYOUT, "B", PortSide.BOTTOM, TOP_LAYOUT.width() / 2, TOP_LAYOUT.height());

    final var before = PortSignature.of(PortLayoutDraft.of(top, "Top", TOP_LAYOUT));
    final var after = PortSignature.of(PortLayoutDraft.of(top, "Top", moved));

    assertNotEquals(before, after);
    final var changes = PortSignature.differences(before, after);
    assertEquals(1, changes.size(), "only one port moved");
    assertEquals(PortSignature.Kind.MOVED, changes.get(0).kind());
    assertEquals("B", changes.get(0).name());
    assertEquals(PortSide.LEFT, changes.get(0).was().side());
    assertEquals(PortSide.BOTTOM, changes.get(0).now().side());
  }

  /**
   * Making the box bigger is not a change to its ports.
   *
   * <p>Ports are pinned to the anchor, which is the box's top-left corner, so a box dragged out to
   * the right leaves every one of them exactly where it was. That is the whole reason the box's
   * size is not in the signature: a user tidying up a published component should not be pushed into
   * a new version that everybody already using it has to opt into.
   */
  @Test
  public void resizingTheBoxIsNotAChange() throws Exception {
    final var project = PcompProjects.read(PcompProjects.THREE_CIRCUITS);
    final var top = project.getCircuit("Top");
    final var roomier =
        new PortLayout(
            "Top",
            TOP_LAYOUT.width() + 60,
            TOP_LAYOUT.height() + 40,
            TOP_LAYOUT.captionX(),
            TOP_LAYOUT.captionY() + 20,
            TOP_LAYOUT.placements());

    final var before = PortSignature.of(PortLayoutDraft.of(top, "Top", TOP_LAYOUT));
    final var after = PortSignature.of(PortLayoutDraft.of(top, "Top", roomier));

    assertEquals(before, after);
    assertTrue(PortSignature.differences(before, after).isEmpty());
  }

  /**
   * A port that stayed where it was but changed width is still a change.
   *
   * <p>The one this rule exists for. Nothing moves, so nothing looks wrong, and every wire that was
   * attached is still attached -- to a port that now carries eight bits where it carried one.
   */
  @Test
  public void changingAPinsWidthIsAChangeEvenThoughNothingMoved() throws Exception {
    final var before = PortSignature.of(topDraft());
    final var after = PortSignature.of(draftOf(withPinA("""
        <a name="label" val="A"/>
        <a name="width" val="8"/>""")));

    final var changes = PortSignature.differences(before, after);
    assertEquals(1, changes.size());
    assertEquals(PortSignature.Kind.WIDTH, changes.get(0).kind());
    assertEquals(1, changes.get(0).was().width());
    assertEquals(8, changes.get(0).now().width());
  }

  /** Turning an input into an output is a change, for the same reason a width change is. */
  @Test
  public void changingAPinsDirectionIsAChange() throws Exception {
    final var before = PortSignature.of(topDraft());
    final var after =
        PortSignature.of(
            draftOf(
                PcompProjects.THREE_CIRCUITS.replace(
                    "<a name=\"label\" val=\"B\"/>",
                    "<a name=\"label\" val=\"B\"/><a name=\"type\" val=\"output\"/>")));

    final var changes = PortSignature.differences(before, after);
    assertEquals(1, changes.size());
    assertEquals(PortSignature.Kind.DIRECTION, changes.get(0).kind());
    assertEquals("B", changes.get(0).name());
  }

  /** A port present in only one of the two is added or removed, whichever side it is on. */
  @Test
  public void portsInOnlyOneOfThemAreAddedOrRemoved() throws Exception {
    final var project = PcompProjects.read(PcompProjects.THREE_CIRCUITS);
    final var top = project.getCircuit("Top");
    final var full = PortSignature.of(PortLayoutDraft.of(top, "Top", TOP_LAYOUT));
    final var fewer = full.subList(0, 3);

    final var removed = PortSignature.differences(full, fewer);
    assertEquals(1, removed.size());
    assertEquals(PortSignature.Kind.REMOVED, removed.get(0).kind());

    final var added = PortSignature.differences(fewer, full);
    assertEquals(1, added.size());
    assertEquals(PortSignature.Kind.ADDED, added.get(0).kind());
  }
}
