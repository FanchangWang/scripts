# AI agent skills

给 AI agent 用的 skill 源码仓库。这里只放**源码**；实际被 agent 加载的是发布后的副本，默认在：

```text
C:\Users\guyue\.agents\skills\
```

## 目录结构

```text
skill/
├── manifest.txt          # 发布清单（单一事实来源）：[skills]
├── publish.ps1           # PowerShell 7 发布脚本
├── publish.sh            # Git Bash / POSIX sh 发布脚本
├── AGENTS.md             # 在本目录工作时的 agent 约定
├── ui-android-compose/   # Android Compose UI 设计
├── audit-android-compose/# Android Kotlin/Compose 项目代码审计
└── ui-web-windows/       # Windows Web 应用 UI 设计（深浅色）
```

每个 skill 目录就是一个可直接发布的 skill 包，至少包含带 YAML frontmatter 的 `SKILL.md`，可选带 `references/`、`templates/`、`checklists/`、`workflows/` 等子目录。

## 命名格式

```text
类别-作用大类-作用小类[-其他补充]
```

- **类别**：`ui`（UI 设计）、`audit`（代码审计）、或未来扩展的其他类别。
- **作用大类**：`android`、`windows`、`web` 等平台 / 领域。
- **作用小类**：`compose`、`kotlin`、`web` 等具体技术 / 场景。
- **其他补充**（可选）：如 `java`、`4.4`、`dark-mode` 等细分说明。

示例：`ui-android-compose`、`ui-android-compose-java`、`ui-android-4.4`、`audit-android-compose`。

发布脚本会校验目录名格式，并校验 `SKILL.md` frontmatter 中的 `name` 与目录名完全一致。

## 当前 skill

| 目录 | 用途 |
|---|---|
| [ui-android-compose](ui-android-compose/) | Android Compose UI 设计，遵循 Google 最新 Material 3 / Compose 规范；两阶段流程（主题 token → UI）+ 自检清单，反 AI slop |
| [audit-android-compose](audit-android-compose/) | Android Kotlin/Compose 项目全工程审计，证据驱动，分域清单 + 工作流 + 报告模板 |
| [ui-web-windows](ui-web-windows/) | Windows 桌面 Web 应用 UI 设计，深浅色主题双轨、Fluent 风格、无障碍对比度、反 AI slop |

## 发布

### Windows（推荐）

必须用 PowerShell 7（`pwsh.exe`），不能用 Windows PowerShell 5.1。

```powershell
pwsh.exe -NoProfile -File "C:\Users\guyue\code\scripts\skill\publish.ps1"
```

### Git Bash / Linux / macOS

Windows 上要用 Git Bash（`C:\Program Files\Git\bin\bash.exe`），不是 `C:\WINDOWS\system32\bash.exe`。

```bash
bash "C:/Users/guyue/code/scripts/skill/publish.sh"
```

### 常用参数

两个脚本参数等价：

| PowerShell | Bash | 说明 |
|---|---|---|
| `-SkillsDir DIR` | `--skills-dir DIR` | 改发布目标目录（默认 `~/.agents/skills`） |
| `-Skill NAME` | `--skill NAME` | 只发布指定 skill，可重复 |
| `-DryRun` | `--dry-run` | 只打印计划，不写文件 |
| `-List` | `--list` | 列出清单后退出 |

示例：

```powershell
pwsh.exe -NoProfile -File .\publish.ps1 -DryRun
pwsh.exe -NoProfile -File .\publish.ps1 -Skill ui-android-compose
pwsh.exe -NoProfile -File .\publish.ps1 -SkillsDir C:\tmp\skills
```

发布脚本会：

1. 读取 `manifest.txt`；
2. 校验源目录、目录名格式、`SKILL.md` frontmatter；
3. 把 `[skills]` 中每个目录镜像覆盖到目标 skills 目录。

脚本**只发布清单里列出的 skill，不删除目标目录里的任何其他 skill**。当初整理项目时删除三个旧 skill（`ui-android`、`mobile-android-design`、`android-compose-code-audit`）是一次性人工操作，不属于发布流程。

## 新增 / 修改 skill

1. 按命名格式新建目录，写好 `SKILL.md`（frontmatter `name` 必须等于目录名）。
2. 大体量内容拆到 `references/`、`templates/` 等子目录，`SKILL.md` 保持精简（部分 agent 有正文 8 KB 上限）。
3. 在 `manifest.txt` 的 `[skills]` 加一行；不再需要的 skill 请自行人工清理发布目录，不要往脚本里加删除名单。
4. 按仓库约定，把新子项目加入根目录 `scripts.code-workspace` 的 `folders`。
5. 先 `-DryRun` 检查，再正式发布；**不要直接手改发布目录里的副本**。

## 约定

- 源码只在本目录维护；发布目录（`~/.agents/skills/`）中的副本会被下次发布覆盖。
- `manifest.txt` 是发布行为的唯一配置来源，不要在两个脚本里硬编码 skill 名单。
- 发布是覆盖式操作：目标里的同名目录会被先删除再复制；不同名的 skill 一律不动。
- 不要生成 / 提交密钥、Cookie、绝对路径等机器相关内容到 skill 正文里；环境相关信息用参数或相对路径表达。
