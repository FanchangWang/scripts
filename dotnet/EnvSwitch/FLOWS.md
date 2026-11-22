# EnvSwitch 功能流程说明（待审核）

> 本文按「一次操作从点击到落盘」的顺序描述每个功能的真实执行路径，全部对应现有代码：
> `Program.cs`（命令行分派 + HTTP 接口）、`EnvService`（业务计算）、`EnvStore`（注册表读写）、
> `ConfigStore`（配置读写与迁移）、`PathUtil` / `RealPath`（PATH 字符串与真实目录）、`Ui/ui.html`（前端）。
> 末尾附本轮代码审计的问题清单与修复记录。

---

## 0. 文件与数据地图

| 关注点 | 位置 |
| --- | --- |
| 命令行分派、HTTP 路由 | `Program.cs` |
| 切换版本 / PATH 视图 / 校验 / 环境变量读写 | `EnvService.cs` |
| 注册表直写 + 变更广播 | `EnvStore.cs` |
| 配置文件加载、迁移、原子保存、首次探测 Java | `ConfigStore.cs` |
| PATH 字符串切分、规范化、去重、排序 | `PathUtil.cs` |
| junction / 软链穿透求真实目录 | `RealPath.cs` |
| 数据结构与 JSON 字段 | `Models.cs` |
| 单文件内嵌网页 | `Ui/ui.html` → 嵌入资源 `EnvSwitch.Ui.ui.html` |
| 配置文件 | `%LOCALAPPDATA%\EnvSwitch\config.json`（本机 `C:\Users\guyue\AppData\Local\EnvSwitch\config.json`，由 `SpecialFolder.LocalApplicationData` 拼出） |

---

## 1. 启动与命令分派

1. 进程启动先设 `Console.OutputEncoding = UTF8`。
2. **先剥离 `--config <路径>`**（可在任意命令前后），剩下的参数交给命令分派；不指定则用默认配置路径。
3. 没有任何参数 → 直接进入网页模式（自动开浏览器、端口取配置）。
4. `switch (argv[0])` 分派：`help / config / list|ls / use|apply / all|apply-all / path / serve`，其余报「未知命令」并返回 1。
5. `path` 是唯一的 PATH 预览/写入命令：打印两侧「当前 → 处理后」，`--apply` 把两侧 PATH 一起写回（见第 6 节）。
   - 不接受 `--scope`（两侧总一起算一起写），也不接受 `--no-sort` / `--no-dedup` / `--apply` 之外的任何参数，出现即报错退出。
   - 曾有过 `env` 命令，已整条移除：查看/编辑环境变量请用网页「环境变量」页，写入用 `use` / `all`。

**写入类命令的默认行为**（防止误改）：

| 命令 | 默认 | 真实写入条件 |
| --- | --- | --- |
| `use` | 真实写入 | 加 `--dry-run` 只预览 |
| `all` | 真实写入 | 加 `--dry-run` 只预览 |
| `path` | 仅预览（打印两侧 PATH「当前 → 处理后」） | 加 `--apply` 才把两侧 PATH 一起写回 |

三个命令都参数从严：出现 `--scope` 或白名单之外的参数（含裸参数）直接报错退出、不写任何东西。

---

## 2. 配置读取 / 迁移 / 保存

### 2.1 读取（`ConfigStore.Load`）

1. **先判路径可用性**（`ClassifyMissing`，`Load` 与 `config` 命令共用这一份判断）：
   - 默认路径 → 永远按「还没建」处理（它的目录本来就由 `Save` 创建，全新机器首启必须能过）；
   - 裸文件名（`--config cfg.json`，`GetDirectoryName` 返回空 = 当前目录）→ 同样按「还没建」；
   - 其它路径 → 先做一次 **1.5 秒上限**的目录探测（`DirectoryReachable`，超时按不可达），不可达 → 抛 `ConfigUnreadableException`，CLI 退出码 1、API 503，**不碰文件也不写任何东西**。这一步在 `File.Exists` / `ReadAllText` 之前：黑洞 UNC 上那两个调用各自要等 SMB 超时（旧实现每次 21 秒），先探测后实测 43ms 返回。
2. 文件不存在 → 按首次运行：`DetectDefaultConfig()` 探测本机 Java → 立即保存（`Save` 负责 `CreateDirectory`）→ 返回（保存失败只打一行警告，内存里继续用）。
3. 文件存在 → `ReadText` 读文本（`IOException` / `UnauthorizedAccessException` 先等 80ms 重试一次，仍失败抛 `ConfigUnreadableException`）。
4. 读到文本 → 反序列化 → `Migrate` → 返回。
5. 反序列化失败 → **先把坏文件复制成 `config.json.bad-yyyyMMdd-HHmmss`** → 用默认配置 → 把默认配置写回原路径（保证下次能启动）。**备份失败则跳过写回**，原文件保持原样（还能手工修），只用内存里的默认配置继续。
6. 读取时文件却消失（极少数竞态）→ 再来一次 `ClassifyMissing`：不可达抛异常，否则按首次运行。

`Migrate`（每次读取都跑，幂等）：

- `Toolchains`、`Settings`、每套工具链的 `Versions`、每个版本的 `Paths` 遇 `null` 一律补成空集合（防 NRE）。
- 工具链 `PathScope` 为空 → 继承全局 `PathScope`（再缺省 `User`）；`EnvScope` 为空 → 继承 `PathScope`。
- 旧的 `AddToPath` 统一置 `true`（现在由「有没有 PATH 条目」决定是否写 PATH）。

### 2.2 保存（`ConfigStore.Save`）

1. 确保目录存在。
2. 序列化到 `config.json.tmp`。
3. `File.Move(tmp, file, overwrite: true)` 整体替换 —— 中途失败不会留下半份 JSON。

### 2.3 首次运行探测 Java

- 候选根目录：`%JAVA_HOME%`、`Program Files\Java`、`Eclipse Adoptium`、`Zulu`、`Program Files (x86)\Java`、`%LOCALAPPDATA%\Programs\Eclipse Adoptium`、`~/.jdks`、`~/code-tools/runtimes`。
- 每个目录（含下一层、下两层）检查 `bin\java.exe`；版本号优先读 `release` 文件的 `JAVA_VERSION`，读不到才从目录名猜；`1.8.x` 归一成 `8`。
- 同一大版本只留一个：`(x86)` +100、`latest` +50、纯 `jre` +20、路径越长分越高，取最低分。
- 当前 `JAVA_HOME` 能对上某个 Home 就用它当激活版本，否则取第一个。

---

## 3. 网页模式与路由

1. `RunWeb` 先读一次配置（只为拿端口与启动行为）。
2. 监听 `http://127.0.0.1:{port}`，`port=0` 时由系统分配，启动后从 `IServerAddressesFeature` 取真实地址打印。
3. 打印「网页界面地址 / 配置文件路径 / Ctrl+C 退出」。
4. 满足 `autoOpenBrowser && OpenBrowserOnStart` 才开浏览器（`serve --no-browser` 可强关）。
5. 路由：
   - `/` 返回内嵌页面。
   - `/api/*` 为接口；未定义的 `/api/*` 由 `MapFallback` 判成 404 JSON，不会被当成页面。
   - `/favicon.ico` 返回 204。
   - 其余任意路径（`/switch`、`/project`、`/toolchain`、`/path`、`/env` 及其刷新）都返回同一页面，前端按 `location.pathname` 决定渲染哪一屏 —— 所以刷新不会跳回首页。
6. 前端 `history.pushState` 切页，`popstate` 回退时按地址重渲染。

---

## 4. 版本切换（核心流程）

### 4.1 前端交互（`renderSwitch` → `applyVersion`）

1. 只显示 `enabled !== false` 的工具链，卡片头列出变量名与两侧作用域。
2. 每个版本是一个按钮，显示版本名 + Home + 「N 条 PATH」。
3. 每张卡片有「仅预览，不写入」勾选框，**默认勾选**；勾选状态按工具链名记忆（`switchDry`），切换完重绘不会被重置。
4. 点版本 → 先用 `confirm` 列出「环境变量 = ?（哪个范围）」「PATH：N 条 / 模板 1 条 / 不写」以及「这次是预览还是真实写入」，取消则什么都不做。
5. 确认后 `POST /api/apply`；成功把消息挂进页面顶部常驻横幅（`lastSwitch`），再 `loadState` + 重绘。
6. 失败（例如非管理员写系统侧）把失败原因写进**红色常驻横幅**，不会 5 秒后就消失。

### 4.2 后端计算（`EnvService.Apply`）

按顺序做 8 步，任一步不满足就 `Fail` 返回，**不做任何写入**：

1. 按名字找工具链（忽略大小写）；找不到 → 失败；`Enabled=false` → 失败（提示「处于不生效状态」）。
2. 按名字找版本；找不到 → 失败。
3. 是否写环境变量 `writeEnv = EnvVar 非空`；需要写但 Home 为空 → 失败。
4. 解析两侧写入目标 `ParseWriteScope`：`User / Machine`，加 `Only` 后缀表示「唯一模式」（写本侧并清理另一侧）。
5. 本版本要写进 PATH 的条目：**显式 `Paths` 优先**；一条都没有才用 `PathTemplate` 展开（`%ENVVAR%` 换成 Home，再展开 `%USERPROFILE%` 等）；两者都空 → 本次不动 PATH。
6. `CollapseEquivalent`：同一真实目录的多条写法（`…\latest\jdk-21\bin` 与 `…\jdk-21.0.13.0.2\bin`）按配置顺序只留**第一条存在的**，其余算「已合并」。
7. `PreparePathEntry`：这侧 PATH 是 `REG_SZ` 时，把「字面无效、展开后确实存在」的 `%变量%` 换成具体路径（计数用于提示）。
8. `MissingForCheck`：展开后仍指向不存在目录的条目 → 整份失败，绝不把一堆空目录写进 PATH。

### 4.3 PATH 的生成与旧条目清理

设「本版本要写入的条目」为 `toAdd`：

1. **旧条目集合 `stale`**：模板原写法 + 该工具链**所有版本**的显式条目 + 模板展开值 + `Home` + `Home\bin`。
2. 取目标侧 PATH 的**注册表原样值** → 去掉 `stale`（字符串级，忽略大小写与结尾 `\`）。
3. 再按真实目录剔一遍：凡与 `toAdd` 任一目录同属一个真实目录的写法（含历史遗留别名），也删掉。
4. 追加 `toAdd`；若 `normalizePathOnApply` 开启则整体排序 + 去重（`sortPathOnApply` 默认 `true`，设为 `false` 则只去重、保留原顺序）。
5. **唯一模式**：另一侧 PATH 同样去掉 `stale + toAdd`，只有结果与当前不同才写。
6. 结果写入 `ApplyResult.PathAfter`，CLI 会逐条打印，网页横幅里给条数统计。

> 因为读的是注册表原样值，PATH 里其它条目的 `%SystemRoot%` 之类写法**不会被顺手改掉**。

### 4.4 真正写入（非预览时）

写之前先算 `envValue`：值含 `%` 且目标变量当下不是 `REG_EXPAND_SZ` 时用 `ExpandEnvironmentVariables` 展开；展开后仍有 `%` → 直接失败（不写坏值）。原值本就是 `ExpandString` 则保留字面写法，交给系统展开。

写入分两步、各自 try：

1. **写环境变量**：`EnvStore.Set(EnvVar, envValue, envTarget.Scope)`，成功记进 `done`；唯一模式且另一侧**存在**同名变量（`KindOf` 非 null）时再单独 try 删除它，删除成功把这条改记为「已写入 X（用户·唯一），并删除了系统侧同名变量」——删除也是已发生的副作用，失败会单独报「已写入变量，但删除系统侧同名变量被拒绝/失败」。
2. **写 PATH**：目标侧写 `pathAfter`；唯一模式且另一侧结果有变化时写另一侧，记进 `done` 时注明「并整理了系统侧 PATH」。
3. 任一步抛 `UnauthorizedAccessException` → `FailPart(done, …)`，消息为「写入 PATH 被拒绝。需要管理员身份运行。**（已写入 JAVA_HOME（用户），这部分不会回滚）**」——写进去的部分会如实点名，没有已写入项时只给原因。
4. 两步都成功后把 `tc.Active = version.Name` 记入配置并保存（原子写）。
5. 变量与 PATH 都写成功、仅配置文件保存失败 → 返回「已写入，但保存配置失败」，明确区分于「整次失败」。

### 4.5 返回消息

`已应用 / 预览：` +「工具链 → 版本」+「变量=值（哪个范围）」+「PATH 写入哪个范围（N 条）」，并附加两类提示：

- 「同版本另外 N 条与它们指向同一目录，已按顺序合并」
- 「N 条 %变量% 已展开成具体路径（该 PATH 是普通字符串，系统不会自动展开）」

---

## 5. 立即应用全部

- 前端：工具链配置页右上角按钮；先 `confirm` 列出「将应用哪些 → 哪些版本、跳过哪些（原因）」，再保存配置，再 `POST /api/apply/all`。
- 后端 `ApplyAll` 是**一次性处理**，不再逐套写注册表：
  1. `ApplyBuffer.Load()`：一次读进系统 / 用户两侧的全部变量（原样值）与 PATH，作为内存快照；
  2. 遍历所有工具链，跳过「名称为空 / 不生效 / 没选激活版本 / **配置里有同名工具链**（重名时按名字取第一套会写错，整组跳过并说明）」，其余在内存快照上串行计算（同一套 `Apply` 逻辑，只是读写都走 buffer，不落盘、不存配置）；
  3. 提交：按侧（系统、用户）各提交一次 —— `CommitScope` 变量逐个 `SetSilent`、PATH 写一次 `SetSilent`，**逐项记录落盘的名字与动作（写入 / 删除）**；只要有一侧真的落盘了（写入或删除）就统一 `EnvStore.Broadcast()` **一次**（10 套工具链也只广播 1 次，原来是每套 2 次）；
  4. 配置只 `Save` 一次（原来每成功一套存一次）。
- 失败归属：`CommitScope` 返回 `CommitReport(Error, Entries)`，`Entries` 里每项是 `CommitEntry(Name, Deleted)`。某侧写不进去时，**所有涉及该侧**的工具链标失败（消息「未写入：…；写入系统侧被拒绝（该侧已写入 JAVA_HOME、删除 PHP_HOME，未回滚）」）并把其激活版本回滚；`origActive` 在逐套 `Apply` **之前**采集，回滚写的是真正的旧值；涉及其它侧的工具链照常成功。「唯一」模式清掉另一侧同名变量是删除，文案写「删除 X」而不是「写入 X」。
- 部分失败不影响其它工具链；接口返回 `items[]` 逐条结果，网页顶部常驻面板用 ✅/❌ 列出，`ok` 仅在全部成功时为 `true`（但 HTTP 仍是 200，前端靠 `postJson` 拿全量结果）。
- CLI `all` 等价此流程，退出码：全成功 0，有失败 1。

---

## 6. PATH 工具（查看 + 勾选写入）

### 6.1 视图构建（`BuildPathView`）

1. 分别读系统 / 用户 PATH 的**原样值**，切成条目。
2. 每条做三件事（存在性探测走 `ProbeExists`，并发一轮，单条上限 1.5s）：
   - **归属识别**：按配置的 `PathTemplate`、该版本全部路径（显式 + 模板 + Home + Home\bin）比对，标出工具链与版本；**纯 PATH 工具链（EnvVar 为空）同样识别**。
   - **存在性**：展开变量后判断目录是否存在；网络路径/超时（1.5s）**当存在**，避免误删。
   - **展开提示**：这侧 PATH 是普通字符串且条目含 `%` 时，给出展开后的实际路径，界面显示「→ 实际生效的是 …」。
3. **规则 1（系统优先）**：与系统条目字符串等价的用户条目，从「用户 · 处理后」移除，标「与系统重复 · 已移除」。
4. **规则 2（版本冲突）**：同一工具链、跨侧出现不同版本 → 标「Java 17 ↔ 21」这类冲突徽标。
5. 「处理后」：先剔除「已失效」与「规则 1 移除项」，再把有效 `%变量%` 换成写入值，再按**真实目录**去重（同目录只留第一条），最后按需排序。
6. 顶部提示句：`系统 N 条，用户 M 条`+`X 条指向已不存在的目录，已从处理后列表剔除`+`Y 条与系统重复的用户条目已自动移除`。

徽标优先级：`已失效` > `冲突` > `与系统重复 · 已移除`。

### 6.2 勾选写入（`applyPath` → `ApplyPathSelection`）

1. 「当前」两列只读；「处理后」两列每行一个复选框，失效/被移除的条目不可勾（默认勾选其余）。
2. 点「应用写入」：
   - 两侧都没勾选 → 直接拒绝并提示（否则会把 PATH 清空）；
   - 某侧原本有条目、处理后一条不剩 → 额外确认一次「该侧 PATH 会被整条清空」；
   - 「仅预览」勾着 → 只算一遍条数不写盘；
   - 未勾 → `confirm` 列出「系统 N 条 / 用户 M 条」并强调**会整体覆盖该侧 PATH**。
3. 后端先过滤空值再判条数：某一侧给了元素但全是 `null` / `""` / 空白 → 400「没有有效条目」，不写任何东西（否则 `string.Join(';', [""])` 就是一个空字符串，等于整侧清空）。过滤后按提交的数组整体写该侧（`string.Join(';')`），写前同样把无效 `%变量%` 换具体路径。`ApplyPathSelection` 内部再兜一层：某侧**给了元素、清理后一条不剩**同样 `Fail`（覆盖任何绕过 HTTP 校验的调用方）。
4. **未提交某一侧（`null`）表示该侧不动**；两侧都没提交（或都是空数组）会被 400 拒绝 —— 防止误清空整条 PATH。空数组仍是「主动清空该侧」的唯一入口，前端对它保留二次确认。
5. 成功（非预览）后前端 `loadState + loadPathView` 刷新，看到的就是注册表里的新状态。
6. CLI 的 `path` 走同一套：默认打印两侧「当前 → 处理后」；`--apply` 时把两侧**一起**提交给 `ApplyPathSelection`（等价于网页里两侧都勾选后点「应用写入」），没有单侧写入入口。`--apply` 前先查「某侧当前有条目、处理后 0 条」——这种会把整侧写成空值，未加 `--allow-empty` 时直接拒绝并指出是哪一侧（失效条目会被剔除，去重也可能全并掉，不该悄悄发生）。

---

## 7. 环境变量页

1. 并发读两侧 `/api/env?scope=User|Machine`，每侧一个可折叠分组，标题带条数。
2. 顶部筛选框按变量名或值模糊过滤（不区分大小写），实时重绘。
3. 值是一个目录/文件路径、但目标已不存在 → 变量名后标「已失效」；判不了（UNC/超时）不标。逐条判断都走 `ProbeExists`（1.5 秒上限），且 `GetAllDetailed` 用 `Parallel.ForEach` 并发探测：值指向断网共享/拔掉的移动硬盘时整页最多等 1.5 秒，不是每条 1.5 秒相加（实测 3 条黑洞 UNC 值约 4.5 秒 → 1.5 秒；裸 `Directory.Exists` 单条 19 秒）。保存成功后 `invalid` 按**新值**重算（后端 `EnvWriteResult.Invalid`），不再沿用旧标记。
4. **`Path` 受保护**：在这个页面上把 `Path` 清空或删除会被直接拒绝，提示去「PATH 工具」页操作（那里有预览和二次确认）。`Path` 的值仍可正常编辑成非空内容。
5. **`%变量%` 与切换版本同口径**：目标不是 `REG_EXPAND_SZ` 时，值里的 `%变量%` 存进去系统不会展开。能展开成真实路径的就展开后再存，并在结果里说明；展不开而写法明显是路径（`%USERPROFILE%\x`、盘符开头）时原样存、但标成「已失效」并说明原因；`-Xmx%MEM%` 这类不是路径的文本原样存、不误标失效。
6. 行内编辑：
   - 值超过 60 字符或含 `;` 用多行输入框，否则单行；
   - 点「保存」先 `confirm` 显示「原值 → 新值」（超 80 字符截断）；
   - 成功只更新这一行（不整表重读），页面滚动位置不跳。
7. 删除：`confirm` 提示不可恢复（系统侧额外提示可能影响其它程序）→ 成功后该行就地置灰标「已删除」，同样不重读整表。
8. **值显示的是注册表原样内容**：`REG_EXPAND_SZ` 的 `%USERPROFILE%\go` 就显示 `%USERPROFILE%\go`，保存时写回也保持这个写法（不会固化成 `C:\Users\guyue\go`）。
9. **非字符串类型只读**：`REG_DWORD` / `REG_QWORD` / `REG_MULTI_SZ` / `REG_BINARY` 由 `EnvStore.NonStringKinds` 单独列出，值为「（该值是 XXX 类型，网页只能只读查看）」，行上只留「删除」；`SetEnv` 收到这类变量直接拒绝（写回会把类型变成字符串），`DeleteEnv` 用 `KindOf` 判存在，所以仍可删。
10. 切到别的页面再回来，未保存的编辑态会被放弃（`showPage` 重置 `envEdit`）。

---

## 8. 工具链配置页

1. 左侧工具链列表（名称 / 不生效标记 / 变量名 / 激活版本），右侧详情 + 版本表格。
2. 所有修改先落到前端副本 `draft`，置 `dirty`；只有点「保存工具链配置」才 `POST /api/config`。
3. 表格列：删除 / 激活（单选）/ 版本名 / Home / PATH 条目（可增删多行）。
4. 版本名被改、且它就是当前激活版本 → 激活名跟着改；删除激活版本 → 激活清空。
5. 保存流程：
   - 前端把 `paths` 里的空白行丢掉再提交；
   - 后端校验工具链名非空 → 校验路径与版本名 → 通过才 `Migrate + Save`；
   - 有错返回 400 + `errors[]`（工具链/版本/字段/下标/原文/说明），前端据此**给对应输入框描红**并在顶部列出逐条原因；
   - 成功 → 重新 `loadState` 并整页重绘（界面立刻变成「已保存」状态）。
6. 「立即应用全部」：先静默保存（失败就中止），再批量应用（见第 5 节）。
7. 删除工具链、删除版本、删除单条 PATH 都有确认或即时可回退（未保存前可点「放弃修改」）。
8. 页面顶部固定提示：「改完不会立即生效，这里只改配置」。

---

## 9. 项目配置页

| 项 | 含义 | 生效时机 |
| --- | --- | --- |
| 服务端口（0=自动分配） | 下次启动监听的端口 | 保存配置后**重启服务**才生效 |
| 切换版本时整理 PATH（排序 + 去重） | `NormalizePathOnApply` | 每次 `Apply` / 立即应用全部时 |
| 整理时是否排序（关掉则只去重） | `SortPathOnApply` | 同上；默认 `true`，与 `path` 命令默认一致 |
| 启动服务时自动打开浏览器 | `OpenBrowserOnStart` | 下次启动 |

保存失败同样在页面顶部列出逐条原因（与工具链页共用同一套错误面板）。

---

## 10. 写入通道（为什么不用 `Environment.SetEnvironmentVariable`）

- 实测本机单次写入 **22.6 秒**：它每次都向所有顶层窗口广播 `WM_SETTINGCHANGE` 并逐个等响应，窗口多或有消息循环挂起就卡住。
- 现方案 `EnvStore.Set`：
  1. 打开 `HKCU\Environment` 或 `HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment`；
  2. `DeleteValue`（值为 `null`）或 `SetValue`（**沿用原有类型**，不会把 `REG_EXPAND_SZ` 降级成 `REG_SZ`）；
  3. 用 `SendMessageTimeout(... SMTO_ABORTIFHUNG, 200ms)` 广播一次，超时就放弃通知（写入已落盘）。
- 通道分两种粒度：
  - 单套 `use` / 环境变量页保存 / 删除 → `EnvStore.Set`（写完立即广播）；
  - 立即应用全部 / CLI `all` → `EnvStore.SetSilent` 逐个静默写，最后统一 `EnvStore.Broadcast()` 一次（`ApplyBuffer.CommitScope`），N 套工具链只有 1 次广播。
- 实测：单变量约 0.7 秒，批量从 41 秒降到约 1.3 秒（再降到一次广播后 2 套批量为 112ms）。
- 权限：写 `HKLM` 被拒时统一转成 `UnauthorizedAccessException`，界面/CLI 提示「需要以管理员身份运行」；批量提交前用 `EnvStore.CanWrite` 预检该侧。

---

## 11. `%变量%` 与 PATH 值的注册表类型

| 这侧 PATH 类型 | 系统行为 | EnvSwitch 的写法 |
| --- | --- | --- |
| `REG_EXPAND_SZ` | 系统会展开 `%USERPROFILE%` 等 | 原样保留，不替换 |
| `REG_SZ`（本机两侧都是） | **完全不展开**，`%VAR%\bin` 等于一条无效路径 | 写入前把「字面无效、展开后存在」的条目换成具体路径；视图里提示实际生效路径 |

判定入口：`EnvService.PathExpandsVariables(scope)` → `EnvStore.KindOf("Path", scope) == ExpandString`。
替换入口：`PreparePathEntry`，两条写入通道都会经过（`Apply` / `ApplyPathSelection`）。

---

## 12. 真实目录判定（同目录合并的基础）

`RealPath.Key(value)`：

1. 展开环境变量 → `PathUtil.Canonical`（去结尾 `\`，但保留 `C:\` 与 `\\server\share\`）。
2. `CreateFileW(0, share=7, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS)` 打开目录/文件句柄 → `GetFinalPathNameByHandleW` 取真实路径（穿透 junction、软链，包括路径中间段的链接）。
3. 去掉 `\\?\` / `\\?\UNC\` 前缀，转小写。
4. UNC 网络路径直接按字符串比较（解析可能很慢）；打不开/不存在 → 退回规范化字符串。

用它的地方：同版本多条目合并（`CollapseEquivalent`）、切换时剔旧写法、PATH 视图去重、失效校验分组。

---

## 13. CLI 命令流程一览

| 命令 | 流程 | 输出 / 退出码 |
| --- | --- | --- |
| `EnvSwitch` / `serve` | 起网页服务 | 打印地址；Ctrl+C 退出 |
| `list` | 读配置 + 每套工具链的当前变量值 + 版本列表（`*` 标激活） | 无配置时提示去网页建 |
| `use <工具链> <版本> [--dry-run]` | `Apply` | 打印消息、变量新值、修改后 PATH；失败 1 |
| `all [--dry-run]` | `ApplyAll` | 逐条 ✔/✘；有失败 1 |
| `path [--no-sort] [--no-dedup] [--apply] [--allow-empty]` | `BuildPathView`（两侧一起） | 打印系统/用户各自的「当前 N → 处理后 M」与逐条结果；默认不写 |
| `config` | 打印配置路径 + 原文（走同一套 `ReadText`/异常分类） | 「尚未生成」只在默认路径或目录可达时提示；目录不可达 / 读不到 → 报错、退出码 1 |

`path` 的额外规则：

- 带 `--apply` 时，把「处理后」两侧列表原样交给 `ApplyPathSelection`，**系统与用户同一轮一起写**；任一侧被拒（多半是系统侧需要管理员）就整轮失败，避免出现「只改了一半」的错位状态。
- 出现 `--scope` → 直接报错退出（提示「两侧总是一起处理」）。
- 带 `--apply` 且某一侧「当前有条目、处理后 0 条」时拒绝写入并指出是哪一侧（失效条目会被剔除，不该把整侧清空）；确认要清空那一侧再加 `--allow-empty`。
- 出现白名单外的任何参数（含 `list` / `get` / `set` / `delete` 这类裸参数）→ 直接报错退出。
- CLI 与网页 PATH 页用的是同一套计算：失效剔除、系统重复移除、同目录合并、`%变量%` 展开一致。

`all` 的额外规则：

- 它是唯一「默认就真实写入」的命令，所以参数同样从严：只认 `--dry-run`，`--scope` 或其它参数（例如拼错的 `--aply`）一律报错退出、不写任何东西。

---

## 14. HTTP 接口清单

进入任何路由之前先过一道中间件：

1. `Host` 只允许 `127.0.0.1` / `localhost` / `[::1]` / `::1`，否则 400（挡 DNS rebinding）；
2. `POST` 的 `Content-Type` 必须是 `application/json`，否则 415（挡浏览器可免预检的 `text/plain`）；
3. 带 `Origin` 且 host 不在白名单 → 403；
4. 未处理异常 → 500 + `{"ok":false,"message":"服务内部错误：…"}` + 控制台一行日志；请求体不是合法 JSON → 400。

| 方法与路径 | 请求体 | 行为 | 失败响应 |
| --- | --- | --- | --- |
| `GET /api/state` | — | 每次重新读配置 + 各工具链当前变量值；空名/重名跳过并保留第一套，不抛异常 | — |
| `GET /api/env?scope=` | — | 该侧全部变量（原样值 + 失效标记 + 非字符串项的 `readOnly`/`kind`） | — |
| `POST /api/env/set` | `{scope,name,value}` | 写单个变量（空串=置空）；非字符串类型直接拒绝 | 200 + `ok:false`（权限/名字非法/类型不支持） |
| `POST /api/env/delete` | `{scope,name}` | 删除单个变量；不存在时返回「无需删除」 | 同上 |
| `POST /api/config` | 完整 `AppConfig` | `Migrate` → 校验 → 原子保存 | 400 + `errors[]`；写盘异常 500 |
| `POST /api/apply` | `{toolchain,version,dryRun}` | 切一个工具链的一个版本；同名工具链直接失败 | 200 + `ok:false` + 原因 |
| `POST /api/apply/all` | `{dryRun}` | 批量应用；重名工具链整组跳过 | 200 + `items[]` |
| `POST /api/path/view` | `{sort,dedup}` | 生成系统/用户「当前 + 处理后」视图（存在性并发探测） | — |
| `POST /api/path/apply` | `{system?,user?,dryRun}` | 整体覆盖指定侧；`null` 侧不动 | 400（两侧都没给 / 两侧都是空数组） |

---

## 15. 保存时的校验规则（`ValidateConfig`）

| 规则 | 触发条件 | 错误字段 |
| --- | --- | --- |
| 工具链名称不能为空 | `/api/config` 入口检查 | —（直接 400 文案） |
| 工具链重名 | 非空名称按 `Trim` + 忽略大小写分组，组内 >1 | `name` |
| 版本 Home 不存在 | Home 看起来是路径（`X:\…` 或 `\\…`）但目录/文件都没有 | `home` |
| PATH 条目不存在 | 按真实目录分组，**整组都不存在**才逐条报错（组内任一条存在即放行） | `path` + 下标 |
| PATH 模板展开后不存在 | **仅当该版本没有任何显式条目**时校验模板展开的每一段 | `template` |
| 版本重名 | 同一工具链里非空版本名重复 | `name` |
| 监听端口越界 | `settings.Port` 不在 0–65535 | `port` |

放行原则：展开后仍残留 `%`（判不了）、网络路径、探测超时（1.5s）一律**放行**，宁可不拦也不误伤。

---

## 16. 权限与失败模型

| 场景 | 表现 |
| --- | --- |
| 非管理员写系统变量 / 系统 PATH | `UnauthorizedAccessException` → 「写入被拒绝，请以管理员身份运行」，不崩溃；若同一侧已写入部分项，消息会点名「已写入 X，这部分不会回滚」 |
| 工具链 `MachineOnly` 在非管理员下切换 | 整次失败（先算后写，写阶段被拒） |
| 批量应用部分失败 | 各条独立成功/失败，汇总里显示失败数；失败项说明是哪一侧被拒、该侧已写入什么 |
| 配置写盘失败 | 接口 500 或 CLI 失败文案；Apply 场景区分「已写入但没记下激活版本」 |
| 配置 JSON 损坏 | 备份成 `.bad-<时间戳>` 后用默认配置继续；**备份失败则不写回**，原文件保持原样，只用内存默认继续 |
| 配置暂时读不到（被占用 / 无读权限） | 先重试一次（间隔 80ms），仍失败则**不写回默认配置**：接口返回 503 +「未做任何修改」，CLI 打印原因并退出码 1。与上一行的「损坏」严格区分，避免偶发占用把真配置覆盖掉 |
| 显式 `--config` 所在目录不可达（断网盘 / 拔掉的 U 盘 / 无权限目录） | 归入「读不到」：先做 1.5 秒上限的目录探测，不可达立即报「配置文件所在目录不存在或无法访问」，CLI 退出码 1、API 503；**不会**当成首次运行去写默认配置。实测黑洞 UNC **43ms**（旧实现每次等 SMB 超时 21 197ms）、`Z:\nope\cfg.json` exit=1；服务运行中把配置目录改名后 `/api/state` **503 / 13ms**、`/api/path/view` 503 / 6ms |
| 默认路径的目录不存在（全新机器 / 新用户 / 删过配置目录） | 按首次运行：`Save` 创建 `%LOCALAPPDATA%\EnvSwitch` 并生成默认配置，exit 0。实测把该目录整体改名后 `list` 恢复出目录 + 1 122 字节默认配置（94ms） |
| `--config` 给裸相对文件名（`cfg.json`） | `GetDirectoryName` 为空 = 当前目录，按首次运行创建；实测 `list --config rel.json` exit=0 并生成文件 |
| 启动端口被占用 / 越界 | `StartAsync` 捕获后打印「启动失败：端口 N 可能被占用或不可用」+ 换端口提示，退出码 1；配置里越界值先降级为 0（自动分配） |

---

## 17. 已知限制（审核时请留意是否符合预期）

1. 校验只判断「存在」，不判断「是不是目录」；先填路径后装软件会被拦（可接受吗？目前没有跳过开关）。
2. 「PATH 工具」的「应用写入」是**整体覆盖两侧 PATH**，勾选结果就是最终内容；未勾选的条目会被删掉。CLI 的 `path --apply` 直接把「处理后」两侧整体写回。前端已加空列表护栏（两侧都没勾 → 拒绝；某侧清空 → 二次确认）；后端会先过滤空值，`[null]` / `[""]` 这类「有元素但全空」的请求直接 400；CLI 在「某侧当前有条目、处理后 0 条」时拒绝 `--apply`，确需清空要加 `--allow-empty`。
3. 用户侧与系统侧的「重复」判定用规范化字符串，`%USERPROFILE%\…` 与已展开的同一路径跨侧不算重复。
4. 条目归属识别只认配置里出现过的路径，配置外的条目没有工具链徽标；纯 PATH 工具链（`EnvVar` 为空）也参与识别。
5. 非管理员会话无法在本机验证系统侧真实写入，只能验证 dry-run 结果与「被拒」路径。
6. PATH 条目很多时保存较慢（每条存在性探测最多等 1.5 秒，PATH 视图已并发探测，52 条约 100ms）。
7. 环境变量页对「值本身就是另一套 PATH 字符串」的变量不做拆条展示。
8. **应用版本时的 PATH 整理默认排序 + 去重**（`sortPathOnApply=true`，与 `path` 命令默认一致）；担心同名 exe 从别的目录命中时，可把它设为 `false`，此时只去重、保留原有顺序。`path` 命令的整理仍默认排序，可用 `--no-sort` 关。
9. **多入口丢失更新**：网页打开期间用 CLI `use` 改了激活版本，网页再保存会用它手里的旧草稿整体覆盖。单机单用户、风险低，暂未加「保存前比对 mtime」。
10. **配置读不到时只重试一次**（80ms）：杀软/同步盘占用通常瞬时，长重试会拖住页面；仍失败就报 503 / 退出 1，由用户重试。
11. **`ProbeExists` 的超时只是「不再等」**：`Task.Run` 里那次 `Directory.Exists` 仍会跑到系统返回为止（实测断网 UNC 约 19 秒），并发探测时可能短暂占用若干线程池线程。目前靠 1.5 秒上限保证接口响应，未加结果缓存。PATH 视图与「环境变量」页都已并发探测，所以 N 条坏值不会累加成 N × 1.5 秒（实测 3 条黑洞 UNC：`/api/env` 4.5 秒 → 1.5 秒）。**并发度受核数限制**：条数 ≤ 核数时一轮 1.5 秒，条数 > 核数会分轮（4 核 + 60 条全坏约 22 秒，仍远好于串行 90 秒），不要把「恒为 1.5 秒」写进预期。
12. **配置「看不到」的三态靠目录可达性判定**（`ClassifyMissing`）：默认路径与裸文件名一律算「还没建」（前者目录由 `Save` 创建，全新机器首启必须能过），其它路径看所在目录是否可达。因此极端的默认路径 ACL 场景仍是「保存失败 + 内存默认」，不拦启动。
13. **批量部分写入的删除记录无法在本机实证**：`CommitEntry(Name, Deleted)` 的删除分支只在「另一侧本来就有同名变量」并且「这一侧写到一半失败」时才会出现；本机非管理员写不了 `HKLM`，碰不到这个组合，只能靠代码路径与编译保证。
14. **路径探测本身也受网络影响**：`ClassifyMissing` 的 1.5 秒上限只保证「不会等 SMB 的主导超时」，探测线程超时后仍会在后台跑到系统返回（同 §17-11）。默认路径不做这条探测（否则首启会被误判不可达），所以 `%LOCALAPPDATA%` 被重定向到离线漫游目录时，读配置仍可能慢，但默认路径这条路径不会被拦成 503。

---

## 附录 A. 本轮审计发现与修复（修复反馈区）

| 编号 | 级别 | 复核结果 | 原因 | 修复说明 |
| --- | --- | --- | --- | --- |
| A1 | 高 | ✔ 已改并编译通过 | `ConfigStore.Save` 直接 `File.WriteAllText` 覆盖，中途异常/断电会把配置截成半份 JSON | 改为先写 `config.json.tmp` 再 `File.Move(overwrite)` 原子替换；实测配置目录无 tmp 残留 |
| A2 | 中 | ✔ 已实测（临时配置） | 配置反序列化失败时直接回落默认，随后任何保存都会把用户原配置永久覆盖 | `Load` 失败先复制成 `config.json.bad-yyyyMMdd-HHmmss` 再回落默认；实测生成 20 字节坏文件备份 + 946 字节默认配置 |
| A3 | 高 | ✔ 已实测（GOPATH） | 环境变量页读的是**展开后**的值，编辑保存会把 `REG_EXPAND_SZ` 的 `%USERPROFILE%\go` 固化成绝对路径，写法语义丢失 | 新增 `EnvStore.GetRaw/AllRaw`；`GetAllDetailed` 改用原样值；PATH 的读写/比较统一走 `GetPathRaw`。实测 `GOPATH` 由 `C:\Users\guyue\go` 变为 `%USERPROFILE%\go`（16 字符） |
| A4 | 高 | ✔ 已实测 | `POST /api/path/apply` 把缺失字段当空数组，误调一次可能清空整条 PATH | 改为「`null` 侧不动」，两侧都没给直接 400；实测「只传 user」返回「系统：未提供，保持原样」 |
| A5 | 中 | ✔ 已改并编译通过 | `{"toolchains":null}` 会在 `/api/config` 触发 NRE → 500 | 入口 `Toolchains ??= []`，`Migrate` 统一补 `Settings/Versions/Paths` 的 null |
| A6 | 中 | ✔ 已实测（临时请求） | 同一工具链版本重名时 `Apply` 取第一个匹配，切换目标不确定 | 保存校验新增 `name` 冲突错误；实测重复版本名被 400 拒绝且未写盘 |
| A7 | 低 | ✔ 已改 | 环境变量/PATH 已写入但配置保存抛异常时，网页收到 500 | `Apply` 捕获并返回「已写入，但保存配置失败」；`/api/config` 写盘异常返回 500 + 明确文案 |
| A8 | 中 | ✔ 已改（编译 + 页面含 `data-dry`） | 切换成功后 `renderSwitch` 重绘，把「仅预览」强制恢复成勾选，用户取消过的真实写入会被悄悄变回预览 | 新增 `switchDry` 按工具链名记忆勾选状态 |
| A9 | 低 | ✔ 已改 | 切页后环境变量编辑态残留，回来仍是编辑框 | `showPage` 进入即 `envEdit = null` |
| A10 | 低 | ✔ 已改 | 项目配置页保存失败只有 toast，没有错误面板 | 项目页复用同一套 `configErrors` 面板；`persistDraft` 失败时也重绘项目页 |
| A11 | 低 | ✔ 已改（0 警告） | `net10.0` TFM 下注册表 API 触发 31 条 CA1416 平台警告，掩盖真实告警 | TFM 改 `net10.0-windows`，构建 0 警告 0 错误 |
| A12 | 低 | ✔ 已改 | 校验失败文案固定写「N 处路径不存在」，版本重名等错误被误述 | 统一改为「N 处配置有误」 |
| A13 | 高 | ✔ 已实测（两侧汇总 + `--apply` 被拒时两侧均不变） | `path` 默认只按 `--scope`（缺省 User）整理一侧，另一侧完全不动，容易造成系统/用户条目重复或错位 | `path` 重写为总是打印**系统 + 用户**两侧「当前 → 处理后」，`--apply` 把两侧一起提交给 `ApplyPathSelection`，任一侧被拒则整轮失败；`--scope` 出现即报错退出 |
| A14 | 中 | ✔ 已实测（`env` 命令现已被整条移除，`EnvSwitch.exe env` 报「未知命令」） | `env` 曾偏离定位：先是 list/get/set/delete 四个子命令，后改成一整套预览 + `--apply`，但写入能力与 `all` 完全重复 | 删除 `env` 命令及其 `RunEnvPreview` / `PrintEnvSide` / `PlanEnvWrites` / `EnvPlanItem`；环境变量查看与编辑留在网页「环境变量」页，写入统一走 `use` / `all`（`all` 同时补上参数从严校验） |

**未修复 / 留待确认**：

| 编号 | 级别 | 状态 | 说明 |
| --- | --- | --- | --- |
| B1 | 中 | 待你确认 | 「先填路径、后装软件」会被保存校验拦住，是否加「忽略校验」开关？ |
| B2 | 中 | 待你确认 | PATH 工具「应用写入」整体覆盖语义是否要改成「增量应用勾选项」？ |
| B3 | 低 | 待你确认 | 环境变量页是否需要按注册表类型标注（`REG_SZ` / `REG_EXPAND_SZ`）？ |
| B4 | 低 | 待你确认 | 非管理员下系统侧真实写入无法本机验证，是否需要以管理员补一次实测？ |

### 附录 A.2 第二轮审计（`audit/audit01.md` 的 A1–A13 + `audit/audit02.md` 的 B1–B4）

复核结论：**16 条已修复、1 条部分修复**（A13 的 6 个子项修了 4 个，多入口覆盖与自动化测试保留）。每条的证据、建议与本次修复的对应关系见两份审计报告末尾的「修复反馈区」。

| 编号 | 级别 | 复核结果 | 原因 | 修复说明 |
| --- | --- | --- | --- | --- |
| A1 | 高 | ✔ 已实测 | 本地接口无 `Host`/`Origin`/`Content-Type` 校验，任意网页可盲打改配置与 PATH | 统一中间件三道闸：Host 白名单 400、非 JSON 415、跨站 Origin 403；实测 400/415/403/200 |
| A2 | 高 | ✔ 已实测 | 工具链重名/空名让 `/api/state` 500，批量应用打错配置 | 状态接口逐条填充不抛；保存校验拒绝重名；`ApplyAll` 重名整组跳过；单套 `Apply` 遇重名直接失败。实测重名配置 `/api/state` 200、保存 400、`all`/`use` 拒绝 |
| A3 | 中 | ✔ 已实测 | 配置 `port` 非法启动即崩（未捕获堆栈） | `ValidateConfig` 校验 0–65535（保存 400）、`serve --port` 参数校验、`RunWeb` 越界降级为 0、`StartAsync` try/catch + 换端口提示、退出码 1 |
| A4 | 中 | ✔ 已实测 | `use` 不校验多余参数、`--dry-run` 被当版本名；`serve` 参数不严 | 新增 `RejectUnknownFlags`；`use` 位置参数 = 过滤选项后的剩余（<2 用法、>2 多余）；`serve` 逐参白名单 |
| A5 | 中 | ✔ 已实测 | PATH「应用写入」缺空列表护栏，可把两侧 PATH 写空 | 前端两侧皆空直接拒绝（浏览器实测提示）、单侧清空二次确认；后端双空 400 |
| A6 | 中 | ✔ 已实测 | 变量先写 PATH 后写，失败不回滚也不说明 | 分步 try + `done` 记录 + `FailPart`；实测输出「已写入 JAVA_HOME（用户），这部分不会回滚」 |
| A7 | 中 | ◐ 部分修复（第三轮 C1 补齐） | 存在性探测超时策略不一致（PATH 串行 1.5s、变量无超时） | PATH 侧已走 `ProbeExists(1.5s)`、`BuildPathView` 并发探测；**变量侧当时漏改**，第三轮 C1 才把 `IsProbablyInvalidPath` / `LiteralExists` 一并改走 `ProbeExists`（实测 19 011ms → 1 506ms） |
| A8 | 低 | ✔ 已改（第三轮 C3 真正落地） | `origActive` 采集太晚，回滚是空操作 | 采集移到逐套 `Apply` 之前；第二轮实际漏做（代码仍在之后），第三轮 C3 补齐并统一注释 |
| A9 | 低 | ✔ 已改 | `Migrate` 不剔 `null`、不归一字符串 | `RemoveAll(x => x is null)` + `Name/EnvVar/PathTemplate/Active/Home` 归一为 `""`；`/api/config` 先 `Migrate` 再校验 |
| A10 | 低 | ✔ 已改 | `Identify` 要求 `EnvVar` 非空，纯 PATH 工具链无归属 | 去掉该前置条件，只要求名称非空 |
| A11 | 低 | ✔ 已实测 | 非字符串注册表值被 `ToString()` 显示并写坏 | `AllRaw` 只收字符串类型 + `NonStringKinds` 只读展示 + `SetEnv` 拒绝改写 + `DeleteEnv` 用 `KindOf` 判存在。实测 `REG_DWORD` 探测变量只读、拒绝改写、可删除 |
| A12 | 低 | ✔ 已改（保留开关） | 默认排序去重会重排整条 PATH，改变命令解析优先级 | 按你的要求**保留默认排序 + 去重**（与 `path` 命令一致），新增 `SortPathOnApply`（默认 `true`）作为可选开关：设为 `false` 时只去重、保留原顺序 |
| A13 | 低 | ⚠ 部分修复（4/6） | 小问题合集 | 已修：`use` 漏版本名、删不存在变量文案、首启 `Save` 保护、`C:/` 规范化；保留：多入口覆盖（见 §17-9）、无自动化测试 |
| B1 | 中 | ✔ 已实测 | `Home` 的 `%变量%` 写进 `REG_SZ` 不展开，校验却按展开后放行 | 写入前算 `envValue`，非 `ExpandString` 时展开、残留 `%` 直接拒绝；实测写入值为展开后的绝对路径 |
| B2 | 中 | ✔ 已实测 | 未处理异常是空 500，无日志无响应体 | `AddSimpleConsole` + `LogError` + 500 JSON；`JsonException` 单列 400。实测坏 JSON → 400、异常 → 500 JSON |
| B3 | 低 | ✔ 已改（部分实测） | 批量单侧部分写入不回滚不报告 | `CommitScope` 返回 `CommitReport(Error, Written)` 并按项报告；实测系统侧被拒时有明确文案；同侧「写一半」本机无法构造，属防御性代码 |
| B4 | 低 | ✔ 已实测 | 环境变量页保存后沿用旧 `invalid` 标记 | 后端 `EnvWriteResult.Invalid` 按新值重算，前端直接采用；实测 `invalid` 随值变化 true/false |

### 附录 A.3 第三轮审计（`audit/audit03.md` 的 C1–C9）

复核结论：**9 条全部处理**（8 条修复 + 1 条保留为已说明的设计代价）。本轮重点是把「修了 90%」的部分补齐：C1 补上变量侧探测、C2 把「读不到」与「损坏」拆开，其余是护栏与文案一致性。

| 编号 | 级别 | 复核结果 | 原因 | 修复说明 |
| --- | --- | --- | --- | --- |
| C1 | 中 | ✔ 已实测 | 变量页的存在性判断仍是裸 `Directory.Exists`，值指向断网共享时页卡 19 秒（A7 只改了 PATH 侧） | `IsProbablyInvalidPath` 与 `LiteralExists` 都改走 `ProbeExists`（1.5s 上限，超时当存在）。实测 `\\192.0.2.1\share\jdk`：`GET /api/env` 由 19 011ms 降到 **1 506ms**；`path` 与 `/api/path/view` 行为不变 |
| C2 | 中 | ✔ 已实测 | 「读失败」和「JSON 损坏」共用一条异常分支：临时读不到 → 静默改用默认配置 → 保存即覆盖真配置，且备份那步也要读文件，所以连备份都没有 | 新增 `ConfigUnreadableException`；`Load` 只在**解析失败**时备份（`.bad-<时间戳>`）+ 默认配置，**读不到**先重试一次（80ms），仍失败就抛。API 由中间件返回 **503**「未做任何修改」，CLI（`use`/`all`/`list`/`path`/`serve`）打印原因并退出码 1。实测独占锁：锁前 200（KeepMe）、锁定中 **503**、解锁后 200；配置 390 字节**原样未被覆盖**、目录无 `.bad-*` / `.tmp`；同时锁定时 `list` 输出「配置文件只是暂时读不到（不是损坏），已放弃本次操作」exit=1。坏 JSON 仍走备份 + 默认（实测生成 `.bad-20261123-055823`） |
| C3 | 低 | ✔ 已代码审查（无运行时影响） | A8 声称的「采集提到 `Apply` 之前」实际没落地，注释与代码相反，`FLOWS.md` 也跟着写错 | 采集移到逐套 `Apply` 之前，注释改成事实；`FLOWS.md` 的 A8 行同步改为「第三轮补齐」 |
| C4 | 低 | ✔ 已页面实测 | `sortPathOnApply` 有开关但界面只有一个复选框（只绑 `normalizePathOnApply`），README 却写「都在项目配置页」 | 项目配置页新增「整理时按字典序排序（关掉则只去重、保留原有顺序）」复选框，绑 `s.sortPathOnApply`；上面的「自动整理」关掉时它自动变灰。浏览器实测：两枚复选框均默认勾选，点掉「自动整理」后排序项变 `disabled`，刷新后恢复默认 |
| C5 | 低 | ✔ 已实测 | PATH 空列表护栏只判数组长度，`[null]` / `[""]` 能绕过，写入的是空字符串（等于清空该侧） | 接口先过滤空白元素再判条数：`{"system":[null]}` / `[""]` / `["  "]` → **400**「没有有效条目」；`{"system":[]}`（真的清空该侧）仍可用；`ApplyPathSelection` 内部再过滤一遍。实测四种请求分别 400/400/400/200、双空 400 |
| C6 | 低 | ✔ 已实测 | 「环境变量」页可把 `Path` 写成空串或整条删除，与 PATH 页的护栏完全不对等 | `SetEnv` 对 `Path` 且新值为空直接拒绝；`DeleteEnv` 对 `Path` 一律拒绝，文案指向「PATH 工具」页。实测 `set Path=""` 与 `delete Path` 都返回 `ok:false` 并给出原因；`Path` 的非空编辑仍可用 |
| C7 | 低 | ✔ 已实测 | `Apply` 会展开 `%变量%`，环境变量页不会，而「已失效」标记按展开后判断 → 可能「存进去无效、界面说正常」 | `SetEnv` 与 `Apply` 对齐：目标非 `REG_EXPAND_SZ` 时，能展开成真实路径就先展开（并在结果里说明）；展不开且写法明显是路径（`%USERPROFILE%\x`、盘符开头）则原样存但**标为已失效**并说明；`-Xmx%MEM%` 这类非路径文本原样存、不误标。实测三组：可展开 → 存 `C:\Users\guyue\code\scripts` + 提示；不可展开 → `invalid:true` + 提示；非路径 → `invalid:false` 无提示 |
| C8 | 低 | ✔ 已实测 | `all` 因重名整组跳过时，收尾句仍让人去「勾选生效、选定激活版本」，真正原因被埋在前面 | 跳过原因分类计数（重名 / 不生效 / 未选激活版本），收尾按真实原因给建议：重名 → 「先把重名的工具链改成不同的名字」；不生效 → 「勾选「生效」」；未选版本 → 「在版本表格里选定激活版本」。实测重名配置输出「…请先把重名的工具链改成不同的名字后再试。」exit=1 |
| C9 | 低 | ✔ 已改（部分实测） | 「唯一」模式部分写入失败时只点名写成功的变量，另一侧同名变量已被删除这件事没说 | `done` 记录改为「已写入 X（用户·唯一），并删除了系统侧同名变量」；删除单独 try，失败时明确「已写入变量，但删除系统侧同名变量被拒绝/失败」；PATH 的「唯一」写入同样记录「并整理了系统侧 PATH」。实测部分写入（env 写用户成功、系统 PATH 被拒）仍输出「（已写入 ENVSWITCH_C9X（用户），这部分不会回滚）」；**删除分支**需要系统侧存在同名变量（非管理员无法构造），只有代码审查 |

**保留 / 已知代价**：C1 附带的「`ProbeExists` 超时后 `Task.Run` 里的探测仍在跑、并发时可能占线程」按原设计保留（结果缓存会引入陈旧判断），已记入 §17-11。
**更正（第四轮 D2）**：上面 C2 行写的「目录无 `.bad-*` / `.tmp`」只对**只读接口**成立。`POST /api/config` 在配置被锁定时保存会失败，`Save` 先写出的 `keep.json.tmp` 会留在目录里；原文件仍然完好（替换那一步没做成），不属于配置被破坏。

### 附录 A.4 第四轮审计（`audit/audit04.md` 的 D1–D5）

| 编号 | 级别 | 复核结果 | 原因 | 修复说明 |
| --- | --- | --- | --- | --- |
| D1 | 中 | ✔ 已实测 | `Load` 先 `File.Exists`，路径不可达（断网盘/U 盘拔掉/无权限目录）时 `File.Exists` 返回 false → 被当成「首次运行」：用 `DetectDefaultConfig()` 生成一份默认配置并尝试写回，`exit=0`，`list` 打印完整默认 Java 配置、`use --dry-run` 照常预演（去掉 dry-run 就会拿这份假配置去写注册表） | `Load` 改为先 `ReadText`，`FileNotFoundException` / `DirectoryNotFoundException` 时看「所在目录是否可达」：显式 `--config` 且目录不可达 → `ConfigUnreadableException`（CLI 打印「配置文件所在目录不存在或无法访问」exit=1，API 503），目录可达或默认路径才按首次运行。实测：`list --config \\192.0.2.1\share\cfg.json` → exit=1（21 197ms，网络超时后报错，不再输出默认配置）；`list --config Z:\nope\cfg.json` → exit=1；目录可达的 `%TEMP%\es_d1\new.json` → 仍按首次运行生成（1 122 字节、exit=0） |
| D2 | 低 | ✔ 已实测 | `RecoverFromBroken` 备份失败也照样 `Save` 覆盖原文件（`catch { }` 后流程继续），一份只是「解析不出来」但内容还在的文件被永久换成默认 | 备份成功才写回（`backedUp` 布尔）：备份失败时打印「为避免覆盖仍可修复的原文件，本次不写回默认配置」，原文件保持原样、只用内存默认继续。实测预占只读 `.bad-<时间戳>` 文件让备份失败：输出上述两行警告，原文件 18 字节**逐字节未变**、无覆盖 |
| D3 | 低 | ◐ 已改（编译 + 回归） | 批量路径 `ApplyBuffer → CommitScope` 把「唯一」模式删掉另一侧同名变量也记成「已写入 X」，与单发分支的口径不一致 | `CommitReport` 的 `Written` 改为 `Entries: List<CommitEntry(Name, Deleted)>`，`CommitScope` 按「新值为 null = 删除」记 `Deleted`；批量失败文案改成「该侧已写入 JAVA_HOME、删除 PHP_HOME，未回滚」，`Broadcast` 判据改成 `Entries.Count > 0`。编译 0 警告；`all --dry-run` 与其余回归不受影响。**删除分支**需两侧有同名变量且一侧写失败，本机非管理员写不了 `HKLM`，未实测（同 C9 限制，记入 §17-13） |
| D4 | 低 | ✔ 已实测（参数层） | CLI `path --apply` 在某一侧处理后 0 条（条目全失效被剔除）时会写空串，既无护栏也无确认 | 加两道：`ApplyPathSelection` 内部「给了元素、清理后 0 条」→ `Fail`（兜任何绕过 HTTP 校验的调用方）；`RunPathBoth` 在 `--apply` 前检查「某侧当前有条目、处理后 0 条」→ 拒绝并指出是哪一侧，确需清空加新增的 `--allow-empty`。实测：`path --allow-empty` 预览 exit=0、`path --allow-empty --bogus` 被参数白名单拒（提示「只支持 --no-sort / --no-dedup / --apply / --allow-empty」）、普通 `path` 预览 exit=0。**「处理后 0 条」需真实 PATH 全失效，未构造** |
| D5 | 低 | ✔ 已实测 | `/api/env` 的 `GetAllDetailed` 逐条串行探测，N 条坏值 = N × 1.5 秒（实测 1 条 1.502s、0 条 6.6ms） | 照 `BuildPathView` 用 `Parallel.ForEach` 并发算 `invalid` 再组装。实测 3 条 `\\192.0.2.1\share\jdk` 用户变量：`GET /api/env` 1 502 / 1 518ms（串行约 4.5s）；基线 0 条 24ms。测试变量已用 `/api/env/delete` 清理，配置 SHA 仍 `48574EBE…9C802D` |

**保留 / 已知代价**：D1 让显式 `--config` 指向不可达目录的报错要多等一个网络超时（21 秒，`File.ReadAllText` 的 SMB 超时），属于系统层等待，未做提前 `Directory.Exists` 短探测（那个本身也要十几秒）——**第五轮已改**：换成 1.5 秒上限的目录探测（A.5 的 E3），现在 43ms 就报错；D2 备份失败时仍然用默认配置继续跑（只是不写回），CLI 退出码为 0——与 D1 的「不拿假配置写注册表」不同，这里原文件内容确实坏了、内存默认不会落盘。

---

### 附录 A.5 第五轮审计（`audit/audit05.md` 的 E1–E3）

| 编号 | 级别 | 复核结果 | 原因 | 修复说明 |
| --- | --- | --- | --- | --- |
| E1 | 高 | ✔ 已实测（端到端） | 第四轮 D1 的条件 `path is not null` 恒为真（`Program` 早把 `configPath ??= DefaultPath` 补上），于是默认路径首启也被「目录不可达」拦死：全新机器 `%LOCALAPPDATA%\EnvSwitch` 不存在 → exit=1，而该目录只有首次运行分支里的 `Save` 会创建 → 永远建不出来 | 抽 `ConfigStore.ClassifyMissing(file)`（默认路径 / 裸文件名一律「还没建」，其它路径看目录可达），`Load` 与 `config` 命令共用同一份判断。实测：把真实配置目录整体改名为备份后，`list`（默认路径、不带 `--config`）**exit=0 / 94ms**，自动建出目录 + 1 122 字节默认配置；随后删除新目录、改回原名，用户配置 SHA 仍 `48574EBE…9C802D`，8099 重启后 200 |
| E2 | 低 | ✔ 已实测 | 裸相对文件名 `--config cfg.json`：`GetDirectoryName` 返回 `""` 被当成「目录不可达」，报错文案冒号后为空 | 同一个 `ClassifyMissing` 里 `string.IsNullOrEmpty(dir)` 直接算「当前目录 = 还没建」。实测 `list --config rel.json` exit=0 并生成文件（94ms）；`config --config rel2.json` 输出「（尚未生成，运行一次后自动生成）」exit=0 |
| E3 | 低 | ✔ 已实测 | 不可达路径的报错要等 SMB 超时，Web 每个请求都付一次（21 秒） | `Load` 在碰文件之前先做 1.5 秒上限的目录探测（`DirectoryReachable`，超时按不可达），不可达立即抛，不再走 `File.Exists` / `ReadAllText`。实测：黑洞 UNC `list` **43ms**（原 21 048ms）；服务运行中把配置目录改名后 `/api/state` **503 / 13ms**、`/api/path/view` 503 / 6ms；正常默认配置 `list` 76ms |

**顺带**：`ConfigUnreadableException.Message` 去掉「配置文件暂时读不到：」前缀（由调用方各自给前缀），503 响应体不再出现两次同一句话；`config` 命令改为先 `File.Exists` + `ClassifyMissing`，仅对不可达路径报错退出。

**保留 / 已知代价**：默认路径不做目录探测（否则首启会被误判不可达），`%LOCALAPPDATA%` 被重定向到离线漫游目录时读配置仍可能慢，但不会被拦成 503；探测线程超时后在后台继续跑到系统返回（同 §17-11、§17-14）。

---


