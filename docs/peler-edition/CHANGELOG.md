# Peler Edition Changelog

This file tracks what **this fork** (`PelerYuan/logisim-evolution-peler`) has added on top of
upstream [Logisim-evolution](https://github.com/logisim-evolution/logisim-evolution). It does not
touch or replace the project's own [`CHANGES.md`](../../CHANGES.md), which is Logisim-evolution's
own changelog for Logisim-evolution's own history — nearly all of the software in this repository is
their work, and this file's only job is to record what changed on top of it.

Each entry below is condensed from that release's own GitHub release notes, which remain the
canonical, fuller description (see
[Releases](https://github.com/PelerYuan/logisim-evolution-peler/releases)). All releases are based
on upstream **Logisim-evolution v4.1.0**.

## v1.6.0 — 2026-09-26

- **MCP tool surface redesigned.** The AI-client integration is now three tools — `eval` (run a Lua
  script against the circuit), `describe` (a live example plus a summary of the current circuit),
  and `reset` (drop the current script session) — replacing the previous 47 flat operations. A
  script drives the circuit through a small object API (`Space`, `Comp`, `Port`, `Net`) instead of
  one request per component or wire, so building something like a full adder now takes a single
  round trip instead of dozens.
- **Editing an already-drawn circuit.** An AI client can now open a hand-drawn circuit, correctly
  read back its existing components and wiring, and build onto it — place a new gate and wire it
  into an existing connection — without disturbing anything already there.
- **Declarative circuit generation.** A script can hand over a truth-table-style specification
  (named inputs, boolean expressions for named outputs) and get a synthesized gate-level circuit
  back in one call, instead of placing and wiring every gate by hand.
- The sandbox is unchanged: no filesystem, network, or reflection access from a script, and a fixed
  instruction budget interrupts a runaway one.

## v1.5.0 — 2026-08-26

- **Save a circuit as your own component.** `Project -> Save as Custom Component...` turns a circuit
  into a reusable `.pcomp` component, available from a **My Components** toolbox category in every
  project on the machine.
- A small appearance editor for laying out a saved component's box: drag ports between edges, resize
  the box, position the name, rename ports, or reset with **Arrange for Me**.
- Component names may include spaces (e.g. `Half Adder`); the underlying circuit name is sanitized
  automatically while every visible label keeps the typed name.
- Versioning for saved components: a layout change that moves a port offers a new version instead of
  silently breaking projects that already placed the old one. Component internals are locked by
  default (double-click places rather than opens).
- **`Project -> Custom Components...`** panel to import, open, delete, or replace installed
  components.
- Saving to official `.circ` still works: each custom component is written as an ordinary circuit,
  each placed instance as a plain subcircuit, so the file opens correctly in upstream Logisim-evolution
  (only the catalog link is lost, silently, since nothing is dropped).

## v1.4.0 — 2026-08-23

- **74xx chips as logic symbols.** A new **TTL Symbols** toolbox category draws the same sixty-one
  74xx chips upstream ships as datasheet-style logic diagrams (inputs left, outputs right, grouped by
  function) instead of the numbered DIP package. Same simulation, same pins, just a second picture of
  the same chip.
- **BFH Symbols** category does the same for the two BFH-Praktika converters.
- A preference and a project-wide command to choose which drawing (DIP or symbol) newly placed and
  already-placed chips use.
- This fork's own splash-screen logo, so it is clear at a glance which build is starting.
- Saving to official `.circ` drops symbols (and warns first), since they are this fork's own
  components; `.pcirc` keeps them.

## v1.3.0 — 2026-08-16

- **`Preferences -> Peler's Features`**: a settings page collecting every preference this fork adds,
  so what can be changed there is also the list of what changed. An automated test fails the build if
  a fork-added preference is not reachable from this page.
- Placement, wiring, right-click, and annotation behaviors that were previously hardcoded became
  configurable, in every case with an option to match official Logisim-evolution's own behavior.
- **AI clients over MCP (experimental).** An embedded Model Context Protocol server lets an AI client
  drive the running application through the same undo stack as manual edits. Off by default; when
  enabled it listens on loopback only and requires a generated access token.
- The window title now shows both versions, e.g. `v1.3.0 (4.1.0)`.
- (This release's packages were rebuilt on 2026-08-18 to fix a quit-hang affecting anyone who had
  switched the MCP server on; the version number did not change.)

## v1.2.0 — 2026-08-11

- **Interactive HTML export (experimental).** `File -> Export as interactive HTML...` writes a
  circuit as a single self-contained, simulating HTML file — no plugin, nothing to install.
- **Component finder.** `Ctrl+F` opens a floating search box over the canvas, matched against both
  the interface-language name and the English identifier.
- **This edition's own file format, `.pcirc`.** Fixes a correctness problem in v1.1.0, where files
  were saved with the `.circ` extension but were not valid `.circ` files (upstream would refuse them
  and, on save, destroy fork-specific annotations for good). `.pcirc` keeps everything; `Save As`
  still offers a genuinely upstream-compatible `.circ`.
- Settings, file associations, the unnamed-project autosave file, and the FPGA workspace directory no
  longer collide with a side-by-side official Logisim-evolution install.

## Earlier

v1.1.0 and prior (including the `v1.0.0-peler.*` tags) shipped continuous placement, Quick Rotate,
wire auto-snap, and schematic annotations. These predate this changelog; see the
[v1.1.0 release notes](https://github.com/PelerYuan/logisim-evolution-peler/releases/tag/v1.1.0) and
earlier for details.
