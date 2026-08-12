# MCP QA Strategy: R0-R3

## Baseline

- Branch: `main`
- HEAD at review: `2a5829da23818cf8d1b857f728ac40e9e764a137`
- Java: 21
- Test framework: JUnit Jupiter 6.0.2, Mockito 5.21.0
- Baseline command: `./gradlew test --no-daemon`
- Baseline result: `BUILD SUCCESSFUL` (6 tasks, 6 seconds in the current checkout)
- Gradle test task forces `java.awt.headless=true` and redirects Java preferences to `build/test-prefs`; MCP tests must preserve this isolation.

## Test seams found in the current model

The model can be exercised without constructing a Swing `Frame`:

1. Create a file with `LogisimFile.createNew(loader, project)` or use an existing XML fixture through `Loader.openLogisimFile`, then construct `new Project(file)` and call `project.setCurrentCircuit(file.getMainCircuit())`.
2. Build mutations with `CircuitMutation` and execute through `project.doAction(mutation.toAction(...))`. This is the required seam for write tools because it updates undo/redo and dirty state.
3. Observe model events with `Project.addCircuitListener(CircuitListener)` and `Project.addProjectListener(ProjectListener)`. `CircuitEvent` exposes add/remove/clear/invalidate/transaction actions. `ProjectEvent` exposes action, undo, redo, file, selection, and repaint lifecycle events.
4. Assert circuit state using `Circuit.getNonWires()`, `getWires()`, `contains`, `Component.getFactory()`, `getLocation()`, `getAttributeSet()`, and `Circuit.getStaticAttributes()`.
5. Avoid direct `AttributeSet.setValue` in write-tool tests except for fixture setup; direct writes bypass `Project` action history and are not representative of MCP behavior.

The current Gradle test process is headless. GUI repaint tests should assert event/repaint callbacks or use a separately tagged/manual test under a display server; do not disable headless globally.

## R0 acceptance and tests

R0 freezes the contract and must not change ordinary startup behavior.

- Run `./gradlew test --no-daemon` and `./gradlew check --no-daemon`.
- Run `./gradlew dependencies --configuration runtimeClasspath` and verify MCP dependencies are pinned (no dynamic versions).
- Run `./gradlew shadowJar --no-daemon`; inspect the jar for MCP classes and, if an SDK is used, required `META-INF/services` entries.
- Start the application without MCP flags in a headless-safe smoke process and verify no MCP thread/socket is started. A unit seam is preferable to launching the full GUI.
- Validate all frozen tool schemas with malformed JSON, omitted required fields, unknown fields, numeric overflow, and null values. Every failure must map to a stable structured error rather than an uncaught exception.

R0 blocker: no MCP transport dependency or protocol adapter exists in the baseline. Protocol tests cannot be written until the chosen SDK/API and transport entry point are committed.

## R1 acceptance and tests

R1 exposes a server probe only; it must not mutate the project.

- Protocol: initialize, capabilities, ping, tools/list, and shutdown over the selected transport. Assert JSON-RPC ids, error shape, and content type.
- Lifecycle: start/stop twice, application shutdown, and failed bind. Assert no leaked executor threads or listening sockets.
- Network: bind only to `127.0.0.1`; occupied default port must either produce a deterministic error or the documented next port. Reject non-local/invalid Origin and missing or invalid token when auth is enabled.
- stdio (if implemented): stdout must contain only JSON-RPC frames; logs and stack traces must be on stderr. Start/stop must close stdin/stdout cleanly.
- Regression: ordinary GUI launch and all baseline tests pass with MCP disabled.

R1 test seam needed: a package-visible/injectable transport server and clock/executor, so tests do not rely on fixed port 8765 or sleep-based timing.

## R2 acceptance and tests

R2 is read-only project discovery and snapshots.

- Fixture projects: empty project; project with one main circuit; project with multiple circuits; project with components and wires; malformed/unknown project and circuit ids.
- Snapshot fidelity: compare DTO factory/name, location, bounds/endpoints, attributes, circuit name, and revision against in-memory objects. Read must not alter undo/redo lengths, dirty flag, or revision.
- Current-state proof: mutate the in-memory circuit through an action, do not save, then read again; the snapshot must include the unsaved mutation and must not reflect stale disk XML.
- Lifecycle: project registration at creation/open, current-circuit switch, project close/unregister. Unknown ids return a stable not-found error.
- JSON compatibility: deterministic ordering where documented, no Java object serialization, and schema fields remain primitive/JSON values.
- Resource tests: `resources/list`, `resources/read`, invalid URI, and unavailable project behavior. If subscriptions are present, verify read-only access does not emit mutation events.

R2 blocker/risk: components do not have a built-in stable id in the baseline. The implementation must define and test id lifetime across snapshots and action replacement/undo; using object identity or list index alone is not acceptable.

## R3 acceptance and tests

R3 is the first full write vertical slice: `add_component`.

- Input validation: known factory/tool, coordinates, optional initial attributes, duplicate/unknown attributes, wrong attribute types, NaN/infinite/out-of-range coordinates, missing project/circuit, and stale expected revision (if revision is already in R3).
- Model: action adds exactly one component to the target circuit; factory and location/attributes match the result; no file reload occurs.
- Event/dirty: circuit add and transaction-done events are emitted in documented order; project becomes dirty; revision increments exactly once per logical operation.
- Undo/redo: `project.undoAction()` removes the added component and restores prior dirty/revision semantics; `project.redoAction()` restores it. MCP undo, if exposed, must use the same action history.
- Threading: invoke the tool from a non-EDT executor and assert model access is serialized onto the documented model/EDT executor. Add a timeout test that fails on deadlock; never use unbounded sleeps.
- GUI bridge: in headless tests assert repaint/project event notification. A manual display test must open a project, call add through a real MCP client, and verify the canvas receives the changed model without reopening the file.
- Repeatability: add ten components at distinct coordinates, read a snapshot after each, and assert no state drift, duplicate ids, leaked tasks, or stale revision.

R3 blocker/risk: `Circuit.mutatorAdd` can perform label-collision normalization and factory side effects. Tests must assert the post-action model rather than assuming requested attributes survive unchanged. If the tool bypasses `Project.doAction`, fail the stage even if the component appears visually.

## Suggested test classes (test sources only)

- `com.cburch.logisim.mcp.McpContractTest`: schema/error validation and deterministic DTO serialization.
- `com.cburch.logisim.mcp.McpServerLifecycleTest`: injectable transport/port/auth/lifecycle tests.
- `com.cburch.logisim.mcp.McpProjectRegistryTest`: registration and current-circuit lifecycle.
- `com.cburch.logisim.mcp.ProjectSnapshotTest`: fixture fidelity, read-only guarantees, and stable ids.
- `com.cburch.logisim.mcp.AddComponentToolTest`: validation, mutation, events, dirty, undo/redo.
- `com.cburch.logisim.mcp.ModelExecutorTest`: EDT/model serialization, ordering, timeout, and exception propagation.
- `com.cburch.logisim.mcp.McpProtocolSmokeTest`: JSON-RPC client/server loopback; tag as integration if it requires a real socket.

Use JUnit `@TempDir` for project files, `CountDownLatch`/`Future.get(timeout)` for concurrency, and deterministic fake executors where possible. Do not add sleeps, global preferences, or non-headless assumptions.

## Stage gate

The QA agent should report each round as `PASS`, `FAIL`, or `BLOCKED` with the command, test class/method, and first concrete failure location. R0-R3 cannot advance on a skipped failing test, a test that only reads a generated file, a direct model mutation outside `Project.doAction`, or a GUI claim without a model/event assertion.

## Current implementation findings (R1 skeleton)

The current uncommitted MCP classes are an R1 probe, not an R2/R3 implementation. The following must remain open at the stage gate:

- `McpServerConfig` defaults to an empty token and accepts arbitrary hosts. Either document loopback-only/no-token as an explicit mode or enforce token/unsafe-host controls before claiming a secure default.
- `McpServerManager` registers a dispatcher with no project tools or resources. It can only satisfy initialize/ping/list probes; it cannot yet demonstrate project discovery or editing.
- `McpHttpHandler.readBody` uses `InputStream.available()` as a pre-check. This is not a reliable request length and should be covered by a real socket test with a body larger than the configured limit.
- HTTP behavior is currently POST-oriented. Real tests are still needed for OPTIONS, GET/notifications, session reuse, CORS/Origin, token failures, invalid JSON, oversized bodies, and shutdown/port release.
- `McpJsonRpcDispatcher` should reject non-object `tools/call.arguments` instead of silently replacing it with an empty object; tests should also cover duplicate tool names and unknown fields once the contract is frozen.
- The current Gradle workspace has multiple agents modifying the same output directory. A failed compile during concurrent writes is an environment blocker until one serialized `./gradlew clean test --no-daemon --no-configuration-cache` completes.

### Latest compile blocker

After `McpProjectRegistry.java` appeared in the shared worktree, `./gradlew test --tests 'com.cburch.logisim.mcp.McpTransportSmokeTest' --no-daemon --no-configuration-cache` stopped in `compileJava` (before tests):

- `McpProjectRegistry.java:501`: `JsonObject.addProperty` receives a wildcard `Object`/null expression, producing an ambiguous overload and incompatible capture error.
- `McpProjectRegistry.java:527`: `AttributeSet.setValue(Attribute<V>, V)` receives `Attribute<?>` plus `Object`, producing a wildcard capture error.

This is a production compile failure and must be fixed before any R1/R2 gate can be evaluated.

The next clean build exposed a second integration mismatch after the registry was rewritten: `McpServerManager.java:60-62` still calls the old `McpProjectRegistry(McpModelExecutor)` constructor, `registerTools`, and resource-provider interface, while the current registry only has a no-arg constructor and id/resolve methods. `McpSnapshot.java:117` also still passes a wildcard attribute value directly to `Attribute.toStandardString`. These are cross-agent integration failures, not test failures.

### Latest Checkstyle blocker

`./gradlew checkstyleMain --no-daemon --no-configuration-cache` fails on `src/main/java/com/cburch/logisim/mcp/McpToolDefinition.java:15,20`: the file contains two top-level types (`McpToolHandler` and `McpToolDefinition`), violating `OuterTypeFilename` and `OneTopLevelClass`. The Checkstyle report transformer also aborts after those warnings. Split the handler into its own file or make it nested before the R0 gate.

After the latest service-layer files landed, a serialized run of `./gradlew checkstyleMain checkstyleTest --no-daemon --no-configuration-cache` completes but reports 178 warnings, mostly one-line statements/curly formatting in `McpProjectService.java` and the same two top-level-type warnings in `McpToolDefinition.java`. Treat this as a quality debt even though Gradle exits zero.

### R2 regression found by model integration test

`McpProjectServiceTest.revisionTracksDesktopActionThatPredatesFirstMcpRead` demonstrates that a GUI action made before the first MCP read is not reflected in `revision`: the snapshot reports `0` instead of `1`. `McpProjectService.refresh()` installs its `ProjectListener` lazily during the first request, so it cannot observe prior `ProjectEvent.ACTION_COMPLETE`. Register listeners when the server/project is registered (or initialize revision from a model/action baseline) before claiming realtime consistency. This test is intentionally failing until the lifecycle is corrected.
