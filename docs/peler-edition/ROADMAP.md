# Logisim Evolution — Peler Edition Roadmap

Fork of [logisim-evolution/logisim-evolution](https://github.com/logisim-evolution/logisim-evolution),
published at [PelerYuan/logisim-evolution-peler](https://github.com/PelerYuan/logisim-evolution-peler).
Goal: a handful of targeted workflow improvements for the circuit editor, kept additive/optional so the
upstream feature set stays fully intact and the fork stays easy to rebase against `upstream/main`.

## Guiding principle

Every change here must be **additive or preference-gated**, never a silent removal of existing behavior.
If a change trades away an existing interaction (see Feature 2), it must be a deliberate, documented
trade-off with an easy way back, not an accident.

## Peler Edition versioning (added 2026-08-07)

The built Windows package (MSI ProductVersion, MSI/zip filenames) now carries its own version
number, independent of upstream's `gradle.properties` `version` (which stays untouched at "4.2.0dev"
and must keep tracking upstream, since `BuildInfo.version` feeds `.circ` file-format compatibility
logic in `XmlReader`/`XmlWriter` — overwriting it with an arbitrary Peler version would risk silently
breaking old/new file default-attribute-value resolution). See `PELER_APP_VERSION` in
`build.gradle.kts` and the "Determine version / release tag" step in `.github/workflows/release.yml`.

Release tags going forward: `v<peler major>.<peler minor>.<peler build number>` (e.g. `v1.0.5`),
independent of upstream's own version. The historical `v1.0.0-peler.1` through `v1.0.0-peler.4` tags
predate this and are not being renamed/rebuilt.

**Multi-platform (added 2026-08-07)**: `release.yml` now mirrors `nightly.yml`'s full platform
coverage instead of Windows only -- Linux deb/rpm/snap, Linux ARM deb/rpm, macOS dmg (x86_64 +
aarch64), Windows MSI/zip, all built from a shared `prep` job (resolves the tag/version once) and
published together in one GitHub Release. Windows ARM is skipped, matching `nightly.yml`'s own
`build_windows_arm` job, which is commented out there too (no WiX on that runner). The
`PELER_APP_VERSION` override (previously wired into `createMsi`/`createExe`/
`createWindowsPortableZip` only) now also applies to `createDeb`/`createRpm`/`createApp`/
`createDmg`, so every platform's package carries the same Peler Edition version number; fixed the
same "outputFile built from the stale upstream-version-based `TARGET_FILE_PATH_BASE`" bug class in
`createRpm`/`createDmg` that `createMsi` needed fixing for earlier.

## Confirmed decisions (from user, 2026-08-07)

- **Feature 1 / Backspace**: keep its existing meaning (undo last placed component) even while
  continuous-placement mode is active. Only Enter, Esc, or right-click end continuous-placement mode.
- **Feature 2 / right-click remap**: the existing context menu (delete / attributes / rotate left /
  rotate right) moves to `Ctrl+Left-click`, which upstream already wires to `Menu Tool` by default
  (see `default.templ`) — no new code needed for that half, just changing what `Button3` maps to.

## Feature 1 — Double-click continuous placement

**Today**: single click on a palette item arms `AddTool`; after one placement it switches back to the
edit tool unless the global preference `AppPreferences.ADD_AFTER` is `unchanged`. Escape already cancels
and switches to the edit tool. Backspace already undoes the last placement.

**Change**: double-clicking a component in the palette (`ToolboxManip.doubleClicked`, currently a no-op
for plain components) arms a "sticky" placement mode on that `AddTool` instance that ignores the global
`ADD_AFTER` preference until explicitly stopped. Stop conditions: Enter, Esc, right-click. Backspace keeps
its current undo-last-placement behavior (see decision above). Plain single-click behavior is unchanged.

Key files: `src/main/java/com/cburch/logisim/tools/AddTool.java`,
`src/main/java/com/cburch/logisim/gui/main/ToolboxManip.java`.

## Feature 2 — Right-click quick rotate

**Today**: `Button3` (right-click) is mapped to `Menu Tool` by default (`default.templ`), which pops a
menu with Delete / Show Attributes / Rotate Left / Rotate Right. The mouse→tool mapping is fully
user-configurable today via Options → Mouse (`MouseOptions.java`, backed by `MouseMappings.java`), and any
`Tool` registered in a library is selectable there.

**Change**: add a new `QuickRotateTool` (rotates the component under the cursor 90° clockwise on
mousePressed, no popup) registered in `BaseLibrary` alongside the existing base tools. In this fork's
`default.templ`, rebind `Button3 → Quick Rotate Tool` and add `Ctrl+Button1 → Menu Tool` (already the
upstream default mapping, kept as-is) so the old right-click menu stays one gesture away. Ship a one-time
status-bar hint the first time a user right-clicks after this change, pointing at the new binding.

Key files: new `src/main/java/com/cburch/logisim/tools/QuickRotateTool.java`,
`src/main/java/com/cburch/logisim/std/base/BaseLibrary.java`,
`src/main/resources/resources/logisim/default.templ`.

## Feature 3 — Wire auto-snap to nearest pin (Phase 2)

**Today**: `WiringTool` only snaps to the drawing grid (`Canvas.snapToGrid`); it has no awareness of
component pin locations. Pin data is available (`Component.getEnds()`), and nearby-component lookup
already exists (`Circuit.getComponents(Location)` / `getAllContaining(Location)`).

**Change**: before falling back to grid-snap, probe for a component within a small pixel radius of the
cursor; if found and it exposes 2+ pins, choose the nearest pin using directional zoning (cursor position
relative to the pin layout — e.g. top half → top pin) and snap there instead. No hit → unchanged grid-snap
behavior. Needs a visual hit indicator and an opt-out preference, given the higher false-positive risk in
dense circuits. Deferred to Phase 2 — highest complexity of the three, touches core wiring/shortening logic.

Key files: `src/main/java/com/cburch/logisim/tools/WiringTool.java`.

**Implemented (2026-08-07)**: `WiringTool.snapToPinOrGrid` runs before every grid-snap call (mouseMoved/
mousePressed/mouseDragged/mouseReleased). Broad-phase: only components whose bounds (expanded by
`PIN_SNAP_RADIUS` = 10px, one grid unit) contain the raw cursor are considered; among their pins
(`Component.getEnds()`), the globally nearest one is picked and only accepted if it's actually within
`PIN_SNAP_RADIUS` — this is a direct generalization of the "which half is the cursor in" zoning
originally described: nearest-pin-by-distance reproduces exactly that split for the common 2-pins-on-
one-side case, and generalizes cleanly to more pins / other layouts. No hit → unchanged grid-snap,
byte-for-byte. Gated by `AppPreferences.WIRE_AUTO_SNAP` (opt-out, defaults on). Visual feedback: a small
green ring is drawn at the snapped pin (`WiringTool.draw`) whenever one is active, so a snap is never
silent. Wire endpoints themselves are not snap targets, only component pins, per the original request.

## Phasing

| Phase | Scope | Risk |
|---|---|---|
| 1 | Feature 1 + Feature 2 + Windows packaging/release pipeline (adapt upstream's existing `jpackage` + `nightly.yml` Windows job to publish a Release on this fork) | Low — isolated to `tools`/`gui.main` package, UI interaction only |
| 2 | Feature 3 (auto-snap) | Medium/High — touches core wire-drawing/shortening logic, needs careful regression testing |
| 3 | Polish: tunable snap radius, snap-hit visual feedback, i18n strings for new UI text, broader manual regression pass | Low |

## Post-Phase-1 fix — app identity (2026-08-07)

The official upstream app name (`logisim-evolution`) was still used for the package name, Windows install
path (`Program Files\logisim-evolution`), Start Menu group, and window title — this collides with an
existing official Logisim Evolution install on the same machine. Fixed by changing `rootProject.name` in
`settings.gradle.kts` to `logisim-evolution-peler`; this is the single source of truth that `build.gradle.kts`
derives `--name` (jpackage/MSI/exe), `--win-menu-group`, output artifact filenames, and `BuildInfo.name` /
`BuildInfo.displayName` (window title, see `Frame.java:600`) from, so one change covers package name,
install path, Start Menu group, and title bar together.

**Backlog** (do last, per user instruction 2026-08-07): the About dialog / credits screen (`About.java`,
`AboutCredits.java`) and CLI help banner (`Startup.java`) already pick up the new `BuildInfo.name`/
`displayName` automatically. Status as of 2026-08-07:
- ✅ `BuildInfo.url` (`gradle.properties` `url`) now points at `github.com/PelerYuan/logisim-evolution-peler`
  instead of upstream — confirmed all its usages (About credits link, `.circ`/VHDL file header comments,
  CLI banner, Help menu "website" link, exported project bundle README) are display/comment-only, no
  compatibility logic depends on it, unlike `version`.
- ✅ `AboutCredits.java` now has its own "Peler Edition" section (new `creditsRolePelerEdition`/
  `creditsPelerEditionDesc` keys, English + `zh`) right after the existing title/copyright/URL block,
  which is left untouched so upstream's own attribution stays intact.
- ⬜ App icon (`support/jpackage/windows/Logisim-evolution.ico`) is still upstream's icon — worth a
  distinct icon so the taskbar/Start Menu entry is visually distinguishable from the official install
  too. Not done: needs an actual art asset, not something worth hand-hacking a placeholder for.

## MSI upgrade support (added 2026-08-07)

Installing a newer build over an older one previously just errored "already installed" instead of
upgrading. `createMsi` now pins `--win-upgrade-uuid` to a fixed, hardcoded UUID
(`48443d3c-0700-47f9-b825-40c7118027da`) — Windows Installer's major-upgrade mechanism keys off this
UUID staying constant across releases (not off `--app-version`, which only tells it *whether* a given
build is newer). Previously this was left to jpackage's own default generation, whose determinism
across builds/name changes isn't documented — pinning it removes that uncertainty entirely.
**This value must never change** — every future release depends on it staying exactly as-is to remain
upgradeable from everything before it. One transitional gap: upgrading from `v1.0.6` or earlier (which
used jpackage's undocumented default UUID) to the first release carrying this pinned UUID may still not
be recognized as an upgrade — from that release onward, all upgrades work correctly.

## Feature 4 — Tidy Wires (Phase 4, implemented 2026-08-07, **deferred from default build 2026-08-07**)

**Status: deferred.** Shipped active in `v1.0.7`, but pulled from the default toolset the same day at
user request — it hasn't had enough hands-on mileage yet to be trusted as an on-by-default tool, and a
mis-route is the one failure mode of this feature that would actually be bad (see the correctness
invariant below; the invariant itself is believed sound and was independently reviewed, but "reviewed"
and "battle-tested across real user circuits" are different bars, and this feature only clears the
first one so far). The engine (`WireTidier.java`) and UI glue (`TidyWiresTool.java`) are untouched and
still compile — only the three registration points that expose them to the user were removed:
`BaseLibrary.java` (tool no longer in the Base palette list), `default.templ` (toolbar button removed),
`MenuProject.java` (Project-menu item, field, and dispatch branch removed). To re-enable: reverse those
three edits. Re-enabling should happen after a dedicated testing pass — at minimum a fan-out net (one
output to 3+ inputs) and an obstacle-between-two-components case, per the original test plan that was
never actually run by a human before `v1.0.7` shipped.

One known edge case from having shipped it active in `v1.0.7`: a user who customized their own toolbar/
template to include `Tidy Wires Tool` while running `v1.0.7` will have that reference silently unresolved
by `BaseLibrary.getTool()` once they upgrade past this point (expected to warn/skip on load, not crash —
not separately verified). Not fixed; deferring is itself the fix for the underlying maturity concern, and
this only affects a self-customized toolbar, not the default one.

New user request: a menu item + toolbar button that re-routes all wiring in the current circuit for
readability, without moving any components. Upstream has nothing like this.

**Correctness invariant (the one thing that must never break)**: Logisim wire connectivity is
determined *purely* by exact `Location` equality between a `Wire`'s two endpoints and other
wires'/components' endpoints (confirmed by reading `CircuitWires.connectWires`/`connectComponents` —
bundles are unioned only at `wire.e0`/`wire.e1`, nothing tests for a wire passing *through* another
point). Two routed wire segments crossing in the middle, with neither having a vertex exactly there,
are therefore **not** electrically connected — this is the normal "crossing ≠ connected" schematic
convention, not a bug to work around. The only way this feature could silently corrupt a circuit's
logic is by placing a new wire's *endpoint* exactly on a `Location` that shouldn't be part of that net.
Avoiding *visual* crossings/overlaps is purely a readability nicety, not a safety requirement.

**Net extraction**: deliberately does NOT reuse `CircuitWires`' internal `Connectivity`/`WireBundle`
machinery (that's entangled with `CircuitState`/simulation timing concerns — unnecessary coupling for
a purely geometric operation). Instead, a standalone union-find keyed by `Location`: union each
`Wire`'s two endpoints; every component's `EndData` locations (all of them, not just outputs — unlike
`CircuitWires.connectComponents`, which skips `INPUT_ONLY` ends for its own simulation-specific
reasons that don't apply here) are terminals. Each union-find group with 2+ terminal locations is a net
that needs re-wiring; groups with 0–1 terminals are skipped (nothing to connect). This intentionally
does not special-case Splitters (each port is just another `EndData` location, handled generically),
Tunnels, or pull resistors (neither has a drawable wire to begin with, so there's nothing for this
feature to touch there either way).

**Routing algorithm**, per net: (1) compute a rectilinear minimum spanning tree over the net's
terminals (Manhattan distance) as an approximation of the optimal Steiner tree — standard, tractable
heuristic; (2) for each MST edge, grid-based pathfind (BFS/A*, one grid unit = 10px cells) an orthogonal
route between the two terminals, treating other components' bounding boxes as hard obstacles and other
components' pin locations as soft-avoid (so a routed wire never visually passes exactly over an
unrelated pin, even though doing so wouldn't be electrically wrong per the invariant above — avoiding
it anyway keeps the result from being *misleading* to read); (3) if pathfinding genuinely fails to find
a route (should be rare given the search space), fall back to a direct L-shaped connection — a net must
never end up with an unconnected terminal after this runs, that would be a correctness regression, not
just an ugly one.

**Execution**: removes every `Wire` in the circuit and adds all newly-routed segments as ONE
`CircuitMutation`/`proj.doAction(...)` — a single Ctrl+Z undoes the whole re-route. Guarded by the same
"can this circuit be modified" check other tools use. Confirmed with user: whole circuit at once (no
per-selection scoping for v1), and a confirmation dialog before running (states the scope, cancelable).

**UI placement**: `MenuProject` (`gui/menu/MenuProject.java`), grouped with the existing
`analyze`/`stats` items (both are already "whole current circuit" tools) — plus a matching
toolbar button.

**Implemented (2026-08-07)**: net/route computation lives in
`src/main/java/com/cburch/logisim/circuit/WireTidier.java` (union-find net extraction, rectilinear
MST, A* grid routing with an explicit cross-net/foreign-pin bend-safety check — see its own class
Javadoc). `src/main/java/com/cburch/logisim/tools/TidyWiresTool.java` is the UI glue (confirm
dialog, `Tool` wrapper). The toolbar button did **not** end up touching `LayoutToolbarModel.java`
as originally planned above — that class only renders whatever `default.templ`'s `<toolbar>`
section lists, so adding `Tidy Wires Tool` there was sufficient on its own (same data-driven
mechanism the existing Poke/Edit/Wiring/Text tool buttons already use).

## Feature 5 — Schematic annotations (Phase 5, design 2026-08-07)

New user request, prompted by schematic-capture tutorials that recommend annotating parts as you draw.
Investigated upstream first: `Circuit.annotate()` (`circuit/Circuit.java:369`) already exists and
auto-labels components in bulk, but it's wired to the FPGA/HDL download flow only (`Download.java`,
`FpgaCommander.java`), only touches components whose factory `requiresNonZeroLabel()` (register-like
parts, not general gates), and produces HDL-variable-style names (`AND_GATE_0`), not EE-style reference
designators. There is no click-a-component-and-type-a-note interaction anywhere — manual labeling today
means opening the Attribute Table sidebar and editing the `Label` attribute row, which is the friction
being reported. Confirmed with user, in order:

1. Scope for v1: a free-text annotation you click onto a **component or a wire endpoint**, rendered
   floating above the target — a genuinely new tool/category, not a reuse or extension of the existing
   `Label` attribute (must not touch `StdAttr.LABEL` or `Circuit.annotate()`'s behavior at all).
2. **Anchored**: moving, rotating, or deleting the target must carry the annotation along / delete it
   with it — not left behind as an orphaned floating note.
3. No batch/auto-numbering (i.e. not building on `Circuit.annotate()`'s engine) for v1 — purely manual,
   one annotation at a time. Auto reference-designator numbering (`U1`, `R1`, ...) stays a backlog idea,
   revisit after the manual tool ships and gets used.

**The hard part, and why this isn't just "Text Tool but pre-positioned"**: `Component` has no
`setLocation`/`translate` — confirmed by reading `comp/Component.java`. Every move, rotate, or reshape in
this codebase (drag via `SelectTool`, `QuickRotateTool`'s rotation, `WiringTool` reshaping a wire) is
implemented as remove-old/add-new through a `ReplacementMap`, never in-place mutation. So a Java object
reference to "the component this annotation is anchored to" goes stale the instant that component is
moved — anchoring can't be "just store a reference and let it ride along" the way `Label` rides along for
free (an attribute *does* survive a move, since `ReplacementMap`-driven moves clone the old component's
`AttributeSet` onto the new instance — which is incidentally *why* `Label` visually tracks a component
today with zero extra code). Two ways to get real anchoring given that constraint:

- **(Rejected) Piggyback on each component's `AttributeSet`.** Would make anchoring free (same mechanism
  `Label` already gets), but requires adding a new attribute to every `ComponentFactory` in the standard
  library (gates, memory, wiring, I/O, ...) to have a slot for it — a broad, invasive diff across shared
  core code, and it still wouldn't cover wire endpoints (`Wire` doesn't carry a general-purpose attribute
  slot for this). Against the "additive, narrowly-scoped" principle this fork has followed so far.
- **(Chosen) Standalone `Annotation` component + a `CircuitListener` that follows replacements.** A new,
  self-contained component (sibling to `Text`, not derived from it) stores its own text and a reference to
  its anchor (component or wire). A new `CircuitListener`, registered once per `Circuit`, watches for
  `CircuitEvent.TRANSACTION_DONE` and inspects the completed transaction's `ReplacementMap`: if the old
  half of any `old → new` pair is something an `Annotation` is anchored to, the listener issues a small
  follow-up action that re-anchors (and re-positions, preserving the original offset) the `Annotation` to
  `new` — or removes the `Annotation` if the target was removed outright (`new == null`). This is a
  second, immediately-adjacent undo step right after the move/delete that triggered it, not folded into
  the same one — an accepted trade-off (one extra Ctrl+Z to also undo the follow-along), not a bug,
  because folding it into the *same* transaction would mean patching the internals of `SelectTool`,
  `WiringTool`, and every other move/delete code path individually. That's precisely the kind of broad,
  invasive surgery this fork avoids — a purely additive listener that touches zero existing tool classes
  is the same trade Logisim's own `Circuit.annotate()` already makes (it also does one `proj.doAction()`
  per labeled component instead of one atomic batch, for the same "additive over invasive" reason).

**Persistence**: `Annotation` is a real `Component` in the circuit's component list, so it saves/loads
through the existing `.circ` XML component mechanism automatically — no schema changes needed for the
annotation's own text/position. The anchor reference itself, however, is a live object link that (like
everything else keyed by object identity in this codebase) does not survive a save/reload by itself;
needs a load-time re-resolution step (match by the anchor's last-known `Location` + factory, best-effort)
documented as a known limitation until implemented and tested.

**UI placement**: new top-level library/category in the component tree (a new `Tool`/`Library`, not
folded into `BaseLibrary`, per user's explicit ask for "a new category on the left"), with its own
`AddTool`-like placement tool: click a component or a wire endpoint to drop a text-entry annotation
anchored there.

**Implemented (2026-08-07)**: `Annotation.java`/`AnnotationAttributes.java`/`AnnotationAnchorTracker.java`
(`src/main/java/com/cburch/logisim/std/annotate/`) and `AnnotateTool.java`
(`src/main/java/com/cburch/logisim/tools/`), registered as `AnnotationLibrary` in `Builtin.java`.
Shipped in `v1.0.9` with i18n coverage across all 11 languages.

**Bug found in v1.0.9, fixed same day**: the new category didn't actually appear anywhere in a
fresh project — confirmed by the user testing the real build, not caught by CI (compiling and
registering a `Library` in `Builtin.java` is necessary but not sufficient). Root cause: which
builtin libraries a *new* project actually loads is controlled by an entirely separate allowlist —
`default.templ`'s `<lib>` element list, consulted by `LibraryManager.loadLibrary` via `Library.getLibrary(String)`
matching each library's `_ID` — not by what's merely registered in `Builtin.java`. Every other
builtin library (Wiring, Gates, Base, ...) has a `<lib name="X" desc="#Y" />` entry there; `AnnotationLibrary`
never got one. Fixed by adding `<lib name="E" desc="#Annotation" />` to `default.templ`. Worth
remembering for any *future* new top-level library: registering in `Builtin.java` alone is not enough.

## Workflow for each phase

1. **Product manager** turns the phase scope above into a concrete task list with acceptance criteria.
2. **Developer** implements on a feature branch (`peler/phase-N`), one commit per logical change.
3. **Reviewer** independently audits the diff against this roadmap and the phase's acceptance criteria
   before it's merged to `main` and pushed to the `logisim-evolution-peler` fork.
