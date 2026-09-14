[CmdletBinding()]
param(
    [ValidateSet('Before', 'After')][string]$Phase = 'After',
    [string]$Serial = 'emulator-5558',
    [string]$Adb
)
# Emulator-only verifier. Reads synthetic truth and fresh receipt, emits no target values.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ([string]::IsNullOrWhiteSpace($Adb)) {
    $projectRoot = Split-Path -Parent $PSScriptRoot
    $sdkLine = Get-Content -LiteralPath (Join-Path $projectRoot 'local.properties') -Encoding UTF8 |
        Where-Object { $_.StartsWith('sdk.dir=') } | Select-Object -First 1
    if ($null -eq $sdkLine) { throw 'SDK location unavailable.' }
    $sdkRoot = $sdkLine.Substring('sdk.dir='.Length).Trim().Replace('\:', ':').Replace('\\', '\')
    $Adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
}
function Invoke-FixtureAdb {
    param([string[]]$Arguments)
    $result = & $Adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'Fixture ADB command failed.' }
    return ($result -join "`n").Trim()
}
function Read-FixtureProperties {
    param([string]$Text)
    $values = @{}
    foreach ($line in ($Text -split "`n")) {
        if ($line.StartsWith('#') -or -not $line.Contains('=')) { continue }
        $parts = $line.Split('=', 2)
        $values[$parts[0].Trim()] = $parts[1].Trim()
    }
    return $values
}
$hardware = Invoke-FixtureAdb @('shell', 'getprop', 'ro.hardware')
if ($hardware -notin @('ranchu', 'goldfish') -or -not $Serial.StartsWith('emulator-')) {
    throw 'Synthetic verifier refuses physical devices.'
}
$source = 'io.github.prince_dulb.ohmynotification.sourcefixture'
$helper = 'io.github.prince_dulb.ohmynotification.test'
$sample = (Invoke-FixtureAdb @('shell', 'run-as', $helper, 'cat', 'no_backup/omn-route-reboot-probe.json')) | ConvertFrom-Json
if ($sample.mode -ne 'synthetic') { throw 'Not a synthetic sample.' }
$truth = Read-FixtureProperties (Invoke-FixtureAdb @('shell', 'run-as', $source, 'cat', 'files/durable-route-source.properties'))
$receipt = Read-FixtureProperties (Invoke-FixtureAdb @('shell', 'run-as', $source, 'cat', 'files/controlled-target-result.properties'))
$status = Read-FixtureProperties (Invoke-FixtureAdb @('shell', 'CLASSPATH=/data/local/tmp/omn-route-shell-probe.jar app_process /system/bin NotificationRouteShellProbe synthetic replay-status'))
$encodedToken = [regex]::Match($sample.route, '(?:#Intent;|;)S\.omn\.synthetic\.target_token=([^;]+);')
if (-not $encodedToken.Success) { throw 'Missing saved synthetic target.' }
$token = [Uri]::UnescapeDataString($encodedToken.Groups[1].Value)
if (-not $token.StartsWith('OMN_SYNTHETIC_REBOOT_') -or $token -ne $truth.target_token -or $token -ne $receipt.target_token) {
    throw 'Target does not match source-generated truth.'
}
$phaseKey = $Phase.ToLowerInvariant() + 'DispatchedAt'
if ($receipt.status -ne 'OPENED' -or $receipt.case_id -ne 'action' -or [long]$status[$phaseKey] -le 0 -or
        [long]$receipt.opened_at_epoch_millis -lt [long]$status[$phaseKey]) {
    throw 'No fresh matching landing receipt.'
}
if ($status.saved -ne 'true' -or $status.sourceUnchanged -ne 'true' -or $status.ordinaryUid -ne 'true' -or
        $status.lastAttemptRejected -ne 'false') { throw 'Saved sample or ordinary replay status failed.' }
$currentBoot = [int](Invoke-FixtureAdb @('shell', 'settings', 'get', 'global', 'boot_count'))
if ($Phase -eq 'After') {
    if ($currentBoot -le [int]$sample.boot -or $status.rebooted -ne 'true' -or $status.originalAvailable -ne 'false') {
        throw 'Full reboot without an original in-memory action is required.'
    }
    $listeners = Invoke-FixtureAdb @('shell', 'settings', 'get', 'secure', 'enabled_notification_listeners')
    if ($listeners.Contains('testsource.RouteProbeListener')) { throw 'Probe listener must remain disabled.' }
    $notifications = Invoke-FixtureAdb @('shell', 'cmd', 'notification', 'list')
    if ($notifications.Contains($source)) { throw 'Synthetic original was reposted or not removed.' }
} elseif ($currentBoot -ne [int]$sample.boot -or $status.rebooted -ne 'false') {
    throw 'Before phase was not run in the capture boot.'
}
Write-Output "PASS phase=$Phase exactFreshTarget=True ordinaryUid=True capturedBoot=$($sample.boot) currentBoot=$currentBoot"
