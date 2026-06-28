# encoding: utf-8
param(
    [string]$Project = "",
    [switch]$Fix
)

$OutputEncoding = [System.Text.UTF8Encoding]::new()
[System.Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()

$root = Split-Path -Parent $PSScriptRoot
$proj = if ($Project) { Join-Path $root $Project } else { $root }

Write-Host "=== 格式检查 ===" -ForegroundColor Cyan
if ($Fix) {
    dotnet format $proj
} else {
    dotnet format --verify-no-changes $proj
}
if ($LASTEXITCODE -ne 0) { exit 1 }

Write-Host "=== 代码风格检查 ===" -ForegroundColor Cyan
if ($Fix) {
    dotnet format style $proj
} else {
    dotnet format style --verify-no-changes $proj
}
if ($LASTEXITCODE -ne 0) { exit 1 }

Write-Host "=== 分析器检查 ===" -ForegroundColor Cyan
if ($Fix) {
    dotnet format analyzers $proj
} else {
    dotnet format analyzers --verify-no-changes $proj
}
if ($LASTEXITCODE -ne 0) { exit 1 }

Write-Host "=== 编译（警告即错误）===" -ForegroundColor Cyan
dotnet build $proj -warnaserror
if ($LASTEXITCODE -ne 0) { exit 1 }

Write-Host "✅ 所有检查通过" -ForegroundColor Green
