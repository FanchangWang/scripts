# encoding: utf-8
param(
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Release",
    [switch]$SkipCheck
)

$OutputEncoding = [System.Text.UTF8Encoding]::new()
[System.Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

if (-not $SkipCheck) {
    & "$PSScriptRoot\check.ps1"
    if ($LASTEXITCODE -ne 0) { exit 1 }
}

Write-Host "=== 编译 ($Configuration) ===" -ForegroundColor Cyan
dotnet build "$root\AutoRunManager.slnx" -c $Configuration --no-restore
if ($LASTEXITCODE -ne 0) { exit 1 }

Write-Host "✅ 编译完成" -ForegroundColor Green
