# encoding: utf-8
param(
    [string]$Project = ""
)

$OutputEncoding = [System.Text.UTF8Encoding]::new()
[System.Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()

$root = Split-Path -Parent $PSScriptRoot
$proj = if ($Project) { Join-Path $root $Project } else { $root }

Write-Host "=== 格式化代码 ===" -ForegroundColor Cyan
dotnet format $proj
Write-Host "=== 格式化代码风格 ===" -ForegroundColor Cyan
dotnet format style $proj
Write-Host "=== 格式化分析器 ===" -ForegroundColor Cyan
dotnet format analyzers $proj

Write-Host "✅ 格式化完成" -ForegroundColor Green
