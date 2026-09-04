Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Push-Location $PSScriptRoot
try {
    uv run ruff format .
    uv run ruff check .
    uv run ty check .
    Write-Host "`n✅ All checks passed." -ForegroundColor Green
} catch {
    Write-Host "`n❌ Check failed." -ForegroundColor Red
    exit 1
} finally {
    Pop-Location
}
