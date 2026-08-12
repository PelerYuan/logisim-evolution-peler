# MCP R6 QA Report

Date: 2026-08-13

Branch and baseline:

- Branch: `main`
- `HEAD`: `2a5829da23818cf8d1b857f728ac40e9e764a137` (uncommitted working tree)
- Focused command: `./gradlew test --tests 'com.cburch.logisim.mcp.*' --no-daemon --no-configuration-cache --rerun-tasks`
- Serial full gates: `./gradlew test --no-daemon --no-configuration-cache --rerun-tasks`, then `./gradlew check shadowJar --no-daemon --no-configuration-cache --rerun-tasks`

## Gate Summary

| Gate | Result | Evidence |
|---|---|---|
| R6 job tools registered | PASS | `run_test_vector`, `get_job`, `list_jobs`, `cancel_job`, `remove_job` all registered and tested. |
| test-vector path policy | PASS | `run_test_vector` calls `pathPolicy.requireAllowed()` before passing validated `File` to `McpTestVectorJobService`; internal path validation remains for direct use. |
| QUEUED cancel race fixed | PASS | `McpJobManager.cancel()` now captures `job.future` inside `synchronized(lock)`, eliminating the race where a cancel before executor assignment would read null. |
| JAR library confirm=true | PASS | `load_library` with `kind=jar` throws -32012 with `requiresConfirmation=true` unless `confirm=true` is passed; tested in `jarLibraryLoadRequiresConfirmation`. |
| save_project_as path audit | PASS | Both save paths go through `pathPolicy.requireAllowed()`; `save_project` uses `loader.getMainFile()` (always the allowed path it was opened from). |
| No blocking Swing dialogs on save | PASS | `mcpDoSave()` temporarily sets `Main.headless = true` so `OptionPane` logs instead of showing modal dialogs on EDT. |
| configure_simulator semantics | PASS | `simulator_reset/step/tick/configure_simulator/set_simulator_mode` all use plain `schema()` (no `expectedRevision`), call `requireProject()` directly, and do not check or modify the edit revision. |
| reset/step/tick contract | DOCUMENTED | Tool descriptions state returned state may precede propagation completion; simulator ops are inherently async. |
| Job lifecycle tests | PASS | `testVectorJobLifecycle` starts a job, polls until terminal, verifies list/cancel/remove/get-after-remove behavior. |
| Path policy rejection test | PASS | `pathPolicyRejectsOutsideAllowedRoots` verifies -32016 for out-of-roots save path. |
| Full regression | PASS | 263/263 tests, 0 failures, 0 errors. |
| check | PASS | Exits zero; 203 pre-existing style warnings (tracked for R8), no new errors. |
| shadowJar | PASS | Fat JAR produced cleanly. |

R6 focused result: **65/65 MCP tests passed**.
Full suite: **263/263 tests, 26 suites**.
`check` and `shadowJar` passed.

## R6 Changes by Blocker

### Blocker 1 – Job tools registered
- `McpProjectService`: added `vectorJobService` field (`McpTestVectorJobService`), `vectorJobService.close()` in `close()`, and five new tool registrations with `schema()` (not `writeSchema`, since job tools are not circuit-revision operations).
- Implemented handlers: `runTestVector`, `getJob`, `listJobs`, `cancelJob`, `removeJob`.

### Blocker 2 – test-vector path through McpPathPolicy
- `McpProjectService.runTestVector()` calls `pathPolicy.requireAllowed(project, rawPath, "run_test_vector")` and passes the resulting `File` to the new `McpTestVectorJobService.start(Project, Circuit, McpProjectRegistry, long, File)` overload.
- `McpTestVectorJobService`: added `File`-accepting `start()` overload that skips internal string validation.

### Blocker 3 – QUEUED cancel race
- `McpJobManager.cancel()`: moved `final var future = job.future` read inside the `synchronized(lock)` block, then releases the lock before calling `future.cancel(true)`.

### Blocker 4 – JAR library confirm=true
- `McpProjectService.loadLibrary()`: `"jar"` case now throws -32012 with `requiresConfirmation=true` if `confirm` is not `true`.
- `load_library` schema updated to include `"confirm"` (boolean, optional).

### Blocker 5 – save_project_as path audit
- Already covered in prior R6 work. `save_project_as` calls `pathPolicy.requireAllowed()`. `save_project` writes to `loader.getMainFile()`, which was set when the project was opened via an allowed path. No change needed.

### Blocker 6 – No blocking Swing dialogs on save
- `mcpDoSave()` private static method added to `McpProjectService`. Temporarily sets `Main.headless = true` (which causes `OptionPane` to log instead of showing modal dialogs), calls `loader.save()` directly, then restores the flag in `finally`.
- Both `save_project` and `save_project_as` now call `mcpDoSave()` instead of `ProjectActions.doSave()`.

### Blocker 7 – EDT freeze mitigation
- Addressed by blocker 6: save no longer blocks EDT on failure dialog.
- Structural limit: large saves still run on EDT since the Logisim file writer requires it. Documented as known limitation.

### Blocker 8 – configure_simulator semantics
- `simulator_reset`, `simulator_step`, `simulator_tick`, `configure_simulator`, `set_simulator_mode` changed from `writeSchema` to `schema()`. They call `requireProject()` via `onModel()` directly, with no `expectedRevision` check and no undo entry.
- Removed the unused `writeSimulation()` and `ProjectSimulation` functional interface.

### Blocker 9 – reset/step/tick contract
- Tool descriptions updated to state: "does not create an undo entry or change revision" / "returned state may precede propagation completion". Simulator operations are async by design.

### Blocker 10 – Missing tests
Four new tests added to `McpProjectServiceTest`:
- `jarLibraryLoadRequiresConfirmation` – verifies -32012 without `confirm=true` for JAR kind.
- `testVectorJobLifecycle` – submits a job, polls until terminal, verifies list/cancel/remove/get-after-remove.
- `configureSimulatorDoesNotCheckRevision` – advances revision to 1, then calls `configure_simulator` without `expectedRevision` and confirms success with no new undo entry.
- `pathPolicyRejectsOutsideAllowedRoots` – verifies -32016 for a path outside `tempDir`.
- `setUp()` now sets `logisim.mcp.allowedPaths` to `tempDir` so save-round-trip and path tests work correctly; `tearDown()` clears it.

## Known Limitations / Deferred to R8

- `tools/call.arguments` non-object silently treated as empty object (blocker 11 from prompt — deferred to R8 per requirement).
- 203 pre-existing Checkstyle style warnings in `McpProjectService.java` and `McpToolDefinition.java`.
- Large saves may hold the EDT briefly (no async save path in this release).
- `reset/step/tick` return state captured synchronously; actual propagation may complete later on the simulator thread.

## MCP Tool Count After R6

37 tools (R5 baseline) + 5 job tools = **42 registered tools**.

(simulate_reset, simulate_step, simulate_tick, configure_simulator, set_simulator_mode are existing — schema changed from writeSchema to schema.)

## Required Retest Flow for R7

```bash
./gradlew test --tests 'com.cburch.logisim.mcp.*' --no-daemon --no-configuration-cache --rerun-tasks
./gradlew test --no-daemon --no-configuration-cache --rerun-tasks
./gradlew check shadowJar --no-daemon --no-configuration-cache --rerun-tasks
```

## R6 Product Verdict

```text
轮次：R6
产品结果：通过
已验证用户场景：
  - 新建、保存、另存为 (mcpDoSave, 无弹窗阻塞)
  - 创建、重命名、切换、删除电路
  - 库查询、加载、卸载；JAR 库需 confirm=true
  - 仿真 reset/step/tick/configure（不污染 undo，不检查 revision）
  - test-vector job 提交、查询、列表、取消、移除
  - 路径越权被 McpPathPolicy 拒绝 (-32016)
阻断问题：无
配置/可用性风险：无新增
必须补测：R7 前补 GUI Xvfb smoke test（saveAs 保存后重载一致性、job 运行中 GUI 不冻结）
下一轮放行条件：R7 分析/外观/导出工具通过，长任务不冻结 EDT，全量 tests/check/shadowJar 通过
```

R6 is approved for entry to R7. This is not final release approval.
