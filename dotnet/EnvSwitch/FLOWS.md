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
2. **先剥离 `--config <路径>`**（可在任意命令前后），剩下的参数交给命令分派；不指定则用默认配置路径。剥参数时当场拒绝两种写法：空字符串（`--config ""`，旧写法会拿探测出的默认配置继续干活、还在当前目录留下 `.tmp`）、以及路径本身是个目录（`--config %TEMP%`）。「是不是目录」放在 `ClassifyMissing` 里判，不在解析段裸调 `Directory.Exists`（黑洞 UNC 上那一句就要吃掉 20 秒 SMB 超时）。
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

1. **先判路径可用性**（`ClassifyMissing` → `ClassifyDirectory`，`Load`、`config` 命令与 `Save` 共用这一份判断）：
   - 默认路径 → 永远按「还没建」处理（它的目录本来就由 `Save` 创建，全新机器首启必须能过）；
   - 裸文件名（`--config cfg.json`，`GetDirectoryName` 返回空 = 当前目录）→ 同样按「还没建」；
   - 其它路径 → **分两段判**。先取 `Path.GetPathRoot(dir)` 对**盘根**做一次 **1.5 秒上限**的探测（`DirectoryReachable`）：根行为空 → 按「还没建」；根探测失败 → `Timeout`（超 1.5 秒）或 `Unreachable`（连不上），抛 `ConfigUnreadableException`，CLI 退出码 1、API 503，**不碰文件也不写任何东西**。这一步在 `File.Exists` / `ReadAllText` 之前：黑洞 UNC 上那两个调用各自要等 SMB 超时（旧实现每次 21 秒），先探测后实测 43ms 返回。
   - 根通了再看目录本身：`Directory.Exists(dir)` → 按「还没建」（**本地盘上的新目录属于合法首次创建**，不再误报「检查网络盘是否已连接」）；`File.Exists(dir)`（目录位置其实是个文件，如 `C:\Windows\notepad.exe\cfg.json`）→ `Unreachable`；两者都不是 → 按「还没建」。
   - 不可达结论缓存 2 秒（`UnreachableStamp`）：2 秒内的后续调用直接返回同一个结论，不必每个请求重付 1.5 秒。**代价是「恢复」有滞后**——命中缓存期间不会再真探，所以路径重新可用后最多要等缓存到期（≤2 秒）才读到；缓存到期后的第一次探测成功即清掉缓存（实测 `subst` 卸载 → 503 ≤72ms，重挂立刻仍 503、+2.6 秒转 200）。
   - `Load(allowFirstRun: false)`（常驻服务在请求里用）：走到「确实要首次运行」分支时直接抛 `FirstRunNotAllowed` —— 服务启动时配置已落过盘，运行中再判成首次运行只可能是目录被改名/删除。
2. 文件不存在 → 按首次运行：`DetectDefaultConfig()` 探测本机 Java → 立即保存（`Save` 负责 `CreateDirectory`）→ 返回。**落盘失败要分两种**：默认路径只打一行警告、内存里继续用（`%LOCALAPPDATA%` 只读时程序还能跑），并把 `LastLoadUsedFallback` 置位——这份配置只够看，`use` / `all` / `path --apply` 的真实写入会被 `Program` 的守卫直接拒绝（`list` 也会注明「临时兜底的默认配置」）；**显式 `--config` 直接抛 `CannotWrite`**（退出码 1），不拿这份「探测出来的」配置继续。两处首启分支（`!File.Exists` 与读时消失）共用 `FirstRunFallback(config, file, explicitPath: !IsDefaultPath(file))`。
3. 文件存在 → `ReadText` 读文本（`IOException` / `UnauthorizedAccessException` 先等 80ms 重试一次，仍失败抛 `ConfigUnreadableException`）。
4. 读到文本 → 反序列化 → `Migrate` → 返回。
5. 反序列化失败 → **先把坏文件复制成 `config.json.bad-yyyyMMdd-HHmmss`** → 用默认配置 → 把默认配置写回原路径（保证下次能启动）。**备份失败则跳过写回**（原文件保持原样，还能手工修）。这里同样按显式 / 默认分流（`RecoverFromBroken(file, explicitPath: !IsDefaultPath(file))`）：**显式路径**在「备份失败」或「默认配置写不回」时抛 `CannotRecover`（退出码 1，不再「警告一下就继续」）；**默认路径**才降级为「内存默认继续」，并把 `LastLoadUsedFallback` 置位。
6. 读取时文件却消失（极少数竞态）→ 再来一次 `ClassifyMissing`：不可达抛异常；仍判首次运行时，`allowFirstRun: false` 直接抛，否则按首次运行。

`Migrate`（每次读取都跑，幂等）：

- `Toolchains`、`Settings`、每套工具链的 `Versions`、每个版本的 `Paths` 遇 `null` 一律补成空集合（防 NRE）。
- 工具链 `PathScope` 为空 → 继承全局 `PathScope`（再缺省 `User`）；`EnvScope` 为空 → 继承 `PathScope`。
- 旧的 `AddToPath` 统一置 `true`（现在由「有没有 PATH 条目」决定是否写 PATH）。

### 2.2 保存（`ConfigStore.Save`）

1. **写侧先过与阅读同一道闸**：`ClassifyMissing` 不是 `FirstRun` 就抛 `ConfigUnreadableException`，一个字节都不写 —— 空值 / 目录 / 盘根不通的路径都在这里被挡住。
2. 目录处理：`allowCreateDirectory: true`（默认，CLI 首次运行）→ `Directory.CreateDirectory`；`false` → 目录不存在直接抛「配置文件所在目录已不存在（可能被改名或删除）」，**绝不重建**。目前传 `false` 的有三处：网页 `/api/config`、`EnvService.Apply`（写激活版本，`:878`）、`EnvService.ApplyAll`（`:1049`）——这三处都是「`Load` 已经成功过，目录刚刚还在」，服务内不该承担建目录的职责。
3. 序列化到 `config.json.tmp`。
4. `File.Move(tmp, file, overwrite: true)` 整体替换 —— 中途失败不会留下半份 JSON。
5. `Move` 抛异常时先 `File.Delete(tmp)` 再原样抛出，避免在用户目录里留下垃圾 `.tmp`。
6. 调用方差异：`Load` 的首次运行分支拿到权限类异常时，**显式 `--config` 抛 `CannotWrite`（exit 1）**、默认路径只警告（见 §2.1-2）。

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
4. 未处理异常 → 500 + `{"ok":false,"message":"服务内部错误：…"}` + 控制台一行日志；请求体不是合法 JSON → 400；`ConfigUnreadableException` → 503，文案里同时列出「网络盘断开 / U 盘拔掉 / 缺少权限」与「被其它程序占用」两类可能。

| 方法与路径 | 请求体 | 行为 | 失败响应 |
| --- | --- | --- | --- |
| `GET /api/state` | — | 每次重新读配置（`allowFirstRun: false`）+ 各工具链当前变量值；空名/重名跳过并保留第一套，不抛异常 | 配置位置不可达 / 目录已不在原位 → 503 |
| `GET /api/env?scope=` | — | 该侧全部变量（原样值 + 失效标记 + 非字符串项的 `readOnly`/`kind`） | — |
| `POST /api/env/set` | `{scope,name,value}` | 写单个变量（空串=置空）；非字符串类型直接拒绝 | 200 + `ok:false`（权限/名字非法/类型不支持） |
| `POST /api/env/delete` | `{scope,name}` | 删除单个变量；不存在时返回「无需删除」 | 同上 |
| `POST /api/config` | 完整 `AppConfig` | `Migrate` → 校验 → 原子保存（`allowCreateDirectory: false`，目录没了就是 503、不重建） | 400 + `errors[]`；位置不可达 503；写盘异常 500 |
| `POST /api/apply` | `{toolchain,version,dryRun}` | 切一个工具链的一个版本；同名工具链直接失败 | 200 + `ok:false` + 原因 |
| `POST /api/apply/all` | `{dryRun}` | 批量应用；重名工具链整组跳过 | 200 + `items[]` |
| `POST /api/path/view` | `{sort,dedup}` | 生成系统/用户「当前 + 处理后」视图（存在性并发探测） | 配置位置不可达 → 503 |
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
| 显式 `--config` 所在目录不可达（断网盘 / 拔掉的 U 盘 / 无权限目录） | 归入「读不到」：先对**盘根**做 1.5 秒上限的探测，不可达立即报错（超时给「未在 1.5 秒内响应：可能是路径不存在、网络盘断开，也可能只是慢（稍后重试可确认）」，连不上给「目录不存在或无法访问」），CLI 退出码 1、API 503；**不会**当成首次运行去写默认配置。实测黑洞 UNC 的 `config` 由 21 101ms 降到 **1 552ms**（`list` 43ms）、`Z:\cfg.json` exit=1 |
| 显式 `--config` 的位置**写不进去**（如 `C:\Windows\System32\cfg.json`、只读共享） | 首次运行发现配置不存在、`Save` 抛权限错误时，**显式路径直接失败**（`exit=1`，「这个位置写不进去，配置文件与环境变量都未被修改」），不再「打一行警告后拿探测配置继续」；只有默认路径才保留「警告 + 内存默认继续」。实测 `list` / `use`（含加 `--dry-run`）均 exit=1 / 87–162ms，目标文件未创建，用户侧 `JAVA_HOME` 与 User `PATH` 逐字节未变 |
| 配置内容坏了 + 该位置不可写（显式 `--config`） | 备份失败或默认配置写不回时抛 `CannotRecover`（exit=1，「配置内容无法解析，且该位置不可写…原配置文件未被修改」）；默认路径维持「内存默认继续」。实测两条分支（`icacls /deny W` 造备份失败、`.tmp` 预占只读造写回失败）均 exit=1，未再打印「已使用默认配置继续运行」 |
| 默认路径不可写（`%LOCALAPPDATA%` 只读）+ 不带 `--config` | 只读命令可用：`list` exit=0 + 一行警告 + 开头注明「这是临时兜底的默认配置」；**真实写入被拒**：`use` / `all` / `path --apply` exit=1（「不能用它执行 … 的写入，未做任何修改」），`--dry-run` 仍 exit=0 只预览。实测 `JAVA_HOME` 前后未变 |
| 显式 `--config` 指向本地**尚不存在的目录** | 盘根可达 + 目录不存在 = 合法首次创建：`Save` 建目录并写默认配置，exit 0。实测 `--config %TEMP%\es_f\newdir\cfg.json` exit=0 / 96ms、目录与配置都生成（不再误报「检查网络盘是否已连接」） |
| 显式 `--config` 指到「其实是文件」的目录（`C:\Windows\notepad.exe\cfg.json`） | 目录存在性判定落到「这是个文件」→ `Unreachable`，exit 1，不写任何东西 |
| `--config` 为空串 / 直接给一个目录 | 解析阶段拒绝，exit 1，不进入任何读写流程；当前目录不留 `.tmp`（实测 `tmp left=0`） |
| 服务运行中配置目录被改名/删除 | 只读接口与保存接口都 503 且**不重建目录**：`/api/state` 16/2/4ms、`/api/config` 11ms，`Test-Path` 仍为 false；目录改回后 `/api/state`、`/api/config` 立即 200 |
| 默认路径的目录不存在（全新机器 / 新用户 / 删过配置目录） | 按首次运行：`Save` 创建 `%LOCALAPPDATA%\EnvSwitch` 并生成默认配置，exit 0。实测把该目录整体改名后 `list` 恢复出目录 + 1 122 字节默认配置（98ms） |
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
12. **配置「看不到」的四种判定靠盘根可达性 + 目录存在性**（`ClassifyMissing` → `ClassifyDirectory`）：默认路径与裸文件名一律算「还没建」（前者目录由 `Save` 创建，全新机器首启必须能过），其它路径先探盘根（不通 = `Unreachable` / 超时 = `Timeout`），根通了再看目录：不存在 = 「还没建」（本地盘的新目录照样合法首建）、位置是文件 = `Unreachable`、路径本身是目录 = `PathIsDirectory`。因此极端的默认路径 ACL 场景仍是「保存失败 + 内存默认」，不拦启动。
13. **有的慢盘会被判成「超时」**：1.5 秒上限对休眠 NAS（5–10 秒才响应）不够，这类盘上首次访问会得到 `Timeout` 文案并 exit 1。文案已改成不承诺结果（「可能是路径不存在、网络盘断开，也可能只是慢（稍后重试可确认）」）——实测同一黑洞 IP 连打三次都是 1.58 秒失败，「重试就能过」这种话对「路径打错」不成立。没有做「超时后自动 5 秒重试」（那会让每次首访都多等 5 秒，宁可把文案说清、由用户重试）。
14. **批量部分写入的删除记录无法在本机实证**：`CommitEntry(Name, Deleted)` 的删除分支只在「另一侧本来就有同名变量」并且「这一侧写到一半失败」时才会出现；本机非管理员写不了 `HKLM`，碰不到这个组合，只能靠代码路径与编译保证。
15. **路径探测本身也受网络影响**：`ClassifyMissing` 的 1.5 秒上限只保证「不会等 SMB 的主导超时」，探测线程超时后仍会在后台跑到系统返回（同 §17-11）。不可达结论缓存 2 秒（`UnreachableStamp`），同一个坏路径的连打请求不再逐次付 1.5 秒；本地盘改名这类场景不经过缓存，每次都是几毫秒的 `Directory.Exists`。默认路径不做这条探测（否则首启会被误判不可达），所以 `%LOCALAPPDATA%` 被重定向到离线漫游目录时，读配置仍可能慢，但默认路径这条路径不会被拦成 503。
16. **「首次运行」与「运行中丢失」只能靠调用方声明区分**：同一个「目录不存在」在 CLI 首启时是合法的（要建），在常驻服务请求里是异常（不能建）。所以 `Load` / `Save` 各有一个开关（`allowFirstRun` / `allowCreateDirectory`），CLI 命令行保持 true，网页接口一律 false。若有人新增调用点却传了默认值，就会退回旧行为——这是设计上的取舍，不是遗漏（第七轮已把 `EnvService` 的两处写点也补成 false）。
17. **「配置落盘失败」也是两种策略**：默认路径失败只警告（内存默认接着跑），显式 `--config` 失败直接 `exit=1`。区别在于前者「用户没指定位置，我们挑的地方坏了」，后者「用户点名的地方不可写」——后者继续执行会拿非用户配置去写注册表。**但默认路径的兜底也不等于可以写**：这份内存默认会被 `LastLoadUsedFallback` 标记，`use` / `all` / `path --apply` 的真实写入一律拒绝（`list` 会注明），只有 `--dry-run` 与只读命令能用。因此「显式路径 + 只读目录」是完全不可用，「默认路径 + 只读目录」是只读可用、写入拒绝。

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
| D3 | 低 | ◐ 已改（编译 + 回归） | 批量路径 `ApplyBuffer → CommitScope` 把「唯一」模式删掉另一侧同名变量也记成「已写入 X」，与单发分支的口径不一致 | `CommitReport` 的 `Written` 改为 `Entries: List<CommitEntry(Name, Deleted)>`，`CommitScope` 按「新值为 null = 删除」记 `Deleted`；批量失败文案改成「该侧已写入 JAVA_HOME、删除 PHP_HOME，未回滚」，`Broadcast` 判据改成 `Entries.Count > 0`。编译 0 警告；`all --dry-run` 与其余回归不受影响。**删除分支**需两侧有同名变量且一侧写失败，本机非管理员写不了 `HKLM`，未实测（同 C9 限制，记入 §17-14） |
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

**保留 / 已知代价**：默认路径不做目录探测（否则首启会被误判不可达），`%LOCALAPPDATA%` 被重定向到离线漫游目录时读配置仍可能慢，但不会被拦成 503；探测线程超时后在后台继续跑到系统返回（同 §17-11、§17-15）。

---

### 附录 A.6 第六轮审计（`audit/audit06.md` 的 F1–F7）

| 编号 | 级别 | 复核结果 | 原因 | 修复说明 |
| --- | --- | --- | --- | --- |
| F1 | 中 | ✔ 已实测 | `config` 命令仍是「先 `File.Exists` 再 `ClassifyMissing`」，顺序与 `Load` 相反：黑洞 UNC 上 `File.Exists` 先吃满 20 秒 SMB 超时，实测 `config` 首访 21 101ms、`list` 86ms | `config` 改成与 `Load` 同序：先 `ClassifyMissing`，`FirstRun` 才 `File.Exists`，读时竞态再判一次。**修复中又踩到同一坑**：给 `--config` 加「是不是目录」校验时用了裸 `Directory.Exists`，首测仍是 21 066ms——把该判定并入 `ClassifyMissing`（`PathIsDirectory`）后实测 **1 552ms / exit 1**，二次调用 49ms（SMB 负缓存） |
| F2 | 中 | ✔ 已实测（端到端） | `POST /api/config` 不 `Load` 直接 `Save`，`Save` 的 `Directory.CreateDirectory` 把被改名搬走的目录原地**重建**并写一份新配置，还返回 200「配置已保存」 | `Save` 加 `allowCreateDirectory`（网页传 `false` → 目录不存在直接抛「配置文件所在目录已不存在（可能被改名或删除）」）；`Load` 加 `allowFirstRun`（网页读接口传 `false`）。**顺带发现只读接口更严重**：`/api/state` 在目录消失时走首次运行分支、把目录重建出来还返回 200，一并堵上。实测（临时配置副本 + 8098）：改名后 `/api/config` **503 / 11ms**、`/api/state` **503 / 16・2・4ms**，`Test-Path` 始终 false，未产生任何新文件；改回后两接口立即 200 |
| F3 | 中 | ✔ 已实测 | `ClassifyMissing` 把「目录不存在」与「目录不可达」判成一类：显式 `--config %TEMP%\新目录\cfg.json`（合法首建）被拒，exit=1 并提示「检查网络盘是否已连接」 | 拆成 `ClassifyDirectory` 两段判：先对**盘根**做 1.5 秒探测（`Path.GetPathRoot`，UNC 根与本地盘根都适用），根通了再看目录——不存在 = 首次运行、位置是文件 = 不可达、路径本身是目录 = `PathIsDirectory`。实测 `--config %TEMP%\es_f\newdir\cfg.json` **exit=0 / 96ms**，目录与 1 122 字节配置都生成；`C:\Windows\notepad.exe\cfg.json` 与 `Z:\cfg.json` 均 exit=1 |
| F4 | 低 | ✔ 已实测 | 503 响应体只说「被其它程序占用或缺少读取权限」，没提「目录不可达」 | 中间件 503 文案改为「可能是该路径当前不可访问——网络盘断开、U 盘拔掉、缺少权限——或被其它程序占用，稍后重试即可」。实测 `/api/state` 503 body 含该整句 |
| F5 | 低 | ◐ 已改（代码级） | `DirectoryReachable` 每次重探，同一坏路径连打 N 次 = N × 1.5 秒 | 加 2 秒不可达缓存（`UnreachableStamp(Dir, Until, TimedOut)`）：命中时直接返回同一结论并回填「是否超时」，探测成功后清缓存。**本机无法端到端复现**：缓存只对「盘根不可达」生效，而本地盘根永远可达、改名场景走的是几毫秒的目录判断；`serve --config <不可达 UNC>` 在 `Load` 就已 exit=1 起不来，构造不出「服务在跑且盘根不可达」的场景。**第七轮更正（H4）**：原写「路径一恢复就能读到」不成立——缓存命中期间不再真探，恢复后最多滞后 2 秒；README / FLOWS 口径已改，缓存只在同进程内有效（CLI 每次新进程不共享） |
| F6 | 低 | ✔ 已实测（文案） | 1.5 秒上限会把「慢但可用」的盘（休眠 NAS 5–10 秒）判成不可达，且无从分辨 | 取报告的第二方案：`MissingConfigKind` 加 `Timeout` 独立态，文案「配置文件所在目录未在 1.5 秒内响应（可能只是慢，重试一次通常就能过）」，与「目录不存在或无法访问」分开。未做「超时后自动 5 秒重试」（会让每次首访都多等 5 秒），代价记入 §17-13。实测黑洞 UNC 首访走的就是这句。**第七轮更正（H3）**：「重试一次通常就能过」对「路径打错」不成立（同一黑洞 IP 连打三次均 1.58 秒失败），文案已改为不承诺结果 |
| F7 | 低 | ✔ 已实测 | `--config ""` 先写 `".tmp"` 再 `Move` 失败，当前目录留下垃圾、exit=0；`--config <已存在目录>` exit=0 + 一行「Access is denied」 | 空串在解析阶段直接拒绝（exit 1）；「是目录」由 `ClassifyMissing` 返回 `PathIsDirectory` → `config` / `list` / `serve` 统一 exit 1；`Save` 的 `Move` 失败先 `File.Delete(tmp)` 再抛。实测 `--config ""` exit=1、`--config %TEMP%\es_f` exit=1（`[失败] … 这个路径是一个目录，请给出配置文件名`），当前目录 `*.tmp` 计数 **0** |

**顺带**：`UnreachableMessage(file, kind)` 抽成 `internal`，`config` 命令与 `Load` 打印同一句话；`config` 命令顺带支持 `Timeout` / `PathIsDirectory` 两种新状态。

**保留 / 已知代价**：F5 的缓存只在同一进程内有效，CLI 每次新进程不共享；本地盘改名场景不经过缓存（每次 `Directory.Exists` 几毫秒）；F6 未加自动重试，文案已点明「可能只是慢」；`ClassifyMissing` 末尾多了一次 `Directory.Exists(file)`（本地盘 ~0.1ms，UNC 可达时走网络但已在 43ms 量级）；「首次运行 vs 运行中丢失」只能靠调用方传开关区分，新调用点若用默认值就会退回旧行为（§17-16）。

---



---

### 附录 A.7 第七轮审计（`audit/audit07.md` 的 H1–H5）

| 编号 | 级别 | 复核结果 | 原因 | 修复说明 |
| --- | --- | --- | --- | --- |
| H1 | 中 | ✔ 已实测（含非 dry-run） | 显式 `--config` 指向写不进去的位置时，首启分支只打一行警告就拿探测出的默认配置继续，`exit=0`；`use` 非 dry-run 会真写 `JAVA_HOME` / PATH | 首启分支抽出 `FirstRunFallback`：显式路径 `throw CannotWrite`，默认路径才「警告 + 内存默认继续」；`--config` 空白校验扩成 `IsNullOrWhiteSpace`。实测 `C:\Windows\System32\envsw7.json` 上 `list` / `use Java 17`（非 dry）均 exit=1，注册表逐字节未变 |
| H2 | 低 | ✔ 已实测 | CLI 兜底句只按「读不到」写：`--config <目录>` 先说是目录，又让人查网络盘 | `ConfigUnreadableException.Hint`：`PathIsDirectory` 传空串不打印第二行，`FirstRunNotAllowed` / `CannotWrite` / `Unreachable` 各给一句。实测目录场景只输出一行 |
| H3 | 低 | ✔ 已实测 | `Timeout` 文案承诺「重试一次通常就能过」，对打错路径不成立 | 改为「可能是路径不存在、网络盘断开，也可能只是慢（稍后重试可确认）」。实测黑洞 UNC 1 549ms 打印新句 |
| H4 | 低 | ✔ 已改口径 | 2 秒缓存让恢复滞后，文档却写「一恢复就能读到」 | 文档统一为「最多滞后缓存到期（≤2 秒）」；未采用「命中缓存再真探」方案（会让真不可达场景持续堆探测线程）。A.6 的 F5 行已加更正 |
| H5 | 低 | ◐ 代码级 + 接口回归 | `Apply` / `ApplyAll` 两处 `Save` 仍用 `allowCreateDirectory: true`，目录在 `Load` 与 `Save` 之间被挪走会重建 | 两处改 `false`。毫秒级竞态窗口未构造实测 |

**保留 / 已知代价**：缓存滞后 ≤2 秒；H5 竞态只保证「不再主动建目录」；可达共享上打错子目录名仍会建出目录（F3 的必然代价）。

---

### 附录 A.8 第八轮审计（`audit/audit08.md` 的 G1–G5 + 自查 S1–S3）

| 编号 | 级别 | 复核结果 | 原因 | 修复说明 |
| --- | --- | --- | --- | --- |
| G1 | 中 | ✔ 已实测 | `explicitPath: path is not null` 恒真（`configPath` 早被 `DefaultPath` 兜上），「默认路径只警告继续」是死代码；`%LOCALAPPDATA%` 只读时 CLI 一律 exit=1，与 README / FLOWS 矛盾，提示还让用户去改没敲过的 `--config` | 新增 `ConfigStore.IsDefaultPath(file)` 作为唯一判定，`ClassifyMissing` 与 `Load` 两处分流共用（不再用编译期常量）。实测改名真实配置目录 + `icacls /deny W`：`list` exit=0 + 一行警告 + 内存默认配置照常列出；恢复后 SHA 不变 |
| G2 | 中 | ✔ 已实测（两条分支） | H1 只堵了「配置不存在」，`RecoverFromBroken` 是另一条出口：内容损坏 + 位置不可写时仍 exit=0，`use` 能真写注册表；写回失败的 `catch {}` 静默 | `RecoverFromBroken(file, explicitPath)`：显式路径在「备份失败」或「写回失败」时抛 `CannotRecover`（exit 1），默认路径才降级并置 `LastLoadUsedFallback`；空 catch 改打印「备份成功但默认配置未能写回…（下次启动仍会按损坏处理并再备份一次）」。实测 A（deny W 造备份失败）exit=1 + 「原配置文件未被修改」；实测 B（预占只读 `.tmp` 造写回失败）exit=1 + 新警告 |
| G3 | 低 | ✔ 已实测 | H2 只接了 `LoadConfigOrExit`，`RunWeb` 的 catch 仍是固定旧句：`serve --config <目录>` 两行自相矛盾，只读位置还说「只是暂时读不到」 | `RunWeb` catch 与另两处一致（`Hint` 非空才打印）。实测 `serve --config C:\Windows\System32` 只 1 行；只读位置 2 行且第二行是「写不进去…未被修改」 |
| G4 | 低 | ✔ 已实测 | `config` 对不可写位置报「尚未生成，运行一次后自动生成」并 exit=0，同位置 `list` 是 exit=1；该承诺永不成立 | 两处文案改「尚未生成；第一次需要写入时才会创建，写不进去会直接报错」。实测 exit=0 + 新文案（只读命令仍不探测可写性） |
| G5 | 低 | ✔ 已实测可见 | `CannotWrite` 说「配置文件与被修改的环境都没有回滚」（什么都没改），且对没给 `--config` 的用户建议改 `--config` | 改「配置写入被拒绝，配置文件和环境变量都没有改动」一类表述，去掉「回滚」与对默认路径用户的 `--config` 建议；G1 修好后默认路径不再走这句 |
| S1 | 中（自查） | ✔ 已实测 | G1 只解决「不该硬失败」，但**兜底配置仍能被写入命令用**：`%LOCALAPPDATA%` 只读时 `use` / `all` / `path --apply` 会拿从未落盘的配置写 `JAVA_HOME` / PATH——H1 要防的同一件事，入口换成默认路径 | `LastLoadUsedFallback` 标记（`Load` 开头复位，首启/损坏兜底置位）+ `RefuseFallbackWrites`：三条写入命令真实写入 exit=1、`--dry-run` 与只读命令放行，`list` 开头注明「临时兜底的默认配置」。实测（改名 + deny W）：`list` 0、`use` 非 dry 1、`use --dry-run` 0、`all` 1、`path --apply` 1，`JAVA_HOME` 未变 |
| S2 | 低（自查） | ✔ 已改 | `config` 的 `catch (ConfigUnreadableException)` 没接 `Hint`（与 G3 同类漏点，CLI 侧） | 补 `if (ex.Hint.Length > 0) Console.Error.WriteLine(ex.Hint)`，三处打印点完全一致 |
| S3 | 中（自查） | ✔ 已实测 | S1 只拦了 CLI 写入，但 `serve` 在兜底状态仍能启动：每个请求都因「不许首启」返回 503，页面变空壳（半死状态没人说清） | `RunWeb` 在 `Load` 后判 `LastLoadUsedFallback` → `exit=1` + 「配置位置写不进去，服务无法启动：…网页与 API 都需要一份能保存的配置…只看不改请用 list / config」。实测：默认目录改名 + deny W → `serve` exit=1 三行说明，`list` 仍 exit=0；恢复后 SHA `48574EBE…9C802D` |

**顺带**：`path --apply` 的兜底守卫移到 `BuildPathView` 之前（注定被拒时不再打印一份不会落盘的「处理后」），且整个命令只有这一处守卫（原先前一版在清理检查前重复加了一道，形成死代码，自查时删掉）。

**保留 / 已知代价**：`LastLoadUsedFallback` 是进程内静态标记（CLI 单进程无并发问题，Web 侧 `Load` 一律 `allowFirstRun: false`，走不到兜底分支，也没有代码读它）；中间件 503 的兜底尾句对「配置损坏且写不回去」不够贴合，第一行 `ex.Message` 已给准确原因，未再分支以免动 F4 已验收文案；`config` 是只读命令，仍不探测可写性；受限账户场景用「改名 + `icacls /deny`」近似构造，未在真正受限账户里跑。

---

### 附录 A.9 第九轮审计（`audit/audit09.md` 的 I1–I2）

| 编号 | 级别 | 复核结果 | 原因 | 修复说明 |
| --- | --- | --- | --- | --- |
| I1 | 中 | ✔ 已实测（端到端） | S1 的 `RefuseFallbackWrites` 只拦 CLI 三条写入命令；Web 侧 `Load(allowFirstRun: false)` 只堵「首次运行」分支，`RecoverFromBroken`（内容损坏）这条兜底出口没接这个开关 → 服务运行中配置变坏且备份/写回失败时，`/api/state` 200 + 探测配置、`/api/apply` 非 dry 会按探测配置写 `JAVA_HOME`/PATH，而配置一字节未落盘；每请求还重打三行警告 | `Load` 的损坏分支：`!allowFirstRun` 时**不备份、不重建、不兜底**，直接抛 `ConfigUnreadableException`（复用 503 通道），磁盘完全不动，所以「未做任何修改」准确。中间件把 503 分成两句（第十轮 J3 更正：判定类型是 `InvalidDataException`，不是 `JsonException`——后者只会出现在更内层）：是 `InvalidDataException` → 「配置内容无法解析…请手工修好这份 JSON」，否则是「暂时读不到…稍后重试」。实测（`serve --config %TEMP%\es9i2\cfg.json`，运行中把配置改成半截 JSON）：`/api/state` **503**、`/api/apply`(dry) **503**、`/api/apply/all`(dry) **503**；目录内只有 `cfg.json`（39 字节，未变）与日志，无 `.bad-*`、无 `.tmp`；`JAVA_HOME` 仍为空 |
| I2 | 低 | ✔ 已实测 | 兜底注记写「上面这份」，而它是整段输出的第三行、真正的列表在下面，方向指反 | 改成「下面列出的这份」。实测（默认目录改名 + `icacls /deny W`）：`配置文件：…` → `[警告] 默认配置未能写入…` → `（注意：下面列出的这份是临时兜底的默认配置…）` → 空行 → `Java [JAVA_HOME] …` |
| S5 | 低（自查） | ✔ 已实测 | I1 落地后同一份故障会被连续轮询反复记录（审计验收 21 的诉求），真正的首因被刷掉 | 中间件加 `lastConfigError`（用「文件 + 异常消息」拼成的键）：同一份故障只 `LogWarning` 一次；任何请求恢复正常（状态码 < 400）就清空，下次复发再记一条。实测：同一种坏法连打 4 次 503 只留 **1 行 `warn:`**；中间插入一次 200 后再换成另一种坏法，连打 2 次共记 **2 行**（键不同才记新的） |

**顺带解释两处审计问到的点**：

- 审计建议「备份并重建成功那条分支给一句『已备份并重建，请重试』」——本实现选择了更保守的路线：服务运行中**根本不备份也不重建**，所以不需要这句，也不会因为每请求重建而每秒落一个 `.bad-<秒级时间戳>` 文件（审计没提，但那是这条路径上的真实风险）。
- `serve` **启动**那一刻仍然是 `allowFirstRun: true`：坏配置会被备份成 `.bad` 并重建一份默认配置后正常启动（审计 §5 已判为「等价于全新安装」的已知行为）。此时磁盘上就是默认配置，页面显示的也一致，不构成「拿探测配置写注册表」。

**保留 / 已知代价**：`lastConfigError` 只按「文件 + 异常消息」去重，同一份文件内容有变但消息尾部的 `BytePositionInLine` 不同时会再记一条（可接受，说明内容确实变了）；`serve` 启动时坏配置仍会被重建（`.bad` 保留）；审计 §5 剔除的三项（`config` 对坏文件原样输出、备份+写回成功的恢复分支、只读 `.tmp` 残留）维持不改。

---

### 附录 A.10 第十轮审计（`audit/audit10.md` 的 J1–J3）+ 结构性收口

| 编号 | 级别 | 复核结果 | 原因 | 修复说明 |
| --- | --- | --- | --- | --- |
| J1 | 中 | ✔ 已实测（三入口） | `JsonSerializer.Deserialize<AppConfig>(...) ?? DetectDefaultConfig()`：字面量 `null` 是合法 JSON，解析**不抛**，于是 `??` 把探测出来的 Java 顶上——服务显示探测配置、`/api/apply` 非 dry 按它写注册表，磁盘上仍然是 `null`、连 `.bad` 都没有。这是「拿探测配置当用户配置」的最后一处出口 | 把「内容是否合法」收敛成**一个判定点** `ParseStrict`：语法错、类型不符、字面量 `null`、`Migrate` 抛的错，统一抛 `InvalidDataException`；`Load` 只处理这一种异常（CLI 备份重建 / 服务 503）。顺带删掉 `?? DetectDefaultConfig()`（它正是「解析成功但没对象 = 没配过」这个错误等式的来源）。实测：CLI `list`/`use --dry-run` 对 `null` → 备份 `.bad` + 重建（exit 0）；`serve` 运行中改成 `null` → `/api/state` / `/api/apply` 均 **503**，文件字节不变、无 `.bad` / `.tmp` |
| J2 | 低 | ✔ 已实测 | Web 中间件自造 503 尾句，从不看 `ex.Hint`：文件被删时正文说「已不在原位」，尾句却让人「稍后重试即可」——同一份异常在 CLI 有三处打 Hint，Web 是唯一自造文案的面 | 中间件改成只用异常自带的处置句：`message = ex.Message +（ex.Hint）`（`Hint` 为空时只给 Message，正好覆盖 `PathIsDirectory` 那种「第一行已说清」的情形）。现在 CLI 与 Web 共用同一份文案，不再各写各的。实测删文件后 503 文案含「改回原名」、不含「稍后重试」 |
| J3 | 低 | ✔ 已改 | `FLOWS.md` 把分流条件写成 `ex.InnerException is JsonException`，代码是 `InvalidDataException`；照文档改代码会让分支永不成立 | 两处文档（FLOWS A.9、audit09 反馈区）均已更正并注明「第十轮 J3 更正」 |

**结构性收口（本轮真正做的事，不只是三条补丁）**：

1. **单一判定点**：`ConfigStore.ParseStrict` 是「这份内容能不能用」的唯一实现；`Load` 只编排「读 → 判位置 → 首启 / 解析 / 兜底」，不再有第二处 `Deserialize` 调用。
2. **单一 Web 入口**：`Program.RunWeb` 里的 `LoadForRequest()`（固定 `allowFirstRun: false`）取代原先散在四个接口的 `Load(configPath, allowFirstRun: false)`——第九轮 I1 就是「新增出口忘了带这个开关」漏的，现在无处可漏。
3. **单一文案**：503 用 `ex.Message + ex.Hint`；CLI 三处也打 `Hint`，已无第三份文案。
4. **可执行矩阵**：`tests/acceptance.sh` 入库，把「路径类 × 文件状态 × 入口」枚举成 **38 条断言**（含 `null`、空文件、`[]`、目录改名、删文件、日志去重、文案口径）。`dotnet build -c Release && bash tests/acceptance.sh` → **PASS 38 / FAIL 0 / SKIP 0**。以后改这块先跑它，而不是等下一轮审计抽样。

**保留 / 已知代价**：`ParseStrict` 对 `Migrate` 的异常只保留 `Message`（原始类型在 `InnerException` 里，服务日志会打整条堆栈）；`config` 命令仍原样输出（含 `null`），这是它「把文件内容打给你看」的定位，已加断言 A12 锁住；矩阵覆盖不到的三类（默认路径不可写、文件被独占锁定、注册表写入内容）见 `audit/acceptance.md` §5，仍需 VM / 手工。
