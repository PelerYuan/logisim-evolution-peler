# MCP R4-R5 QA Report

Date: 2026-08-12 (updated after R5 remediation)

Branch and baseline:

- Branch: `main`
- `HEAD`: `2a5829da23818cf8d1b857f728ac40e9e764a137`
- Focused command: `./gradlew test --tests 'com.cburch.logisim.mcp.*' --no-daemon --no-configuration-cache`
- Serial full gates: `./gradlew test --no-daemon --no-configuration-cache`, `./gradlew check --no-daemon --no-configuration-cache`, and `./gradlew shadowJar --no-daemon --no-configuration-cache`

## Gate Summary

| Gate | Result | Evidence |
|---|---|---|
| R4 core circuit editing | PASS | `tools/list` exposes component/wire edits, attributes, move, rotation, circuit attributes, atomic `batch_edit`, and guarded undo/redo. Focused and full regression suites pass. |
| R5 operation replay | PASS | Exact `operationId` replay returns the original revision and component ID, creates one component and one undo action. Reuse with different parameters returns `-32010`. |
| R5 revision conflicts | PASS | Stale `expectedRevision` returns `-32009` without changing model count, revision, or undo history. |
| R5 revision ordering | PASS | GUI action, MCP add, MCP undo, and MCP redo expose revisions `1, 2, 3, 4`. |
| R5 stable component IDs | PASS | Repeated snapshots and move/undo/redo retain the same component ID. |
| R5 resource subscriptions | PASS | `initialize` advertises subscriptions; each SSE stream has an independent cursor; exact URI filtering, subscribe-before/after mutation, reconnect replay, bounded retention, and resync all pass. |
| R5 HTTP sessions | PASS | Server-issued session ownership, missing/unknown session rejection, authenticated GET event streams, and POST request routing pass. |

R5 focused result: **57/57 tests passed**. The serial full suite passed with **26 suites / 255 tests**. `check` and `shadowJar` also passed. `check` reported 184 non-blocking style warnings; these are tracked for the R8 release gate.

## Passing Scenarios

`McpR5ConsistencyTest` proves these behaviors against a live in-memory `Project` fixture:

1. Idempotent write replay is checked before stale `expectedRevision`, so a transport retry returns cached success rather than `-32009`.
2. A new `operationId` with a stale revision has zero model and history side effects.
3. GUI and MCP actions share one monotonic project revision stream.
4. Replacement-based moves retain stable IDs across snapshots, undo, and redo.

Existing R0-R4 MCP tests also continued to compile. The pre-R5 MCP suite passed before this gate was added.

## Resolved R5 Findings

The earlier report recorded five failures while the transport/session implementation was incomplete. Those failures were fixed and covered by the current focused run:

- `initialize` now advertises resource subscriptions.
- HTTP sessions are server-owned and validated for every request.
- Authenticated GET streams use `text/event-stream` and replay from an independent per-stream cursor.
- `rotate_components`, `set_circuit_attributes`, and atomic `batch_edit` are present and tested.
- Simulator callbacks originating on the EDT publish without re-entering the model executor, avoiding a deadlock.

The GUI smoke evidence is retained outside the repository because it is generated runtime output:

- `/tmp/logisim-r5-gui-pin.Fjtmjp/` — mouse add, revision 5 -> 6, live SSE update and repaint.
- `/tmp/logisim-r5-gui-undo2.Wh1PTJ/` — GUI undo, revision 6 -> 7, live SSE update.
- `/tmp/logisim-r5-stale-gui.kfBpmO/stale-add.json` — stale write rejected with `-32009` and `actualRevision`.

## Deferred Release Work

These are not R5 blockers, but remain required before R8/R9 release approval: standard MCP conformance against real Claude/Codex clients, an explicit `Last-Event-ID`/duplicate-delivery policy, token/endpoint discovery and persistence, and checkstyle warning cleanup.

## Required Retest Flow

Run serially because concurrent Gradle invocations share generated output directories in this worktree:

```bash
./gradlew test --tests 'com.cburch.logisim.mcp.*' --no-daemon --no-configuration-cache
./gradlew test --no-daemon --no-configuration-cache
./gradlew check --no-daemon --no-configuration-cache
```

For the R5 gate, all consistency and transport tests passed without skips. The real-client-equivalent smoke flow was also run: initialize, subscribe to the active circuit resource, edit once in the GUI, observe the update, issue an MCP edit with the new revision, and confirm the live canvas plus undo history change without reopening a file. R5 is product- and QA-approved for entry to R6; this is not final release approval.
