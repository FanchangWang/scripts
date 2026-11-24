param(
    [int]$ProcId,
    [string]$Cfg,
    [string]$Exe,
    [string]$Theme,
    [int]$Delay = 900
)

$logFile = Join-Path -Path (Split-Path -Parent $MyInvocation.MyCommand.Path) -ChildPath 'restart.log'
function Log($m) {
    $line = "$(Get-Date -Format 'HH:mm:ss.fff') $m"
    Add-Content -LiteralPath $logFile -Value $line -ErrorAction SilentlyContinue
}

Log "helper started ProcId=$ProcId Cfg=$Cfg Exe=$Exe Theme=$Theme Delay=$Delay"

# Give the plugin time to finish writing the config, then make sure the old
# instance is really gone. Maye Nano has no remote "reload theme" command, so
# a fresh process is required for the new theme to be picked up.
Start-Sleep -Milliseconds $Delay
$old = Get-Process -Id $ProcId -ErrorAction SilentlyContinue
if ($old) {
    try {
        Stop-Process -Id $ProcId -Force -ErrorAction Stop
        Log "old process force-stopped"
    } catch {
        Log "stop failed: $($_.Exception.Message)"
    }
}

$waited = 0
while ($waited -lt 5000) {
    if (-not (Get-Process -Id $ProcId -ErrorAction SilentlyContinue)) { break }
    Start-Sleep -Milliseconds 100
    $waited += 100
}
Log "old process gone=$(-not (Get-Process -Id $ProcId -ErrorAction SilentlyContinue)) waited=$waited"

# Make sure the theme value is correct even if the app rewrote cfg.json on exit.
if (Test-Path -LiteralPath $Cfg) {
    try {
        $text = [System.IO.File]::ReadAllText($Cfg)
        $text = [regex]::Replace(
            $text,
            '("theme"\s*:\s*)"[^"]*"',
            [System.Text.RegularExpressions.MatchEvaluator]{
                param($m)
                return $m.Groups[1].Value + '"' + $Theme + '"'
            }
        )
        [System.IO.File]::WriteAllText($Cfg, $text)
        Log "cfg theme rewritten to $Theme"
    } catch {
        Log "cfg rewrite failed: $($_.Exception.Message)"
    }
} else {
    Log "cfg missing: $Cfg"
}

try {
    $workDir = Split-Path -Parent $Exe
    $p = Start-Process -FilePath $Exe -WorkingDirectory $workDir -PassThru
    Log "relaunched pid=$($p.Id)"
} catch {
    Log "relaunch failed: $($_.Exception.Message)"
}
