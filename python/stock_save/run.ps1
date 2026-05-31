#!/usr/bin/env pwsh

$SERVICE = "stock-save"
$COMPOSE_FILE = "docker-compose.yml"

function Info  { Write-Host "[INFO] $args" -ForegroundColor Cyan }
function Warn  { Write-Host "[WARN] $args" -ForegroundColor Yellow }
function Error { Write-Host "[ERR] $args" -ForegroundColor Red }

function IsRunning {
    $ps = docker compose -f $COMPOSE_FILE ps --services --filter "status=running" 2>$null
    return $ps -match $SERVICE
}

function HasImage {
    $id = docker compose -f $COMPOSE_FILE images -q $SERVICE 2>$null
    return [bool]$id
}

function Read-YesNo {
    param([string]$Prompt)
    while ($true) {
        $ans = Read-Host "$Prompt [y/N]"
        if ($ans -match '^(y|yes)$') { return $true }
        if ($ans -match '^(n|no|)$') { return $false }
    }
}

function Read-Choice {
    param([string]$Prompt)
    while ($true) {
        $ans = Read-Host "$Prompt (r/s/n)"
        switch -Regex ($ans) {
            '^r$' { return 'restart' }
            '^s$' { return 'stop' }
            '^n$' { return 'noop' }
        }
    }
}

Set-Location -LiteralPath $PSScriptRoot

if (-not (HasImage)) {
    Info "未检测到镜像，直接编译并启动..."
    docker compose -f $COMPOSE_FILE build --no-cache
    docker compose -f $COMPOSE_FILE up -d
} elseif (Read-YesNo "是否重新编译镜像?") {
    if (IsRunning) {
        Info "Compose 正在运行，先停止..."
        docker compose -f $COMPOSE_FILE down
    }
    Info "开始编译镜像..."
    docker compose -f $COMPOSE_FILE build --no-cache
    Info "启动 Compose..."
    docker compose -f $COMPOSE_FILE up -d
} else {
    if (IsRunning) {
        Warn "Compose 当前处于运行状态"
        $choice = Read-Choice "请选择操作: (r)重启 / (s)停止 / (n)无操作"
        switch ($choice) {
            'restart' {
                Info "重启 Compose..."
                docker compose -f $COMPOSE_FILE restart
            }
            'stop' {
                Info "停止 Compose..."
                docker compose -f $COMPOSE_FILE down
            }
            'noop' {
                Info "无操作"
            }
        }
    } else {
        Info "Compose 未运行，启动中..."
        docker compose -f $COMPOSE_FILE up -d
    }
}
