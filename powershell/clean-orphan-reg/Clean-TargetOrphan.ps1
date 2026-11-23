<#
定向清理HiBit生成的空镜像注册表
仅删除：VSCode.* / vscode / zcode / QQMusic.* 空残骸
跳过AppX开头以及其他系统项，不破坏UWP应用

审计修复：
- 增加管理员权限预检（删除 HKLM 项必需）
- 删除前重新校验条件，防止确认期间的竞态误删
- 改用 -LiteralPath，避免键名中的通配符字符被误解析
- 日志改存到脚本目录下的 logs/（已 git 忽略），不再落桌面
- 扫描错误不再被 SilentlyContinue 吞掉
- 日志记录被跳过的项及原因
#>

$hklmRoot = "HKLM:\SOFTWARE\Classes"
$hkcuRoot = "HKCU:\Software\Classes"
$outputDir = Join-Path $PSScriptRoot 'logs'
if (-not (Test-Path -LiteralPath $outputDir)) {
    New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
}
$logFile = Join-Path $outputDir "Clean_Orphan_Reg_$(Get-Date -Format 'yyyyMMdd_HHmmss').log"

$candidatesPattern = '^(VSCode\.|vscode$|zcode$|QQMusic\.)'
$willDelete = @()   # [pscustomobject]@{ Path; Name }
$deleted = @()
$failed = @()
$skipped = @()      # 扫描阶段 + 删除前复核阶段被排除的项

$logContent = @()
$logContent += "=== 定向空注册表清理日志 ==="
$logContent += "时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
$logContent += "匹配规则：前缀 VSCode. / vscode / zcode / QQMusic."
$logContent += "条件：HKLM存在、HKCU不存在、HKLM该项完全为空(无子键无值)`n"

# 管理员权限预检
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    $msg = "[错误] 删除 HKLM 注册表项需要管理员权限，请以管理员身份重新运行 pwsh.exe。"
    Write-Host $msg -ForegroundColor Red
    $logContent += $msg
    $logContent | Out-File -LiteralPath $logFile -Encoding UTF8
    exit 1
}

Write-Host "`n[1] 扫描候选项..." -ForegroundColor Cyan

try {
    $hklmItems = Get-ChildItem -LiteralPath $hklmRoot -ErrorAction Stop
}
catch {
    $msg = "[错误] 无法读取 $hklmRoot : $($_.Exception.Message)"
    Write-Host $msg -ForegroundColor Red
    $logContent += $msg
    $logContent | Out-File -LiteralPath $logFile -Encoding UTF8
    exit 1
}

$logContent += "HKLM 读取成功，共 $($hklmItems.Count) 个子键。"

foreach ($item in $hklmItems) {
    $name = $item.PSChildName
    if ($name -notmatch $candidatesPattern) { continue }

    $hkcuPath = Join-Path $hkcuRoot $name
    if (Test-Path -LiteralPath $hkcuPath) {
        $skipped += "$name | HKCU 存在同名项"
        continue
    }

    $hasSubKeys = $item.GetSubKeyNames().Count -gt 0
    $hasValues = $item.GetValueNames().Count -gt 0
    if ($hasSubKeys -or $hasValues) {
        if ($hasSubKeys -and $hasValues) { $reason = 'HKLM 项含子键和值' }
        elseif ($hasSubKeys) { $reason = 'HKLM 项含子键' }
        else { $reason = 'HKLM 项含值' }
        $skipped += "$name | $reason"
        continue
    }

    $willDelete += [pscustomobject]@{ Path = $item.PSPath; Name = $name }
}

$logContent += "名称匹配但被跳过：$($skipped.Count) 项"
foreach ($s in $skipped) { $logContent += "  $s" }

if ($willDelete.Count -eq 0) {
    Write-Host "没有找到符合条件的待删除项。`n" -ForegroundColor Green
    $logContent += "扫描完成：0 个待删除项。"
    $logContent | Out-File -LiteralPath $logFile -Encoding UTF8
    Write-Host "日志保存至：$logFile"
    exit 0
}

Write-Host "`n将删除共 $($willDelete.Count) 个项：" -ForegroundColor Yellow
$logContent += "待删除列表（$($willDelete.Count)项）:"
foreach ($entry in $willDelete) {
    Write-Host "  $($entry.Path)"
    $logContent += "  $($entry.Path)"
}

Read-Host "`n按回车键开始删除；关闭窗口直接放弃执行"

Write-Host "`n[2] 开始删除（逐项重新校验）..." -ForegroundColor Cyan
foreach ($entry in $willDelete) {
    $p = $entry.Path
    $name = $entry.Name
    $hkcuPath = Join-Path $hkcuRoot $name

    # 重新校验：确认期间其他程序可能重建了同名键或写入了内容
    if (Test-Path -LiteralPath $hkcuPath) {
        Write-Warning "[SKIP] $name : 扫描后 HKCU 出现同名项"
        $skipped += "$name | 删除前复核：HKCU 同名项已出现"
        continue
    }
    $key = Get-Item -LiteralPath $p -ErrorAction SilentlyContinue
    if ($null -eq $key) {
        Write-Warning "[SKIP] $name : 扫描后该项已不存在"
        $skipped += "$name | 删除前复核：项已不存在"
        continue
    }
    if ($key.GetSubKeyNames().Count -gt 0 -or $key.GetValueNames().Count -gt 0) {
        Write-Warning "[SKIP] $name : 扫描后该项已非空"
        $skipped += "$name | 删除前复核：项已非空"
        continue
    }

    try {
        Remove-Item -LiteralPath $p -Recurse -Force -ErrorAction Stop
        Write-Host "[OK] $p" -ForegroundColor Green
        $deleted += $p
    }
    catch {
        Write-Warning "[FAIL] $p : $_"
        $failed += "$p | $($_.Exception.Message)"
    }
}

$logContent += "`n成功删除 $($deleted.Count) 项:"
foreach ($d in $deleted) { $logContent += "  $d" }
$logContent += "`n失败 $($failed.Count) 项:"
if ($failed.Count -gt 0) {
    foreach ($f in $failed) { $logContent += "  $f" }
}
else {
    $logContent += "  无"
}
$logContent += "`n跳过（含扫描阶段与删除前复核）$($skipped.Count) 项:"
if ($skipped.Count > 0) {
    foreach ($s in $skipped) { $logContent += "  $s" }
}
else {
    $logContent += "  无"
}
$logContent += "`n=== 结束 ==="
$logContent | Out-File -LiteralPath $logFile -Encoding UTF8

Write-Host "`n[完成] 成功:$($deleted.Count)  失败:$($failed.Count)  跳过:$($skipped.Count)" -ForegroundColor Cyan
Write-Host "日志文件：$logFile`n"
Write-Host "建议执行下面命令重启资源管理器生效："
Write-Host "Stop-Process -Name explorer -Force`n"
