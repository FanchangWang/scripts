# GitHub 发版流程（给后续维护者 / AI）

本插件的 release 发布在仓库 `FanchangWang/scripts`，tag 统一用 `mayenano-autotheme-` 前缀。
发版前**必须先得到用户明确授权**（commit / push / 打 tag / 创建 release 都要授权）；没有授权只做本地改动和构建。

## 1. 版本号

- **唯一配置源**：`src/p_AutoTheme.cpp` 顶部的 `#define PLUGIN_VERSION "x.y.z"`
- SDK 上报版本自动拼成 `PLUGIN_VERSION ".0"`，设置窗口右下角显示 `插件版本 v<PLUGIN_VERSION>`，两处共用同一宏，不要分别改
- 规则：修 bug 升 patch（`x.y.z → x.y.(z+1)`）；新增功能且向后兼容升 minor（`x.y.z → x.(y+1).0`）
- 当前版本号只在源码 `PLUGIN_VERSION` 里，**文档不要写死版本号**；设置窗口右下角会显示同一版本

## 2. 发版前必须同步的文件

| 文件 | 需要改什么 |
|---|---|
| `src/p_AutoTheme.cpp` | `PLUGIN_VERSION`；如有新功能/修复，对应代码 |
| `dist/mn.cc.arae.autotheme.dll` | 重新编译后的产物（入库交付物） |
| `README.md` | 顶部版本、`功能` 列表、Releases 链接 |
| `使用说明.txt` | 首行版本、功能列表、FAQ |
| `docs/install.md` | `行为` 章节中与实际行为不一致的描述 |
| `docs/release.md` | 本文件的“当前版本”与历史说明（如版本号变化） |

改完文档后全文搜索旧版本号占位符，确认文档里没有写死任何具体版本（源码 `PLUGIN_VERSION` 里的版本号除外）。

## 3. 本机构建与验证

```powershell
cd C:\Users\guyue\code\scripts\cpp\MayeNano-AutoTheme
cmd /c scripts\build.bat
```

构建脚本写死了工具链路径（VS 18 Community / MSVC 14.52.37025 / Windows SDK 10.0.26100.0 / Clang 21），换机器时先改 `scripts\build.bat`。正常的 `_MN_Info C-linkage` 警告可以忽略。

验证（不要跳过）：

1. 退出 Maye Nano，把 `dist\mn.cc.arae.autotheme.dll` 复制到
   `C:\Users\guyue\AppData\Local\Programs\MayeNano\plugin\mn.cc.arae.autotheme\`
2. 启动 Maye Nano，打开插件设置窗口，确认右下角版本号是新版、深色主题正常
3. 确认启动时**不会**误弹设置窗口（`first-run.done` 已存在时应静默）
4. 确认两个「预览主题」按钮互斥，取消 / 关闭窗口后恢复自动切换
5. 确认下拉展开时不被设置窗口遮挡

## 4. 提交与推送

```powershell
cd C:\Users\guyue\code\scripts
git status --short
git add cpp/MayeNano-AutoTheme/...
git commit -m "feat(cpp-maye-nano-autotheme): vX.Y.Z <简述>"
git push origin main
```

注意仓库里可能有其他目录的改动，只 `git add` 本次发版相关的文件。

## 5. 打 tag

每个版本打两个 tag，都指向发版的 commit：

```powershell
git tag -a mayenano-autotheme-vX.Y.Z -m "Maye Nano Auto Theme vX.Y.Z"
git tag -a mayenano-autotheme-latest -m "Maye Nano Auto Theme latest (rolling pointer)"
git push origin mayenano-autotheme-vX.Y.Z mayenano-autotheme-latest
```

- `mayenano-autotheme-vX.Y.Z`：历史版本 tag，永远不动
- `mayenano-autotheme-latest`：固定入口，下次发版要**移动**它（见第 8 节）

如果 tag 已存在，先确认是误操作还是要覆盖；版本 tag 不要复用。

## 6. 制作发布包

zip 根目录必须正好是三个文件（与历史发版保持一致）：

- `mn.cc.arae.autotheme.dll`
- `autotheme.ini`（来自 `config\autotheme.ini`）
- `使用说明.txt`（项目根目录，已更新版本）

```powershell
$src  = 'C:\Users\guyue\code\scripts\cpp\MayeNano-AutoTheme'
$stage = 'C:\Users\guyue\AppData\Local\Temp\opencode\release_stage'
$zip  = 'C:\Users\guyue\AppData\Local\Temp\opencode\mn.cc.arae.autotheme.zip'
Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $zip -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $stage | Out-Null
Copy-Item -LiteralPath "$src\dist\mn.cc.arae.autotheme.dll","$src\config\autotheme.ini","$src\使用说明.txt" -Destination $stage -Force
Compress-Archive -Path "$stage\*" -DestinationPath $zip -CompressionLevel Optimal
```

检查 zip 内容：`[IO.Compression.ZipFile]::OpenRead($zip).Entries` 应只有上述三个文件，且在根目录。

## 7. 创建 release

把更新说明写到临时 md 文件（推荐 `--notes-file`，避免命令行转义中文）：

```powershell
gh release create mayenano-autotheme-vX.Y.Z `
  --title "Maye Nano Auto Theme 插件 vX.Y.Z" `
  --notes-file 'C:\...\release_notes.md' `
  'C:\Users\guyue\AppData\Local\Temp\opencode\mn.cc.arae.autotheme.zip'

gh release create mayenano-autotheme-latest `
  --title "Maye Nano Auto Theme 插件 latest" `
  --notes-file 'C:\...\release_notes_latest.md' `
  'C:\Users\guyue\AppData\Local\Temp\opencode\mn.cc.arae.autotheme.zip'
```

release 标题统一格式：`Maye Nano Auto Theme 插件 <vx.y.z 或 latest>`，不要加功能描述后缀。
版本 release 正文和 latest 正文都要提醒用户通过 `mayenano-autotheme-latest` 获取最新版，
这样以后发新版不需要回去改历史 release。

`mayenano-autotheme-latest` 的正文要写明当前版本号并链接到版本 release，例如
说明该入口始终指向最新版，全部历史版本见 `https://github.com/FanchangWang/scripts/releases`；
正文里不要写死版本号，避免下次发版还要回来改。

## 8. 下次发版时更新 latest（不要重复创建）

`mayenano-autotheme-latest` 的 release 和 tag 已经存在，不要重新 `gh release create`。流程：

```powershell
# 移动滚动 tag 到新 commit
git tag -f -a mayenano-autotheme-latest -m "Maye Nano Auto Theme latest (rolling pointer)" <NEW_COMMIT>
git push --force origin mayenano-autotheme-latest

# 替换附件与说明
gh release edit mayenano-autotheme-latest --notes-file 'C:\...\release_notes_latest.md'
gh release upload mayenano-autotheme-latest 'C:\...\mn.cc.arae.autotheme.zip' --clobber
```

## 9. 旧 release 的处理（重要）

**GitHub 不支持把 `/releases/tag/<旧tag>` 做成 HTTP 跳转到新 release。** 旧链接已经被分发到外部，不要删除旧 tag / 旧 release，否则那个 URL 会变成 404。

正确做法：只改旧 release 正文，置顶醒目提示 + 固定入口链接，历史说明保留在下方；资产按用户要求保留或删除。

```powershell
gh release edit <旧版本tag> --notes-file 'C:\...\release_notes_old.md'
```

正文至少包含：

- `⚠️ 此版本已过期`
- `mayenano-autotheme-latest` 固定入口链接（该入口始终指向最新版）
- 全部版本列表 `https://github.com/FanchangWang/scripts/releases`

**正文里不要写任何具体的最新版本号**，否则每次发版都得回来改历史 release。
以后对外宣传统一用固定入口 `mayenano-autotheme-latest`，不再用带版本号的旧链接。

注：GitHub 没有 release 置顶功能，列表按 tag 对应 commit 日期排序；
想让固定入口排在前面，只能在发版时让它指向更新的 commit（例如发版后新增的文档提交），
这属于可选优化，不影响使用。

## 10. 发版后检查清单

```powershell
gh release list --limit 10
gh release view mayenano-autotheme-latest --json tagName,name,url
gh release view mayenano-autotheme-vX.Y.Z --json assets --jq '.assets[].name'
gh release view mayenano-autotheme-latest --json assets --jq '.assets[].name'
gh release view mayenano-autotheme-vX.Y.Z --json body --jq '.body' | Select-Object -First 5
git status -sb
```

确认：

- [ ] 两个新 release 都各有一个 `mn.cc.arae.autotheme.zip`
- [ ] 标题格式是 `Maye Nano Auto Theme 插件 <vx.y.z 或 latest>`
- [ ] 旧 release URL 仍可访问，正文顶部是过期提示且不含最新版本号
- [ ] 版本 release 和 latest 正文都提醒用户通过 `mayenano-autotheme-latest` 获取最新版
- [ ] latest 正文不含写死的版本号
- [ ] 本机插件目录里的 DLL 与当前 `PLUGIN_VERSION` 一致，设置窗口显示正确版本号
- [ ] 工作区干净

## 常见问题

- **`gh` 命令报认证/网络错误**：`gh.exe` 版本 2.105.0，git 已配置 github.com 代理；GitHub release / raw 下载可走加速器
  `https://github.allproxy.dpdns.org/<完整原始 https URL>`，API 和 git clone 仍用正常地址。
- **`CreateEntryFromFile` 报方法不存在**：PowerShell 调不了该扩展方法，用上面的 `Compress-Archive` 方案。
- **`FindWindowEx` 找不到设置窗口**：用 C# 的 `EnumWindows` 回调按 class `MN_AutoTheme_Settings` 查找，比 PowerShell 脚本块回调可靠。
- **构建失败**：先检查 `scripts\build.bat` 里的工具链路径是否仍存在。
