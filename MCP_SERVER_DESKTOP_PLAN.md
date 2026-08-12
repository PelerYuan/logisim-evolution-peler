# Logisim Peler 桌面内置 MCP Server 开发方案（最终修订版）

## 1. 已确认的需求

Logisim Peler 是 MCP Server。用户先正常打开 Logisim GUI；外部 MCP Client（例如 Claude Desktop、Codex 或其他 MCP Host）连接当前运行的 Logisim 实例，并直接操作内存中的当前 `Project`。

必须满足：

- 修改立即出现在当前 Swing 画布；
- 不通过外部组件生成 `.circ`/`.pcirc` 后重新打开；
- 桌面用户和外部 MCP Client 共享同一个模型、撤销栈、dirty 状态和保存流程；
- 能观察用户在 GUI 中进行的修改；
- 配置尽量简单；
- 开发基线固定为仓库 `main` 分支：`/home/peler/logisim-evolution-peler-git`。

不包含：Logisim 内置 AI 聊天面板、内置模型 API、跨机器多人协同、CRDT。

## 2. 推荐的运行方式

### 2.1 默认：GUI 内嵌 localhost Streamable HTTP MCP Server

Logisim 启动并完成 GUI 初始化后自动启动 MCP Server：

```text
外部 MCP Client
       |
       | HTTP POST/GET + MCP JSON-RPC
       v
127.0.0.1:<port>/mcp
       |
McpServerManager
       |
McpProjectRegistry -> ModelExecutor/EDT -> Project
       |
当前 Swing Canvas 实时刷新
```

默认只绑定 `127.0.0.1`，避免把正在运行的电路控制面暴露到局域网或公网。建议默认端口 `8765`，占用时自动选择下一个端口；启动时在 stderr 和应用日志打印实际 endpoint，并提供一个轻量的“复制 MCP 配置”菜单项，不新增 AI 聊天面板。

外部 MCP Client 的配置只需要一个 URL，例如：

```json
{
  "mcpServers": {
    "logisim-peler": {
      "url": "http://127.0.0.1:8765/mcp"
    }
  }
}
```

如果启用 token，应用生成本地 token 并把 endpoint/token 写入用户配置目录的临时状态文件；文件权限设为用户可读，关闭应用时删除。也可以通过启动参数显式指定端口和 token。

### 2.2 补充：stdio 启动模式

为了兼容只能启动子进程的 MCP Client，提供：

```bash
logisim-evolution-peler --mcp-stdio [optional-project-file]
```

该模式由外部 MCP Client 启动 Logisim 进程，stdin/stdout 只承载 JSON-RPC，日志写 stderr。stdio 不是用户手动打开 GUI 的默认路径；它是兼容模式。

### 2.3 HTTP 实现取舍

官方 Java MCP SDK 2.0.0 的 Streamable HTTP transport 是 Jakarta Servlet，需要嵌入 Servlet 容器。因此实现时加入轻量 Jetty/Undertow 运行时并注册 `HttpServletStreamableServerTransportProvider`。不要把 Spring Boot 引入桌面应用。

首版只实现 localhost HTTP + stdio；SSE 旧传输不作为新接口。

## 3. MCP Server 与 Logisim 的边界

```text
MCP transport (HTTP/stdio)
        |
MCP tool/resource adapters
        |
McpProjectService
        |
Project / LogisimFile / Circuit / Simulator
```

MCP 层只接受 JSON 参数并返回不可变 JSON DTO，不暴露 `Project`、`Circuit`、`Component`、Swing 控件或 Java 反射入口。

### 3.1 项目注册

- 应用启动后注册当前项目；新建/打开项目时加入 registry；关闭窗口时注销；
- 每个项目分配 `projectId`；默认 MCP 请求操作当前 active project，也允许显式传 `projectId`；
- MCP server 没有项目时仍可响应 `list_projects`；
- 项目路径只作为显示信息，不能作为唯一 ID。

### 3.2 修改执行

- 所有电路写操作转换为 `CircuitMutation`/`CircuitTransaction`；
- 统一包装为 `Project.doAction(Action)`，保持 undo/redo、dirty、事件和 GUI repaint；
- MCP transport 线程不直接触碰模型；所有操作提交给 `ModelExecutor`，需要 Swing 状态时进入 EDT；
- 事务失败不提升 revision，也不留下部分变更；
- 对长任务返回 `jobId`，不阻塞 EDT。

### 3.3 实时观察

接入现有：

- `ProjectEvent`：action、current circuit、undo/redo、selection、repaint；
- `CircuitEvent`：add/remove/clear/transaction/display；
- `LibraryEvent`：工具、库、主电路、名称、dirty；
- `Simulator.StatusListener`：reset、状态变化、传播完成。

事件里的内部 Java 对象必须通过 `EventNormalizer` 转换为稳定 JSON：`projectId`、`revision`、`eventId`、`eventType`、稳定对象 ID、变更摘要、时间戳。

## 4. 工具和资源设计

### 4.1 P0 tools：实时电路编辑 MVP

- `list_projects`、`get_project`、`list_circuits`；
- `get_circuit_snapshot`、`find_components`、`get_available_tools`；
- `add_component`、`remove_components`、`move_components`、`rotate_components`；
- `set_component_attributes`、`set_circuit_attributes`；
- `add_wire`、`remove_wires`、`batch_edit`；
- `undo`、`redo`、`get_revision`；
- `get_simulator_state`、`simulator_reset`、`simulator_step`、`simulator_tick`。

### 4.2 P1/P2 tools：覆盖用户大部分能力

- 创建/删除/重命名/移动电路和主电路；
- 外观、Annotation、选择和查看辅助；
- 项目选项、工具栏、鼠标映射；
- 加载/卸载库、VHDL 内容；
- 测试向量、真值表分析、HTML/HDL 导出；
- FPGA/SoC 流程；
- `run_job`、`get_job`、`cancel_job`；
- 复杂菜单能力通过预注册的 `commandId` 白名单补齐，禁止任意类名/方法名/脚本。

### 4.3 resources：让外部 Client 获取实时状态

```text
logisim://projects
logisim://project/{projectId}/snapshot
logisim://project/{projectId}/circuit/{circuitId}
logisim://project/{projectId}/simulation
logisim://project/{projectId}/events
logisim://job/{jobId}
```

资源返回 JSON，带 `revision`、`modifiedAt` 和 schema version。支持 `resources/read`、`resources/subscribe`，发生 GUI 或 MCP 修改时发送 `notifications/resources/updated`。事件使用有上限的环形缓存；断线或过期时返回 `resyncRequired`，客户端重新读取完整 snapshot。

## 5. 并发、撤销和一致性

不做跨机器协同，但 GUI 和外部 MCP Client 仍可能同时操作同一个本地项目，因此需要轻量并发规则：

- 同一项目的写操作严格串行；
- 每次成功修改递增 `revision`；
- 写工具接受 `expectedRevision`，过期时返回 `CONFLICT` 和当前摘要；
- 批量编辑是一个原子 Action；
- MCP 的 `undo` 必须有 MCP operation/action 标记，不能盲目撤销桌面用户刚做的动作；
- 不要求 CRDT，冲突时让 MCP Client 重读后重新调用工具。

## 6. 安全和简易配置

- 默认 HTTP 只绑定 `127.0.0.1`；
- 校验 Origin；
- 默认使用随机 token，或由用户通过 `--mcp-token` 指定；
- 删除、清空、覆盖保存、卸载使用中库、FPGA 下载和外部命令标记为 destructive；
- MCP tool result 标示 destructive/idempotent/readOnly；
- 破坏性操作通过应用的确认回调请求用户确认，不强行弹出新的 AI 面板；
- 路径操作限制在用户明确选择的目录；
- HTTP 请求体、快照、事件缓存和并发 job 有大小上限；
- stdout 只输出合法 MCP JSON-RPC；日志写 stderr；
- 非 MCP 启动方式保持原有行为，不自动监听公网端口。

## 7. Agent 分工

可用槽位为 4 个，开发开始后固定分工：

### Agent A：主协调/架构负责人

- 维护 `main` 分支和阶段任务；
- 决定 MCP SDK、Servlet 容器、线程模型和 JSON schema；
- 集成开发与测试结果；
- 负责最终构建、发布和向用户汇报。

### Agent B：功能开发

- 实现 `McpServerManager`、HTTP/stdio transport；
- 实现 project registry、ModelExecutor、DTO、tools、resources、event normalizer；
- 只通过 `Project.doAction`/`CircuitMutation` 修改模型；
- 每个子任务附测试命令、线程说明和风险。

### Agent C：测试/质量

- 协议、schema、模型、GUI 事件、undo/redo、并发、安全和打包测试；
- 检查 MCP stdout、HTTP Origin/token、路径权限、超时和资源泄漏；
- 对每阶段出具通过/不通过/风险接受结论。

### Agent D：产品经理持续审查

- 维护用户故事、工具命名、输入输出和确认交互；
- 逐阶段检查“外部 MCP Client 连接后能否直接完成真实电路任务”；
- 防止实现退化成“只生成 XML 文件”；
- 按 P0/P1/P2 控制范围和优先级，不修改 Java 代码。

## 8. 分阶段开发和门禁

### 阶段 0：基线/依赖/协议契约

- 在 `main` 上锁定 MCP SDK 2.0.0 和 Servlet 容器；
- 跑通 `./gradlew test`、ShadowJar；
- 冻结 P0 tool/resource schema、错误码和 revision 规则；
- 确认默认端口、token 和关闭行为。

门禁：原有测试全绿，依赖可打包，产品 agent 确认外部 Client 配置示例。

### 阶段 1：嵌入式 server 生命周期

- GUI 启动/关闭 server；
- localhost HTTP `/mcp`、initialize、capabilities、ping；
- stdio 兼容启动参数；
- endpoint/token 日志和最小配置说明。

门禁：标准 MCP Client 能 initialize 和 `tools/list`；普通启动无回归。

### 阶段 2：只读快照/资源

- 项目注册；
- snapshot DTO；
- resources/list/read/subscribe；
- GUI 修改事件归一化和 resync。

门禁：用户在画布手动修改后，外部 MCP Client 能读到最新 revision 和事件。

### 阶段 3：P0 实时编辑

- 元件、属性、导线、批量 Action；
- EDT/电路锁；
- undo/redo/dirty/save；
- expectedRevision/conflict。

门禁：外部 MCP Client 添加元件后当前 GUI 立即显示，桌面用户可以继续编辑和撤销；禁止通过重新打开文件验收。

### 阶段 4：完整语义能力

- P1/P2 工具、仿真、分析、导出、库、VHDL/FPGA；
- job/progress/cancel；
- 白名单菜单命令。

门禁：所有已声明能力都有对应 tool/resource 或明确限制；长任务不冻结 GUI。

### 阶段 5：安全/兼容/发布

- HTTP token/Origin、路径和大小限制；
- MCP conformance、断线、异常、并发；
- Linux/Windows/macOS 包和客户端配置文档。

门禁：高风险问题关闭或明确记录，`main` 分支全量测试和打包通过。

## 9. 推荐代码目录

```text
src/main/java/com/cburch/logisim/mcp/
  McpServerManager.java
  McpTransportConfig.java
  McpProjectRegistry.java
  McpProjectService.java
  ModelExecutor.java
  ProjectSnapshot.java
  ProjectSnapshotSerializer.java
  MutationCommand.java
  EventNormalizer.java
  McpTools.java
  McpResources.java
  McpErrors.java
```

推荐只对 `Main`/`Startup`、`ProjectActions`、`Project` 增加最小生命周期接入，不把 MCP 协议代码塞进电路模型类。

## 10. 工程量

- 阶段 0–1：约 1 周；
- 阶段 2：约 1 周；
- 阶段 3：约 1–2 周；
- 阶段 4：约 2–4 周；
- 阶段 5：约 1–2 周。

可用的 GUI 内嵌 MCP Server + 实时基础编辑 MVP 预计 3–5 工程人周；覆盖大多数 Logisim 用户能力并达到发布质量约 6–10 工程人周。由于不包含内置模型和跨机器协同，明显低于“内置 AI Client + CRDT”方案。

## 11. 当前决策

我建议直接采用：

> **GUI 启动时自动启动 localhost Streamable HTTP MCP Server，stdio 作为兼容模式；外部 MCP Client 直接调用当前内存 Project 的语义工具，事件通过 resources subscription 实时返回。**

这条路径配置最简单，也准确符合“用户打开界面后实时编辑，而不是外部生成文件再打开”。
