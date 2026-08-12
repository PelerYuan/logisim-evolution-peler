# MCP Server 产品验收记录与 R0-R9 放行条件

本记录是产品/验收 agent 对 MCP Server 路线的补充，不修改 Logisim 的 Java 实现。它以用户已确认的目标为准：用户正常打开 Logisim GUI，外部 MCP Client（Claude、Codex 等）连接 GUI 内置的 MCP Server，直接读写当前内存中的 `Project`；画布立即更新，用户可以继续用鼠标编辑；不能通过生成文件再重开来伪造实时编辑。

## 1. 产品结论

现有 `MCP_SERVER_ROADMAP.md` 的总体方向正确，但有四个必须在实现中锁定的门槛：

1. 用户要求推进到 R9。现有路线写到 R8，因此 R9 定义为最终端到端发布验收，不能把 R8 直接称为完成。
2. 配置必须可持续使用。端口自动递增和每次启动随机 token 如果没有固定发现机制，会使已配置的 Claude/Codex 失效。默认端口应可配置且优先复用，token 应持久化或由一键“复制配置”生成完整配置；日志不能是唯一发现入口。
3. “当前项目”必须有明确语义。多窗口时默认目标是最近激活的项目，但工具结果必须返回 `projectId`、项目名和当前电路名；请求也必须允许显式 `projectId`，避免 AI 修改错窗口。
4. MCP 事件不能只依赖元件增删。现有 `CircuitEvent` 没有通用属性变更事件；属性、撤销/重做、文件切换应由 `ProjectEvent`/快照差异补齐，否则外部 Client 会看不到用户的真实修改。

现有代码已提供可复用的产品基础：`Project.doAction` 维护 undo/redo、dirty 和 action 事件（`src/main/java/com/cburch/logisim/proj/Project.java`）；`Projects` 管理多窗口和最近激活窗口（`src/main/java/com/cburch/logisim/proj/Projects.java`）；`CircuitListener`/`ProjectListener` 可作为实时状态桥接入口。验收不接受“只调用序列化器或重载 `.circ`/`.pcirc`”的实现。

`MCP_DEVELOPMENT_PLAN.md` 和 `MCP_EMBEDDED_CLIENT_PLAN.md` 中保留了早期“内置 AI/MCP Client”讨论，不能作为当前实现目标；当前唯一产品目标是 Logisim GUI 内置 **MCP Server**，由外部 MCP Client 连接。若代码、菜单或文档出现内置 AI 面板，应在产品门禁中退回。

## 2. 每轮用户故事、场景和门禁

### R0：基线、依赖和协议契约

**用户故事**：作为外部 MCP Client 作者，我能看到稳定的工具/资源 schema、错误码、revision 和配置示例，并知道哪些能力尚未支持。

**验收场景**：在没有 MCP 参数的普通启动下，Logisim 行为和基线一致；文档明确 P0/P1/P2、默认监听地址、token 生命周期、当前项目语义和“不生成文件协同”的约束。

**测试流程**：运行 `./gradlew test`、`check`、依赖锁定和 ShadowJar；用 schema 校验器验证工具输入输出；检查发布包没有动态依赖或 ServiceLoader 丢失。

**放行条件**：协议契约评审通过，P0 工具能完成“读电路、添加元件、修改、连线、撤销”的最小任务描述；没有把 MCP Client、AI 面板或跨机器 CRDT 混入本项目目标。

### R1：GUI 内置 Server 生命周期

**用户故事**：我打开 Logisim 后不需要启动桥接程序或 MCP 面板，Claude/Codex 能按一份短配置完成连接；我关闭 Logisim 后连接立即失效。

**验收场景**：GUI 启动后 Server 自动启动并只监听 `127.0.0.1`；菜单或状态入口可复制包含 endpoint/token 的完整客户端配置；端口冲突时显示实际端口且下一次优先复用用户配置；应用退出释放端口。stdio 只作为兼容模式，不能污染 stdout。

**测试流程**：真实 JSON-RPC `initialize`、`ping`、`tools/list`、`shutdown`；HTTP 绑定地址、Origin/token、端口冲突、重复启动、退出清理；stdio stdout 逐行纯 JSON 测试；至少一个真实 MCP Client 连接探针。

**放行条件**：新用户从打开 GUI 到客户端 initialize 不超过 10 分钟且不需编辑多处配置；失败时错误信息明确给出 endpoint、认证和下一步。

### R2：项目注册和只读快照

**用户故事**：我可以让外部 Client 准确描述当前画布中的项目、电路、元件、导线和属性，且读操作不会改变模型、dirty 或 undo。

**验收场景**：打开/新建/切换/关闭项目，`list_projects` 和 `get_project` 反映真实内存状态；用户用鼠标添加或移动对象后重新读取快照可见最新状态；多窗口请求显式 `projectId` 时不会操作其他窗口。

**测试流程**：空项目、大项目、多电路、未知 ID、GUI 编辑后快照对比；重复读取检查 revision、dirty、undo 不变；JSON schema、字段缺省和大小上限检查。

**放行条件**：快照可用于构造下一步编辑调用；不会使用磁盘旧文件作为事实来源；稳定 ID 在同一项目会话内经过修改、撤销、重做仍可解析，失效 ID 返回可恢复错误。

### R3：添加元件的完整实时垂直切片

**用户故事**：我在 Claude/Codex 中说“在当前电路中心添加 AND gate”，元件直接出现在已经打开的画布上，我马上可以继续鼠标编辑。

**验收场景**：客户端调用 `add_component` 后返回 `projectId`、`circuitId`、`componentId`、坐标和新 revision；当前 Swing 画布立即重绘；不保存、不关闭、不重开文件也能继续选择、移动和连线；Ctrl-Z 能撤销该操作。

**测试流程**：合法/非法坐标、未知 factory、属性类型、重复请求；模型、画布、dirty、undo、事件检查；请求线程与 EDT 交错 10 次，检查无死锁、无状态漂移、无文件重载。

**放行条件**：真实外部 Client 连续完成 10 次操作；失败事务不产生半个元件、不递增 revision；产品验收日志明确记录“同一内存 Project”。

### R4：核心电路编辑

**用户故事**：我可以让外部 Client完成常用编辑：移动、旋转、删除、改属性、加/删导线以及一个可撤销的批量操作。

**验收场景**：从自然语言任务“添加两个门，设置位宽，移动并连线”开始，工具返回结构化对象 ID 和摘要；用户在任意一步用鼠标改动，下一步仍基于当前画布；批量操作中任一步失败时整体回滚。

**测试流程**：每个工具的成功、未知对象、非法属性、拓扑冲突；批量原子性；子电路、导线端点和标签冲突；GUI repaint、dirty、undo/redo；MCP 与鼠标交错请求。

**放行条件**：所有 P0 写工具均有明确撤销路径和可操作错误；不暴露任意 Java 方法、类名、脚本或反射调用；删除/清空等操作显示影响范围。

### R5：实时资源、revision 和并发一致性

**用户故事**：用户手动编辑时，外部 Client 不需要轮询或重开文件就能收到更新；如果 AI 基于旧状态操作，系统不会覆盖用户刚做的修改。

**验收场景**：GUI 添加、删除、移动、改属性、切换电路、撤销/重做后，订阅端收到带 `projectId`、`revision`、`eventId` 的更新；事件丢失时客户端能读完整快照；旧 `expectedRevision` 返回 `CONFLICT` 和当前摘要。

**测试流程**：resources read/subscribe、断线重连、重复/过期事件、ring buffer 上限；两个 MCP 请求和 GUI 操作交错；高频事件背压；MCP undo 不能盲目撤销桌面用户最近动作。

**放行条件**：事件与快照最终一致，冲突可由“重读后重试”恢复；不承诺 CRDT 或无冲突合并；属性变更不能静默丢失。

### R6：保存、项目管理和仿真

**用户故事**：我可以让外部 Client 新建/切换电路、保存当前工作和执行基本仿真，同时仍使用桌面保存和 undo 语义。

**验收场景**：保存/另存为明确显示目标路径并在覆盖、关闭 dirty 项目、删除电路、卸载库等破坏性操作前确认；仿真 reset/step/tick 不污染编辑 undo；长测试返回 job 状态且 GUI 可继续操作。

**测试流程**：原生 `.pcirc` 保存、`.circ` 兼容导出和 reload 对比；路径越权、取消、超时、库依赖、子电路引用；仿真线程、GUI 冻结和 job 清理。

**放行条件**：用户能判断每个文件操作的目标和结果；失败或拒绝后项目仍可编辑；任何外部路径/命令都不能由模型静默执行。

### R7：分析、外观和导出扩展

**用户故事**：我可以逐步使用更多 Logisim 能力，例如注释、外观、真值表/表达式分析、HTML/HDL 导出；每项能力都有明确 API 或明确限制。

**验收场景**：导出结果与同一项目通过 GUI 菜单导出的结果一致；分析和导出不会改变当前 Project；外观、注释、VHDL 内容保存后可 round-trip；长任务支持进度和取消。

**测试流程**：GUI/MCP 输出对比、大电路性能、取消后的资源清理、保存格式 round-trip、异常库/HDL 输入。

**放行条件**：新增能力按 P1/P2 排序，不为覆盖率牺牲 P0 稳定性；宣传和工具描述不使用“支持全部功能”这类无法验收的表述。

### R8：安全、兼容和发布候选

**用户故事**：我可以放心把 Server 留在桌面应用里，知道默认不会被局域网任意控制，且不同 MCP Client 得到一致协议行为。

**验收场景**：默认仅 loopback；未授权、错误 Origin、过大请求、非法路径和并发超限被拒绝；destructive 工具具备确认钩子；HTTP/stdio 的 stdout、日志、关闭行为符合文档。

**测试流程**：MCP conformance、认证/Origin、路径和请求大小、资源泄漏、断线重连、并发/超时、Linux/Windows/macOS 包构建。

**放行条件**：高风险安全问题关闭或有明确风险接受；普通启动无 Server 配置也不会监听公网；升级时 schema/version 和客户端配置有迁移说明。

### R9：最终端到端发布验收

**用户故事**：作为新用户，我可以按文档打开 Logisim、连接 Claude/Codex，读取当前电路、添加元件、连线、观察我的鼠标修改、撤销并保存，整个过程都发生在同一个 GUI 内存项目中。

**验收场景**：在干净用户配置和已安装发布包上，从零完成以下任务：连接 -> `list_projects` -> 读取快照 -> 添加 AND/OR 门 -> 设置属性 -> 加线 -> 用户鼠标移动一个门 -> 客户端读到新 revision -> 客户端基于新 revision 修改 -> 桌面 Ctrl-Z/MCP undo -> 明确保存。全程禁止生成中间 `.circ`/`.pcirc` 再重开。

**测试流程**：真实 MCP Client 和协议 smoke client 各跑一遍；P0 全量回归；多窗口、断线、重启、端口/token 持久化；`./gradlew test`、`check`、`shadowJar`；发布包启动/退出和日志检查；记录性能、已知限制和非目标。

**最终放行条件**：P0 用户故事全部通过，P1 已声明能力有测试或明确限制，零高风险数据丢失/任意执行问题；新用户在 10 分钟内完成连接；主分支干净且全量门禁通过。否则只能标记为“候选版本/返工”，不能宣布 MCP 功能完成。

## 3. 持续产品审查重点

### P0（任何一项失败都不能放行）

- 内存 Project 直写和画布立即刷新，不能文件重载。
- 当前项目/当前电路语义、稳定对象 ID 和 revision 冲突。
- `Project.doAction`、dirty、undo/redo 与 GUI 共享同一操作历史。
- 属性变更、撤销/重做、文件切换可观察，不只是元件增删事件。
- localhost 默认安全、破坏性操作确认、路径和任意命令边界。
- endpoint/token 可发现且跨次启动不使客户端配置无故失效。

### P1（R6-R7 完成但不能拖垮 P0）

- 电路管理、仿真、分析、导出、外观、VHDL 和可取消 job。
- 每个工具的错误能指导模型修正，长任务不冻结 EDT。

### P2（可记录限制）

- FPGA/SoC 全流程、所有冷门菜单、跨机器协作、CRDT、任意脚本和内置 AI 聊天面板。

## 4. 给开发和测试 agent 的固定审查意见

**开发 agent**：每次交付必须列出真实 MCP 方法、输入/输出示例、目标项目解析规则、线程/EDT路径、revision 更新、undo/action 来源、失败回滚和是否 destructive；用现有 `Project`/`CircuitMutation` 语义接入，不把模型对象或 Swing 控件暴露给协议层。

**测试 agent**：每轮至少有一个真实 transport 测试和一个 GUI/模型联动测试；失败分类为代码回归、环境问题或测试缺陷；不得以跳过 GUI 或只测 Java handler 代替端到端证据。优先覆盖多窗口、属性事件、撤销归属、端口/token 重启和关闭清理。

**主协调 agent**：每轮只在开发、测试和产品均给出放行结论后推进；R0-R9 每轮记录代码范围、测试命令、手工场景、产品结论、已知风险和下一轮前置条件；用户要求“继续”不等于跳过未通过门禁。

## 5. R0 当前实现审查（条件放行）

当前 `src/main/java/com/cburch/logisim/mcp/` 的契约骨架可以作为 R0 起点，但不能据此宣布 R0 完成：

- `McpServerConfig` 的 token 默认为空，而路线要求 HTTP 认证。R1 前必须决定持久 token 或明确仅 loopback 的无 token 模式，并在客户端配置中体现；不能出现“文档要求 token、默认实现没有 token”的分裂。
- `McpServerConfig` 接受任意 host。默认值是正确的，但产品门禁要求非 loopback 监听必须显式 unsafe 开关并给出警告；仅设置普通 host 属性不应意外暴露控制面。
- 端口 `0`、端口冲突和实际 endpoint 的发现/持久化还没有产品闭环。R1 要验证重启后配置不会无故失效，并让用户一键获得完整配置。
- JSON-RPC dispatcher 对 `tools/call.arguments` 的非对象输入当前会静默当成空对象；未知字段、缺失参数、重复工具注册也应返回稳定、可指导模型修正的错误。协议测试不能只测成功路径。
- `McpModelExecutor` 使用同步 `invokeAndWait` 串行化 EDT。需要测试 MCP 请求、Project listener、画布事件和关闭流程交错时无死锁、无无限等待，并证明所有 handlers 共享同一 executor。
- `initialize`、`resources/read`、`resources/subscribe` 的实际 transport 响应要用标准 MCP client 验证，不能只根据 dispatcher 单元测试放行。

本次并行开发期间运行 `./gradlew test --no-daemon` 未通过：`compileJava` 报出大量已有类的 `bad class file`/`NoSuchFileException`，同时涉及新增 MCP 类。这个结果先标记为构建环境/并行编译阻断，不能归因于产品通过；主协调 agent 需要在停止并行 Gradle 进程后执行一次干净、串行的 `clean test`，再判断是否存在真实编译错误。

随后在没有并行 Gradle 任务时重跑 `./gradlew test --no-daemon`，结果为 `BUILD SUCCESSFUL`；MCP 专项测试和全量现有测试均通过。并行构建踩踏问题已不再复现，但后续阶段仍必须避免多个 agent 同时写/清理 `build/`。

对当前 R1 骨架的额外审查：`McpServerManager` 使用 JDK `HttpServer` 目前只处理 POST，尚未证明完整 Streamable HTTP（会话、GET/通知、断线重连）兼容；这可以作为 R1 探针的临时实现，但 R8/R9 不能以“能 POST initialize”代替标准 MCP transport conformance。`McpHttpHandler` 对超大 body、OPTIONS、CORS 及异常响应也需要真实 HTTP 测试；`McpServerManager` 当前 dispatcher 没有项目 tools/resources，R1 只能验收连接探针，不能宣称已能编辑。`Main` 已在正常启动后调用 `startDefault`，因此 R1/R2 必须验证关闭 Server、headless/CLI 启动和端口清理不会改变既有工作流。

## 6. 每轮产品结论格式

```text
轮次：R<n>
产品结果：通过 / 有条件通过 / 返工
已验证用户场景：...
阻断问题：...
配置/可用性风险：...
必须补测：...
下一轮放行条件：...
```
