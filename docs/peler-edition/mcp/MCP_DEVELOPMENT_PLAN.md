# Logisim-evolution Peler MCP 开发执行方案

> **架构说明**：本文件是前一版“Logisim 作为 MCP Server、由外部客户端调用”的执行方案。根据需求澄清，后续开发以 [`MCP_EMBEDDED_CLIENT_PLAN.md`](MCP_EMBEDDED_CLIENT_PLAN.md) 为准：Logisim 内置 AI 面板和 MCP Client，本地电路工具直接操作当前 Project。本文仅保留 agent 分工、测试门禁和阶段管理方面的参考。

## 0. 当前状态

- 仓库：`/home/peler/logisim-evolution-peler-git`
- 分支：`main`
- 当前 HEAD：`2a5829da23818cf8d1b857f728ac40e9e764a137`
- 已验证环境：Git 2.34.1、OpenJDK 21.0.11
- 当前只完成调研和环境准备，尚未开始 MCP Java 功能开发。
- 总体技术结论见 `MCP_IMPLEMENTATION_REPORT.md`（该报告位于之前的源码快照目录，正式开发时会同步到本工作树）。

## 1. 目标定义

### 1.1 首个可验收目标

在同一个 Logisim Peler 桌面进程内：

1. MCP 客户端可以发现当前打开的项目和电路。
2. MCP 可以读取结构化电路快照，而不是只读取 XML 文本。
3. MCP 可以添加、删除、移动、旋转元件，修改属性，添加/删除导线，并执行批量事务。
4. MCP 修改和桌面鼠标修改共享同一 `Project`、撤销/重做栈、dirty 状态和保存流程。
5. 用户在桌面修改后，MCP 客户端可以通过资源订阅收到实时事件。
6. 多个请求不会并发破坏 Swing 或电路锁；旧 revision 请求明确返回冲突。
7. 未使用 MCP 参数启动时，原有 Logisim 行为不变。

### 1.2 完整目标

在首个目标稳定后，继续覆盖：

- 项目/文件/库/电路生命周期；
- 外观、注释、选择和查看状态；
- 仿真、测试向量、分析、HTML/HDL 导出；
- VHDL、FPGA、SoC 等复杂流程；
- 白名单式菜单命令，用于补齐无法立即建模的长尾能力。

“跨机器多人 CRDT 协作”不纳入第一版，也不作为 MCP 接入层的默认承诺；如果之后确实需要，单独立项设计权威服务和操作日志。

## 2. Agent 编排

并发槽位共 4 个，固定分工如下。主 agent 不直接吞并其他角色的职责。

### Agent A：主协调/架构负责人（当前 root）

职责：

- 维护总体架构、MCP 协议兼容性和 Java 代码边界；
- 拆分可独立交付的任务，维护阶段状态；
- 合并实现和测试结果，解决跨模块冲突；
- 负责最终构建、发布包和对用户的状态汇报；
- 任何高风险设计变更必须先记录取舍和影响。

输出：阶段计划、架构决策记录、集成分支、最终验收报告。

### Agent B：MCP 功能开发 agent

职责：

- 实现 `com.cburch.logisim.mcp` 包及最小必要的现有类改造；
- 接入官方 Java MCP SDK，完成 stdio，再完成可选 Streamable HTTP；
- 实现 project registry、ModelExecutor、快照 DTO、稳定 ID、revision、工具和资源；
- 所有模型修改必须走 `Project.doAction`/`CircuitMutation`，不能直接暴露内部对象；
- 给每个修改工具提供参数校验、错误码和操作摘要。

约束：

- 不负责自行判定验收标准；
- 不删除或重写无关的 Swing/电路代码；
- 不以任意反射、任意脚本或任意 XML 写入替代语义 API；
- 每个子任务完成后给出修改文件、线程模型和已知风险。

### Agent C：测试与质量 agent

职责：

- 为每个新工具补单元测试、模型集成测试和协议测试；
- 验证 EDT 串行化、CircuitLocker、undo/redo、dirty/save、事件顺序；
- 验证 stdio 输出纯 JSON-RPC、HTTP Origin/token、路径限制、请求上限；
- 执行 Gradle test、MCP conformance smoke test、并发/断线/重同步测试；
- 对开发 agent 的结果出具“通过/不通过/风险接受”结论。

约束：

- 测试失败不能被静默跳过；
- 区分代码回归、环境缺失和测试本身缺陷；
- 发现协议或数据模型缺陷时立即升级给主协调 agent 和产品 agent。

### Agent D：产品经理/持续审查 agent

职责：

- 持续从用户工作流审查工具命名、输入参数、返回结果和确认交互；
- 保持“用户可以完成什么”与实现范围一致，防止只完成协议外壳；
- 为每阶段维护验收场景和优先级（P0/P1/P2）；
- 检查破坏性操作是否有清晰确认、错误是否可恢复、实时事件是否能被用户理解；
- 在阶段门给出继续、返工或缩减范围的建议。

约束：

- 产品 agent 不直接修改 Java 实现；
- 建议必须关联具体用户场景和验收条件；
- 不把“支持 MCP”误认为“支持所有桌面行为”，对语义 API 与 UI 自动化明确区分。

### 2.1 协作方式

每个阶段由主协调 agent 创建一个短任务包，包含：目标、影响文件、非目标、验收条件、测试命令。开发 agent 完成后，测试 agent 和产品 agent 并行审查；主协调 agent 只在两者给出结论后集成。

没有通过阶段门时，不进入下一阶段。若测试和产品意见冲突，优先保留安全性和数据一致性，并由主协调 agent 记录取舍。

## 3. 阶段路线图与门禁

### 阶段 0：基线与协议锁定

**目标**：建立可重复构建和不会漂移的接口契约。

开发 agent：

- 确认官方 Java SDK 版本、BOM 和传输 API；
- 写出工具/资源 JSON schema 草案；
- 增加 MCP CLI 配置草案，但不启动任何编辑工具。

测试 agent：

- 运行 `./gradlew test`；
- 保存基线测试结果；
- 检查 Java 21、Gradle wrapper、依赖下载是否可用。

产品 agent：

- 确认 P0 用户故事：发现项目、读取电路、添加元件、修改属性、实时刷新、撤销；
- 确认第一版明确不支持的 UI/多人能力。

阶段门：基线测试通过，SDK 版本固定，P0 schema 和非目标获得确认。

### 阶段 1：MCP 生命周期和 stdio

**目标**：完成可被 MCP 客户端识别的最小 server，但不开放修改。

开发 agent：

- `McpServerManager`、`McpTransportConfig`；
- initialize、capability、ping、shutdown；
- stdout/stderr 隔离；
- `list_projects` 和健康检查工具。

测试 agent：

- initialize/version/capability 测试；
- 非 MCP 启动回归测试；
- stdio 中混入日志、异常和多行输出的测试。

产品 agent：

- 审查启动方式、客户端配置示例、错误提示；
- 确认普通双击启动不会意外进入 stdio 模式。

阶段门：至少一个标准 MCP 客户端能完成 initialize、tools/list、tools/call，原有测试无回归。

### 阶段 2：只读快照和资源订阅

**目标**：让 MCP 能准确观察桌面项目状态。

开发 agent：

- 项目注册/注销；
- 稳定 `projectId/circuitId/componentId/wireId`；
- snapshot DTO 和序列化器；
- `resources/list/read`；
- Project/Circuit/Library/Simulator 事件归一化；
- revision、事件序号、环形缓存和 resync 标记。

测试 agent：

- 快照与模型/保存文件的一致性；
- 元件、导线、库、电路切换和模拟事件；
- 断线、旧事件序号、完整重同步；
- 大项目快照大小和响应时间。

产品 agent：

- 审查资源 URI、字段命名、增量事件是否能被 AI 正确理解；
- 验收“用户用鼠标修改后 MCP 能看到”。

阶段门：桌面编辑可稳定反映到 resource read/updated；丢事件可以自动 resync。

### 阶段 3：核心电路编辑

**目标**：完成可撤销的实时共同编辑 MVP。

开发 agent：

- `add_component`、`remove_components`、`move_components`、`rotate_components`；
- `set_component_attributes`、`set_circuit_attributes`；
- `add_wire`、`remove_wires`、`batch_edit`；
- expectedRevision、operationId、冲突错误；
- 所有修改统一进入 ModelExecutor 和 `Project.doAction`。

测试 agent：

- 每个操作后 GUI、快照、undo/redo、dirty/save 一致；
- 事务失败不改变模型/revision；
- 并发操作按顺序执行；
- 锁异常、EDT 死锁、异常恢复和取消。

产品 agent：

- 验收用户“边拖动边让 AI 修改”的典型流程；
- 审查批量操作结果是否易读，冲突是否给出可恢复建议；
- 确认 MCP undo 不会误撤销桌面用户最后一步。

阶段门：P0 用户故事全部通过；核心编辑可被桌面 Ctrl-Z 和 MCP 资源事件观察到。

### 阶段 4：项目完整能力扩展

**目标**：覆盖用户日常操作的长尾语义能力。

开发 agent：

- 电路 appearance、annotation、selection/view state；
- 项目选项、工具栏、鼠标映射；
- 创建/删除/移动电路、库加载/卸载、VHDL 内容；
- 模拟器控制、测试向量、分析和 HTML/HDL 导出；
- job/progress/cancel；
- 白名单 `invoke_command` 补齐复杂菜单动作。

测试 agent：

- 每个工具的 schema、错误路径和长任务生命周期；
- `.pcirc` 原生保存和 `.circ` 兼容导出；
- 组件库缺失、子电路引用、FPGA/VHDL 失败恢复；
- GUI 不冻结和 job 取消后的资源清理。

产品 agent：

- 按 P1/P2 重新排序，阻止低价值 GUI 长尾拖延主线；
- 审查破坏性操作确认和权限文案；
- 确认每个“用户可做的功能”都有对应工具、资源或明确非目标。

阶段门：P0/P1 能力通过，长任务无 GUI 冻结，安全审查通过。

### 阶段 5：发布与兼容性

**目标**：形成可发布的 Peler MCP 版本。

开发 agent：

- HTTP transport（默认 localhost）；
- token/Origin 配置；
- 日志、配置文件、客户端示例和发布包；
- 版本/协议兼容处理。

测试 agent：

- MCP conformance suite；
- 多客户端、断线重连、异常输入、路径越权、请求上限；
- Windows/macOS/Linux 构建验证；
- 性能和资源泄漏检查。

产品 agent：

- 完成发布验收清单和已知限制；
- 以真实用户任务做端到端演练；
- 确认默认安全配置和升级说明。

阶段门：发布候选包在三平台基线测试通过，所有已知高风险问题关闭或明确接受。

## 4. 代码边界和合并规则

### 4.1 推荐目录

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

### 4.2 必须遵守的代码规则

- 模型变更必须走 `Project.doAction(Action)`；
- 电路批量变更使用 `CircuitMutation`/`CircuitTransaction`；
- MCP transport 线程不能直接触碰 Swing 或内部可变对象；
- MCP 返回值必须是不可变 DTO/JSON，不返回 Java 对象引用；
- 每个写工具都有 `expectedRevision` 和 `operationId`；
- 破坏性操作有权限/确认钩子；
- 不修改无关的上游逻辑，不做大规模格式化；
- 每次 agent 交付都附带测试命令和失败风险。

### 4.3 合并顺序

```text
开发 agent 完成
        |
        +--> 测试 agent：自动化测试、并发、安全
        |
        +--> 产品 agent：用户流程、schema、确认交互
        |
主协调 agent：解决意见 -> 集成 -> 全量测试 -> 阶段门
```

没有测试结果和产品验收记录的代码不进入下一阶段。任何涉及文件格式、线程模型、权限或 undo 语义的变更必须增加架构决策记录。

## 5. 估算与资源

按一名熟悉 Java/Swing/Gradle 的工程师计算：

- 阶段 0–1：约 1 周；
- 阶段 2：约 1 周；
- 阶段 3：约 1–2 周；
- 阶段 4：约 2–4 周；
- 阶段 5：约 1–2 周。

因此：

- 可用共同编辑 MVP：约 3–5 工程人周；
- 覆盖大多数用户能力并达到发布质量：约 6–10 工程人周；
- 跨机器多人 CRDT：另行立项。

估算不包含等待外部 MCP 客户端行为变化、平台打包环境和复杂 FPGA 硬件验证。

## 6. 批准后的第一轮动作

你确认方案后，第一轮只做以下内容，不直接进入全量功能：

1. Agent A 建立任务清单、分支策略和阶段 0 验收表。
2. Agent B 锁定 Java SDK、补 MCP CLI 配置骨架，不开放写操作。
3. Agent C 跑 `./gradlew test` 和基线打包，记录结果。
4. Agent D 审核 P0 用户故事、工具命名和 schema 草案。
5. 主协调 agent 汇总四方结果，提交阶段 0 评审，再决定是否进入阶段 1。

## 7. 需要你确认的范围

开始写 Java 功能前，只需要确认以下三点：

1. 第一版是否采用“单桌面进程内实时共同编辑”作为主目标？
2. 是否同意 stdio 默认、localhost Streamable HTTP 可选？
3. 是否接受第一阶段先不做跨机器 CRDT 多人协作？

这三点确认后，开发 agent 才开始修改 Java；在此之前只继续做环境、协议和测试基线工作。
