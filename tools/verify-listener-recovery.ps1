[CmdletBinding()]
<#
.SYNOPSIS
Exercises real process termination and foreground listener recovery in the existing synthetic emulator.
.DESCRIPTION
Emulator and Debug-package guards precede mutation. Keeps database and grants unchanged.
Cancels/reposts only the existing controlled test notification; never instruments the main app.
Outputs counts and timing only. Failure is nonzero; a pass is not proof of OEM compatibility.
.EXAMPLE
.\tools\verify-listener-recovery.ps1 -Mode ForceStop
#>
param(
    [ValidateSet('ForceStop', 'StopApp', 'Sigkill')]
    [string]$Mode = 'ForceStop',
    [ValidatePattern('^emulator-\d+$')]
    [string]$Serial = 'emulator-5558',
    [ValidateRange(1, 5)]
    [int]$Rounds = 1
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$projectRoot = Split-Path -Parent $PSScriptRoot
$sdkLine = Get-Content (Join-Path $projectRoot 'local.properties') -Encoding UTF8 |
    Where-Object { $_.StartsWith('sdk.dir=') } | Select-Object -First 1
if (-not $sdkLine) { throw 'Missing local SDK configuration' }
$sdkRoot = $sdkLine.Substring(8).Trim().Replace('\:', ':').Replace('\\', '\')
$adb = Join-Path $sdkRoot 'platform-tools/adb.exe'
$package = 'io.github.prince_dulb.ohmynotification'

function Invoke-Device([string[]]$Arguments) {
    $result = & $adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB operation failed: $($Arguments[0])" }
    return ($result -join "`n")
}
function Read-Stats {
    return (& (Join-Path $PSScriptRoot 'query-mvp-database-stats.ps1') -Serial $Serial | ConvertFrom-Json)
}
function Start-Inbox {
    $result = Invoke-Device @('shell', 'am', 'start', '-W', '-n', "$package/.MainActivity")
    if ($result -notmatch 'Status: ok') { throw 'MainActivity did not start' }
}
function Wait-Connected {
    $clock = [Diagnostics.Stopwatch]::StartNew()
    do {
        $stats = Read-Stats
        if ($stats.listenerConnected -and $stats.listenerProcessingOperational) { return $clock.ElapsedMilliseconds }
        Start-Sleep -Milliseconds 250
    } while ($clock.ElapsedMilliseconds -lt 15000)
    throw 'Listener or processing still unavailable 15 seconds after foreground launch'
}

function Read-ControlledSystemPostTime {
    # Fixed synthetic key. Quote it for Android's shell so its pipe characters stay literal.
    $record = (& $adb -s $Serial shell "cmd notification get '0|io.github.prince_dulb.ohmynotification.test|5197134|OMN_TEST_ONLY_NOTIFICATION|10151'" 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) {
        if ($record -match '^error: no active notification matching key:') { return 0L }
        throw 'Could not read synthetic system notification'
    }
    $match = [regex]::Match($record, '(?m)^\s*mUpdateTimeMs=(\d+)')
    if ($match.Success) { return [long]$match.Groups[1].Value }
    return 0L
}

try {
    $hardware = (Invoke-Device @('shell', 'getprop', 'ro.hardware')).Trim()
    if ($hardware -notin @('ranchu', 'goldfish')) { throw 'Synthetic emulator required' }
    $identity = Invoke-Device @('shell', 'dumpsys', 'package', $package)
    if ($identity -notmatch '\bDEBUGGABLE\b') { throw 'Debug main package required' }
    $testIdentity = Invoke-Device @('shell', 'dumpsys', 'package', "$package.test")
    if ($testIdentity -notmatch '\bDEBUGGABLE\b') { throw 'Existing Debug test source required' }
    Start-Inbox
    $null = Wait-Connected

    for ($round = 1; $round -le $Rounds; $round++) {
        $pidBefore = (Invoke-Device @('shell', 'pidof', $package)).Trim()
        if ($pidBefore -notmatch '^\d+$') { throw 'Exactly one synthetic main process required' }
        $null = Invoke-Device @('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        switch ($Mode) {
            'ForceStop' { $null = Invoke-Device @('shell', 'am', 'force-stop', $package) }
            'StopApp' { $null = Invoke-Device @('shell', 'am', 'stop-app', $package) }
            'Sigkill' { $null = Invoke-Device @('shell', 'run-as', $package, 'kill', '-9', $pidBefore) }
        }
        Start-Inbox
        $recoveryMillis = Wait-Connected
        $pidAfter = (Invoke-Device @('shell', 'pidof', $package)).Trim()
        if ($pidAfter -eq $pidBefore) { throw 'Process did not change; test did not exercise termination' }

        $sourceScript = Join-Path $PSScriptRoot 'invoke-controlled-notification.ps1'
        $null = & $sourceScript -Serial $Serial -Direct -Operation remove
        Start-Sleep -Milliseconds 300
        $before = Read-Stats
        if ($before.controlledSourceExcluded) { throw 'Controlled source is excluded; leave configuration unchanged' }
        $null = & $sourceScript -Serial $Serial -Direct -Operation publish -CaseId action
        if ($LASTEXITCODE -ne 0) { throw 'Controlled source publish failed' }
        $clock = [Diagnostics.Stopwatch]::StartNew()
        $freshPostPersisted = $false
        do {
            $systemPostTime = Read-ControlledSystemPostTime
            $after = Read-Stats
            # Reinstalling the source while OMN is absent can leave a same-content lifecycle open.
            # A new post must actually be persisted; it need not create a new database row.
            $freshPostPersisted = $systemPostTime -gt $before.controlledLatestPostTime -and
                $after.controlledLatestPostTime -eq $systemPostTime
            if ($freshPostPersisted) { break }
            Start-Sleep -Milliseconds 250
        } while ($clock.ElapsedMilliseconds -lt 10000)
        if (-not $after.listenerConnected -or -not $after.listenerProcessingOperational -or -not $freshPostPersisted) {
            throw 'The current synthetic system post was not persisted after recovery'
        }
        Write-Output ("PASS mode={0} round={1} pidChanged=true connected=true freshPostPersisted=true recoveryAfterLaunchMillis={2}" -f $Mode, $round, $recoveryMillis)
    }
} catch {
    Write-Output "FAIL $($_.Exception.Message)"
    exit 1
}
