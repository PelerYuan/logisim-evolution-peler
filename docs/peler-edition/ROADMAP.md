# Logisim Evolution — Peler Edition Roadmap

Fork of [logisim-evolution/logisim-evolution](https://github.com/logisim-evolution/logisim-evolution),
published at [PelerYuan/logisim-evolution-peler](https://github.com/PelerYuan/logisim-evolution-peler).
Goal: a handful of targeted workflow improvements for the circuit editor, kept additive/optional so the
upstream feature set stays fully intact and the fork stays easy to rebase against `upstream/main`.

## Guiding principle

Every change here must be **additive or preference-gated**, never a silent removal of existing behavior.
If a change trades away an existing interaction (see Feature 2), it must be a deliberate, documented
trade-off with an easy way back, not an accident.

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

**Deferred to backlog** (do last, per user instruction 2026-08-07): the About dialog / credits screen
(`About.java`, `AboutCredits.java`) and CLI help banner (`Startup.java`) already pick up the new
`BuildInfo.name`/`displayName` automatically, but still need a real content pass:
- `BuildInfo.url` (`build.gradle.kts`, `APP_URL`) still points at the upstream repo — should point at
  `github.com/PelerYuan/logisim-evolution-peler` once the About page work happens.
- App icon (`support/jpackage/windows/Logisim-evolution.ico`) is still upstream's icon — worth a distinct
  icon so the taskbar/Start Menu entry is visually distinguishable from the official install too.
- About/credits copy should actually say "Peler Edition" somewhere rather than relying solely on the
  build-derived name string.

## Workflow for each phase

1. **Product manager** turns the phase scope above into a concrete task list with acceptance criteria.
2. **Developer** implements on a feature branch (`peler/phase-N`), one commit per logical change.
3. **Reviewer** independently audits the diff against this roadmap and the phase's acceptance criteria
   before it's merged to `main` and pushed to the `logisim-evolution-peler` fork.
