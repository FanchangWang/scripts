# EnvSwitch

Windows 多版本环境变量切换器 + PATH 整理工具。一个 exe 同时提供**本地网页 GUI**和 **CLI**，使用 .NET 10（LTS）。

## 功能

- 读取**用户**与**系统**环境变量：网页「环境变量」页把两者分组列出（点标题可折叠分组），支持筛选，并可**行内编辑值**或**删除变量**（写入后广播 `WM_SETTINGCHANGE`）。
- 管理多套工具链（Java / Node / Maven …），每套可配置：
  - 要生成的环境变量名（如 `JAVA_HOME`）
  - 多个版本；每个版本可配 Home（环境变量值）和**多条 PATH 条目**（在网页「工具链配置」页的版本框里每行一条）
  - PATH 条目模板（如 `%JAVA_HOME%\bin`）以及是否加入 PATH；版本未填显式 PATH 条目时回退用模板展开 Home
- 切换版本时自动：设置环境变量 → 清理该工具链所有旧版本的 PATH 条目 → 写入当前版本条目 → （可选）排序去重。
- 通过 `Environment.SetEnvironmentVariable(..., User)` 写入，自动广播 `WM_SETTINGCHANGE`，新开的终端立即生效。
- CLI 子命令对 PATH 排序、去重，默认只预览，`--apply` 才写入。
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

# 切换版本（--dry-run 仅预览）
.\EnvSwitch.exe use Java 17 --dry-run
.\EnvSwitch.exe use Java 17

# PATH 排序去重（默认仅预览）
.\EnvSwitch.exe path
.\EnvSwitch.exe path --scope Machine --apply
.\EnvSwitch.exe path --no-sort --dedup --apply

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
      "addToPath": true,
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

- 修改**用户**环境变量无需管理员权限；`pathScope` 设为 `Machine` 或以管理员运行才能改系统变量。
- `pathScope`（PATH 作用范围）：决定切换版本、写入 PATH 时改**用户变量**还是**系统变量**。`User` 只影响当前账号、无需管理员；`Machine` 对所有账号生效、需要管理员。
- `normalizePathOnApply`（切换版本时自动排序去重）：开启后每次切换都把整条 PATH 重新排序并去掉重复项（去重不区分大小写、忽略尾部 `\`），避免反复切换后条目重复堆积、顺序混乱；关闭则按「原有条目 + 新版本条目」原样拼接。
- PATH 去重按 Windows 规则大小写不敏感。
- 网页服务只监听 `127.0.0.1`，不对外暴露。

## 网页界面

界面为左右结构：左侧菜单、右侧内容区（宽度自适应、不限制最大宽度）。地址栏用路径记录当前页（`/switch`、`/project`、`/toolchain`、`/path`、`/env`，也兼容 `?page=env` 和 `#/env`），刷新或直接输入地址都会停在同一页；点击任意菜单都会重新读取最新配置与该页数据后重绘，保证在别处改过的内容立刻可见。

| 菜单 | 用途 |
|---|---|
| 版本切换 | 点击版本按钮切换，可勾选「仅预览」 |
| 项目配置 | 全局设置：PATH 作用范围、切换时自动排序去重、端口、启动时自动开浏览器（页面内带这两项的含义说明） |
| 工具链配置 | 左侧列出工具链，点击选中后右侧分「基础配置」（名称、环境变量名、PATH 模板、激活版本、是否加入 PATH）与「版本列表」（每版本 Home + 多条 PATH 条目），保存写回 JSON |
| PATH 工具 | 系统/用户 PATH 并排列表（当前 / 处理后），重复自动移除、版本冲突徽标、勾选保留条目后写入 |
| 环境变量 | 用户与系统变量分组展示，点分组标题折叠/展开；支持筛选、行内编辑值、删除变量（删除需二次确认） |

### PATH 工具规则

页面分「当前」「处理后」两栏，每栏同时列出「系统」与「用户」列表：

1. 系统与用户中**完全相同**的条目，从用户的「处理后」列表自动移除（「当前」列表中以"与系统重复 · 已移除"徽标标出）。
2. 同一工具链（依据配置里的版本 Home 识别）但**版本不同**时，两侧条目都加橙色"版本冲突"徽标，例如 `Java 17 ↔ 21`。
3. 「处理后」列表每行可勾选是否保留，勾选结果即为写入内容；默认勾选「仅预览，不写入」，取消勾选后再点「应用写入」才会真正写入系统与用户 PATH。
4. 「排序」「去重」开关影响「处理后」列表，改动后自动刷新。去重会忽略尾部目录分隔符并保留不带 `\` 的规范形式（盘符根 `C:\` 与 UNC 根除外），因此 `C:\Program Files\GitHub CLI` 和 `C:\Program Files\GitHub CLI\` 视为同一项，只保留前者。
