/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.annotate;

import static com.cburch.logisim.tools.Strings.S;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitEvent;
import com.cburch.logisim.circuit.CircuitListener;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.proj.Project;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import javax.swing.SwingUtilities;

/**
 * Peler Edition Feature 5: keeps a schematic {@link Annotation} anchored to whatever component or
 * wire it was placed on, across moves/rotates/reshapes/deletes.
 *
 * <p><b>Why this exists at all</b>: {@code Component} has no {@code setLocation}/{@code
 * translate} (confirmed by reading {@code comp/Component.java}). Every move, rotate, or reshape
 * in this codebase is implemented as remove-old/add-new through a {@link
 * com.cburch.logisim.circuit.ReplacementMap} -- so a plain object reference to "the component this
 * annotation is anchored to" goes stale the instant that component moves. This class is what
 * keeps the reference current: one instance per {@link Circuit} (see {@link #getOrAttach}),
 * listening for {@link CircuitEvent#TRANSACTION_DONE} and inspecting the completed transaction's
 * {@code ReplacementMap} for any tracked anchor (or tracked annotation) that was just replaced or
 * removed.
 *
 * <p><b>Undo trade-off (deliberate)</b>: a follow-up move/delete is issued as a *separate* action
 * right after the triggering one, not folded into the same undo step -- folding it in would mean
 * patching the internals of {@code SelectTool}, {@code WiringTool}, and every other move/delete
 * code path individually, exactly the kind of broad, invasive surgery this fork avoids. One extra
 * Ctrl+Z to also undo the follow-along is the accepted cost. See docs/peler-edition/ROADMAP.md,
 * Feature 5, for the full writeup (including why the "piggyback on the target's AttributeSet"
 * alternative was rejected).
 *
 * <p><b>Known limitation</b>: the anchor link is a live in-memory reference, not something that
 * round-trips through .circ XML on its own -- {@link #getOrAttach} best-effort re-resolves it by
 * matching each {@link Annotation}'s saved {@link AnnotationAttributes#ANCHOR_LOC} against
 * whatever sits at that location right now. No match -> left unanchored until the user manually
 * re-annotates it (it still renders at its last position, it just won't follow future moves).
 */
public final class AnnotationAnchorTracker implements CircuitListener {
  private static final Map<Circuit, AnnotationAnchorTracker> TRACKERS = new WeakHashMap<>();

  /**
   * Returns the tracker for {@code circuit}, creating and attaching it (plus best-effort
   * re-resolving any already-present {@link Annotation}s' anchors) the first time this is called
   * for that circuit. Idempotent. Called from {@link com.cburch.logisim.tools.AbstractAnnotateTool}
   * whenever it touches a circuit -- so a circuit that's freshly loaded but never visited with an
   * Annotate tool this session won't have a tracker attached yet, and pre-existing annotations in
   * it won't follow a move that happens before that first visit. Documented, not yet closed.
   */
  public static synchronized AnnotationAnchorTracker getOrAttach(Project proj, Circuit circuit) {
    var tracker = TRACKERS.get(circuit);
    if (tracker == null) {
      tracker = new AnnotationAnchorTracker(proj, circuit);
      TRACKERS.put(circuit, tracker);
      circuit.addCircuitListener(tracker);
      tracker.rescan();
    }
    return tracker;
  }

  private final Project proj;
  private final Circuit circuit;

  // Annotation component -> the live component/wire it's anchored to. IdentityHashMap on
  // purpose: Component doesn't define equals()/hashCode() beyond identity, and we specifically
  // want identity semantics here (two components at the same Location are NOT the same anchor).
  private final Map<Component, Component> anchorOf = new IdentityHashMap<>();

  private AnnotationAnchorTracker(Project proj, Circuit circuit) {
    this.proj = proj;
    this.circuit = circuit;
  }

  /** Registers a freshly-placed annotation's live anchor. Called by the annotate tools. */
  public void registerAnchor(Component annotation, Component anchor) {
    anchorOf.put(annotation, anchor);
  }

  /** Stops tracking an annotation (e.g. it was deleted directly by the user). */
  public void forget(Component annotation) {
    anchorOf.remove(annotation);
  }

  private void rescan() {
    for (final var comp : circuit.getNonWires()) {
      if (!(comp.getFactory() instanceof Annotation)) continue;
      if (anchorOf.containsKey(comp)) continue;
      final var attrs = (AnnotationAttributes) comp.getAttributeSet();
      final var loc = attrs.getAnchorLoc();
      if (loc == null) continue;
      final var target = findAt(loc, comp);
      if (target != null) anchorOf.put(comp, target);
    }
  }

  private Component findAt(Location loc, Component exclude) {
    // Components first, and by pin before wire: a wire endpoint that sits on a component's pin is
    // better owned by the component, for the reason spelled out on pinAnchorPoint below.
    for (final var c : circuit.getNonWires()) {
      if (c == exclude) continue;
      if (loc.equals(componentAnchorPoint(c))) return c;
      for (final var end : c.getEnds()) {
        if (loc.equals(end.getLocation())) return c;
      }
    }
    for (final var w : circuit.getWires()) {
      if (w.getEnd0().equals(loc) || w.getEnd1().equals(loc)) return w;
    }
    return null;
  }

  /**
   * Recomputes where {@code target} anchors an annotation of the given {@code kind}, given the
   * annotation's last known anchor location.
   */
  private static Location anchorLocationOf(Component target, Location lastKnown, String kind) {
    if (target instanceof Wire w) return wireAnchorPoint(w, lastKnown);
    if (AnnotationAttributes.KIND_POINT.equals(kind)) return pinAnchorPoint(target, lastKnown);
    return componentAnchorPoint(target);
  }

  /**
   * The anchor point a non-wire component's annotation is measured from: its bounding box's
   * top-center, NOT {@link Component#getLocation()} -- that's often a pin (e.g. a gate's output,
   * off to one side), which is why annotations used to land beside a component instead of
   * directly above it. Shared with {@link com.cburch.logisim.tools.AnnotateComponentTool} so a
   * freshly-placed annotation's anchor and this tracker's follow-along recomputation always agree
   * on the same point.
   */
  public static Location componentAnchorPoint(Component target) {
    final var bounds = target.getBounds();
    return Location.create(bounds.getCenterX(), bounds.getY(), false);
  }

  /**
   * The anchor point a wire annotation is measured from: whichever endpoint is closer to {@code
   * preferNear} (the original click, or -- when re-resolving after the wire itself was replaced
   * -- the annotation's last-known anchor location). Shared with {@link
   * com.cburch.logisim.tools.AnnotateWireTool}.
   */
  public static Location wireAnchorPoint(Wire w, Location preferNear) {
    return distSq(w.getEnd0(), preferNear) <= distSq(w.getEnd1(), preferNear) ? w.getEnd0() : w.getEnd1();
  }

  /**
   * The anchor point for a note pinned to one specific point on a non-wire component: whichever of
   * that component's pins is nearest {@code preferNear}.
   *
   * <p>This exists because anchoring an endpoint note to the *wire* is not durable. Dragging a
   * component makes Logisim re-route every wire attached to it, and a re-route is not a tidy
   * one-old-wire-to-one-new-wire swap -- a single run can be torn up and rebuilt as several
   * segments with different endpoints, so a note chasing "the wire it was on" ends up stranded
   * mid-canvas. The component at that junction, by contrast, survives a move as exactly one
   * identifiable replacement whose pins the factory recomputes for us -- which also means this
   * keeps working through rotation, where the pin moves somewhere a pure translation would never
   * have predicted.
   */
  public static Location pinAnchorPoint(Component target, Location preferNear) {
    Location best = null;
    var bestDist = Long.MAX_VALUE;
    for (final var end : target.getEnds()) {
      final var d = distSq(end.getLocation(), preferNear);
      if (d < bestDist) {
        bestDist = d;
        best = end.getLocation();
      }
    }
    return (best != null) ? best : componentAnchorPoint(target);
  }

  private static long distSq(Location a, Location b) {
    final var dx = (long) a.getX() - b.getX();
    final var dy = (long) a.getY() - b.getY();
    return dx * dx + dy * dy;
  }

  /**
   * Picks which of a replaced anchor's successors an annotation should follow: the one that lands
   * nearest where the anchor last was.
   *
   * <p>One removal can map to several replacements -- re-routing a wire run tears it up into new
   * segments -- and this used to just take {@code replacements.iterator().next()}, i.e. whichever
   * the set happened to yield first. That is arbitrary, and picking a segment at the far end of a
   * re-routed run is exactly how a note ended up drifting off across the canvas. Nearest-to-last-
   * known is both stable and the obvious intent.
   */
  private static Component nearestReplacement(
      Collection<Component> replacements, Location lastKnown, String kind) {
    Component best = null;
    var bestDist = Long.MAX_VALUE;
    for (final var candidate : replacements) {
      if (lastKnown == null) return candidate;
      final var d = distSq(anchorLocationOf(candidate, lastKnown, kind), lastKnown);
      if (d < bestDist) {
        bestDist = d;
        best = candidate;
      }
    }
    return (best != null) ? best : replacements.iterator().next();
  }

  @Override
  public void circuitChanged(CircuitEvent event) {
    if (event.getCircuit() != circuit) return;
    if (event.getAction() != CircuitEvent.TRANSACTION_DONE) return;
    if (anchorOf.isEmpty()) return;
    final var repl = event.getResult().getReplacementMap(circuit);
    if (repl == null || repl.isEmpty()) return;

    for (final var removed : repl.getRemovals()) {
      // Case 1: the removed component was itself a tracked annotation (e.g. the user dragged the
      // note, or Undo/Redo touched it). Migrate the tracking entry to whatever replaced it, or
      // drop it if it was deleted outright -- either way, its anchor is unaffected.
      if (anchorOf.containsKey(removed)) {
        final var anchor = anchorOf.remove(removed);
        final var replacements = repl.getReplacementsFor(removed);
        if (replacements != null && !replacements.isEmpty()) {
          anchorOf.put(replacements.iterator().next(), anchor);
        }
      }

      // Case 2: the removed component was an anchor that one or more annotations are tracking.
      // Snapshot first -- we mutate anchorOf while iterating.
      for (final var entry : new HashMap<>(anchorOf).entrySet()) {
        if (entry.getValue() != removed) continue;
        final var annotation = entry.getKey();
        final var replacements = repl.getReplacementsFor(removed);
        if (replacements == null || replacements.isEmpty()) {
          anchorOf.remove(annotation);
          scheduleRemoveAnnotation(annotation);
        } else {
          final var attrs = (AnnotationAttributes) annotation.getAttributeSet();
          final var newAnchor = nearestReplacement(replacements, attrs.getAnchorLoc(), attrs.getAnchorKind());
          anchorOf.put(annotation, newAnchor);
          scheduleFollowMove(annotation, newAnchor);
        }
      }
    }
  }

  // Deferred via invokeLater, not called synchronously from circuitChanged: circuitChanged fires
  // from inside the triggering action's own proj.doAction() call, and calling proj.doAction()
  // again reentrantly from there risks corrupting the undo history / firing a stale duplicate
  // event -- same reentrancy hazard TidyWiresTool.confirmAndTidy already had to work around with
  // SwingUtilities.invokeLater (see its own comment, and Project.java:611-640).
  private void scheduleFollowMove(Component annotation, Component newAnchor) {
    SwingUtilities.invokeLater(
        () -> {
          if (!circuit.getNonWires().contains(annotation)) return; // already gone by now
          final var attrs = (AnnotationAttributes) annotation.getAttributeSet();
          final var oldAnchorLoc = attrs.getAnchorLoc();
          if (oldAnchorLoc == null) return;
          final var newAnchorLoc = anchorLocationOf(newAnchor, oldAnchorLoc, attrs.getAnchorKind());
          final var dx = newAnchorLoc.getX() - oldAnchorLoc.getX();
          final var dy = newAnchorLoc.getY() - oldAnchorLoc.getY();
          if (dx == 0 && dy == 0) return; // anchor didn't actually move

          final var newLoc = annotation.getLocation().translate(dx, dy);
          final var copy = (AttributeSet) annotation.getAttributeSet().clone();
          copy.setValue(AnnotationAttributes.ANCHOR_LOC, newAnchorLoc);
          final var replacement = Annotation.FACTORY.createComponent(newLoc, copy);

          final var xn = new CircuitMutation(circuit);
          xn.replace(annotation, replacement);
          proj.doAction(xn.toAction(S.getter("annotationFollowAction")));

          anchorOf.remove(annotation);
          anchorOf.put(replacement, newAnchor);
        });
  }

  private void scheduleRemoveAnnotation(Component annotation) {
    SwingUtilities.invokeLater(
        () -> {
          if (!circuit.getNonWires().contains(annotation)) return; // already gone by now
          final var xn = new CircuitMutation(circuit);
          xn.remove(annotation);
          proj.doAction(xn.toAction(S.getter("removeComponentAction", Annotation.FACTORY.getDisplayGetter())));
        });
  }
}
