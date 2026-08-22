#requires -Version 7
<#
fix_crlf.ps1 — 监听 git 变更文件，将 CRLF 换行符转换为 LF

用法:
  ./fix_crlf.ps1                              单次扫描当前变更文件并修复
  ./fix_crlf.ps1 -Watch                       持续监听（默认 2 秒轮询一次，Ctrl+C 退出）
  ./fix_crlf.ps1 -Watch -IntervalSeconds 5
#>
param(
    [switch]$Watch,
    [int]$IntervalSeconds = 2
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$AllowedExt = @(".py", ".md", ".html", ".js", ".css", ".ps1")
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path

function Get-ChangedFiles {
    # 解析 git status --porcelain（路径相对仓库根），返回绝对路径列表
    $out = & git -c core.quotepath=false status --porcelain 2>$null
    if ($LASTEXITCODE -ne 0) { throw "git status 执行失败，请确认脚本位于 git 仓库内" }
    $top = (& git rev-parse --show-toplevel).Trim().Replace("/", "\")
    $files = [System.Collections.Generic.List[string]]::new()
    foreach ($line in $out) {
        if ($line.Length -lt 4) { continue }
        $status = $line.Substring(0, 2)
        if ($status.Contains("D")) { continue }                            # 跳过已删除文件
        $path = $line.Substring(3)
        if ($path.Contains(" -> ")) { $path = ($path -split " -> ")[-1] }  # 重命名取新路径
        $files.Add((Join-Path $top $path.Trim('"')))
    }
    return $files
}

function Convert-CrlfToLf {
    # 字节级替换 CRLF -> LF，不改编码与 BOM；未检测到 CRLF 则不写盘
    param([string]$Path)
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    $hasCrlf = $false
    for ($i = 0; $i -lt $bytes.Length - 1; $i++) {
        if ($bytes[$i] -eq 13 -and $bytes[$i + 1] -eq 10) { $hasCrlf = $true; break }
    }
    if (-not $hasCrlf) { return $false }
    $ms = [System.IO.MemoryStream]::new($bytes.Length)
    for ($i = 0; $i -lt $bytes.Length; $i++) {
        if ($bytes[$i] -eq 13 -and $i + 1 -lt $bytes.Length -and $bytes[$i + 1] -eq 10) { continue }
        $ms.WriteByte($bytes[$i])
    }
    [System.IO.File]::WriteAllBytes($Path, $ms.ToArray())
    return $true
}

Set-Location -LiteralPath $Root
Write-Host "仓库: $Root"
Write-Host "监听后缀: $($AllowedExt -join ' ')$(if ($Watch) { " | 轮询间隔: ${IntervalSeconds}s" })"

while ($true) {
    $fixed = 0
    foreach ($rel in Get-ChangedFiles) {
        $ext = [System.IO.Path]::GetExtension($rel).ToLowerInvariant()
        if ($AllowedExt -notcontains $ext) { continue }
        if (-not (Test-Path -LiteralPath $rel -PathType Leaf)) { continue }
        try {
            if (Convert-CrlfToLf -Path $rel) {
                Write-Host "[CRLF -> LF] $rel"
                $fixed++
            }
        } catch {
            Write-Warning "处理失败: $rel — $_"
        }
    }
    if (-not $Watch) {
        if ($fixed -gt 0) { Write-Host "共转换 $fixed 个文件" } else { Write-Host "无需转换" }
        break
    }
    Start-Sleep -Seconds $IntervalSeconds
}
