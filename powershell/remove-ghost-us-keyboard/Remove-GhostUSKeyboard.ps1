# Remove-GhostUSKeyboard.ps1
# Temporarily adds and removes en-US to refresh the Windows user language list.
# Does not edit the registry or intentionally download a language pack.
#
# Audit fixes:
# - Rollback now runs before any error reporting. Under $ErrorActionPreference='Stop',
#   Write-Error inside catch threw and skipped the rest of the block.
# - Backup is JSON and can be reapplied with -RestoreFrom (Import-Clixml returns
#   PSObject, which Set-WinUserLanguageList cannot accept).

[CmdletBinding()]
param(
    # Reapply a previously exported JSON backup instead of running the add/remove cycle.
    [string]$RestoreFrom
)

$ErrorActionPreference = 'Stop'
$originalList = $null
$backupPath = $null

# Export in a reconstructable format.
function Export-LanguageListBackup {
    param($LanguageList, [string]$Path)

    $payload = [ordered]@{
        saved_at = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
        languages = @($LanguageList | ForEach-Object {
            [ordered]@{
                language_tag = $_.LanguageTag
                input_method_tips = @($_.InputMethodTips)
            }
        })
    }
    $payload | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $Path -Encoding UTF8
}

# Rebuild a language list from a JSON backup and apply it.
function Import-LanguageListBackup {
    param([string]$Path)

    $payload = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($null -eq $payload.languages -or @($payload.languages).Count -eq 0) {
        throw "Backup has no languages array: $Path"
    }

    # Get-WinUserLanguageList returns a detached copy; clearing it does not touch the system.
    $list = Get-WinUserLanguageList
    $list.Clear()
    foreach ($lang in $payload.languages) {
        [void]$list.Add([string]$lang.language_tag)
    }
    Set-WinUserLanguageList -LanguageList $list -Force

    # Re-apply per-language input method tips.
    $applied = Get-WinUserLanguageList
    foreach ($lang in $payload.languages) {
        $tips = @($lang.input_method_tips)
        if ($tips.Count -eq 0) { continue }
        $entries = @($applied | Where-Object { $_.LanguageTag -eq [string]$lang.language_tag })
        if ($entries.Count -eq 0) { continue }
        foreach ($tip in $tips) {
            if ($entries[0].InputMethodTips -notcontains [string]$tip) {
                $entries[0].InputMethodTips.Add([string]$tip)
            }
        }
    }
    Set-WinUserLanguageList -LanguageList $applied -Force
}

function Write-Failure {
    param([string]$Message)
    # Write-Warning (not Write-Error): under $ErrorActionPreference='Stop',
    # Write-Error throws and would abort the rest of the catch block.
    Write-Warning "Script did not finish successfully: $Message"
}

if ($PSBoundParameters.ContainsKey('RestoreFrom')) {
    try {
        Import-LanguageListBackup -Path $RestoreFrom
        Write-Host "Restored language list from: $RestoreFrom" -ForegroundColor Green
        Get-WinUserLanguageList | Format-List LanguageTag, InputMethodTips
    }
    catch {
        Write-Failure -Message $_.Exception.Message
        exit 1
    }
    return
}

try {
    $originalList = Get-WinUserLanguageList

    # Avoid deleting an English (United States) entry the user intentionally configured.
    $existingEnglish = @($originalList | Where-Object { $_.LanguageTag -eq 'en-US' })
    if ($existingEnglish.Count -gt 0) {
        Write-Warning "The current language list already contains en-US. No changes were made, to avoid removing an intentionally configured language."
        Write-Host "Current languages: $($originalList.LanguageTag -join ', ')"
        return
    }

    # Save a reconstructable backup under the script's backups/ dir (git-ignored).
    $outputDir = Join-Path $PSScriptRoot 'backups'
    if (-not (Test-Path -LiteralPath $outputDir)) {
        New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
    }
    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $backupPath = Join-Path $outputDir "language-list-backup-$timestamp.json"
    Export-LanguageListBackup -LanguageList $originalList -Path $backupPath
    Write-Host "Backup saved to: $backupPath"

    # Add en-US, then immediately remove it, matching the steps that fixed the issue.
    $workingList = Get-WinUserLanguageList
    $workingList.Add('en-US')
    Set-WinUserLanguageList -LanguageList $workingList -Force

    $workingList = Get-WinUserLanguageList
    $englishEntry = @($workingList | Where-Object { $_.LanguageTag -eq 'en-US' })

    if ($englishEntry.Count -eq 0) {
        throw "en-US was not found after the add step; stopping rather than making further changes."
    }

    foreach ($entry in $englishEntry) {
        [void]$workingList.Remove($entry)
    }

    Set-WinUserLanguageList -LanguageList $workingList -Force

    # Verify that en-US is gone.
    $finalList = Get-WinUserLanguageList
    $remainingEnglish = @($finalList | Where-Object { $_.LanguageTag -eq 'en-US' })

    if ($remainingEnglish.Count -gt 0) {
        Write-Warning "The language list still contains en-US. Review the output below."
        $finalList | Format-List LanguageTag, InputMethodTips
        return
    }

    Write-Host ""
    Write-Host "Done: en-US was added and removed. Current language list:" -ForegroundColor Green
    $finalList | Format-List LanguageTag, InputMethodTips
    Write-Host "If the US keyboard still appears in Win+Space, sign out and back in or restart Windows."
    Write-Host "Windows may display a warning about the display language taking effect after the next sign-in; that warning is expected."
}
catch {
    # Roll back FIRST, then report. Reporting via Write-Error under $ErrorActionPreference='Stop'
    # would throw here and skip everything below, leaving the list half-modified.
    $message = $_.Exception.Message
    $restoreError = $null
    $restored = $false
    if ($null -ne $originalList) {
        try {
            Set-WinUserLanguageList -LanguageList $originalList -Force -ErrorAction Stop
            $restored = $true
        }
        catch {
            $restoreError = $_.Exception.Message
        }
    }

    if ($restored) {
        Write-Warning "Script failed: $message. The original language list was restored."
    }
    else {
        Write-Warning "Script failed: $message. Automatic restoration failed or was not attempted: $restoreError"
        if ($backupPath) {
            Write-Warning "Recover with: pwsh.exe -NoProfile -File $PSCommandPath -RestoreFrom '$backupPath'"
        }
    }
    Write-Host "No registry keys were changed."
    exit 1
}
