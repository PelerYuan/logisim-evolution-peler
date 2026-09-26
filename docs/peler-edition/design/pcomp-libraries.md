# 自定义元器件库：从单一目录到多个可分享的具名库

状态：**设计阶段**（2026-09-27）。本文档是 P0，只定方案，不含代码改动。

诉求（维护者原话，2026-09-26）：现在的自定义元件（见
[custom-components.md](custom-components.md)）用下来不满意，想要**能分享给别人**、
**能有多个具名元器件库**（明确类比了 upstream 已有的"加载外部库"行为）、**自己随时能
创建元器件到想要的库里**，并且要有**一个独立的菜单或窗口**来管理，而不是挤在
`Project → Manage Components…` 一个模态框里。

---

## 一、现状为什么不够用

`custom-components.md` 定的架构没有问题——一个自定义元件是一个带派生外观的子电路，这一点
不变。问题出在它外面那一层**管理模型**：

- `pcomp/PcompCatalog` 是一个**进程级静态单例**：`private static List<PcompLibrary>
  installed`、`private static File directory`，硬编码指向
  `~/.logisim-peler/components/`，没有第二个目录的概念。
- `pcomp/PcompCatalogLibrary`（`_ID = "My Components"`）把 `installed()` 里所有元件拍平
  进**同一个**工具箱分类，按工具名（=电路名）去重——这个"一个命名空间"的设计是`custom-
  components.md` 12.6/12.9 那批决定的直接前提，不是可以随手改的细节。
- 没有"这个工程加载了哪些库"的概念：`PcompCatalogLibrary` 对每个工程都是同一个东西，
  开不开、装不装都不受工程本身控制。
- 分享 = 把 `.pcomp` 文件发给别人，对方在管理面板里点"导入"，文件被**复制**进对方那一个
  固定目录（"按引用保留一个装了别人 Downloads 目录的路径，对方哪天清理一下就打不开了"，
  `PcompManagerDialog.java` 里 `onImport` 上方原话）——这条设计本身没问题，问题是可分享的
  单位永远只能是"一个元件"，做不到"一批元件"或"一个持续更新的库"。

**结论**：要的不是修 `PcompCatalog`，是在它之外加一层"库"的概念，同时不能破坏
`custom-components.md` 那批已经验证过的决定（发布即锁死、端口签名、compat 降级）。

---

## 二、已有的现成机制：upstream 的"加载外部库"

Logisim-evolution 本来就有一套通用机制，专门用来处理"外部的、按工程加载/卸载的、路径
引用的库"，只是从未接到 pcomp 系统上：

- `file/LibraryManager`（进程级单例）：缓存 `LibraryDescriptor -> WeakReference
  <LoadedLibrary>`，`loadLogisimLibrary`/`loadJarLibrary` 是两种已有实现，`loadLibrary
  (Loader, String desc)` 按 `type#path` 描述符（`DESC_SEP='#'`：`""`=内建、`"file"`=
  `.circ`、`"jar"`=JAR）分发。路径优先存成**相对于工程目录**的相对路径
  （`toRelative`），解析不到时不是静默失败，是弹目录/文件选择器让用户重新指路
  （`Loader.getFileFor`）。
- `file/LoadedLibrary implements LibraryEventSource`：包住真正的 `Library`，支持热重载
  （`setBase` 做差异对比后 `replaceAll` 静态方法巡回 `Projects.getOpenProjects()` 活体
  修补）。
- `file/LogisimFileActions.loadLibrary(Library, LogisimFile)` /
  `unloadLibrary(Library)`：**已经是对任意 `Library` 通用的可撤销 `Action`**，卸载前
  `LogisimFile.getUnloadLibraryMessage(Library)` 检查是否还有实例/工具栏/鼠标映射在用，
  在用就挡。
- `gui/menu/ProjectLibraryActions`（`doLoadLogisimLibrary`/`doLoadJarLibrary`/
  `doUnloadLibrary`）+ `gui/menu/MenuProject.java` 的现有"Load Library →"子菜单。
- **工具箱渲染零特判**：`gui/generic/ProjectExplorerLibraryNode.buildChildren` 对拿到的
  任意 `Library` 递归渲染 `getTools()` + `getLibraries()`，监听 `ADD_LIBRARY`/
  `REMOVE_LIBRARY` 增量刷新。任何库被 `LoadLibraries` 动作加进
  `LogisimFile.getLibraries()`，自动变成一个新的顶层工具箱分类。
- 跨工程共享：`LibraryManager.instance` 按绝对路径 `File` 做 key，两个工程加载同一个
  路径拿到的是**同一个** `LoadedLibrary`（`WeakReference`），但每个工程自己的
  `getLibraries()` 列表独立决定加载了哪个子集。

这套机制正好长成"可分享、多个、按工程加载"的样子。本次重做的核心决定：**把一个"元器件库"
做成一个真正能被这套机制加载/卸载/持久化的 `Library`**，不再发明第二套平行的加载/持久化
/工具箱渲染逻辑。

已确认**不可复用**的是本 fork 自己那个半成品 `Loader.loadCustomStartupLibraries`
（`custom-components.md` 一、已经分析过它的三个毛病：目录写死、无界面、只挂给启动时那个
工程）——它和这次要做的东西看着像，实际帮不上忙。

一条读代码确认的关键约束：`file/XmlWriter.findLibrary(ComponentFactory)`
（`XmlWriter.java:301-306`）**只查一层**——检查 `file` 自己，再检查
`file.getLibraries()`里每个**直接**成员的 `lib.contains(source)`（`tools/Library.java`
的 `contains` 只看自己的 `getTools()`，不递归 `getLibraries()`）。所以**每个库必须是
挂在工程顶层、`getTools()` 直接拍平自己内容的 `Library`**，不能再套一层"库的库"。这带来
一个正面副作用：不同库里可以有同名电路（比如两个库都有个 `Adder`），因为 `findLibrary`
认的是"哪个具体 `Library` 对象的工具列表里装着这个具体的 `ComponentFactory`"，不是全局
唯一的电路名——这是对现有"电路名全局唯一"限制的实质放宽，值得记下来。

---

## 三、核心模型

全部落在 `com.cburch.logisim.pcomp` 包。

### 3.1 腾名字：`PcompLibrary` 改名为 `PcompComponent`

现有 `PcompLibrary` 的语义是"一个已安装的元件"，和这次要新增的"一批元件组成的具名库"
撞名。纯改名，行为不变：`getMetadata()`/`getCircuit()`/`getSource()`/`isLocked()`/
`ownsCircuit()`/`load(File, Loader)` 原样保留。连锁改动：`PcompCatalog`、
`PcompCatalogLibrary`、`PcompLock`、`PcompReplacement`、`PcompTool`、两个 GUI 对话框、
测试 `PcompLibraryTest` → `PcompComponentTest`。

### 3.2 新增 `PcompLibraryManifest`

```java
record PcompLibraryManifest(String id, String name) {}
```

`id` 是创建时生成的 UUID，是库的稳定身份，目录被移动/改名都不受影响。`name` 是用户可
随时改的显示名——和元件的 `name`（会写死进电路名、发布即锁死）不同，**库名从不出现在
任何工程持久化的引用里**（引用走的是路径描述符，见 3.4），改名永远安全，不需要走
"发布新版本"那一套。

### 3.3 新增 `PcompLibraryFile`

对齐 `PcompFile` 的角色，但服务对象是一整个库目录而不是一个元件文件：
`isPcompLibraryDirectory(File)`、`read(File) -> PcompLibraryManifest`、
`write(File, PcompLibraryManifest)`、`create(File, String name)`（建目录 + 生成 id +
写清单，供"新建库…"用）。清单是目录根下一个 `library.pcomplib` XML 文件，复用
`PcompFile.parse` 已有的加固版 `DocumentBuilderFactory` 设置（不允许外部实体/DTD）。

### 3.4 新增 `PcompComponentLibrary extends Library`

代表"一个已加载的库目录"：持有清单 + 源目录 + 扫描出的 `List<PcompComponent>`。
`getTools()` 把本库自己的元件拍平去重（复用 `PcompCatalogLibrary.toolsOf` 的逻辑，
提成共享静态方法，两边都调）。`getDisplayName()` 返回清单里的 `name`，让工具箱分类
标题是人看得懂的文字而不是 UUID 或路径。

### 3.5 新增 `PcompLibraries`（无状态查询）

替代 `PcompCatalog.componentOf` 目前承担的"这个电路属于哪个元件"跨库查询：

- `componentOf(Circuit)` — 遍历 `Projects.getOpenProjects()` 找到拥有它的
  `PcompComponent`。供 `PcompLock` 用——它的五个调用点（`SubcircuitFactory`、
  `ContinuousPlacement` 等）大多是类型级代码，拿不到 `Project`。
- `componentOf(LogisimFile, Circuit)` — 只查一个工程自己的库树，不遍历全部打开的工程。
  供 `PcompLowering.plan(LogisimFile)` 用——它手上已经有确切的 `LogisimFile`，不该依赖
  "恰好有个打开的工程引用了同一个库"这种偶然性。

### 3.6 `PcompCatalog`/`PcompCatalogLibrary` 保持不变、不泛化

它们就是"那一个永远存在的默认库"的实现，继续挂在 `Builtin.java` 里，继续对应
`default.templ` 那一行 `<lib name="H" desc="#My Components" />`。**不试图**同时服务
"默认库"和"任意用户新建库"两种角色——这正是本次重做要解开的耦合，硬把两者塞进一个类
只会重现同样的问题。

---

## 四、持久化/加载：接进 `LibraryManager`

- `file/LibraryManager.java` 加第三种 `LibraryDescriptor`：`PcompLibraryDescriptor`
  （按库目录 `File` 做 key，形状照抄 `LogisimProjectDescriptor` 的
  `concernsFile`/`equals`/`hashCode`），`toDescriptor(loader)` 返回
  `"pcomplib#" + toRelative(loader, directory)`。
- 新增 `LibraryManager.loadPcompLibrary(Loader, File directory)`：先查 `fileMap`
  缓存，未命中则读清单 + 扫描目录建 `PcompComponentLibrary`，包一层 `LoadedLibrary`
  后缓存、返回——和 `loadLogisimLibrary` 同一个形状。
- `loadLibrary(Loader, String desc)` 的 switch 加 `"pcomplib"` 分支解析
  `pcomplib#<path>` 描述符。
- `Loader.java` 加 `getDirectoryFor(String)`（目录版的 `getFileFor`，路径失效时弹
  目录选择器重新定位，和现有 `.circ`/JAR 缺失文件的交互一致）和公开入口
  `loadPcompLibrary(File directory)`。
- **`LogisimFileActions.loadLibrary`/`unloadLibrary` 不用改一行**：它们本来就是对任意
  `Library` 通用的 undo 动作。一个工程"加载了哪些库"就是它自己的 `getLibraries()`
  列表里多了几个 `pcomplib#...` 描述符，和加载一个外部 `.circ` 库是同一个模型。

**为什么不做一套 fork 本地的、绕开 `LibraryManager` 的加载器**：那样要重新发明路径
相对化、跨工程去重、缺失文件重新定位这三件事，`LibraryManager` 已经做对了，没有理由
因为"内容是 pcomp 而不是 `.circ`/jar"就不复用。

**旧的单一目录不纳入这套机制、不迁移**：`~/.logisim-peler/components/` 继续是
`Builtin` 里固定挂载的那个，`default.templ` 一行不改，已有工程文件全部照常打开。新
能力是纯增量的——用户可以额外创建/加载任意多个具名库，互不影响，老用户在做这件事之前
感知不到任何变化。这是刻意的取舍：把旧目录也改造成"必须被某个工程显式加载"的库，需要
给每个已保存工程的 `<lib desc="#My Components">` 引用做兼容映射，风险和收益不成比例。

---

## 五、工具箱渲染：不需要新代码

见二、`ProjectExplorerLibraryNode` 的机制描述。一个 `PcompComponentLibrary` 被
`LoadLibraries` 动作 `addLibrary` 进工程之后，自动变成一个新的顶层工具箱分类——和加载
一个外部 `.circ` 库分类的机制完全相同。这一步唯一要做的是 3.4 里
`getDisplayName()` 那一行。

---

## 六、分享：目录本身就是可分享单元

一个库就是一个普通文件夹（一个清单文件 + 若干 `.pcomp` 文件，两者都是已经存在的、
自解释的 XML）。分享 = 把文件夹压缩发给别人，对方解压后"加载库"指向这个文件夹——和
今天分享一个 `.circ` 文件本来就是操作系统层面的事一样，不需要为此发明并长期维护一个
新的打包格式（考虑过 `.pcompack` 式的单文件包，`custom-components.md` 九、2 当时也
留着这个问题：结论是格式版本化的长期维护成本，换不来比"文件夹 + zip"更多的东西）。

唯一值得加的便利：管理窗口里的**"导出…"**按钮，把选中库的目录打成一个 `.zip`（纯
`java.util.zip`，解压回来就是同一个可加载的文件夹）。

---

## 七、管理窗口

新建 `gui/pcomp/PcompLibraryManagerFrame`（`JFrame`，非模态——管理多个库时很可能要在
"选中一个库"和"回画布保存新电路进某个库"之间来回切换，模态框会卡住这个流程）。左右
两栏：

- **左（库级）**：当前工程已加载的库列表（含始终存在的默认库）。按钮：**新建库…**
  （`PcompLibraryFile.create` + `LogisimFileActions.loadLibrary`）、**加载库…**
  （目录选择器 + `Loader.loadPcompLibrary` + `LogisimFileActions.loadLibrary`，形状
  照抄 `ProjectLibraryActions.doLoadLogisimLibrary`）、**卸载**（仅当
  `LogisimFile.getUnloadLibraryMessage(lib) == null` 时可用，默认库和内建库一样禁用
  卸载）、**导出…**（见六）。
- **右（元件级，针对左边选中的库）**：复用 `PcompManagerDialog.ComponentTable` 现有
  的 Import/Open/Replace/Delete 四个动作，逻辑不变，只是作用目录从全局唯一的
  `PcompCatalog.directoryFile()` 换成选中库的目录。抽成独立的 `PcompComponentTable`
  组件，方便新旧界面过渡期共用。
- **"把当前电路存进这个库"**：`PcompSaveDialog` 新重载
  `open(Project, PcompComponentLibrary defaultTarget)`，加一个目标库下拉框（默认
  My Components），`destination(PcompMetadata)` 从写死改成按选中库的目录参数化。

**入口**（已与维护者确认）：不新增顶层菜单，在 `gui/menu/MenuProject.java` 现有的
"Load Library →"子菜单旁边加一个"Components →"子菜单，包含"管理元器件库…"（打开
`PcompLibraryManagerFrame`）、"新建库…"、"加载库…"——和现有子菜单是同一套交互习惯。
`Save as Custom Component…` 保留在 Project 菜单原位不动。

**保存新元件默认存到哪个库**（已确认）：默认 My Components，不记上次选择、不强制
每次都选——和现有行为一致，老用户无感；想存到别的库时在下拉框里手动切换。

---

## 八、分期计划（每期独立分支 + PR + 验收，粒度参照 mcp-v2 的 P0-P5）

1. **P0 设计文档**（本文档）。纯文档，无代码。
2. **P1 核心模型**：三、1-5 的全部改名/新类。纯单元测试覆盖，`Builtin`/菜单暂不接线。
   验收：`./gradlew check` 绿；清单读写往返；一个目录的 `.pcomp` 文件能扫描成正确
   拍平去重的 `PcompComponentLibrary`。
3. **P2 接入 `LibraryManager`/`Loader`**：四的全部改动。同时接一个最小可用入口
   （`MenuProject` 现有 Load Library 子菜单下加一项"加载元器件库…"），让功能提前
   端到端可手测。验收：工程加载一个 pcomplib 目录后保存/关闭/重开，引用能正确解析
   （仿照 `file#`/`jar#` 已有的往返测试写法）；两个工程加载同一目录路径得到同一个
   `LoadedLibrary` 实例（引用相等性测试）。
4. **P3 工具箱渲染 + 锁定/降级正确性**：`PcompLock`/`PcompLowering.plan` 从依赖
   `PcompCatalog.componentOf` 改成依赖 `PcompLibraries.componentOf(...)`。验收：
   手动确认加载的库自动变成独立工具箱分类；扩展 `PcompLoweringTest` 和锁定相关测试，
   覆盖"元件来自新加载的库而非默认目录"这个新场景。
5. **P4 迁移/兼容性验证**：预期无新代码（若 P1-P3 保持纯增量）。用测试证明：旧版本
   保存的工程原样打开、`#My Components` 来源的元件照常解析；`default.templ` 按
   CLAUDE.md 要求的 XML 校验方式确认字节级未变；全新工程零操作下依然自动带着默认库。
6. **P5 新管理窗口**：`PcompLibraryManagerFrame`、抽出的 `PcompComponentTable`、
   `PcompSaveDialog` 的目标库下拉框、`MenuProject` 里的"Components →"子菜单，功能
   对齐后废弃 `PcompManagerDialog`。验收：手动 GUI 走查（新建库→存一个电路进去→在
   第二个窗口加载它→换版本→卸载→删除元件）。
7. **P6 分享便利 + 文档**：管理窗口"导出为 zip"；更新 `custom-components.md` 加
   互相引用、`docs/peler-edition/CHANGELOG.md`、`README.md` 功能描述、`CLAUDE.md`
   的代码位置表、全部 12 语种字符串文件。

---

## 九、已知风险

- **`PcompCatalogLibrary.toolsOf` 的去重逻辑要提成共享静态方法**，两个类各自维护一份
  容易漂移——P1 一次做对，不要留到 P5 补。
- **`PcompLock` 的五个调用点大多拿不到 `Project`**，`PcompLibraries.componentOf
  (Circuit)` 只能遍历 `Projects.getOpenProjects()` 兜底，理论上存在"元件所在的工程
  没打开"的边界情况（和今天 `PcompCatalog.componentOf` 依赖同一个全局状态的性质
  相同，不是本次重做引入的新风险，但 P3 的测试要覆盖到）。
- **default.templ 和已有工程零回归是 P4 的硬要求，不是默认成立的假设**——按
  CLAUDE.md 的教训，"看起来没碰"不能代替测试证明。
