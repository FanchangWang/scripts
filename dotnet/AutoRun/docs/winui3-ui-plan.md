# Startup Delayer — WinUI 3 界面重构规划

## 一、现有界面分析

### 管理端 (StartupDelayer)

当前采用 WinForms `TabControl` 四 Tab 布局：

| Tab | 内容 | 核心控件 |
|-----|------|----------|
| Tab 1: 注册表自启动 | 4 个 GroupBlock (HKCU/HKLM/WOW6432Node/UWP)，每个含 DataGridView + 按钮列 | DataGridView, Button Column |
| Tab 2: 启动文件夹 | 2 个 GroupBlock (用户/系统)，同上 | DataGridView, Button Column |
| Tab 3: 计划任务 | 1 个 GroupBlock，多 4 列 (Folder/Trigger/RunLevel/Delay) | DataGridView, Button Column |
| Tab 4: 延时启动 | 工具栏 (5 按钮) + DataGridView (10 列含 4 按钮) | FlowLayoutPanel, DataGridView |

对话框：
- `DelayStartDialog` — 添加延时启动（预填信息，只读展示 + 延时/身份配置）
- `EditItemDialog` — 编辑延时条目（6 字段可编辑）
- `AddItemDialog` — 手动添加（文件浏览 + UWP 选择 + 参数/延时/身份）
- UWP 选择弹窗 — 自定义 Form + ListBox

### 调度端 (StartupDelayerScheduler)

- 无边框半透明面板 (`ProgressForm`)，深色主题，屏幕右下角
- `TableLayoutPanel` 展示启动进度
- 右上角关闭按钮，完成后 3s 倒计时自动关闭

### 现有问题

- Tab 布局平铺，信息密度高但层次感差
- DataGridView 按钮列交互粗糙，按钮状态管理散落在各处
- 弹窗均为 Modal Dialog，阻断主界面
- 无搜索/过滤能力（启动项数量有限，无需搜索）
- 启动文件夹和注册表的界面重复度高
- UWP 应用被混在注册表 Tab 内，层级不清

---

## 二、WinUI 3 重构目标

1. **NavigationView 导航** — 替代 TabControl，左侧垂直导航更符合 Windows 11 设计语言
2. **现代列表控件** — 用 `ListView`/`ItemsRepeater` 替代 DataGridView，支持丰富模板
3. **非阻断交互** — 用 `ContentDialog` 替代 WinForms Modal，用 TeachingTip 做引导
4. **来源独立** — UWP 独立为顶级导航项，与注册表/启动文件夹/计划任务平级
5. **延时预设集中管理** — 延时时间选项在设置中统一配置，各处弹窗自动读取

- **Fluent Design** — Mica 背景、圆角、动画、Segoe UI Variable

---

## 三、主窗口设计

### 3.1 整体布局

```
┌──────────────────────────────────────────────────────────────┐
│  [Logo] Startup Delayer                                      │
├────────────┬─────────────────────────────────────────────────┤
│            │                                                 │
│  🏠 主页    │  (当前页内容)                                    │
│  📋 注册表  │                                                 │
│  📁 启动文件夹│                                                 │
│  ⏰ 计划任务 │                                                 │
│  📱 UWP 应用│                                                 │
│  ⏳ 延时启动 │                                                 │
│            │                                                 │
│  ─────────│                                                 │
│  ⚙️ 设置   │                                                 │
├────────────┴─────────────────────────────────────────────────┤
│  状态栏: N 个启动项 | M 个已延时 | 调度端状态                    │
└──────────────────────────────────────────────────────────────┘
```

- **NavigationView** 左侧导航，`PaneDisplayMode="Left"`
- 底部状态栏显示关键统计数据
- `NavigationView` 的 `IsPaneToggleVisible="True"` 支持收起

### 3.2 导航项

| 导航项 | 对应现有内容 | 说明 |
|--------|-------------|------|
| 主页 | 新增 | 概览仪表盘，展示关键统计和快捷操作 |
| 注册表 | Tab 1 (去掉 UWP) | 当前用户 / 所有用户 / 32 位程序 三个区块 |
| 启动文件夹 | Tab 2 | 用户 / 系统两个区块 |
| 计划任务 | Tab 3 | 登录/开机触发任务 |
| UWP 应用 | Tab 1 中分离 | UWP 应用的启动注册管理 |
| 延时启动 | Tab 4 | 延时启动配置列表 |
| 设置 | 新增 | 延时预设、导入/导出、计划任务管理、关于 |

---

## 四、各页面详细设计

### 4.1 "主页" 页面（新增）

作为应用入口，提供全局概览和快捷操作，让用户一眼了解启动项状况。

```
┌────────────────────────────────────────────────────────────┐
│  欢迎使用 Startup Delayer                                   │
│  管理你的开机启动项，加速系统启动速度                           │
├────────────────────────────────────────────────────────────┤
│                                                            │
│  ┌─ 总览 ───────────────────────────────────────────────┐  │
│  │                                                      │  │
│  │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌────────┐│  │
│  │  │  12     │  │   3     │  │   2     │  │  ✅    ││  │
│  │  │ 启动项   │  │ 已禁用  │  │ 已延时  │  │ 调度端 ││  │
│  │  │ 总计    │  │         │  │         │  │ 已安装 ││  │
│  │  └─────────┘  └─────────┘  └─────────┘  └────────┘│  │
│  └──────────────────────────────────────────────────────┘  │
│                                                            │
│  ┌─ 启动项分布 ─────────────────────────────────────────┐  │
│  │                                                      │  │
│  │  注册表  ████████████████████░░░░  8 项 (3 已延时)   │  │
│  │  启动文件夹  ████░░░░░░░░░░░░░░░░░  2 项             │  │
│  │  计划任务   ██████░░░░░░░░░░░░░░░░  3 项             │  │
│  │  UWP 应用   ████░░░░░░░░░░░░░░░░░  2 项 (1 已延时)   │  │
│  │                                                      │  │
│  │  点击类别名称可跳转到对应管理页面                       │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                            │
│  ┌─ 快捷操作 ──────────────────────────────────────────┐  │
│  │                                                      │  │
│  │  [+ 添加延时启动项]  [导入配置]  [导出配置]             │  │
│  │                                                      │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                            │
│  ┌─ 最近操作 ──────────────────────────────────────────┐  │
│  │                                                      │  │
│  │  微信 → 已加入延时启动 (30s)          2 分钟前         │  │
│  │  Edge → 已禁用                       5 分钟前         │  │
│  │  OneDrive → 已移出延时启动             10 分钟前       │  │
│  │                                                      │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

**设计思路**：
- **总览卡片**：4 个关键数字（总启动项数、已禁用数、已延时数、调度端安装状态），用不同强调色区分
- **启动项分布**：按来源分类的数量可视化条形图（用 WinUI 3 `ProgressBar` 模拟），点击类别名跳转对应页面
- **快捷操作**：最常用功能一键直达（添加、导入、导出）
- **最近操作**：显示最近的操作记录（可选，后期实现），让用户快速回顾

**技术实现**：
- 4 个统计数字用 `NumberBox` 风格的卡片，`Tap` 可导航到对应筛选视图
- 分布图用自定义 `StackPanel` + `ProgressBar` 实现
- 最近操作可暂为静态展示，后期接入操作日志

### 4.2 "注册表" 页面

```
┌────────────────────────────────────────────────────────────┐
│  注册表启动项                                                │
├────────────────────────────────────────────────────────────┤
│  [当前用户]  [所有用户]  [32 位程序]          ← Pivot       │
│  ────────                                              │
│                                                            │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  应用项列表 (卡片样式)                                 │  │
│  │  ...                                                 │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

**改进点**：
- Pivot Tab 使用用户友好的中文名称（当前用户/所有用户/32 位程序），对应 HKCU/HKLM/WOW6432Node
- 列表样式与其他页复用同一 DataTemplate
- **条目统一风格**：所有启动项（注册表/启动文件夹/计划任务/UWP）复用同一 `StartupItemCard` 模板，左侧不显示图标/文字，仅展示应用名、路径、状态和操作按钮

### 4.3 "启动文件夹" 页面

```
┌────────────────────────────────────────────────────────────┐
│  启动文件夹                                                  │
├────────────────────────────────────────────────────────────┤
│  [用户启动文件夹]  [系统启动文件夹]           ← Pivot       │
│  ────────────────                                       │
│                                                            │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  快捷方式列表 (显示 .lnk 目标路径、图标)               │  │
│  │  ...                                                 │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

### 4.4 "计划任务" 页面

```
┌────────────────────────────────────────────────────────────┐
│  计划任务                                                    │
├────────────────────────────────────────────────────────────┤
│                                                            │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ 任务名                               启用  [延时] [禁用]│
│  │ C:\...\tool.exe                                    │  │
│  │ \CustomTasks | 登录时 | 管理员 | 延迟: 30s           │  │
│  ├──────────────────────────────────────────────────────┤  │
│  │ OneDrive Reporting Task             启用  [延时] [禁用]│
│  │ C:\...\OneDriveStandaloneUpdater.exe                │  │
│  │ \Microsoft\Windows\OneDrive | 登录时 | 用户 | 5 分钟   │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

**改进点**：每条直接展示完整信息（路径、文件夹、触发类型、身份、延迟），不使用 Expander，与其他页面保持统一的卡片列表样式

**条目风格统一**：与注册表、启动文件夹页复用 `StartupItemCard` 模板，左侧无图标

### 4.5 "UWP 应用" 页面（从注册表独立）

```
┌────────────────────────────────────────────────────────────┐
│  UWP 应用                                                   │
├────────────────────────────────────────────────────────────┤
│                                                            │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ 微信 (UWP)                          启用  [延时] [禁用]│
│  │ 包名: Tencent.WeChat | AUMID: Tencent.WeChat...     │  │
│  ├──────────────────────────────────────────────────────┤  │
│  │ 照片                                 启用  [延时] [禁用]│
│  │ 包名: Microsoft.Windows.Photos                      │  │
│  ├──────────────────────────────────────────────────────┤  │
│  │ 邮件                                 已延时 [移出]    │  │
│  │ 包名: Microsoft.Windows.Mail                        │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

**条目风格统一**：与其他页复用 `StartupItemCard` 模板，左侧无图标

**独立原因**：虽然 UWP 启动注册信息存储在注册表中，但：
- UWP 应用的概念和操作方式与普通注册表值完全不同（AUMID vs exe 路径）
- UWP 有独立的枚举方式 (`PackageManager` / `SystemAppData`)
- 用户心智模型中 "UWP 应用" 是一个大类，与"注册表启动项"不在同一层级
- 独立页面可以提供更专属的展示（包图标、包名、AUMID）

### 4.6 "延时启动" 页面

按延时时间分组展示，组内支持拖拽排序调整启动顺序。

```
┌────────────────────────────────────────────────────────────┐
│  延时启动                                   [+ 手动添加]    │
├────────────────────────────────────────────────────────────┤
│                                                            │
│  ── 延时 10s ─────────────────────────── 2 项 ────────── │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ ⠿  微信                         10s                 │  │
│  │    C:\Program Files\...\Weixin.exe                    │  │
│  │    来源: HKCU 注册表 | 身份: 用户                     │  │
│  │                                        [编辑]  [启动]  [删除] │  │
│  ├ ⠿  OneDrive                     10s                 │  │
│  │    C:\Program Files\Microsoft OneDrive\OneDrive.exe   │  │
│  │    来源: 注册表 (HKLM) | 身份: 用户                   │  │
│  │                                        [编辑]  [启动]  [删除] │  │
│  └──────────────────────────────────────────────────────┘  │
│  ↕ 拖拽卡片可调整组内启动顺序                               │
│                                                            │
│  ── 延时 30s ─────────────────────────── 2 项 ────────── │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ ⠿  PT 翻译                        30s               │  │
│  │    D:\Programs\pot.exe                               │  │
│  │    来源: 手动添加 | 身份: 管理员                      │  │
│  │                                        [编辑]  [启动]  [删除] │  │
│  ├ ⠿  邮件 (UWP)                   30s                 │  │
│  │    AUMID: microsoft.windowscommunications...           │  │
│  │    来源: UWP 应用 | 身份: 用户                        │  │
│  │                                        [编辑]  [启动]  [删除] │  │
│  └──────────────────────────────────────────────────────┘  │
│  ↕ 拖拽卡片可调整组内启动顺序                               │
│                                                            │
│  ── 延时 60s ─────────────────────────── 1 项 ────────── │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ ⠿  Epson Scanner                 60s                │  │
│  │    D:\Programs\epsonscan2.exe                       │  │
│  │    来源: 启动文件夹 | 身份: 用户                     │  │
│  │                                        [编辑]  [启动]  [删除] │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

**分组逻辑**：
- 按 `delay_seconds` 分组，每组显示延时秒数和条目数
- 组内条目按 `sort_order` 排列
- 组之间按延时时间升序排列（10s → 30s → 60s）
- 每组标题可点击折叠/展开（`Expander`）

**拖拽排序**：
- 使用 WinUI 3 `ItemsRepeater` + `DragDrop` 或 `InteractionTracker` 实现组内拖拽
- 拖拽手柄（⠿ 图标）位于卡片左侧，鼠标悬停时显示抓手光标
- 拖拽时显示半透明占位符和插入指示线
- 拖拽完成后自动更新 `sort_order` 值
- **限制**：只能在同一延时组内拖拽，不能跨组移动（跨组移动需通过编辑修改延时时间）

**操作按钮**：
- 去掉上移/下移按钮，改为拖拽排序
- 每条保留 [编辑]、[启动]、[删除] 三个按钮
- **[启动]**：直接启动当前条目的应用（不等待延时），调用 `ProcessLauncher.Start` 按配置的 `run_as_admin` 启动
- 编辑弹出 ContentDialog（延时选项从设置预设读取）
- 删除前用 ContentDialog 确认，确认后恢复原始启动项

### 4.7 "设置" 页面

```
┌────────────────────────────────────────────────────────────┐
│  设置                                                       │
├────────────────────────────────────────────────────────────┤
│                                                            │
│  ── 延时预设 ─────────────────────────────────────────── │
│                                                            │
│  可用的延时选项:                                            │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ 0s  │  10s  │  20s  │  30s  │  60s  │  120s         │  │
│  │     [删除]      [删除]        [删除]       [删除]      │  │
│  └──────────────────────────────────────────────────────┘  │
│  [+ 添加预设]  秒数: [____]                                │
│  说明: 此处配置的延时选项将在各页面的"延时启动"弹窗中展示    │
│                                                            │
│  ── 调度端管理 ────────────────────────────────────────── │
│  调度端计划任务:     ✅ 已创建                              │
│  [创建计划任务]  [删除计划任务]                               │
│                                                            │
│  ── 配置管理 ──────────────────────────────────────────── │
│  [导入配置]  [导出配置]                                      │
│                                                            │
│  ── 关于 ──────────────────────────────────────────────── │
│  版本: 1.0.0                                                │
│  框架: .NET 10 WinUI 3                                     │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

**延时预设管理**：
- 用户在此维护一个可选延时时间列表（如 0s、10s、20s、30s、60s、120s）
- 每个预设旁有删除按钮（`0s` 为内置项不可删除）
- **删除保护**：删除预设前检查是否有延时条目使用了该预设秒数（`delay_seconds` 匹配），若有则弹出 ContentDialog 提示 "当前有 N 个延时条目使用了 Xs 延时，请先修改这些条目的延时时间后再删除"，并阻止删除
- 底部有添加预设入口（输入自定义秒数）
- 预设数据存储在 `config.json` 的顶层字段中（与 `items` 并列）
- 所有"延时启动"弹窗（DelayStartDialog、AddItemDialog、EditItemDialog）中的延时选项自动从此列表读取

---

## 五、对话框设计

### 5.1 添加延时启动 → ContentDialog

```
┌──────────────────────────────────────┐
│  添加延时启动                     [X]│
│─────────────────────────────────────│
│                                      │
│  应用名    ┌──────────────────┐      │
│            │ 微信             │      │
│            └──────────────────┘      │
│                                      │
│  路径      ┌──────────────────┐ [浏览]│
│            │ C:\...\Weixin.exe│      │
│            └──────────────────┘      │
│                                      │
│  参数      ┌──────────────────┐      │
│            │ -autorun         │      │
│            └──────────────────┘      │
│                                      │
│  延时      [0s] [10s] [20s] [30s]   │
│            [60s] [120s]              │
│            ↑ 从设置中的延时预设读取   │
│                                      │
│  身份      ○ 普通用户  ● 管理员       │
│                                      │
│  来源      HKCU\...\Run (只读)        │
│                                      │
│─────────────────────────────────────│
│                     [取消]  [确认]   │
└──────────────────────────────────────┘
```

**关键变化**：延时时间选项不再硬编码，而是从设置中的预设列表动态读取。

**config.json 结构扩展**：
```json
{
  "version": 1,
  "delay_presets": [0, 10, 20, 30, 60, 120],
  "items": [
    { ... }
  ]
}
```

### 5.2 手动添加 → ContentDialog

与延时启动弹窗类似，额外支持：
- [浏览] 按钮 → `FileOpenPicker`（FileTypeFilter: `.exe`, `.msi`, `.lnk`）
- [UWP] 按钮 → 弹出 UWP 应用选择面板（`ContentDialog` 内嵌 `ListView`）
- 选择文件后自动填充应用名
- 延时选项同样从设置预设读取

### 5.3 编辑延时条目 → Flyout 或右侧面板

推荐 **内联展开**方案（体验更流畅）：
- 卡片直接展开编辑模式，就地修改
- 延时选项从设置预设读取（与添加弹窗一致）

### 5.4 UWP 应用选择 → ContentDialog

```
┌──────────────────────────────────────┐
│  选择 UWP 应用                    [X]│
│─────────────────────────────────────│
│  🔍 [搜索 UWP 应用...]              │
│─────────────────────────────────────│
│  ┌──────────────────────────────┐    │
│  │ [图标] 微信                  │    │
│  ├──────────────────────────────┤    │
│  │ [图标] 照片                  │    │
│  ├──────────────────────────────┤    │
│  │ [图标] 邮件                  │    │
│  └──────────────────────────────┘    │
│─────────────────────────────────────│
│                          [取消] [确认]│
└──────────────────────────────────────┘
```

### 5.5 确认对话框 → ContentDialog

删除、移出延时等操作用 `ContentDialog` 确认，替代 WinForms `MessageBox`：

```csharp
var dialog = new ContentDialog
{
    Title = "删除延时条目",
    Content = $"确定要删除「{item.Name}」吗？原始启动项将被恢复。",
    PrimaryButtonText = "删除",
    CloseButtonText = "取消",
    DefaultButton = ContentDialogButton.Close,
    XamlRoot = this.Content.XamlRoot
};
```

---

## 六、调度端进度面板

### WinUI 3 实现方案

```
┌─────────────────────────────────────────────┐
│  Startup Delayer · 延迟启动进度          [X] │
│─────────────────────────────────────────────│
│                                             │
│   ✅ 微信                         已启动    │
│   ⏳ PowerToys                     剩余 25s │
│   ⏳ 翻译工具                      剩余 50s │
│                                             │
│                                             │
│   ══════════════════ 1/3 完成            │
│                                             │
└─────────────────────────────────────────────┘
```

**WinUI 3 技术方案**：
- `AppWindow` 替代 WinForms 无边框窗口（WinUI 3 原生支持自定义标题栏）
- `MicaMaterial` 或 `AcrylicMaterial` 背景替代手动画圆角 + 半透明
- `ProgressBar` 底部显示整体进度
- `ProgressRing` 在每个等待项旁显示
- `InfoBar` 用于失败通知（替代 NotifyIcon BalloonTip）
- 窗口位置：屏幕右下角，用 `AppWindow.Move` 定位
- 动画：条目状态切换时用 `ImplicitAnimation`

**窗口样式**：
- `ExtendsContentIntoTitleBar = true` — 自定义标题栏融入内容
- 窗口尺寸自适应内容高度（最小 320x200，最大 480）
- 圆角由 WinUI 3 自动处理（Windows 11 默认圆角窗口）

**完成与关闭逻辑**：
- 全部成功：显示"已全部启动"后 3 秒自动关闭
- 存在失败：**不自动关闭**，底部显示失败摘要（"2/3 成功，1 个启动失败"）+ [查看错误日志] 按钮
- 点击 [查看错误日志] 打开日志文件（`%LOCALAPPDATA%\StartupDelayer\scheduler.log`）
- 用户也可随时点击右上角 X 手动关闭

---

## 七、组件映射表

| WinForms 控件 | WinUI 3 控件 | 备注 |
|---------------|-------------|------|
| `TabControl` | `NavigationView` | 左侧垂直导航 |
| `DataGridView` | `ListView` + 自定义 `DataTemplate` | 或 `ItemsRepeater` 更灵活 |
| `Button` Column | `Button` 在 DataTemplate 内 | 按状态 Visibility 绑定 |
| `GroupBox` | `Expander` / `Pivot` | 可折叠区块 |
| `ComboBox` | `ComboBox` / `RadioButtons` | 延时选项用 RadioButtons（从设置读取） |
| `CheckBox` | `CheckBox` | 一致 |
| `TextBox` (只读) | `TextBlock` | 只读展示用 TextBlock |
| `Form` (弹窗) | `ContentDialog` | 非阻断式对话 |
| `OpenFileDialog` | `FileOpenPicker` | WinRT API |
| `SaveFileDialog` | `FileSavePicker` | WinRT API |
| `FlowLayoutPanel` | `StackPanel` / `CommandBar` | 工具栏用 CommandBar |
| `NotifyIcon` | `InfoBar` / Toast | WinRT Toast 或 AppNotification |
| `Timer` | `DispatcherTimer` | 计时器 |
| `TableLayoutPanel` | `Grid` / `StackPanel` | 布局容器 |
| 无边框 Form + Region 圆角 | `AppWindow` + 原生圆角 | WinUI 3 原生支持 |
| `Mutex` | `Mutex` | 一致，无需更改 |

---

## 八、数据模型调整

### 8.1 config.json 扩展

新增 `delay_presets` 字段，用于存储用户自定义的延时预设列表：

```json
{
  "version": 2,
  "delay_presets": [0, 10, 20, 30, 60, 120],
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
    }
  ]
}
```

`version` 从 1 升级到 2，旧版本配置读取时自动补充默认预设 `[0, 10, 30, 60]`。

### 8.2 AppConfig 模型扩展

```csharp
public class AppConfig
{
    public int Version { get; set; } = 2;
    public List<int> DelayPresets { get; set; } = [0, 10, 20, 30, 60, 120];
    public List<StartupItem> Items { get; set; } = [];
}
```

### 8.3 ViewModel 层（新增）

WinUI 3 推荐 MVVM，需要新增 ViewModel：

```
Models/
├── StartupItem.cs        # 保留，数据模型 (config.json)
├── StartupEntry.cs       # 保留，运行时启动项
├── AppConfig.cs          # 扩展，新增 DelayPresets
└── DelayedItem.cs        # 新增，延时条目 ViewModel

ViewModels/
├── MainViewModel.cs              # 主窗口 ViewModel
├── HomePageViewModel.cs          # 主页 ViewModel（统计数据）
├── StartupItemViewModel.cs       # 启动项 ViewModel (状态/命令绑定)
├── DelayedItemViewModel.cs       # 延时项 ViewModel
├── RegistryPageViewModel.cs      # 注册表页 ViewModel
├── StartupFolderPageViewModel.cs
├── ScheduledTaskPageViewModel.cs
├── UwpAppPageViewModel.cs        # 新增，UWP 页 ViewModel
├── DelayedPageViewModel.cs
└── SettingsPageViewModel.cs      # 扩展，含延时预设管理
```

### 8.4 StartupEntry 扩展

```csharp
// 新增属性用于 UI 绑定
public partial class StartupEntry : ObservableObject
{
    [ObservableProperty]
    private bool isEnabled;

    [ObservableProperty]
    private bool isDelayed;

    [ObservableProperty]
    private string statusText;  // "启用" / "禁用" / "已延时"

    [ObservableProperty]
    private string statusBadge;  // Badge 颜色标识
}
```

---

## 九、项目结构调整

```
src/
├── StartupDelayer/                      # WinUI 3 管理端
│   ├── StartupDelayer.csproj            # net10.0-windows, UseWinUI=true
│   ├── App.xaml / App.xaml.cs        # 应用入口
│   ├── MainWindow.xaml / .cs          # 主窗口 (NavigationView)
│   ├── Views/
│   │   ├── HomePage.xaml              # 主页（新增）
│   │   ├── RegistryPage.xaml          # 注册表页（去掉 UWP Pivot）
│   │   ├── StartupFolderPage.xaml     # 启动文件夹页
│   │   ├── ScheduledTaskPage.xaml     # 计划任务页
│   │   ├── UwpAppPage.xaml            # UWP 应用页（新增）
│   │   ├── DelayedPage.xaml           # 延时启动页
│   │   └── SettingsPage.xaml          # 设置页（含延时预设管理）
│   ├── Dialogs/
│   │   ├── DelayStartDialog.xaml      # 添加延时启动（延时从预设读取）
│   │   ├── AddItemDialog.xaml         # 手动添加
│   │   ├── EditItemFlyout.xaml        # 编辑 Flyout
│   │   ├── UwpPickerDialog.xaml       # UWP 选择
│   │   └── ConfirmDialog.xaml         # 确认弹窗
│   ├── Controls/
│   │   ├── StartupItemCard.xaml       # 启动项卡片模板
│   │   ├── DelayedItemCard.xaml       # 延时项卡片模板
│   │   ├── StatCard.xaml              # 统计卡片（主页用）
│   │   ├── SourceBar.xaml             # 来源分布条（主页用）
│   │   └── DelayPresetChip.xaml       # 延时预设标签（设置页用）
│   ├── ViewModels/
│   │   ├── MainViewModel.cs
│   │   ├── HomePageViewModel.cs
│   │   ├── StartupItemViewModel.cs
│   │   ├── DelayedItemViewModel.cs
│   │   ├── RegistryPageViewModel.cs
│   │   ├── StartupFolderPageViewModel.cs
│   │   ├── ScheduledTaskPageViewModel.cs
│   │   ├── UwpAppPageViewModel.cs
│   │   ├── DelayedPageViewModel.cs
│   │   └── SettingsPageViewModel.cs
│   ├── Models/
│   │   ├── StartupItem.cs
│   │   ├── StartupEntry.cs
│   │   └── AppConfig.cs             # 扩展 DelayPresets
│   ├── Services/
│   │   ├── RegistryService.cs
│   │   ├── StartupFolderService.cs
│   │   ├── ScheduledTaskService.cs
│   │   ├── ConfigService.cs           # 扩展延时预设读写
│   │   └── OperationLogService.cs    # 新增，操作日志（主页最近操作）
│   ├── Converters/
│   │   ├── BoolToVisibilityConverter.cs
│   │   ├── StatusToColorConverter.cs
│   │   └── DelayTextConverter.cs
│   ├── app.manifest                  # requireAdministrator
│   └── Assets/
│       └── AppIcon.ico
│
├── StartupDelayerScheduler/                 # WinUI 3 调度端
│   ├── StartupDelayerScheduler.csproj
│   ├── App.xaml / App.xaml.cs
│   ├── MainWindow.xaml / .cs          # 进度面板窗口
│   ├── Services/
│   │   ├── ConfigLoader.cs
│   │   ├── ProcessLauncher.cs
│   │   ├── SchedulerEngine.cs
│   │   ├── ToastNotifier.cs
│   │   └── Logger.cs
│   └── Assets/
│       └── AppIcon.ico
```

---

## 十、关键技术决策

| 决策点 | WinForms 方案 | WinUI 3 方案 | 理由 |
|--------|--------------|-------------|------|
| UI 框架 | WinForms | **WinUI 3 (Windows App SDK)** | 现代 Fluent Design，原生 Windows 11 外观 |
| 布局 | TabControl | **NavigationView** | Windows 11 标准导航模式 |
| 列表 | DataGridView | **ListView + DataTemplate** | 更灵活的自定义渲染 |
| MVVM | 无 | **MVVM (CommunityToolkit.Mvvm)** | 数据绑定、命令绑定、Observable 属性 |
| 弹窗 | Form (Modal) | **ContentDialog** | 非阻断，视觉一致 |
| 文件选择 | OpenFileDialog | **FileOpenPicker** | WinRT 原生 |
| 通知 | NotifyIcon BalloonTip | **AppNotification (WinRT Toast)** | Windows 11 原生通知 |
| 进度面板 | 无边框 Form | **AppWindow + 自定义标题栏** | 原生圆角 + Mica 背景 |
| 延时选项 | 硬编码 ComboBox | **设置中统一管理，弹窗动态读取** | 灵活、可扩展 |
| UWP 管理 | 混在注册表 Tab | **独立顶级导航页** | 用户心智模型清晰 |
| 图标提取 | `Icon.ExtractAssociatedIcon` | **同名 API 或 Shell API** | 一致 |
| P/Invoke | `DllImport` | **LibraryImport** (已实现) | AOT 兼容 |
| 计划任务 | TaskScheduler NuGet | **TaskScheduler NuGet** (保留) | 功能不变 |

---

## 十一、NuGet 包依赖

### 管理端 (StartupDelayer)

| 包 | 用途 |
|----|------|
| `Microsoft.WindowsAppSDK` | WinUI 3 运行时 |
| `Microsoft.Windows.CsWinRT` | WinRT API 访问 |
| `CommunityToolkit.Mvvm` | MVVM 基础设施 (ObservableObject, RelayCommand 等) |
| `CommunityToolkit.WinUI.Controls` | 扩展控件 (可选) |
| `TaskScheduler` | 计划任务操作 (保留) |

### 调度端 (StartupDelayerScheduler)

| 包 | 用途 |
|----|------|
| `Microsoft.WindowsAppSDK` | WinUI 3 运行时 |
| `Microsoft.Windows.CsWinRT` | WinRT API (Toast, AppNotification) |
| `CommunityToolkit.Mvvm` | MVVM (可选，调度端逻辑简单) |

---

## 十二、实施建议

### 阶段划分

**阶段 1：基础框架搭建**
- 创建 WinUI 3 项目，配置 NavigationView 主框架
- 搭建 MVVM 基础结构
- 迁移 Models 和 Services（逻辑基本不变）
- 扩展 AppConfig 新增 DelayPresets

**阶段 2：主页 + 设置页（核心配置）**
- 实现主页（统计卡片 + 来源分布 + 快捷操作）
- 实现设置页（延时预设管理 + 导入导出 + 计划任务管理 + 关于）
- 延时预设的增删改查

**阶段 3：启动项管理页面**
- 实现注册表页（HKCU/HKLM/WOW6432Node 三 Pivot）
- 实现启动文件夹页
- 实现计划任务页
- 实现独立 UWP 应用页
- 实现启动项卡片模板（复用）
- 实现延时启动/禁用/启用交互逻辑

**阶段 4：延时启动页 + 对话框**
- 延时启动列表页（卡片 + 排序）
- ContentDialog：添加延时启动（从设置读取预设）、手动添加、编辑、UWP 选择、确认

**阶段 5：调度端重写**
- WinUI 3 进度面板
- AppNotification 替代 NotifyIcon

**阶段 6：打磨**
- 动画和过渡效果
- 错误处理和边界情况
- 高 DPI 适配测试
