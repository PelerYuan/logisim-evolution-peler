# TTL 逻辑符号视图：调研、研究计划与实现记录

状态：**已实现**（2026-08-22，ROADMAP 里的 Feature 12）。全库 61 片都有符号版，另加了一个
"新放置的 TTL 芯片显示内部门电路"的设置项。实现过程和与本文档草案的偏差见第八节。

第五节的五个问题**已全部有答案**（2026-08-22 由维护者拍板）：1 全做；2 保持 1 位分组画；
3 兼容保存时提示并整片丢弃；4 不支持 HDL 导出；5 沿用实现时取的 `"TTL Symbols"` / `"Sym" + 芯片名`。
第 2 问和第 5 问都是**发布即锁死**的决定，理由见 8.7。

调研日期 2026-08-22。起因是课件里 74HCT283 / 74HCT175 都画成"输入在左、输出在右"的功能块符号，
而软件里这两片只能拖出 DIP 封装图。诉求是在左侧元件栏新开一栏，提供这种简化画法。

这份文档把结论和**得出结论的证据**放在一起，尤其是那些从 Logisim 源码里挖出来、重新调研要花很久
的事实。

---

## 一、现状：软件里现在到底有什么

TTL 元件目前只有一种几何：**DIP 封装**。芯片名写在中间，引脚沿上下两边按物理管脚号排列。

已有两个相关属性（`TtlLibrary.java:98`）：

| 属性 | 作用 |
| --- | --- |
| `VccGndPorts` | 把 Vcc / GND 也变成真端口 |
| `ShowInternalStructure` | 换掉芯片内部的画法 |

`ShowInternalStructure` 容易被误认为就是"简化版"，实际不是。它只换**内部**画什么：

- 门阵列类（7400 等）画出内部的四个门；
- 声明了 `portNames` 的芯片（74283 等）画一个白框，把管脚名标在框外。

两种情况下**端口坐标都不变**，仍然是 DIP 排布。以 74283 为例，打开该属性后是：

```
上排：Vcc  B3  A3  ∑3  A4  B4  ∑4  C4
下排：∑2   B2  A2  ∑1  A1  B1  CIN GND
```

输入输出交错、按管脚号排列——这正是课件符号要消掉的东西。所以**软件里现在没有任何逻辑符号视图**，
这是一个新功能，不是一个现有开关。

---

## 二、代码事实（重新调研代价最高的部分）

### 2.1 几何、端口、逻辑三者的耦合程度

| 关注点 | 位置 | 是否需要改 |
| --- | --- | --- |
| 外形尺寸 | `AbstractTtlGate.getOffsetBounds` | 要 |
| 端口坐标 | `AbstractTtlGate.updatePorts` | 要 |
| 画法 | `AbstractTtlGate.paintInstance` / `paintBase` / 各芯片 `paintInternal` | 要 |
| 逻辑 | 各芯片 `propagateTtl` | **不用** |
| 状态数据 | `TtlRegisterData` / `ShiftRegisterData` / `UpDownCounterData` | **不用** |
| HDL 生成 | `Ttl*HdlGenerator` | 大概率不用，待验 |

**最关键的一条结论：端口索引与端口坐标是解耦的。**

`updatePorts` 的写法是先按管脚号算出 `(dx, dy)`，再 `ps[portindex] = new Port(dx, dy, ...)`。
索引 `portindex` 的推进顺序只跟管脚号、跳过的空脚、GND/Vcc 有关，跟坐标无关。而 `propagateTtl`
一律通过 `state.getPortValue(i)` / `state.setPort(i, ...)` 访问端口。

也就是说：**只要端口的顺序不变，只改坐标，全部 61 片的逻辑代码一行都不用动。**

### 2.2 验证过 `propagateTtl` 不碰几何

对 `std/ttl/` 全目录搜 `getBounds` / `getTranslatedTtlXY` / `getInstance()`，命中 8 个文件
（74161、74164、74165、74166、74192、7474、DisplayDecoder、AbstractOctalFlops）。逐个看过，
**全部在 `Poker` 内部类里**，用于把鼠标点击坐标换算成被点的管脚；没有一处在 `propagateTtl` 里。

推论：符号视图需要自己的 Poker 坐标换算（或者第一版干脆不支持点击拨动），但不影响仿真正确性。

### 2.3 端口名的覆盖率

`portNames` 是构造函数可选参数。50 个直接继承 `AbstractTtlGate` 的芯片类里，**33 个声明了端口名，
17 个没有**。没声明的基本是门阵列（7400/7402/7404/7408/7420/7432/7486 …）——这类芯片一个方块符号
本来就没意义，它就是四个独立的门，现有的 `ShowInternalStructure` 已经是它最好的表达。

推论：符号视图的目标集合天然只是那 33 片里的一部分，不需要覆盖全库。

### 2.4 左侧新开一栏要动哪些地方

```
TtlSymbolLibrary (新)  →  Builtin.java 的 libraries 列表  →  default.templ 的 <lib> 清单
```

三处缺一不可：`Builtin` 决定软件认不认这个库，`default.templ` 决定**新建项目**的元件栏里有没有它
（CLAUDE.md 里已经踩过这个坑）。

文件格式方面：`.circ` / `.pcirc` 里 `<lib name="A" desc="#TTL" />` 的 `name` 只是文件内的句柄，
`XmlWriter.fromLibrary` 用 `Integer.toString(libs.size())` 顺序生成，**不需要预留字母**。真正要稳定
的是 `desc` 里那个 `_ID` 字符串，一旦发布就不能再改。

### 2.5 两个会咬人的实现细节

**`findLibrary` 取第一个命中的库。** `XmlWriter.findLibrary(ComponentFactory)`（第 282 行）遍历
`file.getLibraries()`，返回第一个 `contains(source)` 为真的库。`Library.indexOf` 比的是
`tool.getFactory() == query`，**对象同一性**。

**`AddTool.equals` 归根到底比的是 factory。** 有 `description` 时比 `descriptionBase + description`，
没有时比 `factory`。所以两个指向同一个 factory 实例的 `AddTool` 永远相等。

这两条合起来否掉了一条看起来很省事的路线，见 3.1。

**`ToolSearch` 按 Library 去重，不按 tool。** 新开一栏之后，Ctrl+F 搜 "74283" 会出两条，分别标着
各自的分类名。这是可接受的，甚至是想要的，但要心里有数。

---

## 三、三条路线

### 3.1 路线 A：加个属性，两栏共用同一批 factory

新加属性 `SymbolStyle = DIP | LOGIC`，新栏里放的是同一批 factory 的 `AddTool`，只是属性默认值不同。

**否决。** 理由是 2.5：两个 `AddTool` 指向同一个 factory 就永远 `equals`，`Library.getTool("74283")`、
工具栏、项目文件里的 `<toolbar>` / `<mappings>` 都分不清是哪一栏的；`findLibrary` 又总是把元件归给
排在前面的那个库。新栏在文件层面等于不存在。

顺带澄清一个常见的期待：**这条路线并不能换来 `.circ` 兼容性。** 连线端点在文件里是绝对坐标，符号视图
的端口位置跟 DIP 不一样，上游打开后线一样接不上。几何一变，兼容就没了，跟用哪条路线无关。

### 3.2 路线 B：新库 + 新 factory（推荐）

新包 `std/ttlsymbol/`，一个通用类 + 每片一份布局描述：

```java
public class TtlSymbolLibrary extends Library {
  public static final String _ID = "TTL Symbols";   // 一旦发布不可更改
  public List<? extends Tool> getTools() {
    // AddTool(ComponentFactory) 这个构造函数是 public 的，不走 FactoryDescription 的反射，
    // 所以"一个类多个实例、每个实例一个名字"是可行的
    return SPECS.stream().map(s -> new AddTool(new TtlSymbolGate(s))).toList();
  }
}
```

`TtlSymbolGate` 自己实现几何和画法，`propagateTtl` 直接委托给一个私有的 DIP factory 实例——
因为 2.1 已经证明端口顺序一致时逻辑完全通用。每片一个独立 `_ID`，存档、工具栏、HDL 名都不会撞。

上游文件的改动量：`Builtin.java` 加一行、`default.templ` 加一行、语言文件加库名。其余全在 fork 自己
的新包里。

### 3.3 路线 C：改造 `AbstractTtlGate`，让它支持可插拔布局

最"正统"，但要动上游 586 行的核心基类，而且 61 片芯片全在它下面。跟 fork 的既定原则（尽量少改上游、
便于跟官方对齐）冲突。**除非 B 走不通，否则不选。**

---

## 四、真正的工作量：每片芯片的符号布局表

这是不能自动推导的部分，必须一片一片手写。自动推导只能拿到"哪些是输出"（`outputPorts` 集合），
剩下四件事都得人定：

1. **每个端口去哪一边。** 输出靠右是默认，但 74283 的进位 C4 该跟 ∑ 一起靠右还是单独放右下、
   74175 的 CLK 该在底边还是左边，都是判断题。
2. **同一边的顺序。** 课件是 A0..A3 / S0..S3 这样按位序，而管脚顺序是 A1 B1 A2 B2 交错的。
3. **分组和组标签。** 课件把四条 A 线并在一起标一个 "A"，这是排版信息，源码里没有。
4. **修饰符号。** 低有效端口（`nCLR`、`nQ1`）要画小圆圈，时钟端要画三角，`portNames` 里只有
   `n` 前缀这种弱约定。

草案数据结构：

```java
record TtlSymbolSpec(
    String id,              // "74283S"，发布后不可变
    String dipId,           // "74283"，委托目标
    String caption,         // 框里写什么，"74283"
    int width, int height,  // 以格为单位
    List<PortGroup> left, List<PortGroup> right,
    List<PortGroup> bottom, List<PortGroup> top) {}

record PortGroup(String label, int[] portIndices, boolean bubble, boolean clockEdge) {}
```

`portIndices` 里的数字就是 `propagateTtl` 用的那个索引，所以这张表同时也是可读的接线文档。
以 74175 为例（索引已核对过源码）：

```
0=nCLR(pin1) 1=Q1 2=nQ1 3=D1 4=D2 5=nQ2 6=Q2 7=CLK(pin9)
8=Q3 9=nQ3 10=D3 11=D4 12=nQ4 13=Q4
```

---

## 五、开工前必须定的问题

1. **首批覆盖哪些芯片？** 全库 61 片明显不划算，门阵列那 17 片也没意义。建议先做课程真正用到的：
   74283、74175，再看要不要扩到 74161/74163（计数器）、74157/74153（选择器）、74138/74139（译码器）、
   7485（比较器）、74181（ALU）。
2. **端口位宽：1 位分组画，还是真的 4 位总线？** 课件里 A 是一个标签带四根线，看着像总线。做成真
   4 位端口接线体验最好，但端口数量一变，2.1 那条"逻辑完全通用"的结论就失效了，得给每片写位拆分和
   HDL 映射。建议第一版保持 1 位端口、只在视觉上分组，总线留给第二版。
3. **存成 `.circ` 时怎么办？** 符号元件是 fork 独有的，上游认不出来。三个选项：(a) 像批注那样降级成
   DIP 元件——但如 3.1 所述连线会错位；(b) 直接丢弃并警告；(c) 不管，让上游报错。现有先例是批注
   走 (a) 并在保存时提示有损。需要决定。
4. **要不要支持 HDL 导出？** 端口顺序不变的话 `Ttl*HdlGenerator` 理论上能直接复用，但 `getHDLName`
   会变成 `TTL74283S`。要验证，或者第一版明确不支持。
5. **`_ID` 命名方案。** 一旦发布就锁死。候选：`74283S` / `74283-symbol` / `Symbol74283`。

DONE: 五个问题的答案见 8.7。

---

## 六、分步计划（每步都有可验收的产物）

**Step 0 — 定范围。** 拿第五节的五个问题跟维护者过一遍。产物：本节被替换成确定的答案。

**Step 1 — 打通一片，验证委托假设。** 只做 74283，硬编码布局，不做数据表。产物：能从新栏拖出来、
画对、接线对、真值表跟 DIP 版一致。验收：一个测试并排跑 DIP 版和符号版，对全部 512 种输入组合断言
输出相同。**这一步就能证伪整条路线，所以要先做。**

**Step 2 — 抽出布局数据结构。** 把 Step 1 的硬编码换成 `TtlSymbolSpec`，加上 74175。产物：两片，
一张表。验收：加第三片只需要往表里加一条，不改画法代码。

**Step 3 — 接进元件栏。** `Builtin` + `default.templ` + 12 个语种的库名。验收：
`python3 -c "import xml.dom.minidom as m; m.parse('src/main/resources/resources/logisim/default.templ')"`
通过；新建项目左侧能看到新栏；旧项目文件仍能打开。

**Step 4 — 存档往返。** `.pcirc` 存读一致；按 Step 0 第 3 问的决定实现 `.circ` 兼容行为。验收：
存读往返测试 + 兼容保存的行为测试。

**Step 5 — 剩余芯片 + 交互补齐。** 按 Step 0 第 1 问的清单扩表；决定是否实现 Poker。

**Step 6 — 文档。** ROADMAP 加一节（下一个编号是 Feature 12），README 功能列表，本文档改成"已实现"。
DONE: 2026-08-22。ROADMAP 的 Feature 12 一节同时覆盖符号库和设置项；README 在功能列表里加了
"74xx chips as logic symbols"，并在设置页那一节的清单里加了 TTL 那一条。

---

## 七、已知风险

- **启动开销。** 现在 TTL 库的 61 个 factory 是懒加载的（`FactoryDescription` 用到才反射构造），
  而 `AddTool(ComponentFactory)` 这条路要在 `getTools()` 第一次被调用时就把所有符号 factory 造出来。
  更糟的是 `Library.indexOf` 会调 `tool.getFactory()`，对懒加载的库本来就会全量强制加载。需要实测
  新栏对启动时间的影响。
- **`default.templ` 很脆。** XML 注释里出现字面的 `--` 会让整个模板失效，所有项目都打不开。
- **12 个语种。** 新的库名和任何新的用户可见字符串都要补全
  `src/main/resources/resources/logisim/strings/`。
- **`_ID` 不可回退。** 命名方案定错了只能一直背着。
- **新栏会让 Ctrl+F 出现重名条目。** 见 2.5，需要确认展示上分得清。

---

## 八、实现记录（2026-08-22）

Step 1 和 Step 2 已完成，范围按维护者指示扩到 **全库 61 片**，包括第五节原本建议排除的 17 片门阵列。
代码在 `src/main/java/com/cburch/logisim/std/ttlsymbol/`，尚未提交。

### 8.1 与草案的偏差

第四节草案里的 `TtlSymbolSpec` 带 `id` / `dipId` / `caption` / `width` / `height` 五个字段，实现里
全部删掉了：61 条记录每条重复写一遍身份信息，等于给自己开 61 次打错字的机会。改成从委托对象推导——
`id = "Sym" + delegate.getName()`，工具栏字符串键 `"TTL" + delegate.getName()`（直接复用上游 12 个
语种里已有的 61 条），框里的标题就是芯片名。因此除了库名 `ttlSymbolLibrary` 之外**没有新增任何
本地化字符串**。

宽度不用 `FontMetrics` 量，只按固定的每字符常量算（标签 6px、标题 8px），向上取到 10 的整数倍、
最小 60。理由是宽度决定右侧端口的坐标，而 `.circ` 里连线端点是绝对坐标：拿字体量出来的宽度会让同一
个电路在字体不同的机器上连线错位。

### 8.2 极性（小圆圈）的三种来源

这是唯一一处不能只靠推导的地方，也是这一步唯一出过错的地方。

1. **上游自己标了的**，由生产代码自动识别，布局表不用写：`n` 前缀（`nCLR`、`nOE1`、`n1Y4`，以及
   组号在前的 `1nY0`），正则 `(\d*)n([A-Z0-9].*)`；或者名字里写着 `active LOW`。
2. **上游用了后缀 `n` 的**：`Pn`、`Gn`、`Q7n`。不能自动识别，因为同一片 74182 上的 `Cn` 是数据手册
   里的下标 C(n)，是高有效的进位输入。这几个在布局表里用 `renamedInverted` 显式改名。
3. **上游完全没提的**：7442/7443/7444 的十条输出上游就叫 `O0`..`O9`，管脚名里没有任何极性信息，
   但模型确实是选中的那条拉低。只能在布局表里显式写 `inverted(...)`。

第 2、3 类在第一版里漏掉了 8 片，见 8.4。

### 8.3 测试分五层，每层管一件别层管不了的事

| 测试 | 管什么 | 单独漏掉什么 |
| --- | --- | --- |
| `TtlSymbolEquivalenceTest` | 符号版和 DIP 版逐索引同真值表 | 索引和名字的对应、任何画出来的东西 |
| `TtlSymbolLayoutTest` | 端口不重不漏、落在格点上、转向后仍在框上、简写名确实是那根管脚 | 门的分组、极性 |
| `TtlSymbolGateArrayTest` | 17 片无管脚名芯片的每个门走完整真值表 | 有管脚名的芯片 |
| `TtlSymbolDatasheetTest` | 手写的数据手册向量 | 覆盖面 |
| `TtlSymbolPolarityTest` | 小圆圈 | —— |

`TtlSymbolPolarityTest` 从三个方向逼近同一件事，都不重复布局表已经做过的判断：上游标了低有效的必须
有圈；纯门类芯片按测试里写出的真值表验证，而圈的有无跟真值表取自同一个字段；一位有效译码器的有效
电平直接从芯片本身读出来——十条（或八条）输出里落单的那条是什么电平，圈就必须跟它一致。第三条对
7443/7444 的余三码和余三格雷码编码是无关的，不需要给它们各写一张编码表。

### 8.4 第一版漏掉的极性错误

只靠 574 个测试全绿就发布的话，这 8 片会带着错的符号出去。它们是在人工看渲染结果时发现的，
**修复前后测试都是全绿**——当时没有任何测试看画出来的东西。

- 7413 / 7418 / 7420：四输入 NAND，输出画成了不反相（7421 是 AND，本来就不该有圈，正好做对照）。
- 7442 / 7443 / 7444：十条低有效译码输出一个圈都没画。
- 74181 / 74381：上游的 `Pn` / `Gn` 被原样当成管脚名显示，既没改成 `P` / `G` 也没画圈。

补上 `TtlSymbolPolarityTest` 之后，逐条改回错误版本验证：4 个变异全部被抓到，且分别由该测试的三条
检查中的不同一条抓到。

### 8.5 顺带做的：设置页的"新放置的 TTL 芯片显示内部门电路"

上游本来就有 `ShowInternalStructure` 属性——在 DIP 外框里画出内部门电路，而不是画一个编号方框——
但它是每个元件各自一份、默认关。想一直用这种画法的人得在每一片上手点一次。设置项
`pelerTtlInternalStructure` 只做一件事：把它变成**新放置**的芯片的默认值。

**只管画法，不碰 `VccGndPorts`。** 同一组里的另一个属性会给芯片加两个端口。给它做默认值意味着今天
放的芯片比昨天放的多两根脚，而且已有连线背后的端口索引会整体错位。`theSupplyPinsAreLeftAlone`
把这条线钉住。

**注册的工厂默认值仍保持上游的 `false`。** `XmlWriter.addAttributeSetContent` 会跳过等于工厂默认值
的属性。如果让默认值跟着设置走，那么开着设置放下的芯片存盘时**什么都不写**，换一台设置是关的机器
打开就变回方框。保持上游的默认值，才能让这些文件自己描述自己。已做变异验证：让
`getDefaultAttributeValue` 跟着设置走，属性确实从存出来的 XML 里消失了。

**只影响新放置的芯片。** 已经在画布上的芯片保持放下时的画法——理由和批注保持自己的字体一样：值在
文件里。

**只在 `createAttributeSet` 里读设置是不够的，这一点单元测试全绿也发现不了。** `AddTool` 的构造函数
会问自己的属性集里有没有 `StdAttr.APPEARANCE`，而这一问就把属性集造出来了。61 个工具的属性集在窗口
出现之前就已经建好并缓存，所以只在 `createAttributeSet` 里读设置，效果要等下次启动才看得见——从外面
看跟复选框坏了没有区别。`TtlLibrary` 因此维护一个**弱引用**的实例集合（`Loader` 每个工程建一个
`Builtin`，所以实例不止一个，而静态监听器比它们都活得久），设置变化时把新值推进各实例已经持有的工具
里。推送用 `SwingUtilities.invokeLater`，不用 `invokeAndWait`：变化是在 `java.util.prefs` 自己的
线程上到达的，而它碰的东西被元件栏盯着，改个设置不能有机会把事件线程卡住。这条路径由
`changingTheSettingReachesTheChipsAlreadyInTheToolbox` 守着，变异验证方式是把推送换成一句什么都不做
的调用。

改设置的测试在这里是安全的：`test` 任务早就把 `java.util.prefs` 的 `userRoot` / `systemRoot` 重定向
到 `build/test-prefs/` 了（当初是为了防止无头运行把降级的 `hotkeyMenuMask` 写回去）。已实测确认：跑
完整测试后开发机上真实的配置文件逐字节不变。

### 8.6 现状

`./gradlew check`：956 个测试全绿，checkstyle 0 条。61 片符号尺寸在 60x100 到 60x210 之间
（7474 和 74192/74193 宽 70），没有失控的框。符号那 5 层测试共 650 个（7 + 61 + 17 + 489 + 76），
设置项 5 个，`.circ` 兼容行为 6 个。

人工验收：61 片符号逐个看过渲染结果（8.4 那 8 个极性错误就是这么找到的）；设置项在真实 X 显示上
双向跑通，改设置前后各放一片，并确认画布上已有的芯片保持原样。

文档：ROADMAP 的 Feature 12、README 功能列表和设置页清单都已补上（Step 6 完成）。

第五节五个问题的答案见 8.7，全部已定。

### 8.7 第五节五个问题的最终答案（2026-08-22）

| # | 问题 | 决定 | 是否发布即锁死 |
| --- | --- | --- | --- |
| 1 | 覆盖哪些芯片 | 全库 61 片，门阵列也做 | 否 |
| 2 | 端口位宽 | 保持 1 位、只在视觉上分组 | **是** |
| 3 | 存成 `.circ` | 提示 + 整片丢弃，不降级成 DIP | 否 |
| 4 | HDL 导出 | 不支持 | 否 |
| 5 | `_ID` 命名 | 沿用 `"TTL Symbols"` / `"Sym" + 芯片名` | **是** |

第 2 问锁死的理由值得写下来：改成真 4 位总线会改变端口数量，而 `.circ` / `.pcirc` 里的连线是按端口
位置存的，**已经存过的文件里所有接到符号芯片的连线都会失效**。所以这不只是"以后要多写位拆分代码"，
是一个发布之后就没法回头的兼容性决定。

第 3 问的实现（见 ROADMAP 的 Feature 12）：`XmlWriter` 兼容模式里丢掉 `TtlSymbolGate` 元件和
`TtlSymbolLibrary` 库，`PelerCompat.hasSymbolChips` 触发保存前提示，提示文案按"每种损失一段"拼装，
所以同时含批注和符号的项目会看到两段。**不降级成 DIP 版**：两者端口位置不同，降级后每根连线要么接错
端口要么接空，在上游打开是一个安静地算错的电路；整片丢掉只是留下明显悬空的线，难看但安全。

顺带查清了一件常被问到的事：**交互式 HTML 导出对 74xx 芯片一片都不支持，DIP 版也一样。**
`HtmlExporter.supportedKinds()` 是一张 38 个名字的白名单，从来就没有任何 TTL 芯片；符号版只是加入了
一个本来就被按名字拒绝的家族，不是 Feature 12 造成的退化。要支持得给每片芯片写一份 JavaScript 模型。
