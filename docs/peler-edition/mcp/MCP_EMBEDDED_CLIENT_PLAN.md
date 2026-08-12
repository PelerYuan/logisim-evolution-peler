# Logisim Peler 内置 MCP Client/AI 编辑方案（修订版）

> **已废止**：需求已确认是 Logisim 内置 MCP Server，由 Claude/Codex 等外部 MCP Client 连接。请以 [`MCP_SERVER_ROADMAP.md`](MCP_SERVER_ROADMAP.md) 为准；本文件仅保留为历史讨论记录。

## 1. 需求澄清

目标是：用户正常打开 Logisim Peler 界面，在应用内部打开一个 AI/MCP 面板，直接对当前项目进行实时编辑。AI 的修改必须立即出现在当前 Swing 画布中，不允许采用“外部程序生成 `.circ` 文件，再让 Logisim 重新打开”的工作流。

开发基线固定为：

- Git 仓库：`/home/peler/logisim-evolution-peler-git`
- 分支：`main`
- 当前基线提交：`2a5829da23818cf8d1b857f728ac40e9e764a137`
- Java：21
- 现有基线：`./gradlew test` 已通过

此前的 `MCP_IMPLEMENTATION_REPORT.md` 主要讨论“Logisim 作为 MCP Server、由外部客户端调用”的方向。本修订版覆盖该方向，后续开发以本文件为准。

## 2. 关键架构判断

MCP Client 本身只负责连接 MCP Server、发现工具/资源并调用它们；它不是大语言模型，也不会自动产生 AI 回复。要实现“用户在 Logisim 内和 AI 一起编辑”，应用需要同时具备：

1. **内置 AI Model Client**：调用 OpenAI 或 OpenAI-compatible 的模型 API，负责对话和工具调用决策。
2. **内置 MCP Client**：连接用户配置的外部 MCP Server，提供额外工具和资源。
3. **本地 Logisim Tool Registry**：把当前 `Project` 的读取、编辑、仿真能力直接注册给内置 AI agent。它们在同一 JVM 内执行，不需要通过文件或 loopback MCP Server。
4. **Project Mutation Service**：把本地工具调用转换为现有 `Project.doAction`/`CircuitMutation`，保证 GUI、撤销重做、dirty 状态和保存流程一致。

推荐的整体关系：

```text
用户
  |
  v
Logisim Swing UI + 内置 Chat/AI 面板
  |
AgentSession / ToolRouter
  |------------------------------|
  v                              v
本地 Logisim 工具                 MCP Client
(同一 JVM、直接 Project)          (stdio/Streamable HTTP，可选)
  |                              |
  v                              v
ProjectMutationService        外部 MCP Servers
  |
Project / Circuit / Simulator
```

这样既满足实时编辑，又保留 MCP 的可扩展性。核心编辑不依赖任何外部 MCP Server，配置最简单；外部 MCP Server 用于文件、资料、脚本或其他用户选择的工具。

## 3. 最简配置策略

### 3.1 首次使用

用户只需要：

1. 打开 Logisim Peler；
2. 打开内置 AI 面板；
3. 选择模型提供商和模型，填写 API Key；
4. 输入“在当前电路添加一个 4 位加法器”等自然语言指令。

本地电路编辑工具默认内置，不需要配置 MCP Server，不需要导出文件，不需要启动外部桥接程序。

### 3.2 模型提供商设计

为避免把应用锁死在单一厂商，第一版实现 `ModelProvider` 抽象，优先支持 OpenAI-compatible HTTP API：

- 默认 Base URL：OpenAI API；
- 用户可改为兼容 OpenAI Chat Completions/Responses 的服务地址；
- 配置项：Base URL、API Key、模型名、温度/最大输出、请求超时；
- 流式输出使用 HTTP streaming，用户可以看到模型逐步回复；
- API Key 默认优先从环境变量读取，设置面板允许覆盖；不要把明文 key 写进项目文件。

这样可以兼容 OpenAI、企业代理、兼容网关和本地模型服务。后续可增加 Anthropic/Gemini 原生适配器，但不阻塞第一版。

### 3.3 外部 MCP Server 配置

外部 MCP Server 是可选项，采用设置向导：

- 本地 server：命令、参数、环境变量，使用 stdio；
- 远程 server：URL、可选 bearer token，使用 Streamable HTTP；
- 启动时只连接启用的 server；连接失败不影响 Logisim 本地编辑；
- 所有远程工具显示来源 server、权限和破坏性标记；
- 不默认监听 HTTP，也不要求用户安装 Node/Python 桥接程序。

## 4. 内置 Agent 工作流

```text
用户输入
  |
  v
AgentSession 读取当前 Project snapshot/revision
  |
  v
ModelProvider 发送 prompt + 本地工具 schema + 外部 MCP 工具 schema
  |
  +-- 普通文本 --> 流式显示到 Chat 面板
  |
  +-- 本地工具调用 --> 参数校验 -> 用户确认(必要时) -> EDT/ModelExecutor
  |                         -> Project.doAction -> 返回结构化结果
  |
  +-- 外部 MCP 工具调用 --> MCP Client -> 外部 server -> 返回结果
  |
  v
将工具结果回传模型，直到得到最终回复或用户取消
```

### 4.1 本地工具不走文件

本地工具直接持有 `Project` 服务引用：

- 查询读取当前内存模型；
- 写操作创建 `CircuitMutation` 和 `Action`；
- 在 Swing EDT/模型执行器中串行执行；
- 事件立即刷新画布和快照；
- 只在用户明确要求时保存到 `.pcirc`/`.circ`。

模型操作完成后，用户不需要重新打开项目，也不会因为外部文件覆盖而丢失当前 undo 历史。

### 4.2 实时上下文

不把每一次鼠标移动都发送给模型。推荐：

- Project/Circuit 变更后递增 `revision`；
- 当前 chat session 保存最近一次已知 revision；
- 用户或 AI 完成一次语义编辑后，发送精简事件摘要；
- 模型需要细节时调用 `get_circuit_snapshot`；
- 如果模型要基于旧 revision 修改，返回冲突并让 agent 先重读。

## 5. 本地 Logisim 工具范围

### P0：必须首批完成

- `get_project`、`list_circuits`、`get_circuit_snapshot`；
- `get_available_tools`；
- `add_component`、`remove_components`、`move_components`、`rotate_components`；
- `set_component_attributes`、`set_circuit_attributes`；
- `add_wire`、`remove_wires`、`batch_edit`；
- `undo`、`redo`、`get_revision`；
- `get_simulator_state`、`simulator_reset`、`simulator_step`、`simulator_tick`。

### P1：第二阶段

- 电路创建/删除/重命名、主电路；
- Annotation、appearance、选择和视图辅助；
- 项目选项、库加载/卸载、VHDL 内容；
- 测试向量、真值表分析、HTML/HDL 导出；
- `run_job`、`get_job`、`cancel_job`。

### P2：长尾功能

- FPGA/SoC 工作流；
- 复杂 GUI 菜单命令；
- 白名单式 `invoke_command`，只能调用预注册 commandId，不能执行任意 Java 方法或脚本。

## 6. 实时编辑与安全边界

### 6.1 线程模型

- 模型网络请求在后台线程执行；
- `ModelExecutor` 将本地模型查询/修改串行化；
- 修改通过 EDT 和现有电路锁执行；
- 长任务返回 job，不阻塞 EDT；
- 用户可以随时取消当前 agent loop；
- 应用关闭时关闭 ModelProvider、MCP sessions 和后台 executor。

### 6.2 确认策略

无需确认：读取快照、查找元件、仿真 step、普通属性修改（可配置）。

需要确认：清空电路、删除大量对象、覆盖保存、卸载正在使用的库、FPGA 下载、外部命令、访问远程 MCP 工具。

确认显示：工具名、目标项目、电路、影响对象数量、变更摘要和撤销方式。用户拒绝后把拒绝原因返回模型，模型必须继续解释或改用安全方案。

### 6.3 API Key 和隐私

- API Key 不进入 `.circ`/`.pcirc`；
- Chat 内容默认只发送用户明确提交给模型的上下文；
- 电路快照发送前显示当前模型/上下文范围；
- 外部 MCP Server 看到的数据必须标示来源和权限；
- 设置中提供“仅本地模型/禁用外部 MCP”模式。

## 7. Agent 分工与阶段门

开发开始后固定使用 4 个 agent 槽位：主协调/架构、功能开发、测试质量、产品经理持续审查。

### 阶段 0：基线与契约

- 架构：锁定 MCP Java SDK 2.0.0、ModelProvider 接口、Tool DTO 和 revision 规则；
- 开发：建立 `McpClientManager`、`AgentSession`、`ModelProvider` 空骨架，不接入编辑；
- 测试：保持当前 `./gradlew test` 绿灯，加入协议/schema 基线；
- 产品：确认 P0 用户故事、面板入口和确认文案。

门禁：SDK 依赖可构建，非 MCP 启动无回归，P0 schema 通过审查。

### 阶段 1：内置 AI 面板和模型连接

- 开发：Swing Chat 面板、流式消息、取消、provider 设置、错误处理；
- 测试：模拟 provider、超时、断网、流式中断、key 不存在；
- 产品：验收“打开应用即可配置并发送第一条指令”，不要求用户安装外部组件。

门禁：可以完成真实或 mock 的对话，但尚未修改电路。

### 阶段 2：本地只读工具

- 开发：project/circuit snapshot、available tools、simulator state；
- 测试：快照和 GUI/模型一致，桌面编辑后 agent 重读正确；
- 产品：验收模型能理解当前电路，而非只看到文件名。

门禁：AI 能解释当前电路，所有数据来自内存 Project。

### 阶段 3：本地实时编辑 MVP

- 开发：P0 编辑工具、EDT 串行、revision/conflict、Project.doAction；
- 测试：GUI 即时刷新、undo/redo、dirty/save、并发和失败事务；
- 产品：验收“用户打开电路，输入一句话，画布立即变化”，以及拒绝/撤销流程。

门禁：P0 编辑用户故事全通过，不能通过外部文件重载替代。

### 阶段 4：外部 MCP Client

- 开发：stdio server 管理、工具发现、资源读取、可选 Streamable HTTP；
- 测试：连接失败隔离、本地工具仍可用、权限/Origin/token、session 关闭；
- 产品：验收添加一个外部工具无需手工编辑复杂配置文件。

门禁：外部 MCP 是增量能力，断开不影响本地 Logisim AI 编辑。

### 阶段 5：完整能力和发布

- 开发：P1/P2 工具、长任务、打包、迁移和文档；
- 测试：完整回归、三平台构建、性能、隐私、安全和资源泄漏；
- 产品：按真实任务完成发布验收和已知限制清单。

## 8. 推荐代码目录

```text
src/main/java/com/cburch/logisim/ai/
  AgentSession.java
  ModelProvider.java
  OpenAiCompatibleProvider.java
  ModelMessage.java
  ToolRouter.java
  ChatHistory.java

src/main/java/com/cburch/logisim/mcp/
  McpClientManager.java
  McpServerConfig.java
  McpToolCatalog.java
  McpResourceCatalog.java
  McpSessionRegistry.java

src/main/java/com/cburch/logisim/ai/logisim/
  LogisimToolRegistry.java
  ProjectQueryService.java
  ProjectMutationService.java
  ProjectSnapshot.java
  ModelExecutor.java
  RevisionTracker.java
```

不建议把 AI、MCP transport 和电路操作混在 `Project` 类中；`Project` 只增加必要的事件/生命周期入口。

## 9. 依赖和实现注意事项

官方 Java SDK 2.0.0 推荐 Gradle：

```kotlin
implementation(platform("io.modelcontextprotocol.sdk:mcp-bom:2.0.0"))
implementation("io.modelcontextprotocol.sdk:mcp")
```

SDK 可直接用于 Java 21。第一版内置 MCP Client 先实现 stdio；Streamable HTTP 后置，因为 HTTP 传输需要 Servlet 容器和更复杂的安全生命周期。ShadowJar 需要检查 SDK 的 ServiceLoader 文件是否被保留（必要时配置 `mergeServiceFiles()`）。

模型 API 第一版优先使用 JDK `HttpClient` + 已有/SDK JSON binding，避免再引入完整 Spring Boot。所有网络依赖和 API 响应都必须有超时、大小限制和可取消路径。

## 10. 估算

- 阶段 0–1：1–2 工程人周；
- 阶段 2：1 工程人周；
- 阶段 3：1–2 工程人周；
- 阶段 4：1–2 工程人周；
- 阶段 5：1–2 工程人周。

内置 AI + 本地实时编辑 MVP 预计 4–6 工程人周；带外部 MCP 配置、P1/P2 能力和发布质量预计 6–9 工程人周。这个估算不包含训练模型，也不包含跨机器协同。

## 11. 目前还需要确认的一件事

为了保持配置简单，我建议默认实现 **OpenAI-compatible API**，同时允许用户填写自定义 Base URL；这样既支持 OpenAI，也支持兼容网关和本地服务。

需要你确认的是：

> 是否同意第一版按“OpenAI-compatible API + 内置 AI 面板 + 内置本地 Logisim 工具 + 可选外部 MCP Client”实现？

如果你没有特别指定其他模型协议，我将按这个方案进入阶段 0；不会再把 Logisim 设计成必须由外部 MCP 客户端连接的 MCP Server。
