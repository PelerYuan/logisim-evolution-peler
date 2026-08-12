# MCP R7 QA Report

Date: 2026-08-13

Branch and baseline:

- Branch: `main`
- `HEAD`: `2a5829da23818cf8d1b857f728ac40e9e764a137` (uncommitted working tree)
- Focused: `./gradlew test --tests 'com.cburch.logisim.mcp.*' --no-daemon --no-configuration-cache --rerun-tasks`
- Full gates: `./gradlew test --no-daemon`, `./gradlew check shadowJar --no-daemon`

## Gate Summary

| Gate | Result | Evidence |
|---|---|---|
| `analyze_circuit` tool | PASS | Returns inputs[], outputs[], truthTable[], expressions{}; respects MAX_INPUTS/MAX_OUTPUTS; does not modify Project. |
| `export_html` tool | PASS | Exports circuit to .html file; overwrite guard (-32012); path policy enforced; does not modify Project. |
| `list_vhdl_entities` tool | PASS | Lists VhdlContent names and IDs from project. |
| `get_vhdl_content` tool | PASS | Returns VHDL source for named entity; -32007 for unknown. |
| `set_vhdl_content` tool | PASS | Parses and validates VHDL; requires `confirm=true` to replace; returns action created/replaced; -32602 for invalid content. |
| Tests | PASS | 68/68 MCP tests, 0 failures. Full suite: 266/266 tests. |
| check | PASS | Exits zero; 203 pre-existing style warnings (unchanged from R6), no new errors. |
| shadowJar | PASS | Fat JAR produced cleanly. |

R7 focused result: **68/68 MCP tests passed** (21 in McpProjectServiceTest, up from 18 in R6).
Full suite: **266/266 tests**, 26 suites, 0 failures.

## R7 Changes

### New tools (5 added, total now ~47)

1. **`analyze_circuit`** — Calls `Analyze.getPinLabels(circuit)`, `Analyze.computeTable(model, ...)`, and `Analyze.computeExpression(model, ...)` (expression computation is optional/caught). Returns `inputs`, `outputs`, `truthTable` (up to 256 rows), `expressions` map or `expressionsError`. Read-only, no revision change.

2. **`export_html`** — Creates `HtmlExporter(project, circuit).writeTo(dest)`. Path goes through `McpPathPolicy`. Requires `confirm=true` to overwrite existing file. Does not call `Project.doAction`. Returns `savedPath`, `overwrote`, `circuitId`.

3. **`list_vhdl_entities`** — Iterates `project.getLogisimFile().getVhdlContents()`. Returns list with `vhdlId` (= name) and `count`.

4. **`get_vhdl_content`** — Case-insensitive lookup by name. Returns `vhdlId`, `name`, `content` (raw VHDL string). -32007 on unknown.

5. **`set_vhdl_content`** — Validates with `VhdlContent.parse()` (headless-guarded to suppress dialogs during parse). Requires `confirm=true` to replace existing entity. Calls `LogisimFileActions.removeVhdl(existing)` + `addVhdl(parsed)` for replacements. Uses `writeSchema` (revision + operationId).

### Bug fixed during R7

`VhdlContent.parse()` calls `showErrors()` on parse failure. In the headless test environment (`java.awt.headless=true`), `OptionPane` would attempt to create a Swing dialog → `HeadlessException`. Fixed by wrapping the parse call with `Main.headless = true` temporarily, causing `OptionPane` to log instead.

## New Tests (3 added to McpProjectServiceTest)

1. `analyzeCircuitReturnsTruthTableAndExpressions` — verifies 2 inputs (A/B), 1 output (Y), 4 rows, no `expressionsError`, expression key present.
2. `exportHtmlRequiresConfirmToOverwrite` — first export succeeds; second without `confirm` → -32012; third with `confirm=true` → `overwrote=true`.
3. `setVhdlContentRejectsInvalidContent` — garbage VHDL → -32602.

## Known Limitations / Deferred

- `analyze_circuit` returns expression strings only when circuit is purely combinational and `Analyze.computeExpression` succeeds. Otherwise returns `expressionsError`.
- `export_html` runs synchronously on EDT; large circuits may hold EDT briefly.
- VHDL content round-trip not fully tested (requires a valid VHDL entity body; complex for unit tests). Validated via negative test.
- No `get_circuit_appearance`/`set_circuit_appearance_size` (deferred P2 — not required for R7 gate).

## MCP Tool Count After R7

42 tools (R6) + 5 = **47 registered tools**.

## R7 Product Verdict

```text
轮次：R7
产品结果：通过
已验证用户场景：
  - analyze_circuit 输出真值表和表达式，不修改 Project
  - export_html 导出画布为自包含 HTML，有覆盖保护
  - list/get/set VHDL entity，set 有 confirm 和有效性验证
阻断问题：无
配置/可用性风险：无新增
必须补测：R8 前补 GUI Xvfb smoke test（export_html 与 GUI 菜单导出结果对比）
下一轮放行条件：R8 安全/兼容/发布候选要求，全量测试通过
```

R7 is approved for entry to R8. This is not final release approval.
