# Logisim Peler MCP Server 分阶段开发路线图

## 1. 已确认目标

Logisim Peler 在用户打开 GUI 后内置并启动 MCP Server。Claude、Codex 或其他外部 MCP Client 连接当前运行的 Logisim，直接读写内存中的当前 `Project`。修改必须立即反映到 Swing 画布，不允许以“外部生成 `.circ`/`.pcirc` 文件后重新打开”作为实现方式。

开发基线：

- 仓库：`/home/peler/logisim-evolution-peler-git`
- 分支：`main`
- 基线提交：`2a5829da23818cf8d1b857f728ac40e9e764a137`
- Java：21
- 基线命令：`./gradlew test` 已通过

第一版不做：内置 AI 聊天面板、跨机器多人协作、CRDT、一次性覆盖所有菜单和 FPGA 流程。

## 2. 总体架构决定

```text
Claude / Codex / 其他 MCP Client
              |
              | MCP JSON-RPC
              v
Logisim 内嵌 MCP Server
  Streamable HTTP: localhost 主路径
  stdio: 外部 Client 启动进程的兼容路径
              |
McpProjectRegistry + ModelExecutor
              |
Project.doAction / CircuitMutation / Simulator
              |
当前 Swing GUI、undo/redo、dirty、保存
```

### 2.1 传输策略

- 用户手动打开 GUI 的主路径：localhost Streamable HTTP，默认绑定 `127.0.0.1`，默认端口 `8765`，端口冲突自动递增。
- 外部 MCP Client 只需要配置 `http://127.0.0.1:<port>/mcp`；应用启动时把实际 endpoint 写入日志，并提供复制配置的轻量菜单命令。
- stdio 作为兼容模式：`logisim-evolution-peler --mcp-stdio`，stdout 只能输出 JSON-RPC，日志写 stderr。
- HTTP 默认不开公网监听；token、Origin 校验和破坏性操作确认必须存在。

### 2.2 修改原则

- 读取通过稳定 JSON DTO；不序列化内部 Java 对象。
- 写操作统一进入 `ModelExecutor`，在正确的 Swing/模型线程执行。
- 电路修改统一使用 `CircuitMutation`/`CircuitTransaction` 并包装为 `Project.doAction(Action)`。
- 每个项目维护 `projectId` 和 `revision`；写工具接受 `expectedRevision`，过期返回冲突。
- 事件由 Project/Circuit/Library/Simulator listener 归一化后，通过 resources subscription 推送。

## 3. Agent 协作流程

开发开始后保持 4 个角色：

### Agent A：主协调与架构

维护 `main` 分支、拆分任务、解决跨模块冲突、运行全量验证、整理每轮报告并向用户汇报。A 不在没有测试和产品结论时直接宣布完成。

### Agent B：功能开发

负责 MCP transport、registry、DTO、tools、resources、事件桥和必要的 Java 接入。所有改动必须列出文件、线程模型、错误处理和回滚方式。

### Agent C：测试与质量

负责单元、集成、协议、线程、并发、安全、GUI 手工和打包测试。失败必须分类为代码回归、环境问题或测试缺陷，不得用跳过测试代替修复。

### Agent D：产品经理持续审查

维护用户故事和每轮验收场景，审查工具命名、返回信息、确认交互和配置难度；检查外部 Client 是否真的改变了当前画布，而不是生成文件。D 不直接修改 Java。

### 每轮固定节奏

1. A 发布本轮目标、影响范围、非目标和验收表。
2. B 实现垂直切片并附自测结果。
3. C 并行执行自动化和手工测试。
4. D 用真实用户流程审查并给出通过/返工意见。
5. A 集成到 `main`，运行全量门禁，形成轮次报告。
6. 用户确认通过后才进入下一轮；未通过时只修复本轮问题，不扩大范围。

## 4. 轮次路线图

### 第 0 轮：基线、SDK 与契约

**目标**：建立可重复构建和冻结第一批 MCP 接口。

**开发内容**

- 锁定官方 Java MCP SDK 版本和 Servlet 容器依赖；
- 新增 MCP 配置对象和启动开关设计，不连接项目模型；
- 定义 P0 工具 schema、资源 URI、稳定错误码、revision 规则；
- 定义 `McpProjectRegistry`、`ModelExecutor`、`ProjectSnapshot` 的接口。

**测试流程**

- `./gradlew test`；
- `./gradlew check`；
- `./gradlew dependencies` 检查 SDK 版本无动态依赖；
- ShadowJar 试构建，检查 SDK ServiceLoader 文件没有丢失。

**产品验收**

- 工具名称和参数能表达“当前打开项目的电路编辑”；
- 明确 `.circ`/`.pcirc` 只用于保存，不是 MCP 协同数据通道；
- 明确第一版 P0/P1/P2 边界。

**通过标准**

- 基线测试全绿；
- schema、错误码、端口/token 默认值有文档；
- 无 MCP 参数启动行为与基线一致。

### 第 1 轮：Server 生命周期与连接探针

**目标**：GUI 启动后出现可连接但尚未编辑的 MCP Server。

**开发内容**

- `McpServerManager` 启停和应用退出清理；
- localhost Streamable HTTP `/mcp`；
- initialize、capabilities、ping、shutdown；
- stdio 兼容启动参数；
- endpoint、端口、token 的日志和轻量复制配置入口。

**测试流程**

- MCP initialize/tools/list 协议测试；
- HTTP 只监听 `127.0.0.1` 测试；
- 端口占用自动递增测试；
- token、Origin、错误 HTTP 状态测试；
- stdio stdout 纯 JSON-RPC 测试；
- 启动/关闭/重复启动无线程和端口泄漏。

**产品验收**

1. 用户打开 Logisim，不需要启动额外桥接程序。
2. 用户从日志或菜单得到 endpoint。
3. Claude/Codex 配置一个 URL 后能完成 initialize。
4. 关闭 Logisim 后 endpoint 不再接受请求。

**通过标准**：至少一个真实 MCP Client 和一个协议测试客户端都能连接；普通 GUI 回归通过。

### 第 2 轮：项目注册与只读快照

**目标**：外部 MCP Client 能准确观察当前内存项目。

**开发内容**

- 在项目新建、打开、切换、关闭时注册/注销；
- `list_projects`、`get_project`、`list_circuits`；
- `get_circuit_snapshot`、`find_components`、`get_available_tools`；
- component/wire/circuit DTO 和稳定 ID；
- `logisim://projects`、`project/{id}/snapshot` 等 resources。

**测试流程**

- 用测试 XML 创建项目，核对快照与模型对象；
- GUI 手动切换电路、库和项目后重复读取；
- 空项目、大项目、未知 projectId、未知 circuitId 错误测试；
- 快照只读性测试：MCP 读操作不能改变 undo/dirty/revision；
- JSON schema 和字段兼容性测试。

**产品验收**

- Claude/Codex 能回答当前电路有哪些元件、位置和属性；
- 读到的是当前 GUI 内存状态，不是磁盘旧文件；
- 输出内容足够模型生成下一步编辑参数。

**通过标准**：外部 Client 读取当前项目与 GUI 画面一致，且不会触发保存或模型变更。

### 第 3 轮：单一垂直切片（添加元件）

**目标**：先用一个完整操作证明“外部 Client -> MCP -> 内存 Project -> 画布”链路。

**开发内容**

- `add_component`，支持 factory/tool 名称、坐标、初始属性；
- 参数校验和结构化结果（componentId、位置、revision）；
- `ModelExecutor` + EDT 路径；
- `Project.doAction` 和 CircuitMutation 接入；
- 事件归一化和 snapshot 更新。

**测试流程**

- 单元测试：工具参数、未知 factory、非法坐标、属性类型；
- 模型测试：元件确实进入 Circuit，事件和 dirty 状态正确；
- undo/redo 测试：桌面 Ctrl-Z 和 MCP undo 均可恢复；
- GUI 手工测试：外部 Client 添加后画布立即刷新；
- 线程测试：请求线程不直接访问 Swing，EDT 无死锁。

**产品验收**

实际操作：打开空项目，在 Claude/Codex 中请求“在当前电路中心添加一个 AND gate”，无需重开文件，画布出现元件，随后用户用鼠标继续编辑。

**通过标准**：这个场景连续执行 10 次无状态漂移、无文件重载、无 GUI 卡死。

### 第 4 轮：核心电路编辑

**目标**：覆盖最常用的拓扑和属性修改。

**开发内容**

- `remove_components`、`move_components`、`rotate_components`；
- `set_component_attributes`、`set_circuit_attributes`；
- `add_wire`、`remove_wires`；
- `batch_edit` 原子事务；
- selection/focus 等只影响查看的辅助命令。

**测试流程**

- 每种操作的成功/非法/对象不存在测试；
- 批量操作中途失败必须完全回滚；
- 电路锁、导线修复、子电路引用和标签冲突测试；
- MCP 修改后 GUI repaint、dirty、undo history 一致；
- 用户 GUI 修改与 MCP 修改交错执行测试。

**产品验收**

- 外部 Client 能从自然语言完成“添加门、移动、连线、改位宽/标签”；
- 用户可以在每个 MCP 操作后继续鼠标编辑；
- 错误消息告诉模型如何修正，而不是只返回 Java 异常。

**通过标准**：P0 电路编辑场景通过，所有写工具有撤销路径。

### 第 5 轮：实时资源和并发一致性

**目标**：用户手动编辑时，外部 Client 能及时观察；多个请求不会互相破坏。

**开发内容**

- `ProjectEvent`、`CircuitEvent`、`LibraryEvent`、Simulator 事件桥；
- `resources/subscribe`、`notifications/resources/updated`；
- revision、expectedRevision、operationId；
- 有上限的事件 ring buffer 和 `resyncRequired`；
- MCP undo 与 action/operation 映射。

**测试流程**

- GUI 添加/删除/移动后订阅端收到事件；
- MCP 修改后订阅端和 GUI 都收到一致结果；
- 旧 revision 返回 `CONFLICT`，模型不变；
- 事件断线、重复、过期和完整 resync；
- 两个 MCP 请求与 GUI 操作交错的顺序/一致性测试；
- 事件高频下的内存上限和背压测试。

**产品验收**

- 用户不需要手动刷新或重新打开项目；
- Claude/Codex 在用户刚改完后能先观察到新状态再继续操作；
- 冲突提示可理解、可恢复，不覆盖用户刚做的修改。

**通过标准**：实时事件、revision 冲突和重同步全部通过；不做 CRDT 合并。

### 第 6 轮：保存、项目管理与仿真

**目标**：从“编辑电路”扩展到安全的日常工作流。

**开发内容**

- `new/open/close/save/save_as`（破坏性操作确认）；
- 电路创建/删除/重命名、主电路；
- library 查询和安全加载/卸载；
- simulator state/reset/step/tick；
- 测试向量执行以 job 形式返回。

**测试流程**

- `.pcirc` 原生保存、`.circ` 兼容导出和重新加载对比；
- 未保存变更、覆盖文件、路径越权和关闭 dirty 项目；
- 模拟器线程、超时、取消和 GUI 不冻结；
- 库依赖和子电路引用完整性。

**产品验收**

- 用户能让外部 Client 保存当前工作，但必须明确目标和确认；
- 仿真操作不破坏编辑 undo 栈；
- 长任务显示状态，失败后项目仍可继续编辑。

**通过标准**：文件、项目和仿真 P1 场景通过，破坏性操作均有确认。

### 第 7 轮：分析、外观和导出扩展

**目标**：覆盖更多用户可见能力，但保持核心稳定。

**开发内容**

- Annotation、appearance、默认方框、选择/查看辅助；
- 真值表/表达式分析、电路生成；
- HTML/HDL 导出；
- VHDL 内容和常用项目选项；
- `jobId`、progress、cancel 和 job resource。

**测试流程**

- 输出与现有 GUI 命令对比；
- 大电路性能、取消和异常清理；
- 导出不改变当前 Project；
- 外观对象、注释和保存格式 round-trip 测试。

**产品验收**：每个新增能力有明确 tool/resource，或者文档列出限制；不允许用模糊的“支持全部功能”宣传。

**通过标准**：P1 能力通过，长任务不阻塞 GUI。

### 第 8 轮：安全、兼容和发布候选

**目标**：达到可交付状态。

**开发内容**

- HTTP token、Origin、端口配置、关闭清理；
- 工具 read-only/idempotent/destructive 标记；
- 请求/快照/事件/job 大小上限；
- Claude/Codex 配置文档、版本兼容和发布包。

**测试流程**

- MCP conformance initialize/tools/resources；
- 未授权、Origin、路径、请求体、并发和资源泄漏；
- stdio/HTTP 双 transport；
- Linux/Windows/macOS 包构建；
- 全量 `./gradlew test`、`./gradlew check`、`./gradlew shadowJar`。

**产品验收**

- 新用户按文档在 10 分钟内连接一个外部 MCP Client；
- 完成“读取当前电路 -> 添加元件 -> 连线 -> 用户继续编辑 -> 保存”的端到端任务；
- 已知限制、权限和关闭方式清楚可见。

**通过标准**：无高风险未决问题，main 分支全量门禁通过，才考虑发布。

### 第 9 轮：最终端到端发布验收

**目标**：在干净用户配置和发布包上验证完整用户旅程，正式决定是否交付。

**开发内容**

- 固化 endpoint/token 的可发现和跨次启动策略；
- 完成 Claude/Codex 配置示例、版本迁移和已知限制文档；
- 整理 P0/P1/P2 能力清单和发布包；
- 清理临时诊断，确保 executor/socket 在退出时关闭。

**测试流程**

- 真实 MCP Client 和独立 JSON-RPC smoke client 各执行一次：连接、读取、添加元件、属性修改、连线、观察 GUI 修改、revision、撤销、保存；
- 测试全程禁止生成中间文件后重新打开；
- 多窗口、项目切换、断线、重启、端口/token、权限和关闭清理；
- `./gradlew test`、`./gradlew check`、`./gradlew shadowJar`；
- 发布包启动/退出并检查日志、endpoint 和工作树。

**产品验收**

- 新用户按文档在 10 分钟内连接 Claude/Codex；
- 当前 GUI 和外部 Client 始终指向同一个内存 Project；
- 用户可以继续鼠标编辑、撤销并保存；
- 已声明的 P1/P2 能力都有测试或明确限制，不能用“支持全部功能”代替证据。

**通过标准**：P0 用户故事全部通过，零高风险数据丢失/任意执行问题，main 分支全量门禁通过；否则标记为候选版本并返工，不宣布完成。

## 5. 自动化测试分层

### 5.1 单元测试

测试 DTO、schema、参数校验、错误映射、ID/revision、事件归一化和配置解析。它们不需要启动 GUI，运行快，提交即运行。

### 5.2 模型集成测试

用现有 `Loader` 创建测试项目，通过 `Project`/`Circuit` 验证：工具调用后的元件、导线、属性、dirty、undo/redo、保存和 reload。所有写操作必须检查模型不变量。

### 5.3 协议测试

使用 stdio 和 localhost HTTP 发送真实 JSON-RPC：initialize、tools/list、tools/call、resources/list/read/subscribe、错误和 shutdown。禁止只测试 Java handler 而不测试 transport。

### 5.4 GUI/线程测试

在虚拟显示环境启动 Swing，外部 MCP 请求和鼠标操作交错执行；检查画布刷新、EDT 不阻塞、CircuitLocker 不报错。必要时使用 Xvfb 和真实 MCP client smoke script。

### 5.5 安全与鲁棒性测试

验证默认只监听 localhost、Origin/token、非法路径、超大 JSON、重复 operationId、旧 revision、断线、请求超时、任务取消和应用关闭。

## 6. 每轮交付物模板

每一轮结束时主协调 agent 必须提交：

```text
轮次：R<n>
代码范围：
新增/修改文件：
外部可用工具与资源：
已通过测试命令：
手工验收步骤与结果：
产品验收结论：
已知风险/限制：
下一轮前置条件：
```

用户确认“通过”后才开始下一轮；如果用户要求调整范围，先更新路线图和 schema，再写代码。

## 7. 第一轮实际启动清单

等用户批准开始开发后，第一轮只做：

1. 主协调 agent 确认当前 `main` 无未提交功能改动，建立任务清单。
2. 开发 agent 完成 SDK/Servlet 容器依赖评估和 server 生命周期骨架。
3. 测试 agent 保存 `./gradlew test`、`check`、`shadowJar` 的基线结果，并搭建 MCP JSON-RPC smoke harness。
4. 产品 agent 审查 endpoint、端口、token、Claude/Codex 配置示例和 P0 工具 schema。
5. 主协调 agent 汇总结果，给出 R0 验收报告；只有用户确认后进入 R1。
