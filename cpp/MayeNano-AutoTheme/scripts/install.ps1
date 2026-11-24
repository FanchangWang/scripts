<#
.SYNOPSIS
  把编译好的 Auto Theme 插件安装到 Maye Nano。
.DESCRIPTION
  1) 退出 Maye Nano 后运行；2) 复制 DLL / 配置 / restart.ps1；
  3) 把插件 ID 写入 cfg.json 的 plugin 数组。
#>
param(
    [string]$TargetDir = 'C:\Users\guyue\AppData\Local\Programs\MayeNano'
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$pluginId = 'mn.cc.arae.autotheme'

if (Get-Process -Name 'Maye Nano' -ErrorAction SilentlyContinue) {
    throw 'Maye Nano 仍在运行，请先完全退出再安装。'
}
if (-not (Test-Path -LiteralPath (Join-Path $TargetDir 'Maye Nano.exe'))) {
    throw "目标目录没有 Maye Nano.exe：$TargetDir"
}

$dst = Join-Path $TargetDir "plugin\$pluginId"
New-Item -ItemType Directory -Path $dst -Force | Out-Null

foreach ($f in @(
    @{ From = 'dist\mn.cc.arae.autotheme.dll'; To = "$pluginId.dll" },
    @{ From = 'config\autotheme.ini';           To = 'autotheme.ini' }
)) {
    Copy-Item -LiteralPath (Join-Path $project $f.From) -Destination (Join-Path $dst $f.To) -Force
}

# Old versions had a restart helper; it is no longer used.
Remove-Item -LiteralPath (Join-Path $dst 'restart.ps1') -ErrorAction SilentlyContinue

$cfgPath = Join-Path $TargetDir 'cfg.json'
$cfg = Get-Content -LiteralPath $cfgPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($cfg.plugin -notcontains $pluginId) {
    $cfg.plugin = @($cfg.plugin) + $pluginId
    $cfg | ConvertTo-Json -Depth 20 -Compress | Set-Content -LiteralPath $cfgPath -Encoding UTF8
}

Write-Host "已安装到 $dst"
Write-Host "已在 cfg.json 中启用插件：$pluginId"
