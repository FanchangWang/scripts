# ui-web-windows

Windows 桌面 Web 应用 UI 设计 skill（给 AI agent 用）。遵循 Fluent 2 / Windows 11 规范，深浅色主题双轨，WCAG 2.2 AA 对比度，抑制 AI slop 审美。

## 结构

```text
SKILL.md                          入口：触发条件、工作流、硬规则、验证
references/tokens-and-theming.md  两层 token、light-dark()、OKLCH、深色不反转、系统强调色
references/fluent-windows.md      字体/字阶、材质、PWA 标题栏、Web 微规则、组件 API
references/accessibility.md       WCAG 2.2 数值、焦点、键盘、验证清单
references/anti-slop.md           禁用模式、AI 默认审美指纹、两遍法、文案规则
assets/tokens.example.css         可复制的 token 样板
```

## 发布

在 `skill/` 目录执行（见 [../README.md](../README.md)）：

```powershell
pwsh.exe -NoProfile -File ..\publish.ps1 -Skill ui-web-windows
```
