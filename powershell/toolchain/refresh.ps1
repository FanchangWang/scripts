param(
  [string]$Command = 'refresh',
  [string]$OutputDirectory = 'C:\Users\guyue\.agents\skills\local-toolchain'
)

# Thin compatibility wrapper for the scanner/generator.
& (Join-Path $PSScriptRoot 'toolchain.ps1') -Command $Command -OutputDirectory $OutputDirectory
exit $LASTEXITCODE
