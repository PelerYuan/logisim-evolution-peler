# MCP v2 设计：面向对象的电路操作层

这份文档是重做 MCP 的设计依据和交接说明。它假定读者没有参与过之前的讨论，所以把结论和**得出结论
的证据**放在一起——特别是那些从 Logisim 源码里挖出来、重新调研要花很久的事实。

状态：设计已定，尚未动工。未决问题集中在最后一节。

---

## 一、为什么重做

现在的 MCP 服务器（`src/main/java/com/cburch/logisim/mcp/`，约 5000 行）能用，但让模型搭一个全加器
要花很久。把成本拆开，来源有五个：

| 成本 | 来源 |
| --- | --- |
| 30 次工具调用往返 | 交互形态：一次一个 JSON 工具调用 |
| 推导引脚坐标 | 信息缺失：快照里根本没有端口信息，模型必须复现 Logisim 的内部几何 |
| `add_wire` 连了空气也返回成功 | 反馈设计：没有局部错误信号，只有全局真值表 |
| 47 个工具，描述面向维护者 | API 设计：`"Add a component through CircuitMutation and Project.doAction"` 说不清什么时候该用 |
| 每步之后重建心智模型 | 状态可见性 |

**关键判断：这五条没有一条需要"提高抽象层级"才能解决。** 早期讨论一度认为要把 API 换成声明式的
参数化指令（"这个电路应该实现 S = a ^ b ^ cin"），后来发现那是把两个独立的轴混在了一起：

- **抽象层级**：低层几何操作 ↔ 高层声明式规格
- **交互形态**：一次一个工具调用 ↔ 一段脚本

大部分成本在第二个轴上，以及信息缺失和反馈缺失这两个与抽象层级无关的问题上。而且提高抽象层级有
一个致命的副作用：**声明式生成只能生成，不能编辑**。维护者明确需要 MCP 能修改用户手画的电路，那
就必须保留操作级的自由度。

所以方向是：**保留操作自由度，换掉交互形态，补上端口信息和错误反馈。**

---

## 二、总体架构

三层，职责边界清楚：

```
MCP 工具面（3 个工具）
      ↓
Lua 脚本层        便利与组合：生成器、对齐、批量操作、模型自己写的 helper
      ↓
Java 操作层       正确性地基：端口模型、校验、布线器、事务
      ↓
Logisim 模型      Circuit / CircuitMutation / Project.doAction
```

**分层判据（写新代码时按这句话决定放哪一层）：**

> 出错了会不会静默产生一个错的电路？会 → Java；不会 → Lua。

按这条划：校验、布线器、事务、端口模型、Dot 栅格留在 Java，必须可单元测试，而且必须不能被脚本绕过
——如果布线器在 Lua 里，模型可以写一个自己的、不做校验的版本。`ripple_adder(n)`、`align(...)`、
批量操作放 Lua，错了顶多难看或报错。

Java 层不 import Swing，不 import mcp，线程调度由适配层负责（见第九节的锁规则）。

---

## 三、已确认的 Logisim 模型事实

这一节是这份文档最省时间的部分。每条都是读源码确认过的，不要凭直觉推翻。

### 3.1 连接是几何的，且只在端点成立

`CircuitWires.connectWires` 只在 `wire.e0` / `wire.e1` 处合并 `WireBundle`
（[CircuitWires.java](../../../src/main/java/com/cburch/logisim/circuit/CircuitWires.java)，约 813 行）。
`CircuitPoints.add` 对 Wire 也只登记两个端点。

**Logisim 没有网表对象。** 你不能"逻辑地"连接两个端口，必须真的放线让它们在坐标上碰上。这是旧 MCP
那个"`add_wire` 成功了但什么都没连上"的根源。

### 3.2 但每次事务后 `WireRepair` 自动跑

[CircuitTransaction.java:67](../../../src/main/java/com/cburch/logisim/circuit/CircuitTransaction.java)
在每个被修改的电路上运行 `WireRepair`，它做三件事：merge（合并共线的线）、overlap、**split**。
`doSplits`（[WireRepair.java:199](../../../src/main/java/com/cburch/logisim/circuit/WireRepair.java)）
把任何"内部包含了其他已注册坐标"的线切开。

所以 T 形连接自动成立，程序化建线和编辑器画线享受同样的待遇，不用手工切线。

### 3.3 推论：交叉安全，穿过引脚危险

由 3.1 和 3.2 合起来：

- **两条线纯十字交叉不相连**。交叉点没有任何端点注册，`doSplits` 不会在那里切。
- **线经过一个元件引脚会被切开并连上**。引脚坐标是注册过的位置。

**这条对布线器极其有利**：不需要做通道分配来避免短路，线可以随便交叉。布线器唯一必须保证的是
**不从不属于本网的引脚上穿过**。

同时它也保证了**端点遍历是完备的**——提交后从端口坐标出发用 `Circuit.getWires(Location)` 做并查集
就能算出真实网表，不必考虑"某个点落在某条线中间"的情况。

### 3.4 Wire 是直线段，而且构造函数不校验

`Wire.create(e0, e1)` 的私有构造函数
（[Wire.java:79](../../../src/main/java/com/cburch/logisim/circuit/Wire.java)）直接假设 x 相等或 y 相等，
**传一条对角线进去它会静默接受**并按 x 排序，产出一条非法的线。

所以"折了很多次的线"是我们的高层概念，落地时必须自己拆成 N 条轴对齐的 `Wire`。

### 3.5 端口没有名字，四个候选名字源都不够用

| 来源 | 覆盖 | 能否当标识符 |
| --- | --- | --- |
| `Port.getToolTip()` | 67 个文件设了 | **不能**，是本地化的句子。`registerClkTip` 英文 "Clock: value updates on trigger"，中文 "输入：时钟（在触发条件满足时更新数值）" |
| HDL 生成器的端口名 | **只有 20 个文件** | 能，但覆盖率太低 |
| Pin 的 `StdAttr.LABEL` | 只有子电路 | 能，且是用户自己起的 |
| 端口数组顺序 | 全覆盖 | 能 |

Logisim 内部其实有名字——`Register` 用 `ps[OUT]` / `ps[IN]` / `ps[CK]` / `ps[CLR]` / `ps[EN]` 这些
Java 常量做下标（[Register.java:260](../../../src/main/java/com/cburch/logisim/std/memory/Register.java)）
——但常量名编译完就没了，运行时拿不到。

**结论：用索引寻址，不写名字表。** `getToolTip()` 做不了标识符，但做**给人和模型读的说明**恰好合适，
而且自动跟界面语言走。

### 3.6 门的端口布局是规整的

`AbstractGate.computePorts`
（[AbstractGate.java:118](../../../src/main/java/com/cburch/logisim/std/gates/AbstractGate.java)）：
`ports[0]` 永远是输出，`ports[1..n]` 是输入。端口**坐标**随 `StdAttr.FACING` 变，但**数组下标不变**。

**所以 `port(int)` 必须定义成端口数组下标，不能定义成"从上往下第几个"**——否则元件一旋转就全乱，而且
是偶发的、很难查的错。

另外注意：`ports[0] = new Port(0, 0, ...)`，偏移是 (0,0)，**门的锚点就是它的输出引脚**，元件本体往左
长出去。这是放置接口要吸收掉的隐藏几何。

### 3.7 子电路是唯一的例外

`SubcircuitFactory.computePorts`
（[SubcircuitFactory.java:117](../../../src/main/java/com/cburch/logisim/circuit/SubcircuitFactory.java)）
从 `getPortOffsets(facing)` 的遍历顺序建端口数组——**顺序跟朝向和引脚布局走**，往内部电路加一个 Pin
就可能让所有实例的端口下标重排。索引寻址子电路是不稳的。

但同一段代码里：

```java
ports[i].setToolTip(StringUtil.constantGetter(label));
```

**子电路端口的 `getToolTip()` 返回的就是 Pin 的 label 原文**，不是本地化句子。

所以规则是：**内置元件用索引，子电路用 label**，而这个例外不需要我们写任何名字表。

### 3.8 端口数量本身会随属性变

RAM 就是例子：Logisim 自己都得用 `RamAppearance.getNrBEPorts(attrs)` 和 `getBEIndex(i, attrs)` 算下标
（[Ram.java:328](../../../src/main/java/com/cburch/logisim/std/memory/Ram.java)）。

**推论：属性必须在取端口之前定好，属性一变，之前取得的端口引用必须失效。** 静默指向别的端口比抛异常
危险得多。

### 3.9 栅格：可见 10 单位，内部允许 5 单位半格

- 编辑器吸附到 **10**：`Canvas.snapXToGrid` 是 `((x + 5) / 10) * 10`
  （[Canvas.java:210](../../../src/main/java/com/cburch/logisim/gui/main/Canvas.java)）
- `Location.create(x, y, true)` 吸附到 **5**：`Math.round(x / 5) * 5`
  （[Location.java:23](../../../src/main/java/com/cburch/logisim/data/Location.java)）——半格是**可表示
  的合法状态**，只是编辑器不产生它
- 全部 320 个字面量 `new Port(dx, dy, ...)` 偏移都是 10 的倍数
- 门的计算偏移看着可疑（`skipStart` 有 -5 和 -15），但验算下来恒为 10 的倍数：`skipStart` 要么乘
  `inputs`（偶数分支），要么乘 `inputs - 1`（奇数分支时为偶数）

**结论：锚点在 10 的倍数上，所有端口也在 10 的倍数上。** dot 坐标系自洽，而且它自洽是因为**我们保证
锚点对齐**——DSL 用 dot 坐标做入参、转换时乘 10，半格锚点在语法上就产生不出来。

但 DSL **读得到**半格（旧文件、程序化生成的内容），所以 `Dot` 不能假设自己总能除尽。

### 3.10 读连接关系只需要公开 API

`Circuit.getComponents(Location)`（625 行）、`getWires(Location)`（728 行）、`getAllLocations()`（704
行）、`isConnected(Location, Component)`（758 行）都是公开的。`WireBundle` 是包私有的，但我们不需要它
——配合 3.3 的完备性保证，端点并查集就够。**不用改上游的可见性。**

### 3.11 元件在加入电路之前就有端口

`ComponentFactory.createComponent(loc, attrs)` 返回的对象立刻就能 `getEnds()`。这解决了"延迟提交就读不
到自己刚放的东西"的矛盾：元件即时创建（端口和 Dot 立刻可用），`CircuitMutation` 攒到提交才落地。

### 3.12 打包相关：不要用 `javax.tools.JavaCompiler`

[build.gradle.kts:187](../../../build.gradle.kts) 用 `jdeps` 从 jar 算出模块列表喂给
`jpackage --add-modules`。`jdk.compiler` 是服务提供者模块，jdeps 扫不出来——**本机开发一切正常，装到
用户机上 `getSystemJavaCompiler()` 返回 null**。要运行时编译 Java 只能用自带编译器的 Janino。

---

## 四、Java 操作层：核心不变量

包：`com.cburch.logisim.dsl`（公开 API）、`com.cburch.logisim.dsl.internal`（布线器、布局、mutation 构建、
待定网表）。

四条不变量，实现时不许破例：

**1. 坐标可以作为放置的输入，永远不能作为连接的输入。**

靠类型系统强制，不靠纪律。`connect` 只有这两个重载：

```java
Net connect(Port a, Port b);
Net connect(Port p, Net existing);
```

没有接受 `int, int` 的重载，也没有接受 `Dot` 的重载。**这个 API 在语法上就写不出"把这个坐标连到那个
坐标"。**

原因见 3.1：放置坐标是**自由参数**（放 (16,10) 还是 (17,10) 都能工作，只是好不好看），布线坐标是
**约束解**（差一格就静默什么也没连上，而引脚坐标是元件类型 × 朝向 × 属性的函数）。

**2. 读侧完全保真，写侧可以受约束。**

`Net.path()` 是一串 Dot，任意折点都能表示；用户手画的、路径七拐八弯的线，DSL 全都读得出来、说得清属于
哪个网。读的自由度损失会让工具残废（读不懂用户的图就没法编辑），写的自由度损失只让工具风格固定。

**3. 低层操作存在，但不是阻力最小的路。**

删掉低层会让编辑功能残废。正确做法是让两者并存但人机工程学不对等：

```java
space.connect(a.port(0), b.port(1));   // 顺手、安全、有校验
space.wires().add(dot1, dot2);          // 存在，但要自己先弄到两个 Dot，没有校验
```

模型走第一条不是因为第二条被封了，是因为第二条更费劲。**这件事在扁平的工具调用 API 里做不到**——47 个
工具可达性完全相同。在 OO API 里可以，靠方法归属、命名和文档里给的例子。

**4. 静默替换是禁止的。**

提示满足不了要抛，布不通要抛，坐标被吸附要把落点告诉调用方。"看起来成功了其实做了别的"是旧 MCP 的
核心失败模式，不许复发。

---

## 五、Java 操作层：接口

### Space

```java
public final class Space {
  public static Space of(Project proj);
  public static Space of(Project proj, String circuitName);

  // 只读 —— 查询式，不是全量 dump（见第七节）
  public String circuitName();
  public Summary summary();
  public List<Comp> components();
  public List<Comp> componentsOf(Kind kind);
  public Optional<Comp> byLabel(String label);
  public List<Comp> near(Comp c, int cells);
  public List<Net> nets();
  public CheckReport check();

  // 放置
  public Placement place(Kind kind);

  // 连接
  public Net connect(Port a, Port b);
  public Net connect(Port p, Net existing);

  // 删除
  public void remove(Comp c);
  public void disconnect(Net n);

  // 低层（第三条不变量：存在但不好用）
  public WireOps wires();

  // 提交
  public CommitResult commit(String actionName);
  public void rollback();
  public boolean isDirty();
}
```

### Comp / Port / Dot / Net

```java
public final class Comp {
  public Kind kind();
  public String id();                     // DSL 内稳定标识，用于日志和错误
  public Optional<String> label();
  public Comp label(String text);

  public List<Port> ports();
  public Port port(int index);            // 内置元件
  public Port port(String pinLabel);      // 仅子电路（3.7）
  public List<Port> inputs();
  public List<Port> outputs();

  public Attrs attrs();
  public Comp set(String attr, Object v); // 使已取得的 Port 全部失效（3.8）
  public Comp facing(Direction d);

  public Dot origin();
  public Bounds bounds();
}

public final class Port {
  public Comp owner();
  public int index();
  public Dir dir();                       // IN / OUT / INOUT
  public int width();
  public boolean exclusive();
  public Optional<String> name();         // 子电路 = pin label；内置元件 = empty
  public Optional<String> desc();         // getToolTip()，本地化说明（3.5）
  public Dot at();
  public Optional<Net> net();
  public boolean isConnected();
}

public final class Dot {
  public int col();                       // dot 坐标，仅在对齐时有效，否则抛 OffGridException
  public int row();
  public boolean onGrid();
  public int rawX();                      // Logisim 原生单位，诊断用，永远可读
  public int rawY();
  // 没有公开构造器，没有 Dot.at(x, y)；toLocation() 包内可见
}

public final class Net {
  public String id();
  public int width();
  public List<Port> ports();
  public List<Port> drivers();            // 输出端口
  public List<Dot> path();                // 提交后可读，任意折点
  public boolean isCommitted();

  // 布线提示（第六节）
  public Net preferAbove();
  public Net preferBelow();
  public Net preferLeft();
  public Net preferRight();
  public Net viaColumn(int col);
  public Net viaRow(int row);
}
```

`Dot.col()` 在遇到半格时抛异常而不是静默取整——宁可给一个说得清的错误，也不要因为一个异常文件让整个
DSL 崩在别处（3.9）。

### Kind：自描述延伸到放置之前

```java
public final class Kind {
  public static Kind of(String key);           // "gates/and"、"wiring/pin"
  public static List<Kind> available(Space s);

  public String key();
  public String displayName();                 // 本地化
  public List<AttrSpec> attributes();          // 能设哪些属性、取值范围
  public List<PortSpec> portsFor(Attrs a);     // 不放置就能看端口
}
```

`portsFor(attrs)` 值得强调：模型可以**在放置之前**查清"两输入与门有几个端口、哪个是输出"，把"索引对应
什么"彻底变成可查询的问题。这是不写名字表的底气。

### Placement

```java
public final class Placement {
  public Placement at(int col, int row);        // dot 坐标，包围盒左上角（默认，符合直觉）
  public Placement anchorAt(int col, int row);  // Logisim 原生锚点（门 = 输出脚，见 3.6）
  public Placement rightOf(Comp other, int gap);
  public Placement below(Comp other, int gap);
  public Placement with(Attrs attrs);
  public Placement facing(Direction d);
  public Comp place();
}
```

不给坐标时由自动布局兜底，但那是便利，不是主路径。

### 放置时的校验

坐标由调用方给，中间层的价值是**把静默失败变成显式错误**：

- **栅格对齐**：dot 坐标乘 10，落点通过 `Comp.origin()` 返回，不静默偏移
- **重叠检测**：包围盒相交 → `PlacementException`，带上撞到了谁
- **引脚撞车**：新元件的引脚正好落在别的元件引脚上 → 在 Logisim 里会直接连起来，八成不是本意 → 报错
- **锚点语义归一**：`at` 与 `anchorAt` 两个入口

### 异常

```java
DslException (RuntimeException)
├── PortDirectionException       // 输出接输出，带两端的方向
├── WidthMismatchException       // 带两端的位宽
├── ExclusiveViolationException  // 独占端口被连第二次，带已有的那条网
├── UnknownPortException         // 越界或 label 不存在，必须带上可用端口清单
├── StaleReferenceException      // 属性变更后的旧引用（3.8）
├── UnknownKindException         // 带相近候选
├── OffGridException             // 半格坐标（3.9）
├── PlacementException
└── RoutingException             // 布不通或提示无法满足，带受阻位置和原因
```

**硬规则：每个异常都带结构化字段**，上层据此生成消息，绝不靠解析异常字符串。`UnknownPortException`
必须列出可用端口——错误要说领域语言，"输出 cout 没有被任何东西驱动"而不是 "component not found"。

### CheckReport

```java
public record CheckReport(
    List<Port> unconnected,
    List<Net> undriven,           // 没有任何输出端口的网
    List<Net> multiplyDriven,
    List<WidthConflict> conflicts) {
  public boolean ok();
}
```

这个**返回而不抛**——它是查询，不是操作。

---

## 六、事务与布线

### 事务模型

- **元件即时创建**（3.11），因而端口和 Dot 立刻可用
- **变更延迟提交**，攒成一个 `CircuitMutation`
- **连接关系由 DSL 自己维护一份待定网表**（端口↔网的并查集）。提交前 Logisim 里没有网表可查，三重校验
  和 `check()` 都跑在这份待定网表上，**错误在提交前就报出来**
- 提交后再从 `Circuit.getWires(Location)` 反查真实网表做一次对账

`commit()` 依次做：校验 → 布局（给自动放置的元件定坐标）→ 布线 → 构造一个 `CircuitMutation` 装下所有
Component 和 Wire → `proj.doAction(...)`。**产生恰好一条撤销记录**——否则搭一个全加器会在撤销栈里留下
几十条，用户按一次 Ctrl+Z 只退回一根线。之后 `WireRepair` 自动跑（3.2）。

### 布线器（`dsl.internal`）

- 输入：待提交的元件几何 + 待定网表
- 输出：每条网的 Dot 路径，拆成若干条轴对齐的 Logisim `Wire`（3.4）
- 算法：**最多两折的曼哈顿路径**。编辑器自己也只做一折
  （[WiringTool.java:354](../../../src/main/java/com/cburch/logisim/tools/WiringTool.java)）
- 障碍集合：**所有不属于本网的端口坐标**。元件本体包围盒可选（美观问题，不是正确性问题）
- 三种折法都不通就平移一条轨道重试，仍不通抛 `RoutingException`
- **不需要通道分配**——交叉不相连（3.3）

### 布线提示

三个层次，都不让调用方写出完整路径：

- `preferAbove/Below/Left/Right` —— 只说方向，具体位置由布线器挑
- `viaColumn(col)` / `viaRow(row)` —— 一个整数定住主干位置。这是曼哈顿 Z 形路径的**唯一自由参数**，
  给了它路径就完全确定
- 完整的 `path(Dot...)` **第一阶段不做**。`viaColumn` 已覆盖"走这边"的全部表达力；完整路径要额外做
  一整套校验（相邻点共线、首尾等于两端口、不经过外网引脚），投入不小、收益是覆盖罕见情况。接口位置留着

**提示满足不了必须抛**（第四条不变量）：`viaColumn(24)` 那一列被占了，不许悄悄改走 25 列，要抛
`RoutingException` 并说明"第 24 列在第 12 行被 `and3` 的输入脚占用"。静默把意图换成别的，正是旧 MCP
那类失败。

**补自由度要沿意图补，不沿几何补**：以后不够用时加的是 `avoid(region)`、`connectBus(outs[], ins[])`、
`align(c1, c2, c3)`，而不是 `path(dot, dot, dot)`。

---

## 七、读侧：查询式，不是全量 dump

编辑现有电路意味着模型得先看懂它。一个五十元件的电路整个 dump 成对象描述，token 开销可观且大部分无关。

```java
space.summary();                 // 元件类型计数 + 输入输出引脚，先看轮廓
space.componentsOf(Kind.AND);
space.byLabel("carry");
space.near(comp, 3);             // 附近三格内的元件
comp.port(0).net().ports();      // 这个脚连到哪些地方
```

模型按需要拉。这是编辑需求引入的设计面，纯生成器思路里不存在。

---

## 八、Lua 脚本层

选 Lua 的理由：**交互式要求持久变量**。模型第一次调用里 `a = space:place(...)`，几分钟后第二次调用里
用 `a` ——指令序列要支持这个就得自己维护符号表、自己定义作用域，那是在很差地重新实现一门语言。

"薄核心 + 脚本标准库"有成熟先例：Neovim、Redis、OpenResty、几乎所有游戏引擎。对这个 fork 还有一个特别
的好处：**Lua 标准库是资源文件，改它不用重新打包应用**，迭代"模型需要什么便利函数"会快一个数量级。

不选 JS 的理由：Nashorn 在 JDK 15 已从 JDK 移除，GraalJS 太重，Rhino 偏老。LuaJ 约 500 KB、纯 Java、
jpackage 下没问题，语言小到沙箱面容易审干净。

### 沙箱（必须一开始就做对）

**绝对不要用 `JsePlatform.standardGlobals()`。** 它带的库包括 `os`（有 `os.execute`）、`io`，以及最
要命的 **`luajava`——能用反射实例化任意 Java 类**，那就是一行 RCE。这个服务器是网络可达的（回环 +
令牌），CLAUDE.md 里"任何监听端口的东西默认关闭"那条规矩正是为这类事写的。

手工组装 globals：

```java
Globals g = new Globals();
g.load(new BaseLib());     // 还要摘掉 dofile / loadfile
g.load(new TableLib());
g.load(new StringLib());
g.load(new MathLib());
// 不加：JseIoLib、JseOsLib、LuajavaLib、PackageLib、DebugLib
```

具体类名和 `standardGlobals()` 到底装了哪些库，实现时**照着引入的 LuaJ 版本核一遍**——这是安全边界，
不能凭记忆写。

**必须防失控循环。** `while true do end` 会让那个线程永远转下去。用 debug hook 按指令计数中断，每次
执行给一个指令预算，超了抛。这不是可选项。

### 绑定

LuaJ 的自动反射绑定（`luajava` 风格）**必须排除**，就是上面那个 RCE。所以是手写绑定层：每个类一个
`LuaTable` 加 metatable，`Space` / `Comp` / `Port` / `Dot` / `Net` 五个类，大概每个几十行。

**Java 异常跨界时不能退化成一句字符串。** 结构化异常（带可用端口清单、带受阻位置）要转成 Lua 的 table
错误对象，字段保留，`tostring` 给出可读消息。这样模型既看得懂，也能程序化处理。

### 什么写在 Lua 里

按第二节的判据。第一批候选：`align(...)`、`connect_bus(...)`、`ripple_adder(n)`，以及模型自己临时写的
helper。

---

## 九、MCP 工具面

三个工具就够，不用再为每个操作写 schema：

- `eval(script)` —— 执行一段 Lua，返回结果或结构化错误
- `describe()` —— 拿 API 文档和当前电路轮廓
- `reset()` —— 丢掉会话状态

加上工程级的少数几个（`list_projects` / `open_project` / `save_project`）。相比现在的 47 个是数量级的
削减，而每个工具在每次请求里都要占 token。

### 线程规则（血教训，不许重犯）

**永远不要在持锁状态下跳到事件分发线程。** MCP 代码里有过两个这种形状的死锁：
`McpModelExecutor.call` 持有 monitor 跨越 `invokeAndWait`；`McpServerManager.start` 持锁构造一个
构造函数里会跳到 EDT 的服务。两者都和 EDT 互相等待。

**`jstack` 报不出这种死锁**，因为一条边是 `invokeAndWait` 的 wait/notify 而不是检测器能建图的 monitor，
所以症状是进程静静地坐着、线程转储里什么也没有。第一个让每次工具调用都挂住，第二个让应用启动后没有窗口
也没有任何提示。

规则：**锁外构造，只在发布时持锁；通知监听者也在锁外。** Java 操作层自己不做线程调度，每个方法断言自己
在模型线程上，不对就直接抛；跳线程是适配层的事。

---

## 十、拆除清单

不是全删。管道是踩过坑换来的，删掉等于把那两个死锁重新挣一遍。

| 部分 | 行数 | 处置 |
| --- | --- | --- |
| `McpProjectService`（47 个工具） | 2380 | **删**。这就是要换掉的东西 |
| `McpChangeJournal` + `McpOperationLedger` + `poll_changes` + 按 operationId 的 undo/redo | ~456 | **删**。给"多客户端并发编辑同一工程"准备的乐观并发机制，实际场景是一个人一个模型 |
| `McpJobManager` + `McpTestVectorJobService` | ~450 | **删**。真值表比对是毫秒级的，异步作业是凭空的复杂度 |
| `McpSnapshot`（几何转储） | 162 | **重写**成第七节的查询式读接口 |
| `McpHttpHandler` / `McpServerConfig` / `McpServerManager` / `McpPathPolicy` / `McpModelExecutor` / `McpJsonRpcDispatcher` / `McpStdioServer` | ~1250 | **留**。回环绑定、令牌鉴权、路径白名单、EDT 跳转、两个死锁的修复 |
| `McpBundleWriter` + `bridge.js` + `MenuMcp` + 12 语种字符串 | ~300 | **留**。已验证通过的安装链路 |
| `docs/peler-edition/mcp/` 下 11 份旧 QA 文档 | — | **删**。描述的是被删掉的那套设计 |

对应的测试同步删。旧代码在 git 历史里（`a5d489601` 及之前），删得干净也找得回来。

---

## 十一、分期与验收判据

**P0 拆除。** 按第十节执行。判据：`./gradlew check` 通过；连一次客户端，`tools/list` 只返回幸存的
工程级工具。单独提交，让 diff 干净。

**P1 Java 操作层。** `com.cburch.logisim.dsl` 包，第四、五、六节的全部内容，纯单元测试。判据：

- 一个架构测试断言 `dsl` 包不 import `javax.swing`、`com.sun.net`、`com.cburch.logisim.mcp`
  （仓库里已有先例：`PelerOptionsTest` 用类似手法守护偏好设置的可达性）
- 用这套 API 搭出全加器，`Analyze.computeTable`
  （[Analyze.java:170](../../../src/main/java/com/cburch/logisim/circuit/Analyze.java)）出来的真值表正确。
  **不接受"没报错"作为通过**——那正是旧 MCP 的失败模式
- 撤销栈里只有一条记录
- 故意接反方向、故意位宽不符、故意占用 `viaColumn` 指定的列，三种情况都抛出带结构化字段的异常

**P2 Lua 层。** 沙箱、绑定、指令预算、错误转换。判据：

- 一个测试验证 `os`、`io`、`luajava`、`require` 在脚本里全都取不到
- 一个测试验证 `while true do end` 会在预算内被中断
- 用 Lua 复现 P1 的全加器，真值表正确
- Java 异常跨界后字段完整

**P3 MCP 工具面。** 三个工具 + 会话状态。判据：**实测一次"搭一个全加器"，记录耗时和调用次数，和旧版
对比**。这是整件事的意义所在，要有数字。

**P4 编辑现有电路。** 查询式读接口的完整化、基于已有元件的相对操作。判据：打开一个用户手画的电路，
读出全部连接关系正确，在其上追加一个门并连上，不破坏原有布线。

**P5 声明式生成层。** `space.synthesize(spec)`，内部走 `AnalyzerModel` → `CircuitBuilder.build()` →
`CircuitMutation`。注意 `CircuitBuilder.build()` 开头就 `result.clear()`
（[CircuitBuilder.java:196](../../../src/main/java/com/cburch/logisim/std/gates/CircuitBuilder.java)），
所以它替换整个电路，只能用在新建电路上，不能用于增量编辑。生成器库（`ripple_adder` 等）建在这一层之上。

早期讨论一度想让 P5 取代 P1——不行，声明式只能生成不能编辑。它是**建在操作层之上的一个方法**，不是
替代品。这也解释了为什么两条路线之前看起来互相冲突：它们本来就不该在同一层竞争。

---

## 十二、未决问题

1. **自动布局兜底策略。** `place(kind)` 不给坐标时放哪。建议第一阶段最简：按放置顺序从左到右排一行。
   动手前值得先读 `CircuitBuilder` 内部的 `Layout` 和 `InputData`
   （[CircuitBuilder.java:54,139](../../../src/main/java/com/cburch/logisim/std/gates/CircuitBuilder.java)），
   看分层布局能不能复用——这个答案也直接影响 P5。

2. **`Kind` 的命名键。** 需要稳定、与界面语言无关、可枚举。`"gates/and"` 这种两段式是建议，但要跟
   Logisim 的库/工具命名对一遍，确认能唯一确定一个 `ComponentFactory`。

3. **Lua 会话状态的生命周期。** 一个会话绑一个 Lua state，那么它和 `Project` / `Circuit` 的绑定关系是
   什么？用户在界面里切换了电路、关闭了工程，state 怎么办？

4. **`WireOps` 低层接口的具体形态。** 第四条不变量要求它"存在但不好用"，但"不好用"要有个度——太难用
   就等于没有，编辑功能会缺口。

5. **LuaJ 的版本与许可。** LuaJ 是 MIT，与 GPLv3 兼容；引入前照例核一遍实际发布物的许可声明。

---

## 附：标准约束

以下来自 CLAUDE.md，在这项工作里同样适用，容易被忘：

- **无 emoji**，文档、代码注释、提交信息、界面字符串一律不用。状态标记用 `DONE:` / `TODO:`
- **任何监听端口的东西默认关闭**，回环绑定，强制令牌
- **新的界面字符串要补齐 12 个语种**
- **不要碰 `BuildInfo.version` 和 `displayName`** ——前者写进每个保存的文件，后者进文件头和生成的 VHDL。
  fork 自己的版本号是 `BuildInfo.pelerVersion`
- **不要为了探测而以 headless 方式运行应用**：`AppPreferences.hotkeyMenuMask` 在 headless 下退化成
  `ALT_DOWN_MASK`，并会把错的默认值持久化进真实偏好存储
