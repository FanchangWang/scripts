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

1. 文件不存在 → `DetectDefaultConfig()` 探测本机 Java → 立即保存 → 返回。
2. 文件存在 → 反序列化 → `Migrate` → 返回。
3. 反序列化失败 → **先把坏文件复制成 `config.json.bad-yyyyMMdd-HHmmss`** → 用默认配置 → 把默认配置写回原路径（保证下次能启动）。

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
4. 追加 `toAdd`；若开启「切换时自动排序去重」，再整体排序 + 去重。
5. **唯一模式**：另一侧 PATH 同样去掉 `stale + toAdd`，只有结果与当前不同才写。
6. 结果写入 `ApplyResult.PathAfter`，CLI 会逐条打印，网页横幅里给条数统计。

> 因为读的是注册表原样值，PATH 里其它条目的 `%SystemRoot%` 之类写法**不会被顺手改掉**。

### 4.4 真正写入（非预览时）

1. 写环境变量：`EnvStore.Set(EnvVar, Home, envTarget.Scope)`；唯一模式且另一侧**存在**同名变量时删除它。
2. 写 PATH：目标侧写 `pathAfter`；唯一模式且另一侧结果有变化时写另一侧。
3. 成功后把 `tc.Active = version.Name` 记入配置并保存（原子写）。
4. 任一步写注册表被拒（`UnauthorizedAccessException`）→ 返回「需要以管理员身份运行」，此时**先写的部分不会回滚**（提示里说明了）。
5. 环境变量与 PATH 都写成功、仅配置文件保存失败 → 返回「已写入，但保存配置失败」，明确区分于「整次失败」。

### 4.5 返回消息

`已应用 / 预览：` +「工具链 → 版本」+「变量=值（哪个范围）」+「PATH 写入哪个范围（N 条）」，并附加两类提示：

- 「同版本另外 N 条与它们指向同一目录，已按顺序合并」
- 「N 条 %变量% 已展开成具体路径（该 PATH 是普通字符串，系统不会自动展开）」

---

## 5. 立即应用全部

- 前端：工具链配置页右上角按钮；先 `confirm` 列出「将应用哪些 → 哪些版本、跳过哪些（原因）」，再保存配置，再 `POST /api/apply/all`。
- 后端 `ApplyAll`：遍历所有工具链，跳过「名称为空 / 不生效 / 没选激活版本」，其余逐个调用 `Apply`（同一个 dryRun）。
- 部分失败不影响其它工具链；接口返回 `items[]` 逐条结果，网页顶部常驻面板用 ✅/❌ 列出，`ok` 仅在全部成功时为 `true`（但 HTTP 仍是 200，前端靠 `postJson` 拿全量结果）。
- CLI `all` 等价此流程，退出码：全成功 0，有失败 1。

---

## 6. PATH 工具（查看 + 勾选写入）

### 6.1 视图构建（`BuildPathView`）

1. 分别读系统 / 用户 PATH 的**原样值**，切成条目。
2. 每条做三件事：
   - **归属识别**：按配置的 `EnvVar`、`PathTemplate`、该版本全部路径（显式 + 模板 + Home + Home\bin）比对，标出工具链与版本。
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
   - 「仅预览」勾着 → 只算一遍条数不写盘；
   - 未勾 → `confirm` 列出「系统 N 条 / 用户 M 条」并强调**会整体覆盖该侧 PATH**。
3. 后端按提交的数组整体写该侧（`string.Join(';')`），写前同样把无效 `%变量%` 换具体路径。
4. **未提交某一侧（`null`）表示该侧不动**；两侧都没提交会被 400 拒绝 —— 防止误清空整条 PATH。
5. 成功（非预览）后前端 `loadState + loadPathView` 刷新，看到的就是注册表里的新状态。
6. CLI 的 `path` 走同一套：默认打印两侧「当前 → 处理后」；`--apply` 时把两侧**一起**提交给 `ApplyPathSelection`（等价于网页里两侧都勾选后点「应用写入」），没有单侧写入入口。

---

## 7. 环境变量页

1. 并发读两侧 `/api/env?scope=User|Machine`，每侧一个可折叠分组，标题带条数。
2. 顶部筛选框按变量名或值模糊过滤（不区分大小写），实时重绘。
3. 值是一个目录/文件路径、但目标已不存在 → 变量名后标「已失效」；判不了（UNC/超时）不标。
4. 行内编辑：
   - 值超过 60 字符或含 `;` 用多行输入框，否则单行；
   - 点「保存」先 `confirm` 显示「原值 → 新值」（超 80 字符截断）；
   - 成功只更新这一行（不整表重读），页面滚动位置不跳。
5. 删除：`confirm` 提示不可恢复（系统侧额外提示可能影响其它程序）→ 成功后该行就地置灰标「已删除」，同样不重读整表。
6. **值显示的是注册表原样内容**：`REG_EXPAND_SZ` 的 `%USERPROFILE%\go` 就显示 `%USERPROFILE%\go`，保存时写回也保持这个写法（不会固化成 `C:\Users\guyue\go`）。
7. 切到别的页面再回来，未保存的编辑态会被放弃（`showPage` 重置 `envEdit`）。

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
| 切换版本时自动排序去重 PATH | `NormalizePathOnApply` | 每次 `Apply` / 立即应用全部时 |
| 启动服务时自动打开浏览器 | `OpenBrowserOnStart` | 下次启动 |

保存失败同样在页面顶部列出逐条原因（与工具链页共用同一套错误面板）。

---

## 10. 写入通道（为什么不用 `Environment.SetEnvironmentVariable`）

- 实测本机单次写入 **22.6 秒**：它每次都向所有顶层窗口广播 `WM_SETTINGCHANGE` 并逐个等响应，窗口多或有消息循环挂起就卡住。
- 现方案 `EnvStore.Set`：
  1. 打开 `HKCU\Environment` 或 `HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment`；
  2. `DeleteValue`（值为 `null`）或 `SetValue`（**沿用原有类型**，不会把 `REG_EXPAND_SZ` 降级成 `REG_SZ`）；
  3. 用 `SendMessageTimeout(... SMTO_ABORTIFHUNG, 200ms)` 广播一次，超时就放弃通知（写入已落盘）。
- 实测：单变量约 0.7 秒，批量从 41 秒降到约 1.3 秒。
- 权限：写 `HKLM` 被拒时统一转成 `UnauthorizedAccessException`，界面/CLI 提示「需要以管理员身份运行」。

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
| `path [--no-sort] [--no-dedup] [--apply]` | `BuildPathView`（两侧一起） | 打印系统/用户各自的「当前 N → 处理后 M」与逐条结果；默认不写 |
| `config` | 打印配置路径 + 原文 | 未生成时提示先跑一次 |

`path` 的额外规则：

- 带 `--apply` 时，把「处理后」两侧列表原样交给 `ApplyPathSelection`，**系统与用户同一轮一起写**；任一侧被拒（多半是系统侧需要管理员）就整轮失败，避免出现「只改了一半」的错位状态。
- 出现 `--scope` → 直接报错退出（提示「两侧总是一起处理」）。
- 出现白名单外的任何参数（含 `list` / `get` / `set` / `delete` 这类裸参数）→ 直接报错退出。
- CLI 与网页 PATH 页用的是同一套计算：失效剔除、系统重复移除、同目录合并、`%变量%` 展开一致。

`all` 的额外规则：

- 它是唯一「默认就真实写入」的命令，所以参数同样从严：只认 `--dry-run`，`--scope` 或其它参数（例如拼错的 `--aply`）一律报错退出、不写任何东西。

---

## 14. HTTP 接口清单

| 方法与路径 | 请求体 | 行为 | 失败响应 |
| --- | --- | --- | --- |
| `GET /api/state` | — | 每次重新读配置 + 各工具链当前变量值 + 两侧 PATH | — |
| `GET /api/env?scope=` | — | 该侧全部变量（原样值 + 失效标记） | — |
| `POST /api/env/set` | `{scope,name,value}` | 写单个变量（空串=置空） | 200 + `ok:false`（权限/名字非法） |
| `POST /api/env/delete` | `{scope,name}` | 删除单个变量 | 同上 |
| `POST /api/config` | 完整 `AppConfig` | 校验 → 迁移 → 原子保存 | 400 + `errors[]`；写盘异常 500 |
| `POST /api/apply` | `{toolchain,version,dryRun}` | 切一个工具链的一个版本 | 200 + `ok:false` + 原因 |
| `POST /api/apply/all` | `{dryRun}` | 批量应用 | 200 + `items[]` |
| `POST /api/path/view` | `{sort,dedup}` | 生成系统/用户「当前 + 处理后」视图 | — |
| `POST /api/path/apply` | `{system?,user?,dryRun}` | 整体覆盖指定侧；`null` 侧不动 | 400（两侧都没给） |

---

## 15. 保存时的校验规则（`ValidateConfig`）

| 规则 | 触发条件 | 错误字段 |
| --- | --- | --- |
| 工具链名称不能为空 | `/api/config` 入口检查 | —（直接 400 文案） |
| 版本 Home 不存在 | Home 看起来是路径（`X:\…` 或 `\\…`）但目录/文件都没有 | `home` |
| PATH 条目不存在 | 按真实目录分组，**整组都不存在**才逐条报错（组内任一条存在即放行） | `path` + 下标 |
| PATH 模板展开后不存在 | **仅当该版本没有任何显式条目**时校验模板展开的每一段 | `template` |
| 版本重名 | 同一工具链里非空版本名重复 | `name` |

放行原则：展开后仍残留 `%`（判不了）、网络路径、探测超时（1.5s）一律**放行**，宁可不拦也不误伤。

---

## 16. 权限与失败模型

| 场景 | 表现 |
| --- | --- |
| 非管理员写系统变量 / 系统 PATH | `UnauthorizedAccessException` → 「写入被拒绝，请以管理员身份运行」，不崩溃 |
| 工具链 `MachineOnly` 在非管理员下切换 | 整次失败（先算后写，写阶段被拒） |
| 批量应用部分失败 | 各条独立成功/失败，汇总里显示失败数 |
| 配置写盘失败 | 接口 500 或 CLI 失败文案；Apply 场景区分「已写入但没记下激活版本」 |
| 配置 JSON 损坏 | 备份成 `.bad-<时间戳>` 后用默认配置继续 |

---

## 17. 已知限制（审核时请留意是否符合预期）

1. 校验只判断「存在」，不判断「是不是目录」；先填路径后装软件会被拦（可接受吗？目前没有跳过开关）。
2. 「PATH 工具」的「应用写入」是**整体覆盖两侧 PATH**，勾选结果就是最终内容；未勾选的条目会被删掉。CLI 的 `path --apply` 直接把「处理后」两侧整体写回。
3. 用户侧与系统侧的「重复」判定用规范化字符串，`%USERPROFILE%\…` 与已展开的同一路径跨侧不算重复。
4. 条目归属识别只认配置里出现过的路径，配置外的条目没有工具链徽标。
5. 非管理员会话无法在本机验证系统侧真实写入，只能验证 dry-run 结果。
6. PATH 条目很多时保存较慢（每条存在性探测最多等 1.5 秒，实际通常毫秒级）。
7. 环境变量页对「值本身就是另一套 PATH 字符串」的变量不做拆条展示。

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
