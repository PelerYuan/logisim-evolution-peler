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

### Dev builds must outrank the current stable (2026-08-10)

The scheme above put dev at a fixed `1.0.<run_number>` and started stable at `v1.1.0`, on the
reasoning that keeping the two in separate number ranges keeps them from colliding. They do not
collide — but they are ordered, and the order came out backwards.

Every channel shares one `--win-upgrade-uuid`, so Windows Installer compares the numbers *across*
channels. Once stable v1.1.0 existed, every dev build — `1.0.32` and counting — looked older than
it, so installing a dev build over stable is a downgrade, which the MSI refuses. The dev channel is
by definition the newer code, so this made it uninstallable for anyone already on stable.

Fixed by giving dev the patch field of the *current* stable: `X.Y.<run_number>` where `X.Y` comes
from whichever release GitHub marks latest. Stable then owns the major and the minor, and the
workflow rejects a stable tag whose patch is not zero — `1.1.1` would sort below the dev builds of
`1.1.x` and could never be installed over them. The base is read from the releases API rather than
from git tags, because this repository still carries upstream's tags and `v4.1.0` is the highest.

Nothing publishes into `1.0.x` any more; `v1.0.5` through `v1.0.20` stay as historical tags.

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

**Phase 1** — Feature 1 + Feature 2 + Windows packaging/release pipeline (adapt upstream's existing
`jpackage` + `nightly.yml` Windows job to publish a Release on this fork).
*Risk: low* — isolated to the `tools`/`gui.main` packages, UI interaction only.

**Phase 2** — Feature 3 (auto-snap).
*Risk: medium/high* — touches core wire-drawing/shortening logic, needs careful regression testing.

**Phase 3** — Polish: tunable snap radius, snap-hit visual feedback, i18n strings for new UI text,
broader manual regression pass.
*Risk: low.*

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

- DONE: `BuildInfo.url` (`gradle.properties` `url`) now points at `github.com/PelerYuan/logisim-evolution-peler`
  instead of upstream — confirmed all its usages (About credits link, `.circ`/VHDL file header comments,
  CLI banner, Help menu "website" link, exported project bundle README) are display/comment-only, no
  compatibility logic depends on it, unlike `version`.
- DONE: `AboutCredits.java` now has its own "Peler Edition" section (new `creditsRolePelerEdition`/
  `creditsPelerEditionDesc` keys, English + `zh`) right after the existing title/copyright/URL block,
  which is left untouched so upstream's own attribution stays intact.
- TODO: App icon (`support/jpackage/windows/Logisim-evolution.ico`) is still upstream's icon — worth a
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

**Bug found in v1.0.10, fixed same day**: the fix above's own explanatory XML comment in
`default.templ` contained a literal `--`, which is illegal anywhere inside an XML comment body
(only legal as part of the closing `-->`). Broke parsing of the *entire* template — every project
failed to open with a `SAXParseException`, reported by the user as an empty project greeting them
on launch. Fixed by rewriting the comment to avoid `--`, and validated with a Python XML parse
check before pushing (`python3 -c "import xml.dom.minidom as m; m.parse(...)"`) — now standard
practice before touching `default.templ` again.

**Bugs found in v1.0.11 testing, fixed as v1.0.12**: two, both from the user clicking around the
real build. (1) Anchoring always favored the nearest wire endpoint over the component actually
clicked, because `findAnchorTarget` checked wire endpoints across the whole circuit *before*
checking component hit-boxes — and a component's own pins/wires always sit within the snap radius
of its body, so the wire won every time. Fixed by checking component hit-boxes first, wire
endpoints only as a fallback (later superseded entirely by the v1.0.13 tool split below, which
removes the ambiguity instead of just re-ordering it). (2) Typed annotation text was silently
lost for brand-new annotations: the original design used inline canvas caret editing
(`TextEditable`/`Caret`, mirroring `TextTool`), and its `editingStopped()` commit path did
`xn.add(comp)` where `comp`'s attributes were fixed *at creation time*, before any text was typed
— nothing ever copied the caret's live buffer back into them. Replaced the whole input mechanism
with a modal `JOptionPane` + `JTextArea` dialog (also delivering multi-line support, requested at
the same time): `getText()` is read directly on OK and set on the attributes *before* the
component is ever created, eliminating the class of bug rather than patching around it. Had to
explicitly `dialog.getRootPane().setDefaultButton(null)`, since `JOptionPane` otherwise binds
Enter to the OK button and steals every newline meant for the text area.

**Redesign after v1.0.12 testing, shipped as v1.0.13**: three more issues from continued hands-on
testing.

1. **Saving any circuit with an annotation on it failed outright** (`File Error: Annotation
   component not found`, then dropped the component from the saved XML). Root cause:
   `AnnotationLibrary.getTools()` only ever contained the bespoke `AnnotateTool`, never an
   `AddTool(Annotation.FACTORY)` — and `Library.contains(ComponentFactory)` / `Library.getTool(String)`
   (used by `XmlWriter.findLibrary` on save and `XmlReader.findTool` on load, respectively) only
   look inside `AddTool`s. So the library could never resolve its own component factory by name,
   on either the save or the load path — the save path just happened to be what the user hit
   first. `BaseLibrary` already solved this exact problem for `Text` (also placed by a bespoke
   `Tool`, not a drag-and-drop `AddTool`): override both methods with a private `AddTool` fallback
   purely so the lookup has something to find. Same fix applied here.
2. **Split `AnnotateTool` into `AnnotateComponentTool` and `AnnotateWireTool`**, per explicit user
   request ("one dedicated to marking components, one dedicated to marking wire endpoints, so
   it's easier to tell apart during actual use") — replacing the single tool's click-priority
   heuristic (component hit-box, then nearest wire endpoint) with two tools that each only ever
   look for their own kind of target. Shared logic (the edit-existing-annotation flow, the modal
   dialog, the circuit-mutation/tracker plumbing) lives in a new `AbstractAnnotateTool` base class;
   each subclass supplies its own target search, anchor-point calculation, and toolbox icon.
3. **Component annotations didn't land directly above the component.** The anchor point used to
   be `Component.getLocation()`, which for most components is a pin coordinate (e.g. a 2-input AND
   gate's location is its output pin, off to one side), not the visual center of its body — so a
   horizontally-centered note ended up centered on the wrong point. Fixed by anchoring component
   annotations to the component's bounding box top-center (`Bounds.getCenterX()`, `Bounds.getY()`)
   instead. `AnnotationAnchorTracker` exposes this as `componentAnchorPoint(Component)` (and the
   wire equivalent as `wireAnchorPoint(Wire, Location)`) so both annotate tools and the tracker's
   own follow-along recomputation agree on the same point.

**Polish pass after v1.0.13 testing, shipped as v1.0.14**: three more from hands-on use.

1. **Newlines never rendered.** The dialog accepted them (that's what it was added for in v1.0.12)
   and `StringUtil.estimateBounds` already counted them, but painting went through a single
   `GraphicsUtil.drawText` call, which bottoms out in `Graphics.drawString` — that has no notion of
   `\n` and renders an embedded newline as a missing-glyph box on one line. `Annotation.paintGhost`
   now splits on `\n` and paints line by line, unioning the per-line bounds. Lines stack *upward*
   from the component's location so the bottom line stays a fixed distance from whatever is being
   annotated: adding a line pushes the note away from the circuit rather than down into it.
   Relatedly, `configureNewInstance` no longer calls `instance.setTextField(...)`. That registered
   an `InstanceTextField` as the component's `TextEditable` feature, which handed the Text Tool an
   inline single-line caret editor for annotation text — a second way in, alongside the dialog,
   that silently could not handle the newlines the dialog exists to accept.
2. **Wire notes sat too far from their endpoint.** They reused the component tool's 30px gap,
   which exists to clear a component's *body*; a wire endpoint is a point, so a note that far away
   reads as belonging to nothing in particular. Now one grid square up and one to the side, and
   diagonal rather than straight up so the note never lies along the wire. Which side is chosen by
   leaning away from wherever the wire itself runs (`AnnotateWireTool.sideOf`), with the text
   aligned to run outward from the endpoint (`horizontalAlignFor`) so the note doesn't fold back
   across the wire it was offset away from.
3. **Clicking an already-annotated target stacked a second note on it** instead of editing the
   first, so a target could accumulate notes rendered illegibly on top of each other (visible in
   the user's screenshot). Worse, whether a click meant "edit" or "add another" depended on
   whether it happened to land on the existing note's own text bounds or on the target underneath.
   Now one note per anchor point: `AbstractAnnotateTool` looks for an annotation already anchored
   at the computed anchor location and re-opens it if found. Matched via the saved `ANCHOR_LOC`
   attribute rather than the tracker's live in-memory map, so it still holds for notes loaded from
   a `.circ` file in a later session.

## First stable release — v1.1.0 (2026-08-08)

Everything before this was a development build. All 18 earlier tags (`v1.0.0-peler.1` through
`v1.0.19`) are now marked **pre-release** on GitHub, so they no longer appear as the project's
latest release; they are kept only as history.

**Why `v1.1.0` and not `v1.0.0`.** The obvious number for a first stable release is 1.0.0, but the
development builds already consumed the whole `1.0.x` range. Publishing 1.0.0 now would leave the
first stable release sorting *below* nineteen superseded dev builds in every tag listing and
version comparison. `v1.1.0` is the lowest number that sorts above them, which makes "1.0.x were
the dev builds, 1.1.0 is the first real one" true both by convention and by ordering. Retagging is
cheap if a different scheme is preferred later.

Release contents, on top of official Logisim-evolution v4.1.0:

- Continuous placement (Feature 1), Quick Rotate (Feature 2), wire auto-snap (Feature 3), and
  schematic annotations (Feature 5). Tidy Wires (Feature 4) remains deferred and unregistered.
- Upstream's **Help > About** window is restored to upstream's own content. It had been edited to
  add a Peler Edition section, and it rendered the copyright line from `BuildInfo.name`, so it read
  `Copyright (c) 2001-2026 logisim-evolution-peler developers` — claiming a quarter-century of other
  people's work for a personal fork. `AboutCredits` now holds upstream's name and URL as explicit
  constants, independent of this fork's packaging identity.
- New **Help > About Peler's Edition** window (`AboutPelerEdition`) describing what the fork adds
  and stating plainly that it is unofficial, unaffiliated, and unsupported by upstream, with bug
  reports routed here rather than to upstream.
- Application icons carry a red **P** in the bottom-left corner, so the fork is distinguishable in
  the taskbar and Start Menu from an official install beside it. Applied to jpackage artwork for
  all three platforms and to the in-app window icons, generated from the pristine v4.1.0 artwork so
  regeneration cannot stamp a P onto a P.
- README rewritten to lead with attribution to upstream.

## Feature 6 — The fork's own file format, `.pcirc` (2026-08-09)

**The problem that forced this.** Every file this fork saved was a `.circ` that official
Logisim-evolution v4.1.0 refused to open cleanly — not only annotated ones, *every* one, including a
project with no annotations at all. The `<lib desc="#Annotation">` line comes from `default.templ`,
and `AppPreferences.REMOVE_UNUSED_LIBRARIES` defaults to `false`, so it was written into every file
whether used or not; upstream's reader has no such library and errors on it. Beyond that, upstream
regenerates a file from its in-memory model when it saves, so anything it cannot represent is
destroyed the first time it saves — silently, with no diff and no warning.

Two options were weighed with the user: (A) stay on `.circ` and make the format degrade gracefully,
or (B) take a distinct extension and add an explicit export. **B was chosen**, with the reasoning
that it also frees future features from having to survive in official Logisim. Data safety over
sharing convenience: a separate extension means upstream can only ever destroy a copy exported on
purpose, never the file being worked in.

**Native format**: `.pcirc` (`Loader.PELER_EXTENSION`). `LogisimFileFilter.accept` takes both
extensions, so official `.circ` files still open exactly as upstream reads them.

**Compat lowering**: `XmlWriter` gains a `compatMode` flag, set by `PelerCompat.isCompatTarget(destFile)`.
When on, it drops `AnnotationLibrary` from the library list, skips Peler-only tools in the mouse
mappings and toolbar (`PelerCompat.isPelerOnly` — `QuickRotateTool`, `TidyWiresTool`,
`AbstractAnnotateTool`), and rewrites each `Annotation` as an upstream `Text` component
(`fromAnnotationAsText`) carrying `text`/`font`/`color`/`halign`/`valign`. Multi-line notes are
joined by a space, since `Text` cannot hold a newline. The note's link to its component is lost —
this is documented, not hidden.

**Save As semantics**: two filters (`PELER_FILTER`, `LOGISIM_COMPAT_FILTER`). A typed extension always
wins over the selected filter (`chosenExtension`). Choosing the compatible format for a file with
annotations raises a three-option dialog (switch to `.pcirc` / keep `.circ` anyway / cancel), and the
acceptance is remembered per `LogisimFile` in a weak set so it warns once, not on every save.

**File associations**: the installers used to declare `extension=circ` and
`application/x-logisim-circuit`, byte-identical to upstream's, so whichever edition was installed
last owned every `.circ` on the machine. Now `extension=pcirc` and
`application/x-logisim-peler-circuit` across all three `support/jpackage/*/file.jpackage` files.

**Newline fix, found on the way.** Multi-line annotations lost their newlines on save. The cause was
`Attribute.toStandardString` (`data/Attribute.java:62`), which strips the entire C0 range
0x00 through 0x1F — newline included. `XmlWriter`/`XmlReader`/`Font` already had a child-text-node
path for multi-line values; it was simply unreachable. Fixed with a dedicated `ATTR_TEXT` in
`AnnotationAttributes` that strips the same range minus `0x0A`. It keeps the attribute name `"text"`,
so files saved before the fix still load. Note for whoever edits it: the escapes there are written as
regex-level `\x0A`, not Java's own backslash-u form, because the compiler's lexer expands the
latter before the regex engine ever sees it — and does so even inside a comment, which is a compile
error.

Key files: `file/PelerCompat.java` (new), `file/Loader.java`, `file/XmlWriter.java`,
`proj/ProjectActions.java`, `std/annotate/AnnotationAttributes.java`, `support/jpackage/*/file.jpackage`.

## Feature 7 — Component finder, Ctrl+F (2026-08-09)

User request: a hotkey-summoned floating search box for finding and selecting a component without
hunting through the tree. Checked upstream first — it has nothing like it; the closest thing is the
toolbox tree's own type-to-select, which only matches a prefix of a visible node. Scope confirmed
with the user as "全都收": every `Tool`, not only placeable components.

`gui/find/ToolSearch.java` indexes the whole `LogisimFile` recursively (with a visited set, since
library graphs can revisit). Each `Entry` carries the tool, its library, its display name and its
English `_ID`, so `and` finds 与门 in a Chinese interface. Scoring is subsequence matching with
bonuses for word starts, consecutive runs, exact match and prefix, a capped gap penalty, and a mild
length penalty; a display-name hit outranks an ID hit, which outranks a library-name hit.

`gui/find/FindToolDialog.java` is an undecorated modeless `JDialog` showing up to 12 results with
their real toolbox icons, painted through a `ComponentDrawContext`. Enter selects the tool ready to
place, Shift+Enter selects it in continuous-placement mode, Esc or losing focus closes it.

Registered as `AppPreferences.HOTKEY_FIND_TOOL` (Ctrl+F), which `HotkeyOptions` picks up by
reflection over `AppPreferences`' `HOTKEY_*` fields, so it appears under Preferences > Hotkeys and can
be rebound with no extra wiring. `MenuProject` carries the menu item and keeps its accelerator in
sync.

**Gotcha worth remembering**: the result renderer originally used `BoxLayout` and the first row
rendered as "NAND..." — Swing list renderers are rubber-stamped, never joined to a real hierarchy, so
`revalidate()` is a no-op and `BoxLayout` reuses cached child widths. `BorderLayout` has nothing to
cache and renders correctly.

Key files: `gui/find/ToolSearch.java`, `gui/find/FindToolDialog.java` (both new),
`prefs/AppPreferences.java`, `gui/menu/MenuProject.java`.

### Enter and Shift+Enter did nothing (found and fixed 2026-08-10)

The feature shipped with its two headline keys dead. Pressing Enter left the dialog open and armed
no tool; only the mouse double-click worked. It had been recorded as "unverified by automation",
on the theory that synthetic key events never reach the JVM — the wrong conclusion, and it kept the
bug alive. Real XTEST events do reach Swing; only `xdotool key --window <id>` is ignored.

The cause is a method-resolution trap with no compiler warning. `javax.swing.Action` has carried a
`default boolean accept(Object)` since JDK 9. `AcceptAction extends AbstractAction` inherits it, and
Java resolves an unqualified call against the *inner* class's own hierarchy first, so
`accept(sticky)` inside that action bound to `Action.accept(Object)` — boxing the flag, returning
true and never reaching `FindToolDialog.accept(boolean)`. Confirmed from the bytecode rather than by
reading: `invokestatic Boolean.valueOf` followed by `invokevirtual accept:(Ljava/lang/Object;)Z`
and a `pop`.

The same call written in the `MouseAdapter` compiled correctly, because `MouseAdapter` has no
`accept` member to shadow the enclosing one — which is exactly why double-click worked and the keys
did not, and why the difference looked like an input-layer problem.

Fixed by renaming the enclosing method to `chooseSelected(boolean)`, a name no nested `Action` can
inherit; qualifying the call would have worked too but leaves the trap for whoever adds the next
action. `FindToolDialogTest` (new) fails if a method named `accept` reappears on the class.

Verified live on the Linux VM: Enter arms the selected tool and closes; Shift+Enter arms it in
continuous mode, and three clicks placed three gates; Esc closes with the current tool untouched;
double-clicking the third result selects that row rather than the default first one.

## Feature 8 — Settings and identity isolation (2026-08-09)

Changing the language in one edition changed it in the other. `Preferences.userNodeForPackage(X.class)`
keys off the *package name*, which is identical in both editions, so both were reading and writing the
same store at `HKCU\SOFTWARE\JavaSoft\Prefs\com\cburch\logisim`.

`prefs/PelerPreferences.java` (new) moves this edition to its own node, `com/cburch/logisim-peler`.
`AppPreferences.getPrefs()` and `AssemblyWindow` both use it. Because nothing is inherited, a
first-run prompt offers to copy the official edition's settings across (detected by that node being
non-empty), and **File > Import Settings from Logisim-evolution...** does the same later. A
`pelerImportAsked` marker stops the prompt reappearing.

**Ordering constraint**: `AppPreferences` reads every stored value during class initialisation, so the
import must run before anything touches it — hence `PelerPreferences.offerImportOnFirstRun()` is the
first statement of `Main.main()`. The prompt therefore cannot use the normal string bundle either; it
reads the locale straight out of the legacy node and loads its own `ResourceBundle`. Verified live:
the prompt came up in Chinese, importing copied 51 keys, and the imported locale applied on that same
run.

Other collisions found and fixed in the same pass: the unnamed-autosave prefix and suffix (both
editions wrote `.logisim-unnamed-autosave_*`, so each offered to recover the other's crashed session,
and the naming produced `.foo.pcirc.circ.autosave`); the macOS package identifier, which jpackage
defaults to the main class name and so was identical in both (now `com.cburch.logisim.peler`); the
snap package name; and the Flatpak desktop/metainfo IDs, still named
`com.github.reds.LogisimEvolution`.

Key files: `prefs/PelerPreferences.java` (new), `prefs/AppPreferences.java`, `Main.java`,
`gui/menu/MenuFile.java`, `file/Loader.java`, `build.gradle.kts`, `snap/snapcraft.yaml`,
`support/Flatpak/`.

### FPGA workspace (2026-08-10)

The last shared path left after that pass. Upstream defaults `FPGAWorkspace` to
`~/logisim_evolution_workspace`, and both editions generated into it. That directory is not an inert
output dump: `DownloadBase.getProjDir` lays it out as `<workspace>/<project file name>/<circuit>/`
and `writeHDL` calls `cleanDirectory` on the circuit's directory before regenerating it. The same
project opened in either edition therefore resolves to the same path, and each download deletes
whatever the other edition put there.

The default is now `~/logisim_evolution_peler_workspace`, kept next to the shared one rather than
inside it, and defined in `PelerPreferences.defaultFpgaWorkspace()` so the reason sits with the rest
of the separation logic instead of inline in `AppPreferences`.

Only the *default* moves. `PrefMonitorString` writes to the store on `set()` only, so a workspace
the user picked themselves is a stored value and still wins; **FPGA > Options** changes nothing. No
migration of an existing default workspace: the fork has no users yet, and what lives there is
generated HDL and scripts that the next download rebuilds anyway.

`FPGAWorkspace` is also skipped by the settings import (`copyNode`, root level only, alongside
`pelerImportAsked`). Importing it would hand both editions the same generating-and-cleaning
directory again, which contradicts what the import dialog promises. Pointing this edition back at
the old workspace by hand still works.

`PelerPreferencesTest` (new) pins the default away from upstream's path. It deliberately does not
touch `AppPreferences`, whose static initialiser reads the real preference store.

Key files: `prefs/PelerPreferences.java`, `prefs/AppPreferences.java`,
`src/test/java/com/cburch/logisim/prefs/PelerPreferencesTest.java` (new).

## Feature 9 — Interactive HTML export (experimental, 2026-08-10)

Export a circuit as one self-contained HTML page that cannot be edited but still simulates: click an
input pin and the values propagate. Branch `peler/html-export`.

### Why not the obvious approaches

**Precomputing every state at export time** and shipping a lookup table needs no simulator in the
page and is perfectly faithful — for combinational circuits with few input bits. One 8-bit pin is
already 256 entries, two are 65536, and a single flip-flop turns the state space into (inputs x
internal state) with no bound. A digital logic course is mostly sequential circuits, so this dies on
the second circuit anyone tries.

**Compiling the Java to WebAssembly** (CheerpJ, TeaVM) would be the highest-fidelity route, but
Logisim's model classes are entangled with AWT down to component painting, so what compiles is the
whole Swing application: tens of megabytes, slow to start, and an editor — the opposite of the
requirement, which would then have to be locked back down.

### The split that makes this tractable

Geometry comes from Logisim, dynamics from the page.

Component bodies are drawn by the editor's own paint code through `TikZWriter`, which already backs
File > Export Image's SVG option, so gates and everything else look exactly as they do in the editor
with no drawing code in JavaScript. Each component is rendered through a writer of its own and its
fragment wrapped separately; grouping one shared writer's output by index into `TikZInfo.contents`
would not survive `optimize()`, which merges and reorders. `TikZInfo.buildSvgDocument` (new) returns
the document instead of writing a file.

Only what the simulation changes is drawn by the page: wires and their colours, and the three
components that show or accept a value (`Pin`, `LED`, `Probe`), which also need hit areas. The
editor's own wire colours are exported with the netlist, since they are preferences — an export from
someone who retuned them should not come out looking like a different program.

### Connectivity

`HtmlCircuitModel` derives nets itself rather than borrowing `CircuitWires`' bundle map, which is
package-private and carries much more than this needs. It reproduces the editor's rules: ports and
wire ends sharing a `Location` are one node, a wire end landing anywhere along another wire joins
it, and same-label tunnels are one node. Splitters are deliberately *not* connectivity — a splitter
maps bit ranges, which is component behaviour, so it will be exported as a component and sliced by
the runtime.

### Phase 1 scope

Combinational only: pins, constants, tunnels, probes, LEDs and the gate family. The runtime iterates
to a fixed point with a 200-round cap rather than reproducing `Propagator`'s event queue, and says
so on screen if it fails to settle. Anything outside `HtmlExporter.supportedKinds()` stops the export
and is named in a dialog — an export that silently computes the wrong answer would be worse than no
export.

Verified end to end: a two-input AND circuit exported from the running application, the netlist
checked to have the gate output and the output pin on one net, all four rows of the truth table
evaluated by running the page's own simulation code under Node, and the page itself clicked through
in Chrome — inputs toggle, wires change colour, the output follows.

### Phase 2 — the differential test (2026-08-10)

The export states the semantics of every supported component twice, in two languages, and nothing
makes the second copy follow the first. A divergence surfaces as a page that quietly computes the
wrong answer, so this was built before the component list grew rather than after.

`HtmlExportDifferentialTest` loads a fixture circuit, drives every combination of its input pins
through Logisim's own `Propagator`, exports the page, lifts the engine out of it at the
`__SIMULATION_END__` marker, runs the same sweep under Node, and compares row by row. It follows
`TtyInterface`'s headless pattern: a fresh `CircuitState.createRootState` per row, `driveInputPin`
in, `Pin.FACTORY.getValue` out. It skips itself when `node` is not on the PATH.

**Tests now run with their own preferences store.** Touching almost anything in the simulator pulls
in `AppPreferences` -- `Value`'s colours are preferences -- and a headless run resolves
`hotkeyMenuMask` to `ALT_DOWN_MASK` and persists it, which would rewrite the developer's real
shortcuts from a test run. `build.gradle.kts` now points `java.util.prefs.userRoot` at a directory
under `build/`. Only the Unix backend honours that; on Windows the registry store ignores it.

The harness was mutation-checked rather than trusted: inverting the JavaScript XOR to behave like OR
made it fail on the expected row, so it has teeth.

It caught two real faults immediately, both in hand-written fixtures rather than in the exporter,
which is itself informative -- port coordinates are not guessable. Gates whose outline carries a
curve or an inversion bubble (XOR, NOR, NAND) sit ten units wider on the input side than AND, so
wires drawn to the obvious coordinate miss and leave the circuit silently unconnected. And a doubled
hyphen inside an XML comment makes a `.circ` unparseable, the same trap `default.templ` carries a
warning about; the loader then tries to report it through a dialog and throws `HeadlessException`
instead.

### Phase 3 — splitters, and why values had to move onto bits (2026-08-10)

A splitter cannot be a component that drives its ports. It is passive and bidirectional, so such a
component has to re-drive on each round whatever it read on the last one; a driver that then changes
value collides with that stale echo and the wire goes red for a round before settling. The first
design had exactly this shape and would have failed the moment anyone clicked an input.

Nor is a splitter plain connectivity: it joins *individual bits* of two nets, not the nets. So value
identity moved off the net and onto the bit. Every net now carries a `threads` array naming the
identity of each of its bits, splitters merge those identities in the exporter, and the runtime keeps
one array indexed by thread. This is the same shape `WireBundle` uses in the editor, arrived at for
the same reason.

Also added: bit extender (zero, one, sign and input modes), power, ground, and multi-bit constants.

`splitter.circ` joins two 1-bit inputs into a 2-bit bus through a splitter and extends a third input,
so it fails if bit identity is wrong in either direction. Checked that it is not a vacuous pass: the
circuit's own truth table shows S counting 0..3 and E holding its forced high bit.

Not covered here: a pin's tristate and pull attributes, and drawing the width-mismatch error the
editor shows when two different widths meet.

### Phase 4 — sequential logic (2026-08-10)

Clock, D flip-flop and register, plus tick, run and reset controls that appear on the page only when
there is something for them to drive.

**Settling happens in two alternating phases.** Combinational logic iterates to a fixed point while
every state element holds its output still; only then do state elements look at their inputs and
decide whether an edge arrived. Latching inside the settling loop would let one clock edge be seen
several times, and a chain of flip-flops would shift more than one stage per tick. The fixture is
built to fail if that regresses: the first flip-flop feeds its own inverted output back, so it halves
the clock, and the second follows it one edge later.

**This is a delta-cycle model, not a timed one.** Logisim gives components real propagation delays.
A circuit whose behaviour depends on gate delay -- a pulse generator built from a chain of inverters
is the usual example -- will not agree with the editor, and the differential test is the thing that
will say so.

The clock phase was taken from `Clock.ClockState.updateTick` rather than reasoned out. The first
attempt used the high duration where Logisim uses the low one, which starts the clock in the
opposite phase; the differential test caught it on tick 0 of the very first sequential fixture.

`HtmlExportDifferentialTest` grew a second comparison for this: sequential fixtures cannot be swept
over their inputs, so it drives a fixed number of ticks with `toggleClocks` on the Java side and
`tick()` on the page's, and compares after every one.

Not covered: counters, T/JK/SR flip-flops, shift registers, and RAM.

### Phase 5 — display and clickable components (2026-08-10)

Seven-segment display, hex digit display, LED, RGB LED, button and DIP switch. None of these could
be drawn by hand in the page without a second, worse copy of Logisim's artwork, so none of them is:
each is rendered by Logisim's own painter once per state at export time.

**A component is driven offline to get those pictures.** `HtmlOfflineState` is an `InstanceState`
with no simulation behind it, so `propagate` can be run against port values the exporter picks. The
seven-segment display's segment map is therefore never restated anywhere; it comes out of the
component. Buttons and DIP switches, whose value is not on any port, are set through their own
`InstancePoker` at the coordinate the poker maps to that switch, so a click in the page and a click
in the editor pick the same one by construction.

**Shipping 256 pictures per display would be absurd, so states travel as differences** against the
all-zero render, in the cheapest encoding that is exact for the component: one patch per state bit
where the bits are independent (seven-segment, DIP switch), one patch per state where the shapes
stay put but recolour (hex digit, whose four bits jointly choose a glyph), and whole fragments only
where the shapes genuinely differ (a released button is a raised polygon, a pressed one a flat
rectangle). Per-bit independence is checked against real renders rather than assumed, exhaustively
where that is affordable. A seven-segment display costs about 400 bytes instead of 50 kB.

**A component that remembers anything is refused an appearance.** The encoding is looked up by the
value on the ports, which can only be right for a component that has nothing else to go on; a
flip-flop's indicator shows what it latched. The exporter renders a state cold and again after
another state has gone by, carrying the component's data across, and drops the encoding if the two
differ. Flip-flops and registers therefore keep a frozen picture rather than a confidently wrong
one, which is a phase 6 gap, not a bug.

`HtmlExportAppearanceTest` unpacks every state of every animated component and compares it against a
fresh render, and asserts the chosen encoding so a silent fall back to whole pictures is visible.
The poke components join the differential test, since a button whose polarity or a switch whose bit
order came out reversed looks perfectly reasonable on its own.

**Two real defects came out of this phase, both older than it.**

`ComponentDrawContext`'s six-argument constructor takes `printView`, not `showState`. Every export
since phase 1 passed `true`, so every component was drawn in print mode with state and colour
suppressed — which is why an LED had to be drawn by hand at all. Fixing it turned the whole page
colour-accurate, including the flip-flop indicators.

`Value.create(Value[])` indexes its array **least significant bit first**. The exporter and the
differential test both filled it the other way round. On a one-bit port that is invisible, which is
why every fixture passed; the hex digit display showed `A` for an input of `5`. Caught by reading
the page, not by a test. `splitter.circ` now takes a two-bit input pin apart, which is the only
fixture with a wide input pin, and reverting the fix makes it fail.

Also in this phase: port dots are drawn by the page and coloured live rather than baked in, and
bodies are rendered against a scratch circuit state, so an export no longer depends on what the
editor happened to be showing.

### Phase 6 — arithmetic, plexers and counters (2026-08-10)

Multiplexer, demultiplexer and decoder; adder, subtractor, negator, comparator, multiplier and
shifter; T, J-K and S-R flip-flops and the counter. Each is a transcription of the component's own
`propagate`, including the paths that only run when an input is floating, because a page that shows
a clean number where the editor shows a floating bus is lying about the circuit.

**Values are BigInt throughout.** Logisim allows buses up to 64 bits and a JavaScript number stops
being exact at 53, so an ordinary number would quietly round a wide adder.

**Fixtures connect with tunnels placed on the ports rather than with wires.** A comparator's three
outputs sit ten units apart; routing wires between them would make the fixture a test of the
routing. `arith.circ` and `plexer.circ` feed every component the same narrow inputs, so one sweep
covers all of them, and two bits is enough: the sweep is exponential in the input width and the
semantics do not get more interesting at eight.

**A flip-flop's remembered clock level starts unknown, not low.** `ClockState`'s constructor says
low, which would make a circuit whose clock is already high latch once at startup. It does not: a
fresh circuit propagates once with every wire still unset, so the level a flip-flop first sees is
unknown and no edge fires. `ffedge.circ` clocks a flip-flop from an input pin and sweeps from a
fresh state, which is the only arrangement where the difference is visible; taking the constructor
at its word fails its row 3 and nothing else here notices.

The JavaScript half of the combinational sweep now builds a fresh engine per row, matching the
fresh `CircuitState` the Java half already built. Sharing one engine across rows carried a
flip-flop's contents into the next row, which passed anyway and would have hidden exactly this.

One deliberate divergence: Logisim throws when a counter holding an undefined value is clocked,
because it works out the carry from a value it has just decided is null. The page settles on an
undefined count with no carry.

Not covered: divider, bit adder, bit finder, bit selector, priority encoder, shift register, ROM,
RAM and random. The shift register's parallel ports depend on its appearance attribute, and the
memory arrays need their contents exported and a poke interface, so both are more than a
transcription.

### Phase 7 — subcircuits (2026-08-10)

Flattened at export. The box keeps being drawn by Logisim's painter, and everything behind it is
pulled into the one netlist as components nobody draws.

**Nodes are named by scope as well as by location.** Two circuits are free to use the same
coordinates and the same tunnel names, and inside a subcircuit those mean different wires. The
union-find was keyed on `Location` alone until this phase, which was correct only because there was
nothing else to collide with. The fixture is built so that it does collide: every level uses tunnels
called a, b and c, and the instances sit on the inner circuit's own coordinates. Making the scope
constant turns row 2 of its truth table into an error on both outputs.

**A pin inside a subcircuit is not a component of the flattened design.** It is the point where a
wire crosses the boundary, so it is dropped and the two nodes are merged instead. Keeping it would
have been worse than redundant: an input pin left in place would drive its own zero onto a net the
parent is already driving.

**The port-to-pin mapping is the editor's own.** `CircuitAttributes.getPinInstances()` is the array
the editor built when it worked out where the ports go, so the two orders agree by construction
rather than by both sorting the pin list the same way.

Connectivity for the whole design, subcircuits included, is now settled before any node becomes a
net: two nodes that a later merge would have joined would otherwise already be two nets with no way
back. That is why the model has two passes.

The menu's refusal walks into subcircuits too, since their contents end up in the page just the
same.

The fixture is a full adder made of two half adders, wrapped again in main, so the nesting is two
levels and one circuit is used twice at each level.

### Phase 8 — the page itself (2026-08-10)

**Pins and probes are drawn by Logisim too**, wherever their value has few enough states to render
one picture each, which covers every pin narrow enough to read at a glance. An input pin is set
through `Pin.FACTORY.driveInputPin` for the same reason a switch is set through its poker: its value
is its own, not something arriving on a wire. A pin too wide for that keeps the page's own value
box, which is legible rather than faithful, and the encoder now refuses a per-state table over four
thousand changes so one wide component cannot cost a hundred kilobytes.

**Values are written the way the editor writes them**, in the component's own radix, with a whole
hex or octal digit reported as unknown when any bit in it is. The characters standing for a floating
or conflicting bit are preferences in Logisim, so they travel with the netlist alongside the
colours. The differential test now compares against `Value.toDisplayString` rather than a
hand-written mirror of it, so the comparison covers how a value is written as well as what it is.

**The page is a workspace, not a document.** One canvas filling the window, on the same white
sheet and dot grid the editor's own canvas draws, with the controls floating over it. There is no
page chrome left to follow a reader's dark theme, which is the point: Logisim's artwork is in
Logisim's colours and those assume paper.

Panning and zooming go through the SVG's viewBox rather than by scaling the element, so lines stay
one pixel wide and text stays sharp at any zoom. The grid is a screen space background moved to
match, which is how the editor draws it too, and it costs nothing to redraw. The gestures are the
editor's, taken from `Canvas.mouseWheelMoved`: control zooms about the pointer, shift scrolls
sideways, a bare wheel scrolls up and down. Control also covers the trackpad pinch, which browsers
report as a wheel event with the control flag set.

**A drag is not a click.** Which of the two a gesture was is decided by whether it moved, not by
where it started, so dragging across a pin pans the view and a still click on the same pin still
toggles it. Getting this wrong is invisible in a screenshot and obvious in use.

**The pointer is captured on the first real movement, not on the press.** Capturing on the press
sends the `click` that follows to the capture target rather than to whatever was pressed, so every
pin, button and switch on the page goes dead while the drawing still looks perfect. It shipped in
dev build 35 that way. Nothing caught it because the test dispatched its own pointer events, and a
made up pointer id cannot be captured at all, so the very line at fault never ran. Interaction is
now checked by driving a real browser with real input; synthetic events are the wrong tool for
anything the browser itself decides.

Two bugs came out of testing that rather than reading it. `fit()` divided by a window that had no
size yet, settled on the smallest zoom there is, and left the page apparently blank; it now refuses
a zero sized window and is retried after the first layout. And the guard that tells a drag from a
click reached the DIP switch but not the pin, because the patch that added it aborted halfway and
was never written -- so dragging across an input pin toggled it. Both were found by driving the
page, not by looking at it.

Verified end to end through the application itself: File > Export as interactive HTML on a
two-level nested full adder, saved through the file dialog, opened in a browser, and swept through
all eight rows of its truth table.

The twelve locales already carried this feature's strings from phase 1.

### Known limits

- **Delta-cycle, not timed.** Components have no propagation delay, so a circuit that depends on
  gate delay does not behave as it does in the editor.
- **Not yet supported**, and refused by name rather than exported wrongly: RAM, ROM, shift
  register, divider, bit adder, bit finder, bit selector, priority encoder, random.
- **A pin wider than about nine bits** falls back to a value box drawn by the page rather than by
  Logisim.

## Feature 10 — The fork's own settings page (2026-08-12)

One Preferences tab, **Peler's Features**, holding the settings every earlier feature needs.

### Why a tab of its own

The alternative was to file each setting under the upstream panel it belongs to — placement under
Layout, the save warning under Template, and so on. That reads tidier and is worse in both
directions. Someone hunting for a setting this edition added has nowhere to start, because no
panel's title says whether the fork touched it; and someone comparing this build against the
official one cannot see what has been added, because the additions would be interleaved with
upstream's. A single tab answers both questions, and the tab's contents are a readable summary of
what this fork actually changes about the editor.

### What is on it

| Setting | Default | Reaches |
| --- | --- | --- |
| Picking a component | click places one, double-click keeps placing | `ToolboxManip`, `LayoutToolbarModel` |
| Component finder's Enter | keeps placing | `FindToolDialog` |
| Wire auto-snap, and its distance | on, 20 circuit units | `WiringTool` |
| Right-clicking a component | rotates clockwise | `QuickRotateTool` |
| New annotation font, size, colour | platform sans, 12, RGB(90,100,115) | `AnnotationAttributes` |
| Warning when saving as `.circ` | once per file each session | `ProjectActions` |

Two of these changed a default rather than only exposing one. The finder now keeps placing on
<kbd>Enter</kbd>, at the maintainer's request, with <kbd>Shift</kbd>+<kbd>Enter</kbd> for a single
placement; the pair is resolved at keypress time so the setting only decides which key is which and
neither behaviour is ever out of reach.

### A preference that had no way to reach it

`WIRE_AUTO_SNAP` shipped with Feature 3 and the README said from that same commit that auto-snap
"can be turned off in preferences". It could not. The preference was read by `WiringTool` and
written by nothing: no panel referenced it, so the only way to change it was to edit the preference
store by hand. Nothing failed to compile and no test noticed, because a dead preference behaves
exactly like a live one right up until someone tries to change it.

`PelerOptionsTest.testEveryPelerPreferenceIsReachableFromThePreferencesWindow` now scans this
edition's block of `AppPreferences` and requires every declared preference to be named by some file
under `gui/prefs/`, with an explicit exemption list for the ones that are internal bookkeeping
(currently only `SHOWN_QUICK_ROTATE_HINT`, which records that a one-time hint has been shown).

### Decisions worth keeping

**Turning quick rotation off is done in the tool, not in the mouse mapping.** Right-click is bound
to Quick Rotate by `default.templ`, which means the binding is copied into every project file as it
is created. A preference could therefore only ever affect new projects, and every file made before
the setting existed would keep right-clicking to rotate with no way to say otherwise. So
`QuickRotateTool.mousePressed` consults the setting and hands the press to `MenuTool` instead --
which also means an existing project changes behaviour the moment the setting does.

**The check sits after the stop-placement checks, not before.** "Esc, Enter or a right-click stops"
is what this edition documents about continuous placement, and it has to stay true in every mode.
The setting is about what a right-click does when nothing is armed.

**"Double-click does not keep placing" is named for the gesture, not the outcome.** It was first
written as "always place just one", which is a promise it cannot keep: whether the tool stays
selected after one component lands is upstream's Layout -> "After adding component", which can be
set to keep the component tool. This setting only decides whether a double-click additionally pins
the tool down regardless of that.

**Annotation defaults reach new notes only.** Every annotation writes its own font and colour to
the file -- `XmlWriter`'s skip-the-default shortcut applies to library defaults, not to components
-- so changing the setting cannot restyle notes that already exist.

**A titled border cannot go straight on a `TableLayout` panel.** `TableLayout` ignores its
container's insets in both `preferredLayoutSize` and `layoutContainer`, so the title is drawn
through the first row of controls. It is upstream's layout manager, used by upstream's own panels,
so `PelerOptions` wraps each group in a `BorderLayout` panel and puts the border there rather than
changing the manager underneath them.

### Testing

`PelerOptionsTest` reads sources and bundles rather than constructing the panel: `AppPreferences`'s
static initialiser reads the real preference store, and reading it headless writes wrong defaults
back (see CLAUDE.md). It checks that every string the panel asks for exists and is non-empty in all
twelve bundles, that the twelve agree on which `peler*` keys they carry, that every fork preference
is reachable from Preferences, and that the panel is registered in the tab list. Both string checks
were mutation-verified by deleting one Polish key, and the reachability check by unwiring the
auto-snap checkbox; each failed as intended and passed again once restored.

Driven by hand on the real X display: the panel renders with all five groups and correct defaults;
"click keeps placing" places three gates from one toolbox click and three canvas clicks; right-click
with rotation off opens the component menu; right-click set to anticlockwise turns an east-facing
gate to north while leaving its neighbour alone; and the finder's new default places three gates
from one <kbd>Enter</kbd>.

### A bug that was not one

Feature 10's testing appeared to turn up a serious defect: close the Preferences window, and File ->
Preferences would never open it again. It reproduced on a clean worktree at `3c6f3ca4f`, so it was
written up as an upstream v4.1.0 defect.

It is not a defect. It was the test.

`xdotool windowclose` is not the window's close button. Its own manual page says it "will destroy
the window, but will not try to kill the client controlling it" -- it calls `XDestroyWindow`
directly rather than sending `WM_DELETE_WINDOW`, so the X window vanishes while the application is
never told. A probe printing `isVisible()` on the way into `showPreferences` settled it: after a
forced destroy the frame still reported `visible=true`, which makes `setVisible(true)` a no-op and
leaves nothing on screen. Clicking the real titlebar button instead gives `visible=false` and the
window comes straight back, verified by screenshot.

Worth recording because the false version was convincing: it reproduced every time, on an unmodified
build, with no exception logged. A GUI test driver that bypasses the window manager can manufacture
a bug that looks exactly like an application one.

## Feature 11 — Embedded MCP server (experimental, contributed 2026-08-13)

Contributed as a single commit on top of `2a5829da2`, roughly 9,600 lines: a
`com.cburch.logisim.mcp` package exposing 47 tools over Streamable HTTP and stdio, nine test
classes, and its own design and QA notes (now under `docs/peler-edition/mcp/`). Every circuit change
goes through `Project.doAction`, so an AI client and a mouse share one undo stack. The design notes
in that directory are the contributor's own and describe the tool surface in detail.

Merged cleanly into `main` with no conflicts, and the contributor's test claims held on re-run:
277 tests before this review's additions, 0 failures, 75 of them MCP.

### What was changed before merging

**It was on by default, and open.** `logisim.mcp.enabled` defaulted to true and the token defaulted
to empty, and `McpHttpHandler.authorized()` returns true when the token is blank. Verified rather
than inferred: a stock launch printed `Embedded MCP server listening at http://127.0.0.1:8765/mcp`,
and a bare `curl` with no credentials completed `initialize` and `tools/list`. `Origin` is also
allowed when absent or `"null"`, which covers every non-browser process and sandboxed `file://`
pages. The `confirm=true` gates on the destructive tools are caller-supplied JSON arguments, not
user prompts, so they protect against a careless client and not against a hostile one.

Now: off unless asked for, a checkbox in Preferences -> Peler's Features, and a 24-byte token
generated on first enable and handed over by Copy MCP Configuration (in the Help menu at the time;
it has its own menu now, below). Re-verified end to end -- stock launch binds nothing; enabled, an
unauthenticated request and a wrong-token request both get 401 and the generated token gets 200.

The test that encoded the old behaviour was named `usesSafeDefaultsWhenNoPropertiesAreSet` and
asserted `assertTrue(config.enabled())`. It now asserts the opposite under the same name, which is
the honest place for it: the name was right and the value was wrong.

**Five UI strings were hardcoded English** (`new JMenuItem("Copy MCP Configuration")` and the dialog
text), so the Help menu showed English in every locale. Now through `S.get` with all twelve
bundles, and registered in `MenuHelp.localeChanged`.

**`McpProjectService` carried 201 checkstyle violations** -- helper methods and lambda bodies
written as single lines up to 665 characters. Expanded to zero, mechanically, with each replacement
asserted to match exactly once and the 77 MCP tests as the check that nothing changed. Two
identical methods, `writeSchema` and `lifecycleSchema`, were folded onto a shared helper.

**One `new Font(Font.MONOSPACED, ...)`**, the fallback-less physical font that is how CJK turns into
boxes elsewhere in this application. Harmless for ASCII JSON, replaced with `deriveFont` anyway so
it is not copied.

**Eleven `MCP_*.md` files sat in the repository root**; moved to `docs/peler-edition/mcp/`.

### An MCP menu, and a bundle Claude Desktop can install (2026-08-13)

Copy MCP Configuration was one item at the bottom of Help, and it produced a configuration only a
client that speaks HTTP can use. Claude Desktop is not one: it installs **MCP Bundles** -- a zip of
`manifest.json` plus a server, extension `.mcpb` since the rename from `.dxt` in late 2025 -- and a
bundle manifest can only describe a **local process spoken to over stdin and stdout**. There is no
way to write "connect to this URL" in one.

Which rules out the obvious shortcut and also the second obvious one: the contributed
`--mcp-stdio` mode advertises a tools capability and then returns **zero tools**, because it has no
window and so no project to expose. The whole point of this server is that the circuit being edited
is the one on screen.

So the bundle carries a bridge instead of a server: `resources/logisim/mcp/bridge.js`, ~180 lines
of dependency-free Node, reads a JSON-RPC line from stdin, POSTs it to the endpoint, writes the
reply back. Two details are not incidental:

- **It reopens its session.** The server issues an `Mcp-Session-Id` on initialize and 404s a
  request carrying a stale one, so a Logisim restart would otherwise break every client until it
  was restarted too. The bridge replays the original `initialize` on a 404 or 400 and retries the
  request once, single-flighted so a burst produces one reopen. Verified across a real restart.
- **It does not await.** Requests are forwarded as they arrive rather than one at a time, so a slow
  tool call cannot hold up the ping behind it.

`McpBundleWriter` zips that script together with a generated manifest naming the live endpoint and
the live token -- read from the running configuration, not from preferences, since a system property
overrides the stored value exactly when it matters. Entry timestamps are zeroed so two exports of
one configuration are byte-identical.

The menu itself is new: **MCP**, between Window and Help, holding Copy MCP Configuration and Export
MCP Bundle.

It was first written to grey itself out while the server was off, following a new
`McpServerManager` listener rather than the preference -- the preference changes first and the bind
can still fail, so following the preference would enable the menu for a server that never started.
The maintainer reversed it (2026-08-13), and the reasoning holds: a greyed-out menu is the one
thing that cannot say why it is greyed out, and this one is greyed out precisely on a first run,
which is when someone is looking for the feature. Both entries now open a dialog that says what an
AI client could do with this application, that it is off until switched on, and that it then
listens on this computer only and requires a token -- with a button that opens the settings page.

The button is what makes it worth a dialog rather than a sentence. Naming the path in prose means
writing "Preferences -> Peler's Features" in twelve languages and keeping all twelve in step with
the menus they name; `PreferencesFrame.showPelerPreferences()` is the same instruction, correct by
construction. The message is laid out as HTML at a fixed width, because a dialog given a plain
string breaks it only where the string does -- survivable in English, where the translator can
place the breaks, and not in Chinese or Japanese, where a paragraph carries no spaces at all and
would come back as one line thousands of pixels wide. Checked by rendering the Chinese strings.

The listener the first version needed went with it: nothing else wanted one. The preferences panel
already updates its own status line after starting or stopping the server, so the manager is back
to being asked rather than telling. What stayed is the lock discipline that came out of building
it, below.

### Three deadlocks, one shape, none of them visible to a thread dump

The first was in the contributed code and had been there since it was merged: `tools/call
list_projects` never returned. `McpModelExecutor.call` held a monitor across
`SwingUtilities.invokeAndWait`. The simulator thread, arriving from `propagationCompleted`, held
that monitor while waiting for the event dispatch thread; the event dispatch thread, inside
`windowOpened` delivering to a project-list listener, waited for the monitor. Every later tool call
queued behind the same monitor forever.

The second was mine, and it stopped the application from opening a window at all: `start` held the
manager's lock while constructing `McpProjectService`, whose constructor hops to the event dispatch
thread -- and the event dispatch thread was in `LogisimMenuBar`, building the MCP menu, which in
that first version asked `isRunning()` to decide whether to grey itself out, and so wanted that
same lock. Enabling the checkbox launched a process with no interface and no error. The menu no
longer asks, but that is not what makes it safe: anything on that thread may ask.

The same rule fixes both: **do not hold a lock across a hop to the event dispatch thread.** The
executor's monitor was deleted outright; the manager now builds outside the lock and takes it only
to publish, re-checking for a racing starter and closing what it built if it lost. `close` got the
same treatment -- it captures the service, clears the fields, and closes outside the lock.

The third arrived from the other end of the application's life, reported as the Mac freezing when
quit from the Dock, and it showed that rule to be a special case of a larger one. Quitting calls
`System.exit` from the event dispatch thread -- `ProjectActions.doQuit` always has, upstream code
this fork never touched, and on macOS the Dock's Quit is the ordinary way in, because
`Projects.java` deliberately does not quit on closing the last window when the screen menu bar is
in use. That thread is then inside `Shutdown.runHooks`, joining the MCP shutdown hook, while the
hook is inside `McpProjectService.close` asking that same thread to detach some listeners. No lock
anywhere. The window stayed on screen, nothing responded, and a terminate signal did nothing
either, shutdown having already begun, so it took a kill -- which is what "completely frozen" meant.
The `closed` flag did not save it: `McpServerManager.close` closes the service before the executor,
so the guard was still open when the hop was made.

The rule is therefore the wider one: **before hopping to the event dispatch thread, ask whether that
thread can still serve you.** `McpModelExecutor.canReachModel()` answers it -- false once the
executor is stopped, and false while the JVM is shutting down, detected by offering the runtime a
shutdown hook and taking it straight back out, since it refuses both once shutdown has started (a
flag set by a hook of our own would depend on hook ordering, which is unspecified). `close` asks
before detaching and skips it when the answer is no, which loses nothing: the listeners and
everything holding them go with the process. Cancelling the running jobs stays outside the guard,
because those own threads. `call` refuses the hop outright in the same condition, so any future
caller gets an exception instead of a hang.

Worth recording because `jstack` reports **no deadlock** for any of the three: one edge is
`invokeAndWait`'s wait/notify, which the detector does not graph. It printed the two halves of the
third one plainly -- the hook in `invokeAndWait`, the event dispatch thread in
`ApplicationShutdownHooks.runHooks` at `Thread.join` -- and still counted zero. What found the
second one was a temporary print of the actual state (`pref=true ... running=false`) after several
turns of theorising about preference event ordering, which is the general lesson.

All three have regression tests validated in both directions against the broken version. The first
two force the interleaving rather than hoping for it, and interrupt their helper thread in a
`finally` so a future regression fails one test instead of wedging the shared event dispatch thread
for the rest of the run. The third cannot be done that way at all: a shutdown that has begun cannot
be called off, so there is no state left to assert on. It forks a JVM
(`McpShutdownHookProbe`) and asks only whether the process ends -- and on the unfixed code the whole
application was confirmed to hang this way too, not just the probe.

### Considered and declined

**Per-tool authorisation.** The token is all-or-nothing: a client that can call `list_projects` can
call `load_library`. Declined by the maintainer (2026-08-13). The server is off by default and bound
to the loopback interface, so whoever turns it on is granting one client they chose, on their own
machine, the ability to drive the application -- which is what an MCP endpoint is for. A permission
matrix over 47 tools would be answered by ticking every box.

### Still worth doing

**`McpPathPolicy` allowed roots cannot be granted from the interface.** File access is bounded by
`logisim.mcp.allowedPaths` (a system property or environment variable) plus the directory of each
open project's main file. The second source carries ordinary use -- open a circuit by hand and the
tools can work beside it -- but it is the only one available to anyone who launched the application
from a desktop entry rather than a shell, and a project that has never been saved contributes no
root at all. So there is no way to grant, say, a fixed export directory without editing the launcher
arguments. Deliberately left until real use shows whether it matters: this is the only boundary
`load_library` has, and `load_library` puts a JAR on the classpath.

## Known open items

- **CJK text renders as tofu boxes in the project explorer.** Diagnosed, and left unfixed at the
  user's instruction (2026-08-09). FlatLaf supplies UI fonts as composites with a CJK fallback;
  `new Font(name, style, size)` builds a fallback-less physical font, so `canDisplay('设')` is false,
  while `deriveFont(...)` preserves the composite. Two layers: `ProjectExplorer`'s renderer builds its
  bold font with `new Font(plainFont.getFontName(), Font.BOLD, size)`, and it derives that plain font
  from the reused renderer's *current* font, so the damage then leaks to every row. `Main.updateGlobalFont`'s
  `new FontUIResource(appFont, ...)` has the same shape but was not separately tested. This is an
  upstream v4.1.0 defect, reproducible in the official build, and worth reporting upstream.
- **macOS bundle identifier is unverified.** `--mac-package-identifier` was added to `build.gradle.kts`
  based on jpackage's documented default; confirming it needs a real macOS build and a look at
  `Info.plist`.
- **The exported project bundle's inner file is still named `.circ`.**
- **Roughly 372 upstream commits are in this fork but not in v4.1.0.** Only the red-highlight one has
  been reverted. A full rebase onto the release tag was raised with the user and not undertaken, since
  it would drop upstream bug fixes and require re-applying every Peler feature.

## Workflow for each phase

1. **Product manager** turns the phase scope above into a concrete task list with acceptance criteria.
2. **Developer** implements on a feature branch (`peler/phase-N`), one commit per logical change.
3. **Reviewer** independently audits the diff against this roadmap and the phase's acceptance criteria
   before it's merged to `main` and pushed to the `logisim-evolution-peler` fork.
