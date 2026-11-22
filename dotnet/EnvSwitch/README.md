# EnvSwitch

Windows 多版本环境变量切换器 + PATH 整理工具。一个 exe 同时提供**本地网页 GUI**和 **CLI**，使用 .NET 10（LTS）。

## 功能

- 读取**用户**与**系统**环境变量：网页「环境变量」页把两者分组列出（点标题可折叠分组），支持筛选，并可**行内编辑值**（保存前二次确认，展示原值与新值）或**删除变量**（删除后就地置灰、标「已删除」并撤掉按钮，不重读整表，页面滚动位置不变）；变量值是目录/文件路径但目标已不存在时，变量名后标「已失效」。
- 管理多套工具链（Java / Node / Maven …），每套可配置：
  - 要生成的环境变量名（如 `JAVA_HOME`）与 PATH 条目模板（如 `%JAVA_HOME%\bin`）；两者**都可留空**，留空表示这套工具链不写环境变量 / 不写 PATH
  - 是否生效（关闭 = 相当于没配置过这套工具链）
  - **环境变量作用范围**与 **PATH 作用范围**，各四档：`用户`、`系统`、`用户唯一`、`系统唯一`（唯一 = 写本侧并把另一侧的残留清掉）
  - 多个版本；每个版本可配 Home（环境变量值）和**多条 PATH 条目**（版本没填显式条目时回退用模板展开 Home）
- 切换版本时自动：设置环境变量 → 清理该工具链所有旧版本的 PATH 条目 → 写入当前版本条目 → （可选）排序去重。
- **同一版本里指向同一目录的多种写法只写一条**：一个版本可以填多条 PATH，若其中几条其实指向同一个真实目录（典型是 `…\latest\jdk-21\bin` 这类 junction / 软链别名与 `…\jdk-21.0.13.0.2\bin` 这种真实目录），按配置顺序只保留**最靠前且目录存在**的那一条（第一条不存在就用后面第一条存在的），其余同目录写法会从 PATH 里一并删除，不再重复堆积。判定靠解析真实路径（穿透 junction，含路径中间的软链），不是比字符串。
- **保存配置前校验路径**：每个版本的 Home、版本里的每条 PATH 条目、以及「该版本没填条目时才用到」的 PATH 模板（先把 `%环境变量名%` 换成 Home、再展开 `%USERPROFILE%` 这类系统变量），只要看起来是路径却不存在，就整份拒绝保存，并返回逐条错误（网页上对应输入框描红）；判断按真实目录分组，同一组的写法只要有一条存在就放行（反正写入时只会用那一条）。切换版本写 PATH 前也会再挡一次旧配置里残留的不存在条目。
- 通过直接写注册表（`HKCU\Environment` / `HKLM\…\Session Manager\Environment`）并在写入后广播一次 `WM_SETTINGCHANGE`（最多等 200ms）落盘，新开的终端立即生效；不调 `Environment.SetEnvironmentVariable`，因为它每次写都要向所有顶层窗口广播并逐个等响应，本机实测单次可拖到 20 秒以上。
- CLI：`list` 看当前配置、`use` 切换某个版本（环境变量 + PATH 一起写）、`all` 一次性应用全部生效工具链、`env` 列/读/写/删环境变量、`path` 对 PATH 排序去重（默认只预览，`--apply` 才写入）；`--scope` 支持 `--scope Machine` 与 `--scope=Machine` 两种写法。
- 首次运行自动探测本机 Java 安装（读取 `release` 文件，不执行任何 exe）生成默认配置。

## 构建（单文件绿色 exe）

需要 .NET SDK 10 或更高（SDK 11 可编译 `net10.0` 目标）：

```powershell
cd dotnet/EnvSwitch
dotnet publish -c Release -o publish
```

产物为 `publish/EnvSwitch.exe`，自包含、单文件，拷到任意 Windows x64 机器即可运行，无需安装 .NET。

## 运行

```powershell
# 直接启动网页界面（默认行为），自动打开浏览器
.\EnvSwitch.exe

# 指定端口、不自动开浏览器
.\EnvSwitch.exe serve --port 5180 --no-browser

# 列出工具链 / 版本 / 当前值
.\EnvSwitch.exe list

# 切换版本（环境变量与 PATH 一起写；--dry-run 仅预览）
.\EnvSwitch.exe use Java 17 --dry-run
.\EnvSwitch.exe use Java 17

# 一次性应用所有「生效且有激活版本」的工具链（环境变量 + PATH 一起写，
# 等同网页的「立即应用全部」）
.\EnvSwitch.exe all --dry-run
.\EnvSwitch.exe all

# PATH 排序去重（默认仅预览）
.\EnvSwitch.exe path
.\EnvSwitch.exe path --scope Machine --apply
.\EnvSwitch.exe path --no-sort --dedup --apply

# 环境变量：列 / 读 / 写 / 删（--scope 省略时列表和读取会用户+系统都来一遍，
# 写入和删除省略则默认写用户侧）
.\EnvSwitch.exe env                          # 列出用户 + 系统的全部变量
.\EnvSwitch.exe env list --filter JAVA
.\EnvSwitch.exe env get JAVA_HOME
.\EnvSwitch.exe env set MY_FLAG 1 --scope Machine
.\EnvSwitch.exe env set MY_FLAG --scope User # 值省略 = 写成空串
.\EnvSwitch.exe env delete MY_FLAG --scope User

# 指定配置文件
.\EnvSwitch.exe --config D:\envswitch.json list
```

配置文件默认位于 `%APPDATA%\EnvSwitch\config.json`，可用 `--config` 覆盖。

## 配置格式

```json
{
  "toolchains": [
    {
      "name": "Java",
      "envVar": "JAVA_HOME",
      "pathTemplate": "%JAVA_HOME%\\bin",
      "enabled": true,
      "envScope": "User",
      "pathScope": "User",
      "active": "17",
      "versions": [
        {
          "name": "8",
          "home": "C:\\Program Files\\Java\\jre1.8.0_513",
          "paths": [
            "C:\\Program Files\\Java\\jre1.8.0_513\\bin",
            "C:\\Program Files (x86)\\Common Files\\Oracle\\Java\\java8path"
          ]
        },
        { "name": "11", "home": "C:\\Program Files\\Zulu\\zulu-11-jre" },
        { "name": "17", "home": "C:\\Program Files\\Java\\jdk-17" }
      ]
    }
  ],
  "settings": {
    "normalizePathOnApply": true,
    "pathScope": "User",
    "openBrowserOnStart": true,
    "port": 0
  }
}
```

## 权限说明

- 修改**用户**环境变量无需管理员权限；工具链的作用范围选到「系统」或以管理员运行才能改系统变量。
- `envScope` / `pathScope`（每套工具链各自设置）：`User` 写用户、`Machine` 写系统；`UserOnly` 写用户并删除系统侧同名变量 / 本工具链条目；`MachineOnly` 反之。四值都可用，只是写系统侧需要管理员。
- `settings.normalizePathOnApply`（切换版本时自动排序去重）：开启后每次切换都会把整条 PATH 重新排序并去掉重复项（去重不区分大小写、忽略尾部 `\`），避免反复切换后条目重复堆积、顺序混乱；关闭则按「原有条目 + 新版本条目」原样拼接。该值仍是全局设置，在「项目配置」页。
- PATH 去重按 Windows 规则大小写不敏感。
- 网页服务只监听 `127.0.0.1`，不对外暴露。

## 网页界面

界面为左右结构：左侧菜单、右侧内容区（宽度自适应、不限制最大宽度）。地址栏用路径记录当前页（`/switch`、`/project`、`/toolchain`、`/path`、`/env`，也兼容 `?page=env` 和 `#/env`），刷新或直接输入地址都会停在同一页；点击任意菜单都会重新读取最新配置与该页数据后重绘，保证在别处改过的内容立刻可见。

提示统一在页面顶部：轻提示（成功 5 秒、失败 12 秒后自动消失，点一下立即关闭）浮在窗口顶部居中；切换失败、保存被拒、批量应用结果这类需要反复看的，还会在内容区顶部留一条常驻横幅。

| 菜单 | 用途 |
|---|---|
| 版本切换 | 点版本按钮切换，先弹二次确认（列出会写入的变量、PATH 条数与作用范围，并说明是否仅为预览）；切换结果（成功 / 失败原因）常驻显示在页面顶部；「不生效」的工具链不显示 |
| 项目配置 | 全局设置：端口、启动时自动开浏览器、切换版本时自动排序去重 |
| 工具链配置 | 顶部提示「改完不会立即生效」+「立即应用全部」（保存配置后把**所有**「生效且有激活版本」的工具链逐个应用，结果逐条列出）；左侧工具链列表，右侧「基础配置」（一行一项：名称 / 环境变量名 / PATH 模板 / 是否生效 / 环境变量作用范围 / PATH 作用范围，每项下方有 ℹ️ 解释）与「版本列表」（表格列序：删除 / 激活单选 / 版本名 / Home / PATH 条目，删除按钮在行首，「添加版本」在表格下方） |
| PATH 工具 | 系统/用户 PATH 并排列表（当前 / 处理后）；目录已不存在的条目在「当前」列表标「已失效」，并在「处理后」自动剔除；同一个目录的不同写法（软链别名与真实目录）按真实目录算一条，只留靠前的那条；可排序、去重后勾选写入（默认「仅预览，不写入」，取消勾选后点「应用写入」会先二次确认要写的系统/用户条数，确认后整体覆盖） |
| 环境变量 | 用户与系统变量分组展示，点分组标题折叠/展开；支持筛选、行内编辑值（保存前二次确认）、删除变量（二次确认后就地置灰、标「已删除」并撤掉操作按钮，不重读整表，滚动位置不变）；值指向的目录/文件已不存在的变量标「已失效」 |

### PATH 工具规则

页面分「当前」「处理后」两栏，每栏同时列出「系统」与「用户」列表：

1. 系统与用户中**完全相同**的条目，从用户的「处理后」列表自动移除（「当前」列表中以"与系统重复 · 已移除"徽标标出）。
2. 同一工具链（依据配置里的版本 Home 识别）但**版本不同**时，两侧条目都加橙色"版本冲突"徽标，例如 `Java 17 ↔ 21`。
3. 「处理后」列表每行可勾选是否保留，勾选结果即为写入内容；默认勾选「仅预览，不写入」：勾着的时候点「应用写入」只按当前勾选重算一遍并提示会写多少条，**不改系统**；想真正落盘先取消这个勾选。它的用途是批量清理 / 排序前先看一眼结果，确认无误再写。
4. 「排序」「去重」开关影响「处理后」列表，改动后自动刷新。去重会忽略尾部目录分隔符并保留不带 `\` 的规范形式（盘符根 `C:\` 与 UNC 根除外），因此 `C:\Program Files\GitHub CLI` 和 `C:\Program Files\GitHub CLI\` 视为同一项，只保留前者。
5. 每条条目都会检查目录是否还在：已不存在的在「当前」列表标「已失效」，并被自动从「处理后」列表剔除，写入时不会带上（因此历史软件卸载后残留的 PATH 会被清理掉）。
6. 「去重」按**真实目录**判断：`…\latest\jdk-21\bin` 这种 junction / 软链别名与它指向的 `…\jdk-21.0.13.0.2\bin` 会被算成同一项，只保留列表中靠前的那条（保留原写法，不会替换成解析后的真实路径）。
