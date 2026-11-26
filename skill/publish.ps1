<#
.SYNOPSIS
    把 skill/ 下的 skill 源码发布到 agent 的 skills 目录（默认 ~/.agents/skills）。

.DESCRIPTION
    读取同目录 manifest.txt：
      - [skills]  逐个镜像复制到目标目录（覆盖同名目录）
      - [retired] 从目标目录删除旧 skill（可用 -NoRemoveLegacy 关闭）
    发布前校验目录名格式、SKILL.md 存在性、frontmatter name 与目录名一致。

    必须用 PowerShell 7：pwsh.exe -NoProfile -File publish.ps1

.EXAMPLE
    pwsh.exe -NoProfile -File .\publish.ps1
    pwsh.exe -NoProfile -File .\publish.ps1 -DryRun
    pwsh.exe -NoProfile -File .\publish.ps1 -Skill ui-android-compose
    pwsh.exe -NoProfile -File .\publish.ps1 -SkillsDir C:\tmp\skills -NoRemoveLegacy
    pwsh.exe -NoProfile -File .\publish.ps1 -List
#>
[CmdletBinding()]
param(
    [string]$SkillsDir,
    [string[]]$Skill,
    [switch]$List,
    [switch]$DryRun,
    [switch]$NoRemoveLegacy
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$manifestPath = Join-Path $scriptDir 'manifest.txt'
if ([string]::IsNullOrWhiteSpace($SkillsDir)) {
    $SkillsDir = Join-Path $HOME '.agents/skills'
}
$SkillsDir = [System.IO.Path]::GetFullPath($SkillsDir)

function Read-Manifest {
    param([Parameter(Mandatory)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "找不到 manifest：$Path"
    }

    $skills = [System.Collections.Generic.List[string]]::new()
    $retired = [System.Collections.Generic.List[string]]::new()
    $section = ''

    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        $text = $line.Trim()
        if ([string]::IsNullOrEmpty($text) -or $text.StartsWith('#')) { continue }
        if ($text -match '^\[(.+)\]$') {
            $section = $Matches[1].ToLowerInvariant()
            continue
        }
        switch ($section) {
            'skills'  { $skills.Add($text) }
            'retired' { $retired.Add($text) }
        }
    }

    return @{
        Skills  = $skills.ToArray()
        Retired = $retired.ToArray()
    }
}

function Assert-DirectoryName {
    param([Parameter(Mandatory)][string]$Name)

    # 3~4 段：类别-作用大类-作用小类[-补充]
    if ($Name -notmatch '^[a-z][a-z0-9]*(?:-[a-z0-9]+){2,3}$') {
        throw "目录名不符合命名格式（类别-作用大类-作用小类[-补充]）：$Name"
    }
}

function Get-FrontmatterName {
    param([Parameter(Mandatory)][string]$Path)

    $lines = [System.IO.File]::ReadAllLines($Path)
    if ($lines.Count -lt 2 -or $lines[0].Trim() -ne '---') {
        throw "SKILL.md 缺少 YAML frontmatter：$Path"
    }

    $limit = [Math]::Min($lines.Count, 21)
    for ($i = 1; $i -lt $limit; $i++) {
        $line = $lines[$i].Trim()
        if ($line -eq '---') { break }
        if ($line -match '^name:\s*(.+?)\s*$') {
            return $Matches[1].Trim("'", '"')
        }
    }
    throw "SKILL.md frontmatter 缺少 name 字段：$Path"
}

$manifest = Read-Manifest -Path $manifestPath

if ($List) {
    Write-Host '将发布（manifest [skills]）：'
    foreach ($name in $manifest.Skills) { Write-Host "  - $name" }
    Write-Host '发布时移除（manifest [retired]）：'
    foreach ($name in $manifest.Retired) { Write-Host "  - $name" }
    Write-Host "目标目录：$SkillsDir"
    exit 0
}

if ($manifest.Skills.Count -eq 0) {
    throw "manifest 中没有 [skills] 条目：$manifestPath"
}

# 去重
$seen = [System.Collections.Generic.HashSet[string]]::new()
$allSkills = [System.Collections.Generic.List[string]]::new()
foreach ($name in $manifest.Skills) {
    if (-not $seen.Add($name)) { throw "manifest [skills] 重复条目：$name" }
    $allSkills.Add($name)
}

# 选择要发布的 skill
$toPublish = [System.Collections.Generic.List[string]]::new()
if ($null -eq $Skill -or $Skill.Count -eq 0) {
    foreach ($name in $allSkills) { $toPublish.Add($name) }
} else {
    foreach ($name in $Skill) {
        if ($allSkills -notcontains $name) {
            throw "未知 skill：$name（manifest 中的可选项：$($allSkills -join ', ')）"
        }
        if ($toPublish -notcontains $name) { $toPublish.Add($name) }
    }
}

# 校验源
foreach ($name in $toPublish) {
    Assert-DirectoryName -Name $name
    $src = Join-Path $scriptDir $name
    if (-not (Test-Path -LiteralPath $src -PathType Container)) { throw "找不到 skill 目录：$src" }

    $skillMd = Join-Path $src 'SKILL.md'
    if (-not (Test-Path -LiteralPath $skillMd -PathType Leaf)) { throw "找不到 $skillMd" }

    $frontName = Get-FrontmatterName -Path $skillMd
    if ($frontName -ne $name) {
        throw "SKILL.md frontmatter name ($frontName) 与目录名 ($name) 不一致：$skillMd"
    }
}

$retiredToRemove = [System.Collections.Generic.List[string]]::new()
if (-not $NoRemoveLegacy) {
    foreach ($name in $manifest.Retired) {
        # 旧 skill 用宽松格式（至少 2 段），不套用新命名规则
        if ($name -notmatch '^[a-z][a-z0-9]*(?:-[a-z0-9]+)+$') {
            throw "retired 目录名不合法：$name"
        }
        # 安全：绝不删除本次要发布的 skill，即使误写进 retired
        if ($toPublish -contains $name) { continue }
        if ($seen.Contains($name)) { continue }
        $retiredToRemove.Add($name)
    }
}

Write-Host "目标目录：$SkillsDir"
Write-Host "发布：$($toPublish.Count) 个；移除旧 skill：$($retiredToRemove.Count) 个"
if ($DryRun) { Write-Host '[DRY-RUN] 以下操作不会实际执行' }

foreach ($name in $toPublish) {
    $src = Join-Path $scriptDir $name
    $dest = Join-Path $SkillsDir $name
    if ($DryRun) {
        Write-Host "[DRY-RUN] 发布 $name -> $dest"
        continue
    }
    if (Test-Path -LiteralPath $dest) {
        Remove-Item -LiteralPath $dest -Recurse -Force
    }
    Copy-Item -LiteralPath $src -Destination $dest -Recurse -Force
    Write-Host "[OK] 发布 $name"
}

foreach ($name in $retiredToRemove) {
    $dest = Join-Path $SkillsDir $name
    if (-not (Test-Path -LiteralPath $dest)) {
        if (-not $DryRun) { Write-Host "[SKIP] 旧 skill 不存在：$name" }
        continue
    }
    if ($DryRun) {
        Write-Host "[DRY-RUN] 移除旧 skill $dest"
        continue
    }
    Remove-Item -LiteralPath $dest -Recurse -Force
    Write-Host "[OK] 移除旧 skill $name"
}

Write-Host '完成。'
