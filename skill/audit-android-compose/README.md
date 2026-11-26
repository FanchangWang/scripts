# audit-android-compose

Android Kotlin / Compose 项目代码审计 skill（给 AI agent 用）。证据驱动、全工程覆盖，按最新 Google Android / Material 3 规范检查，产出可追溯的审计报告。

由旧 `android-compose-code-audit` 整理而来：完整保留 12 个域检查清单、四种审计工作流和六类报告模板；去掉环境特定的安装说明与厂商命令，补充 2025–2026 官方规范链接和过时 API 清单。

## 结构

```text
SKILL.md                                  入口：铁律、阶段、判据、交付物
checklists/01..12-*.md                    12 个审计域
workflows/full-audit.md                   全审计阶段提示词
workflows/targeted-audit.md               定向审计
workflows/regression-audit.md             回归审计
workflows/security-audit.md               安全审计
templates/project-inventory.md            项目盘点模板
templates/tracker.md                      审计跟踪表
templates/finding.md                      单个 finding 模板
templates/domain-report.md                域报告
templates/regression-report.md            回归报告
templates/final-report.md                 最终报告
references/audit-phases.md                Phase 0–7 详细执行细则
references/official-references.md         官方规范与过时 API 清单
```

## 发布

```powershell
pwsh.exe -NoProfile -File ..\publish.ps1 -Skill audit-android-compose
```
