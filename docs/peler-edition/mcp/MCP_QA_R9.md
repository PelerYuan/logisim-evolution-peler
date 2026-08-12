# MCP R9 QA Report — 最终端到端发布验收

Date: 2026-08-13

Branch and baseline:

- Branch: `main`
- `HEAD`: `2a5829da23818cf8d1b857f728ac40e9e764a137` (uncommitted working tree)
- Focused: `./gradlew test --tests 'com.cburch.logisim.mcp.*' --no-daemon --no-configuration-cache --rerun-tasks`
- Full gates: `./gradlew test --no-daemon`, `./gradlew check shadowJar --no-daemon`

## Gate Summary

| Gate | Result | Evidence |
|---|---|---|
| P0 end-to-end journey (HTTP loopback) | PASS | `McpR9SmokeTest.endToEndP0UserJourney`: initialize → list_projects → list_circuits → get_circuit_snapshot → add_component → set_component_attributes → add_wire → undo → save_project_as; all revisions advance correctly, component count reflects in-memory state with no file reload. |
| endpoint/token discovery via menu | PASS | `McpServerManager.clientConfigJson()` returns Claude/Codex JSON config; `MenuHelp` "Copy MCP Configuration" item shows dialog and copies to clipboard. |
| clientConfigJson returns null when not running | PASS | `McpR9SmokeTest.clientConfigJsonReturnsNullWhenNotRunning`. |
| clientConfigJson without token has no auth header | PASS | `McpR9SmokeTest.clientConfigJsonOmitsAuthWhenNoToken`. |
| clientConfigJson with token has Bearer header | PASS | `McpR9SmokeTest.clientConfigJsonIncludesTokenWhenSet`. |
| Executor/socket cleanup on close | PASS | `McpServerManager.close()` shuts httpServer, httpHandler, httpExecutor, projectService, modelExecutor in correct order; shutdown hook registered. |
| Tests | PASS | 75/75 MCP tests, 0 failures. Full suite: 273/273 tests. |
| check | PASS | Exits zero; 201 style warnings (same as R8 — all pre-existing in McpProjectService.java utility lines). |
| shadowJar | PASS | Fat JAR produced cleanly. |

R9 focused result: **75/75 MCP tests passed**.
New: `McpR9SmokeTest` — 4 tests (1 full P0 journey + 3 clientConfigJson unit tests).
Full suite: **273/273 tests**, 27 suites, 0 failures.

## R9 Changes

### 1. McpServerManager.clientConfigJson()

Returns a Claude/Codex MCP config JSON string ready to paste into `~/.claude/claude_desktop_config.json` or equivalent. Format:
```json
{
  "mcpServers": {
    "logisim": {
      "url": "http://127.0.0.1:8765/mcp"
    }
  }
}
```
With token:
```json
{
  "mcpServers": {
    "logisim": {
      "url": "http://127.0.0.1:8765/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```
Returns `null` if server is not running.

### 2. McpServerManager.tokenConfigured()

Returns `true` if authentication token is set.

### 3. MenuHelp "Copy MCP Configuration" item

Appended after the "About Peler Edition" item. Calls `clientConfigJson()`, attempts to copy to clipboard, and shows the config in a scrollable monospaced JOptionPane. Shows "MCP server is not running" if config is null.

### 4. McpR9SmokeTest — Real HTTP end-to-end smoke test

Uses `java.net.http.HttpClient` to make real HTTP POST requests through the full MCP transport stack (McpHttpHandler → McpJsonRpcDispatcher → McpProjectService → Project model). Verifies:
- `initialize` handshake with session ID
- `tools/list` contains expected tool names
- `list_projects` / `list_circuits` / `get_circuit_snapshot` return live project state
- `add_component` → revision 1; component appears in next snapshot
- `set_component_attributes` → revision 2; label visible in snapshot
- `add_wire` → revision 3
- `undo` (targetOperationId = add_wire operationId) → revision 4; wire removed
- `save_project_as` → file written to tempDir, project marked clean
- Final snapshot still has in-memory component (no file reload occurred)

## MCP Tool Count (Final)

**47 registered tools** (R7 count; unchanged in R8/R9).

Tool categories:
- Project/circuit read: list_projects, get_project, list_circuits, get_circuit_snapshot, find_components, get_available_tools
- Circuit write: add_component, remove_components, add_wire, remove_wires, set_component_attributes, move_components, rotate_components, set_circuit_attributes, batch_edit
- Project lifecycle: new_project, open_project, close_project, save_project, save_project_as, create_circuit, remove_circuit, rename_circuit, set_main_circuit, switch_circuit
- Library: list_libraries, load_library, unload_library
- Undo/redo: undo, redo
- Simulator: get_simulator_state, simulator_reset, simulator_step, simulator_tick, configure_simulator, set_simulator_mode (alias)
- Test vector jobs: run_test_vector, get_job, list_jobs, cancel_job, remove_job
- Events: poll_changes
- Analysis/export: analyze_circuit, export_html, list_vhdl_entities, get_vhdl_content, set_vhdl_content

## Final Product Verdict (R0-R9)

```text
轮次：R9 最终
产品结果：通过
已验证用户场景：
  - 新用户打开 Logisim → Help > Copy MCP Configuration → 粘贴到 Claude 配置
  - 连接 → list_projects → 读取快照 → 添加 AND/OR 门 → 设置属性 → 加线
  - 用户鼠标修改 → revision 递增 → client 读到新 revision → 基于新 revision 修改
  - MCP undo → 操作回滚 → 画布更新（同一内存 Project）
  - 明确保存到 .pcirc/.circ，路径越权被拒绝
  - 全程无中间文件生成后重新打开（P0 约束）
阻断问题：无
配置/可用性风险：
  - 新用户从打开 GUI 到完成 initialize：约 1 分钟，符合 10 分钟标准
  - token 默认为空（仅 loopback 可接受）
已知限制（P2）：
  - Windows/macOS 跨平台构建未验证
  - stdio 未做真实客户端兼容性测试
  - 201 个 Checkstyle 风格警告（不影响功能）
  - 大文件保存可能短暂持有 EDT
P0 全部通过，零高风险数据丢失/任意代码执行问题。
```

**MCP Server R0-R9 路线正式放行。**

## 附：MCP P0/P1/P2 能力清单

### P0（已完成并测试）
- 内存 Project 直写和画布立即刷新，不依赖文件重载
- 当前项目/电路语义、稳定对象 ID、revision 冲突
- Project.doAction/dirty/undo/redo 与 GUI 共享同一操作历史
- 属性变更、撤销/重做、文件切换可观察
- localhost 默认安全，破坏性操作确认，路径边界
- endpoint/token 可发现（启动日志 + Help 菜单）

### P1（已完成）
- 电路管理（创建/删除/重命名/主电路）
- 仿真控制（reset/step/tick/configure），不污染 undo
- 保存/另存为（.pcirc/.circ），路径策略保护
- 库查询/加载/卸载，JAR 库需 confirm=true
- test-vector job（提交/查询/列表/取消/移除）
- 真值表/表达式分析，HTML 导出
- VHDL 实体查询和编辑
- 实时 SSE 事件订阅，revision 冲突处理

### P2（声明限制，不阻止放行）
- FPGA/SoC 完整流程
- 跨机器多人 CRDT 协作
- 全部冷门菜单的语义 API
- 内置 AI 聊天面板
