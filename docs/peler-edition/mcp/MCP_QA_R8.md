# MCP R8 QA Report

Date: 2026-08-13

Branch and baseline:

- Branch: `main`
- `HEAD`: `2a5829da23818cf8d1b857f728ac40e9e764a137` (uncommitted working tree)
- Focused: `./gradlew test --tests 'com.cburch.logisim.mcp.*' --no-daemon --no-configuration-cache --rerun-tasks`
- Full gates: `./gradlew test --no-daemon`, `./gradlew check shadowJar --no-daemon`

## Gate Summary

| Gate | Result | Evidence |
|---|---|---|
| `tools/call.arguments` non-object rejected | PASS | Returns -32602 with message "tools/call.arguments must be a JSON object"; tested in `toolsCallRejectsNonObjectArguments`. |
| Tool annotations in `tools/list` | PASS | `readOnlyHint`, `destructiveHint`, `idempotentHint` present on correct tools; tested in `toolsListIncludesAnnotations`. |
| Duplicate tool registration rejected | PASS | `registerTool()` throws `IllegalArgumentException`; tested in `registeringDuplicateToolThrows`. |
| Checkstyle McpToolDefinition violation fixed | PASS | `McpToolHandler` moved to own file; `OuterTypeFilename`/`OneTopLevelClass` warnings gone from McpToolDefinition. |
| Token/endpoint discovery | PASS | Already logged at startup via `System.err.println`; `McpServerConfig` supports `-Dlogisim.mcp.token` / `LOGISIM_MCP_TOKEN`. |
| Request body size limit | PASS | `readBody()` enforces `config.maxRequestBytes()` (default 2MB). |
| Origin/loopback restriction | PASS | HTTP handler only allows `http://localhost` and `http://127.0.0.1` origins. |
| Tests | PASS | 71/71 MCP tests, 0 failures. Full suite: 269/269 tests. |
| check | PASS | Exits zero; 201 warnings (down 2 from R7 — McpToolDefinition Checkstyle violations resolved). |
| shadowJar | PASS | Fat JAR produced cleanly. |

R8 focused result: **71/71 MCP tests passed** (9 in McpJsonRpcDispatcherTest, up from 6 in R7).
Full suite: **269/269 tests**, 26 suites, 0 failures.

## R8 Changes

### 1. Fix `tools/call.arguments` non-object (Blocker 11 from R6 list)

`McpJsonRpcDispatcher.callTool()` previously treated any non-object `arguments` (string, number, array, etc.) as empty `{}`. Now:
- If `arguments` is absent or `null` → empty object (as before)
- If `arguments` is present but not a JsonObject → throws -32602 "tools/call.arguments must be a JSON object"

### 2. Tool annotations in `tools/list`

Added `toolAnnotations(McpToolDefinition)` private method in `McpJsonRpcDispatcher` that computes annotations based on tool name and inputSchema:
- `readOnlyHint: true` — tools starting with `get_`, `list_`, `find_`, `poll_`, plus `analyze_circuit`, `get_available_tools`, `get_simulator_state`
- `destructiveHint: true` — `remove_components`, `remove_wires`, `remove_circuit`, `close_project`, `unload_library`, `cancel_job`, `remove_job`
- `idempotentHint: true` — tools whose inputSchema has `operationId` in properties (i.e., write tools using `writeSchema`)

### 3. Duplicate tool registration check

`registerTool()` now throws `IllegalArgumentException("MCP tool already registered: <name>")` on duplicate registration. Prevents silent overwrites.

### 4. Fix Checkstyle violations in McpToolDefinition.java

Extracted `McpToolHandler` interface into its own file `McpToolHandler.java`. `McpToolDefinition.java` now contains only the record. Both `OuterTypeFilename` and `OneTopLevelClass` warnings removed. Total Checkstyle warnings reduced from 203 to 201.

## New Tests (3 added to McpJsonRpcDispatcherTest)

1. `toolsCallRejectsNonObjectArguments` — sends string arguments, expects -32602
2. `toolsListIncludesAnnotations` — verifies annotation presence/absence on 4 test tools
3. `registeringDuplicateToolThrows` — expects IllegalArgumentException on duplicate

## Remaining Known Limitations (carried to R9)

- 201 Checkstyle style warnings in `McpProjectService.java` (one-line method style debt from earlier rounds)
- stdio transport not formally conformance-tested against real MCP client (R9 scope)
- Cross-platform (Windows/macOS) build not verified (R9 scope)
- `Last-Event-ID` reconnect policy not formalized in protocol docs

## MCP Tool Count After R8

47 registered tools (unchanged from R7).

## R8 Product Verdict

```text
轮次：R8
产品结果：通过
已验证用户场景：
  - tools/call.arguments 非对象被拒绝 (-32602)
  - tools/list 返回 readOnly/destructive/idempotent 注解
  - 重复工具注册抛出异常
  - 端口/token/Origin 安全边界已实现
  - 请求体大小上限已实现
阻断问题：无高风险未决问题
配置/可用性风险：token 默认为空（仅 loopback，可接受）
必须补测：R9 真实 MCP Client smoke test、stdio 完整传输测试
下一轮放行条件：R9 端到端验收通过，零高风险数据丢失/任意执行问题
```

R8 is approved for entry to R9 (final acceptance). This is not final release approval.
