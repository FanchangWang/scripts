# AutoRun Delayed Startup Manager

## 需求概述

Windows 11 桌面级开机启动项管理器，双组件架构：**管理端**（.NET WinForms）用于配置延迟启动项并管理原始启动项状态，**调度端**（.NET AOT）用于开机后轻量执行调度。统一 C# 语言。

## 架构设计

### 双进程架构

```
┌──────────────────────────────────────────┐
│          管理端 (.NET WinForms)            │
│  - 列出注册表启动项 / 启动文件夹 / 计划任务  │
│  - 添加/编辑/删除延迟启动条目               │
│  - 导出/导入配置                          │
│  - 启动时自动提权 (requireAdministrator)   │
│  - 自带启动项管理功能（启用/禁用）           │
└──────────────┬───────────────────────────┘
               │ 写入 JSON 配置文件
               ▼
┌──────────────────────────────────────────┐
│    调度端 (.NET AOT, 计划任务 onlogon 触发) │
│  - 读取配置                              │
│  - 计时+调度启动                          │
│  - 透明进度面板显示进度                    │
│  - 失败时 Windows 通知                    │
└──────────────────────────────────────────┘
```

### 数据流

1. 管理端以管理员权限启动，读取系统启动项并展示
2. 用户添加/编辑延迟启动配置
3. 管理端将配置写入 `%LOCALAPPDATA%\AutoRunManager\config.json`
4. 管理端对加入延迟启动的原始启动项执行禁用（注册表 StartupApproved 软禁用、移出启动文件夹、禁用计划任务）
5. 管理端在首次运行时创建计划任务（onlogon, 延迟 3s）触发调度端，卸载时删除该任务
6. 调度端由计划任务触发启动，读取配置，按延迟时间排序，到时逐一启动
7. 调度端弹出 WinForms 半透明面板，显示启动进度
8. 全部完成后提示并在 n 秒后自动关闭面板

## 模块设计

### 1. 管理端 (.NET 10 WinForms)

#### 主界面布局（4 个 Tab）

```
┌──────────────────────────────────────────────────────────────────┐
│ [刷新列表]                                                       │
├──────────────────────────────────────────────────────────────────┤
│ Tab 1: 注册表自启动 │ Tab 2: 启动文件夹 │ Tab 3: 计划任务 │ Tab 4: 延时启动 │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│ (每个 Tab 的内容见下方详述)                                       │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

**每个 Tab 内部结构示例（以注册表 Tab 为例）：**

```
┌─ 注册表自启动 ────────────────────────────────────────────────────┐
│                                                                   │
│ ── HKEY_CURRENT_USER ──────────────────────────────────────────── │
│ 应用名 │ 命令/路径                        │ 状态  │ 操作                │
│────────┼──────────────────────────────────┼───────┼─────────────────────│
│ 微信   │ C:\Program Files\...\Weixin.exe  │ 启用  │ [延时启动] [禁用]   │
│ WeChat │ C:\...\WeChat\WeChat.exe         │ 禁用  │ [延时启动] [启用]   │
│                                                                   │
│ ── HKEY_LOCAL_MACHINE ────────────────────────────────────────── │
│ 应用名 │ 命令/路径                        │ 状态  │ 操作                │
│────────┼──────────────────────────────────┼───────┼─────────────────────│
│ Java   │ C:\...\java.exe                  │ 启用  │ [延时启动] [禁用]   │
└───────────────────────────────────────────────────────────────────┘
```

**Tab 4: 延时启动（核心功能页）：**

```
┌─ 延时启动 ────────────────────────────────────────────────────────┐
│ [手动添加] [导入配置] [导出配置]  [创建计划任务] [删除计划任务]     │
│                                                                   │
│ 排序│ 应用名 │ 路径          │ 延时 │ 身份  │ 来源       │ 操作       │
│─────┼────────┼───────────────┼──────┼───────┼────────────┼───────────│
│  1  │ 微信   │ ...Weixin.exe │ 30s  │ 用户  │ HKCU\Run  │ [编辑] [删]│
│     │        │               │      │       │            │ [上移][下]│
│  2  │ 翻译   │ ...pot.exe    │ 30s  │ 用户  │ 手动       │ [编辑] [删]│
│     │        │               │      │       │            │ [上移][下]│
│  3  │ PT     │ ...PT.exe     │ 60s  │ 管理员│ 计划任务   │ [编辑] [删]│
│     │        │               │      │       │            │ [上移][下]│
└───────────────────────────────────────────────────────────────────┘
```

#### 核心功能

- **Tab 1: 注册表自启动**（含启动项管理）
  - 读取 `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`
  - 读取 `HKLM\Software\Microsoft\Windows\CurrentVersion\Run`
  - 读取 `HKLM\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Run`（32位程序）
  - 分区块展示：HKCU 区 / HKLM 区 / WOW6432Node 区，每个区块有小标题标明来源
  - 每行右侧两按钮：
    - **[延时启动]** — 弹窗预填信息，确认后 StartupApproved 软禁用原项 + 写入 config.json
    - **[禁用]/[启用]** — 通过 StartupApproved 软禁用/重新启用，不涉及延时逻辑
  - 禁用方式：**不删除注册表值**，在 `HKCU\...\Explorer\StartupApproved\Run` 下写入同名 REG_BINARY 以标记禁用（Windows Task Manager 和 MSCONFIG 的一致做法）
  - 已加入延时的条目，状态列显示"已延时"，按钮变为 **[移出延时]**，隐藏 [禁用/启用] 按钮
  - 移出延时后，恢复显示 [禁用/启用] 按钮

- **Tab 2: 启动文件夹**（含启动项管理）
  - 读取 `%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup`（用户）
  - 读取 `%PROGRAMDATA%\Microsoft\Windows\Start Menu\Programs\Startup`（系统）
  - 分区块展示：用户启动文件夹 / 系统启动文件夹
  - 每行右侧两按钮：**[延时启动]**、**[禁用]/[启用]**（禁用 = 移走快捷方式到 backup 目录）
  - 已加入延时的条目，按钮变为 **[移出延时]**，隐藏 [禁用/启用] 按钮

- **Tab 3: 计划任务**（含任务管理）
  - 用 `Microsoft.Win32.TaskScheduler` 枚举，过滤触发器为 onlogon/onstart 的任务
  - 分区块：按任务来源或类别分组（可选）
  - 每行右侧两按钮：**[延时启动]**、**[禁用]/[启用]**（禁用 = `Task.Enabled = false`）
  - 已加入延时的条目，按钮变为 **[移出延时]**，隐藏 [禁用/启用] 按钮
  - 权限继承：读取任务 XML 中的 `<RunLevel>` 判断管理员/普通用户，**弹窗预填时自动继承该设置**

- **延时启动弹窗**（点击 **[延时启动]** 时弹出）
  - 自动填充：应用名（从注册表值名/文件名/任务名获取）
  - 自动填充：命令路径
  - 自动填充：命令行参数（如能获取到）
  - **延时时间**：下拉选择 0s / 10s / 30s / 60s / 自定义输入
  - **启动身份**：默认「普通用户」（仅计划任务条目继承原任务的 RunLevel）
  - **来源信息**：只读显示条目来源，精确到具体路径（如 `HKCU\...\Run` / `HKLM\...\Run` / `用户启动文件夹` / `系统启动文件夹` / `计划任务名称`）
  - 确认后：禁用原始启动项 + 写入 config.json

- **Tab 4: 延时启动列表**（已加入延时的条目管理）
  - 展示 config.json 中的所有条目
  - 按 `delay_seconds` + `sort_order` 正序排列
  - 列：排序号、应用名、路径、延时时间、启动身份、来源（精确描述）
  - 操作列：[编辑] [删除] [上移] [下移]
  - **[上移]/[下移]** — 调整相同延时时间内的启动顺序（用于处理软件启动依赖）
  - **[编辑]** — 弹窗修改延时时间、启动身份、命令行参数等
  - **[删除]** — 从延时启动移除 + 恢复原始启动项
  - **[手动添加]** — 弹窗：选择 exe/msi/lnk，填参数，选延迟时间，选管理员/普通用户，支持 UWP
  - **[创建计划任务]** / **[删除计划任务]** — 手动管理调度端自身的计划任务（方便绿色版分发）

- **导入/导出**
  - 导出：SaveFileDialog → 保存 config.json 副本
  - 导入：OpenFileDialog → 读取并合并到现有配置

- **关闭即退出**
  - 关闭主窗口直接退出，无系统托盘

#### 关键技术点

| 功能 | 实现方式 |
|------|----------|
| 管理员提权 | app.manifest `requireAdministrator`，启动即提权 |
| 注册表读取 | `Microsoft.Win32.RegistryKey` |
| 注册表软禁用 | 不删值，在 `HKCU\...\Explorer\StartupApproved\Run` 下写入同名 REG_BINARY 标记禁用（与 Task Manager 一致） |
| 注册表软启用 | 删除 `StartupApproved\Run` 下的对应条目 |
| 计划任务枚举 | `Microsoft.Win32.TaskScheduler` NuGet 包 |
| 计划任务 XML 解析 | 用 `TaskService.GetTask(taskName).Xml` 直接获取 XML |
| 计划任务禁用/启用 | `Task.Enabled = false` / `true` |
| UWP 应用枚举 | `Windows.Management.Deployment.PackageManager` WinRT API |
| 创建本程序计划任务 | `schtasks /create /tn "AutoRunManager/Scheduler" /tr "<exe>" /sc onlogon /delay 0000:03 /rl HIGHEST /f` |

### 2. 调度端 (.NET 10 AOT WinForms)

#### 调度端权限架构

这是本项目最关键的设计决策。核心矛盾：
- 管理员应用需要管理员令牌才能无 UAC 启动
- 普通用户应用不应该以管理员身份运行（拖拽、OLE、文件权限等问题）

**方案：单进程 + 令牌降权启动普通应用**

```
计划任务(onlogon 延迟 3s, /rl HIGHEST) → AOT exe 启动
         │
         ├─ Mutex 检测是否已有实例运行 → 是则退出
         │
         ├─ 读取 %LOCALAPPDATA%\AutoRunManager\config.json
         │
         ├─ 弹出半透明进度面板
         │
         ├─ 按延迟排序 → 依次倒计时 → 到点启动：
         │   ├─ run_as_admin=true  → Process.Start (管理员令牌继承，无 UAC)
         │   └─ run_as_admin=false → CreateProcessAsUser (降权到登录用户)
         │
         ├─ 启动成功 → 面板标记 ✓
         ├─ 启动失败 → WinRT ToastNotification("xxx 启动失败")
         │
         └─ 全部完成 → 面板显示"已全部启动，窗口即将关闭"
                       → 3s 后自动关闭
```

**降权启动原理**：

```csharp
// run_as_admin=true: 直接启动，自身上下文即管理员，无 UAC
Process.Start(new ProcessStartInfo(path, args) { UseShellExecute = false });

// run_as_admin=false: 以登录用户的身份启动（不能只靠继承）
// 步骤：
// 1. WTSGetActiveConsoleSessionId() 获取当前用户会话 ID
// 2. WTSQueryUserToken(sessionId, out hUserToken) 获取用户令牌
// 3. CreateProcessAsUser(hUserToken, path, args, ...) 以用户身份启动
// 4. 也可用 DuplicateTokenEx + CreateEnvironmentBlock 确保环境变量正确
```

**为什么不拆成两个进程**：
- 两个调度端同时存在，相互协调进度面板显示较复杂
- 一个面板展示所有条目更简洁，无需协调两个 UI
- 只需 `CreateProcessAsUser` 一个 P/Invoke 即可解决，代码量极少

#### 启动流程

```
计划任务(onlogon 延迟 3s) → AOT exe 启动
         │
         ├─ Mutex 检测 → 已有实例则退出
         ├─ 读取 config.json
         ├─ 弹半透明进度面板（Form Opacity + TopMost + 无边框）
         │   显示待启动列表（按 delay_seconds + sort_order 升序）
         │   面板右上角有关闭按钮（X），点击即退出整个调度端
         │
         ├─ 遍历列表，对每条：
         │   ├─ 等待剩余延迟时间（Task.Delay）
         │   ├─ 更新面板倒计时
         │   ├─ run_as_admin=true  → Process.Start（继承管理员）
         │   └─ run_as_admin=false → CreateProcessAsUser（降权到当前用户）
         │   ├─ 成功 → 面板标记 ✓
         │   └─ 失败 → Toast 通知 + 面板标记 ❌
         │
         └─ 全部完成 → 面板显示"已全部启动，窗口即将关闭"
                       → 3s 后 Close()
```

#### 面板设计

```
┌─────────────────────────────────────[X]┐
│  AutoRun Manager - 延迟启动进度         │
│                                        │
│  ⏳ 微信              剩余 5s          │
│  ⏳ PowerToys         剩余 30s         │
│  ✅ Maye Nano 已启动                    │
│  ❌ pot 翻译工具                        │
│                                        │
│  进度: 1/4          窗口 3s 后关闭      │
└────────────────────────────────────────┘
```

面板右上角 [X] 关闭按钮可随时退出调度端。

#### 依赖

| 用途 | 方式 |
|------|------|
| 配置解析 | `System.Text.Json` (source generator 模式) |
| 进度面板 | `System.Windows.Forms` |
| 进程启动（管理员） | `System.Diagnostics.Process` |
| 进程启动（普通用户） | `P/Invoke: WTSQueryUserToken + CreateProcessAsUser` |
| 单实例 | `System.Threading.Mutex` |
| Toast 通知 | `Windows.UI.Notifications` via CsWinRT |
| 日志 | `System.Console` + 重定向到文件 |

#### 进度面板方案

- `FormBorderStyle = None`、`TopMost = true`、`ShowInTaskbar = false`
- `Opacity` + 圆角 Region 实现半透明圆角窗口
- `TableLayoutPanel` + `Label` 展示列表项状态
- `System.Windows.Forms.Timer` 驱动倒计时更新
- `Task.Delay` 用于精确的延迟启动调度
- 面板右上角有关闭按钮，点击即 `Application.Exit()` 退出整个进程
- 全部完成后 `await Task.Delay(3000)` → `Close()`

## 配置格式

### config.json (存放于 %LOCALAPPDATA%\AutoRunManager\config.json)

```json
{
  "version": 1,
  "items": [
    {
      "id": "a1b2c3d4-...",
      "name": "微信",
      "path": "C:\\Program Files\\Tencent\\Weixin\\Weixin.exe",
      "args": "-autorun",
      "delay_seconds": 30,
      "run_as_admin": false,
      "sort_order": 1,
      "source": "registry",
      "source_detail": "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
      "source_key_name": "Weixin"
    },
    {
      "id": "e5f6g7h8-...",
      "name": "Epson Scanner",
      "path": "D:\\Programs\\epsonscan2.exe",
      "args": "",
      "delay_seconds": 60,
      "run_as_admin": false,
      "sort_order": 2,
      "source": "startup_folder",
      "source_detail": "%APPDATA%\\Microsoft\\Windows\\Start Menu\\Programs\\Startup",
      "source_key_name": "Epson Scanner.lnk"
    },
    {
      "id": "i9j0k1l2-...",
      "name": "SomeTool",
      "path": "C:\\Tools\\sometool.exe",
      "args": "",
      "delay_seconds": 10,
      "run_as_admin": true,
      "sort_order": 1,
      "source": "scheduled_task",
      "source_detail": "Microsoft\\Windows\\SomeTask",
      "source_key_name": "SomeTask"
    },
    {
      "id": "m3n4o5p6-...",
      "name": "MyApp",
      "path": "D:\\Apps\\myapp.exe",
      "args": "--silent",
      "delay_seconds": 30,
      "run_as_admin": false,
      "sort_order": 1,
      "source": "manual",
      "source_detail": ""
    }
  ]
}
```

字段说明：

| 字段 | 说明 |
|------|------|
| `id` | UUID v4，唯一标识 |
| `name` | 显示名称 |
| `path` | 可执行文件路径 |
| `args` | 命令行参数 |
| `delay_seconds` | 延迟秒数（0 = 立即启动） |
| `run_as_admin` | 是否以管理员身份启动 |
| `sort_order` | 排序序号，同秒数内按此顺序启动（1,2,3...） |
| `source` | 来源：`registry` / `startup_folder` / `scheduled_task` / `manual` |
| `source_detail` | 来源路径描述（如 `HKCU\...\Run` / `用户启动文件夹` / `计划任务名`） |
| `source_key_name` | 注册表值名称 / 任务名称 / 文件名（用于恢复：注册表通过删除 StartupApproved 恢复，任务通过 Task.Enabled=true，快捷方式通过重新创建） |

恢复逻辑：
- **注册表**：`source_key_name` 是注册表值名。恢复时删除 `StartupApproved\Run` 下同名条目，原始值仍在 `Run` 中不受影响
- **启动文件夹**：`source_key_name` 是文件名（如 `xxx.lnk`）。恢复时从 `backup\{user|system}\` 复制回原启动文件夹
- **计划任务**：`source_key_name` 是任务名。恢复时 `Task.Enabled = true`

## 项目目录结构

```
auto-run/
├── src/
│   ├── AutoRunManager/                  # .NET 管理端
│   │   ├── AutoRunManager.csproj
│   │   ├── Program.cs                   # Main 入口 + 提权检测
│   │   ├── app.manifest                 # requireAdministrator
│   │   ├── Forms/
│   │   │   ├── MainForm.cs              # 主窗口 + 4 Tab
│   │   │   ├── AddItemDialog.cs         # 手动添加弹窗
│   │   │   ├── DelayStartDialog.cs      # 延时启动配置弹窗（预填信息）
│   │   │   └── EditItemDialog.cs        # 编辑延时条目弹窗
│   │   ├── Services/
│   │   │   ├── RegistryService.cs       # 注册表读取/StartupApproved 软禁用/恢复
│   │   │   ├── StartupFolderService.cs  # 启动文件夹读取/移动/恢复
│   │   │   ├── ScheduledTaskService.cs  # 计划任务枚举/禁用/启用/XML解析
│   │   │   └── ConfigService.cs         # JSON 配置读写
│   │   ├── Models/
│   │   │   ├── StartupItem.cs           # 单个启动项模型
│   │   │   └── AppConfig.cs             # 配置文件根模型
│   │   └── Resources/
│   │       └── AppIcon.ico              # 程序图标
│   │
│   └── AutoRunScheduler/                # .NET AOT 调度端
│       ├── AutoRunScheduler.csproj      # PublishAot=true
│       ├── Program.cs                   # Main 入口 + Mutex + 消息循环
│       ├── ConfigLoader.cs              # 配置读取（JsonSourceGenerator）
│       ├── ProcessLauncher.cs           # 进程启动封装（管理员/普通用户）
│       ├── ProgressForm.cs              # 进度面板窗口（含关闭按钮）
│       └── ToastNotifier.cs             # WinRT Toast 通知
```

## 开发计划

### Phase 1: 管理端 — 列表展示 + 启动项管理（启用/禁用）

目标：能跑起来看到所有启动项，能启用/禁用

- [ ] 创建 `AutoRunManager` 项目，目标框架 `net10.0-windows`，`<UseWindowsForms>true</UseWindowsForms>`
- [ ] 添加 `app.manifest`，启用 `requireAdministrator`
- [ ] 实现 `Models/StartupItem.cs` 和 `Models/AppConfig.cs`
- [ ] 实现 `Services/RegistryService.cs` — 读取 HKCU/HKLM/WOW6432Node，通过 StartupApproved 软禁用/启用
- [ ] 实现 `Services/StartupFolderService.cs` — 读取两个启动文件夹，支持移到 `backup\{user|system}\` / 恢复
- [ ] 实现 `Services/ScheduledTaskService.cs` — 通过 `Microsoft.Win32.TaskScheduler` 枚举任务，禁用/启用
- [ ] 实现 `Services/ConfigService.cs` — 读取/写入 `%LOCALAPPDATA%\AutoRunManager\config.json`
- [ ] 实现 `Forms/MainForm.cs` — Tab 1/2/3 布局，分区块展示，每行两个操作按钮
- [ ] 按钮逻辑：已延时条目隐藏 [禁用/启用]，仅显示 [移出延时]

### Phase 2: 管理端 — 延时启动交互

目标：完整的延时启动 CRUD

- [ ] 实现 `Forms/DelayStartDialog.cs` — 延时启动弹窗（预填名称、路径、参数、来源精确到具体路径）
- [ ] 实现已加入延时条目标记为"已延时"，[延时启动] → [移出延时]，隐藏 [禁用/启用]
- [ ] 实现 Tab 4 延时启动列表（DataGridView 展示 config.json 条目）
- [ ] 实现上移/下移调整启动顺序（sort_order）
- [ ] 实现 `Forms/EditItemDialog.cs` — 编辑延时条目
- [ ] 实现删除延时条目（从 config 移除 + 恢复原始启动项）
- [ ] 实现 `Forms/AddItemDialog.cs` — 手动添加弹窗（路径选择、参数、延迟、管理员标志、UWP）
- [ ] 实现管理端自身的计划任务管理：创建/删除 onlogon 任务（界面按钮）
- [ ] 导入/导出配置（SaveFileDialog / OpenFileDialog）

### Phase 3: 调度端 (.NET AOT)

目标：开机后能自动弹出面板，调度启动，发通知

- [ ] 创建 `AutoRunScheduler` 项目，目标框架 `net10.0-windows`，`<PublishAot>true</PublishAot>`
- [ ] 实现 `ConfigLoader.cs` — 用 `JsonSerializerContext` source generator 模式读取配置
- [ ] 实现 `ProcessLauncher.cs` — 封装 `Process.Start` + `CreateProcessAsUser` P/Invoke
- [ ] 实现 `ProgressForm.cs` — 无边框、置顶、半透明、列表展示、右上角有关闭按钮
- [ ] 实现倒计时调度引擎 — 按 `delay_seconds` + `sort_order` 排序，`Timer` 更新 UI + `Task.Delay` 触发启动
- [ ] 启动成功/失败后更新面板状态文字
- [ ] 全部完成后 3s 自动关闭面板
- [ ] 实现 `ToastNotifier.cs` — 失败时发送 WinRT Toast 通知
- [ ] Mutex 单实例检测
- [ ] 日志输出到 `%LOCALAPPDATA%\AutoRunManager\scheduler.log`

### Phase 4: 集成与部署

- [ ] 打包安装程序（WiX / Inno Setup）
- [ ] 管理端检测首次运行 → 创建计划任务（`/rl HIGHEST`）；卸载时删除任务
- [ ] Tab 4 提供 [创建计划任务] [删除计划任务] 按钮供手动管理
- [ ] 修复各边界情况：配置不存在、路径无效、调度端已被删除等
- [ ] 用户文档

## 关键设计决策记录

| 决策 | 选项 | 选择 | 理由 |
|------|------|------|------|
| 目标框架 | .NET 8/9/10 | **.NET 10 LTS** | 2026 年 6 月最新正式版，支持到 2028 年 |
| UI 框架 | WinForms / WPF / WinUI 3 | **WinForms** | Tab + DataGridView + 按钮交互，原生支持最省事 |
| 管理端提权 | 按需提权 / 启动即提权 | **启动即提权** | 操作 HKLM/计划任务都需要管理员，app.manifest 最省事 |
| 调度端语言 | .NET AOT | **.NET AOT** | 统一 C# 语言，进度面板直接用 WinForms 控件 |
| 调度端权限 | LIMITED / HIGHEST | **HIGHEST** | 避免 runas 弹 UAC，管理员应用直接继承令牌 |
| 普通应用启动方式 | runas 降权 / 拆双进程 / **CreateProcessAsUser** | **CreateProcessAsUser** | 单进程 + 降权启动，无需协调双 UI，无 UAC 弹窗 |
| 注册表禁用方式 | 删值 / StartupApproved | **StartupApproved** | 与 Task Manager 一致，软禁用，保留原值 |
| 计划任务操作 | `schtasks` CLI / `TaskScheduler` NuGet | **TaskScheduler NuGet** | .NET 原生库，无需解析 CSV |
| 通知方案 | WinRT Toast / 托盘 Balloon | **WinRT Toast** | Windows 11 原生通知体验（CsWinRT） |
| 单实例方案 | Mutex / 命名管道 | **Mutex** | 最轻量，无需额外通信 |
| 配置格式 | JSON / TOML / XML | **JSON** | 通用性好，.NET 原生 System.Text.Json |
| 程序间共享代码 | 类库项目 / 文件链接 | **文件链接** | 两个项目直接 `<Link>` 共享 Models，无需额外项目 |
| 系统托盘 | 使用 / 不使用 | **不使用** | 管理完即退出，不常驻后台 |

## 注意事项与坑点

### 1. WinRT Toast 通知

```csharp
// 需要：
// 1. 项目引用 Microsoft.Windows.SDK.NET.Ref
// 2. .exe 在开始菜单有快捷方式（否则 Toast 不生效）
// 3. AppUserModelID 注册到快捷方式
```

### 2. CreateProcessAsUser 降权启动

```csharp
// 关键 API（.NET LibraryImport 写法）：
[LibraryImport("wtsapi32.dll")]
static partial int WTSGetActiveConsoleSessionId();

[LibraryImport("wtsapi32.dll", SetLastError = true)]
[return: MarshalAs(UnmanagedType.Bool)]
static partial bool WTSQueryUserToken(int sessionId, out IntPtr token);

[LibraryImport("advapi32.dll", SetLastError = true)]
[return: MarshalAs(UnmanagedType.Bool)]
static partial bool CreateProcessAsUser(
    IntPtr hToken, IntPtr lpApplicationName,
    IntPtr lpCommandLine, IntPtr lpProcessAttributes,
    IntPtr lpThreadAttributes, [MarshalAs(UnmanagedType.Bool)] bool bInheritHandles,
    uint dwCreationFlags, IntPtr lpEnvironment,
    IntPtr lpCurrentDirectory, ref STARTUPINFO lpStartupInfo,
    out PROCESS_INFORMATION lpProcessInformation);

// 步骤：
// 1. WTSGetActiveConsoleSessionId() → sessionId
// 2. WTSQueryUserToken(sessionId, &hToken) → 用户令牌
// 3. CreateEnvironmentBlock(&env, hToken, false) → 用户环境变量
// 4. CreateProcessAsUser(hToken, null, cmdLine, null, null, false, 0, env, null, ref si, out pi)
// 5. DestroyEnvironmentBlock(env)
// 6. CloseHandle(hToken)
```

### 3. .NET AOT 限制

- 目标框架 `net10.0-windows`，AOT 需要安装 `clang` 或 `VS 2022` C++ 工具链
- JSON 序列化必须使用 source generator 模式（`JsonSourceGenerator`），不能用反射
- WinForms 在 AOT 下完全可用（.NET 9 起稳定支持）
- 所有 P/Invoke 需要 `LibraryImport` 而不是 `DllImport`

### 4. Microsoft.Win32.TaskScheduler 用法

```csharp
// 枚举所有任务
using TaskScheduler;
using (var ts = new TaskService())
{
    var tasks = ts.FindAllTasks(t =>
        t.Definition.Triggers.Any(tr =>
            tr is LogonTrigger || tr is BootTrigger));
    foreach (var task in tasks)
    {
        var name = task.Name;
        var xml = task.Xml;  // 直接获取 XML
        var enabled = task.Enabled;
        task.Enabled = false;  // 禁用
    }
}
```

### 5. 计划任务 XML 解析（判断管理员）

```xml
<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <Principals>
    <Principal id="Author">
      <RunLevel>HighestAvailable</RunLevel>  <!-- HighestAvailable=管理员, Limited=普通用户 -->
      <LogonType>InteractiveToken</LogonType>
    </Principal>
  </Principals>
</Task>
```

通过 `Task.Xml` 可直接获取 XML，无需执行 schtasks。

### 6. 配置路径

```
%LOCALAPPDATA%\AutoRunManager\
├── config.json          # 延迟启动配置
├── scheduler.log        # 调度端运行日志
├── backup\              # 启动文件夹备份
│   ├── user\            # 用户级启动文件夹备份
│   │   └── *.lnk
│   └── system\          # 系统级启动文件夹备份
│       └── *.lnk
└── icons\               # 可选：缓存图标
```

### 7. .csproj 关键配置

**管理端 (AutoRunManager.csproj)**:
```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>WinExe</OutputType>
    <TargetFramework>net10.0-windows</TargetFramework>
    <UseWindowsForms>true</UseWindowsForms>
  </PropertyGroup>
  <ItemGroup>
    <PackageReference Include="TaskScheduler" Version="*" />
  </ItemGroup>
</Project>
```

**调度端 (AutoRunScheduler.csproj)**:
```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>WinExe</OutputType>
    <TargetFramework>net10.0-windows</TargetFramework>
    <UseWindowsForms>true</UseWindowsForms>
    <PublishAot>true</PublishAot>
  </PropertyGroup>
  <ItemGroup>
    <PackageReference Include="Microsoft.Windows.SDK.NET.Ref" Version="10.0.*" />
  </ItemGroup>
</Project>
```
