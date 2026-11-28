# skill 子项目约定

- 本目录只存 skill **源码**。agent 实际加载的是发布后的副本：`C:\Users\guyue\.agents\skills\<skill-name>\`。不要直接编辑发布目录里的文件，改源码后重新发布。
- 命名格式固定为 `类别-作用大类-作用小类[-其他补充]`，全部小写、连字符分隔，例如 `ui-android-compose`、`audit-android-compose`。`SKILL.md` 的 frontmatter `name` 必须与目录名完全一致。
- `manifest.txt` 是发布清单的唯一事实来源：只有 `[skills]`，列出要发布的目录名。发布脚本只复制这些目录，**不删除目标目录里的任何其他 skill**；新增 skill 改这里即可。
- 用 `pwsh.exe`（PowerShell 7）运行 `publish.ps1`；bash 脚本用 Git Bash（`C:\Program Files\Git\bin\bash.exe`）。不要用 Windows PowerShell 5.1 或 `system32\bash.exe`。
- 发布前先用 `-DryRun` / `--dry-run` 检查；发布是覆盖式的（先删目标同名目录再复制）。
- `SKILL.md` 保持精简，大体量规则拆到 `references/`、`checklists/`、`workflows/`、`templates/`。部分 agent 对 skill 正文有 8 KB 上限，正文只保留入口、流程和索引。
- skill 内容必须与具体机器的绝对路径、账号、密钥无关；这类信息用参数或环境变量表达。
- 按仓库约定，新增子项目要同步加入根目录 `scripts.code-workspace` 的 `folders`，并在根目录 `README.md` 的分组索引与项目索引中登记。
- 旧 skill（`ui-android`、`mobile-android-design`、`android-compose-code-audit`）的内容已归纳进现有的 Android skill，发布目录里的旧副本是整理时**一次性人工删除**的；不要在发布脚本里恢复任何删除名单。
