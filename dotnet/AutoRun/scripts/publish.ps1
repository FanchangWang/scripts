# encoding: utf-8
param(
    [ValidateSet("Debug", "Release")]
    [string]$Configuration = "Release",
    [switch]$SkipCheck,
    [switch]$SkipPublish
)

$OutputEncoding = [System.Text.UTF8Encoding]::new()
[System.Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root "publish"

$managerProj = Join-Path $root "src\AutoRunManager\AutoRunManager.csproj"
$schedulerProj = Join-Path $root "src\AutoRunScheduler\AutoRunScheduler.csproj"

$modes = @(
    @{ Suffix = "" },
    @{ Suffix = "-selfcontained" },
    @{ Suffix = "-selfcontained-singlefile" }
)

if (-not $SkipCheck) {
    & "$PSScriptRoot\check.ps1"
    if ($LASTEXITCODE -ne 0) { exit 1 }
}

function Publish-Project {
    param(
        [string]$Project,
        [string]$Name,
        [string]$Runtime,
        [switch]$SelfContained,
        [switch]$SingleFile
    )

    $rid = $Runtime
    $label = "$Name-$rid"
    $extra = @()

    if ($SelfContained) {
        $label += "-selfcontained"
        $extra += "--self-contained"
    }
    else {
        $extra += "--no-self-contained"
    }

    if ($SingleFile) {
        $label += "-singlefile"
        $extra += "-p:PublishSingleFile=true"
    }

    $extra += "-p:SatelliteResourceLanguages=zh-Hans"

    $publishDir = Join-Path $outDir $label
    Write-Host "--- 发布: $label ---" -ForegroundColor Yellow

    $dotnetArgs = @(
        "publish", $Project,
        "-c", $Configuration,
        "-r", $rid,
        "-o", $publishDir
    ) + $extra

    & "dotnet" $dotnetArgs 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "❌ 发布失败: $label" -ForegroundColor Red
        exit 1
    }
    Write-Host "✅ 发布成功: $label -> $publishDir" -ForegroundColor Green
}

if (-not $SkipPublish) {
    # 清空整个 publish 目录
    if (Test-Path $outDir) {
        Remove-Item "$outDir\*" -Recurse -Force
    }

    # ---------- 分别发布 ----------
    $archs = @("win-x64", "win-x86", "win-arm64")

    foreach ($arch in $archs) {
        Publish-Project -Project $managerProj -Name "Manager" -Runtime $arch
        Publish-Project -Project $managerProj -Name "Manager" -Runtime $arch -SelfContained
        Publish-Project -Project $managerProj -Name "Manager" -Runtime $arch -SelfContained -SingleFile
    }

    foreach ($arch in $archs) {
        Publish-Project -Project $schedulerProj -Name "Scheduler" -Runtime $arch
        Publish-Project -Project $schedulerProj -Name "Scheduler" -Runtime $arch -SelfContained
        Publish-Project -Project $schedulerProj -Name "Scheduler" -Runtime $arch -SelfContained -SingleFile
    }

    # ---------- 合并包（Manager + Scheduler） ----------
    foreach ($arch in $archs) {
        foreach ($mode in $modes) {
            $label = "AutoRun-$arch$($mode.Suffix)"
            $combinedDir = Join-Path $outDir $label
            New-Item -ItemType Directory -Path $combinedDir -Force | Out-Null

            $managerDir = Join-Path $outDir "Manager-$arch$($mode.Suffix)"
            $schedulerDir = Join-Path $outDir "Scheduler-$arch$($mode.Suffix)"

            Write-Host "--- 合并包: $label ---" -ForegroundColor Yellow
            Copy-Item "$managerDir\*" $combinedDir -Recurse -Force
            Copy-Item "$schedulerDir\*" $combinedDir -Recurse -Force
            Write-Host "✅ 合并包: $label -> $combinedDir" -ForegroundColor Green
        }
    }
}

# ---------- Inno Setup 安装包 ----------
$issFile = Join-Path $PSScriptRoot "setup.iss"
$iscc = Get-Command "iscc" -ErrorAction SilentlyContinue
if (-not $iscc) {
    $paths = @(
        "${env:ProgramFiles(x86)}\Inno Setup 6\iscc.exe"
        "${env:ProgramFiles}\Inno Setup 6\iscc.exe"
        "$env:LOCALAPPDATA\Programs\Inno Setup 6\iscc.exe"
    )
    foreach ($p in $paths) {
        if (Test-Path $p) { $iscc = $p; break }
    }
}

if ($iscc) {
    $buildProps = [xml](Get-Content (Join-Path $root "Directory.Build.props"))
    $version = $buildProps.Project.PropertyGroup.Version
    $installerDir = Join-Path $outDir "installers"
    New-Item -ItemType Directory -Path $installerDir -Force | Out-Null

    foreach ($arch in @("win-x64", "win-x86")) {
        foreach ($mode in $modes) {
            $label = "AutoRun-$version-$arch$($mode.Suffix)"
            Write-Host "--- 安装包: $label ---" -ForegroundColor Yellow
            & $iscc $issFile /DMyAppVersion=$version /DSourceDir=$outDir /DArch=$arch /DMyAppSuffix=$($mode.Suffix) /DInstallerDir=$installerDir
            if ($LASTEXITCODE -eq 0) {
                Write-Host "✅ 安装包: $label.exe" -ForegroundColor Green
            }
            else {
                Write-Host "❌ 安装包编译失败" -ForegroundColor Red
            }
        }
    }
}
else {
    Write-Host "`n⚠ Inno Setup 未安装，跳过安装包编译" -ForegroundColor Yellow
    Write-Host "  下载: https://jrsoftware.org/isdl.php" -ForegroundColor Yellow
}

Write-Host "`n🎉 全部发布完成，输出目录: $outDir" -ForegroundColor Green
