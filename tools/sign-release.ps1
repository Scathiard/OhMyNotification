[CmdletBinding()]
<#
.SYNOPSIS
Signs the v1 release APK with the project release key and an Android signing-certificate lineage.

.DESCRIPTION
The release-key password is requested as a SecureString, exposed only to child signing processes
through a process-scoped environment variable, and cleared in a finally block. No password file is
created. The default lineage rotates the existing local Android Debug certificate to the dedicated
OhMyNotification release certificate so the Android 16 daily-use installation can be upgraded
without deleting its app data.

.EXAMPLE
.\tools\sign-release.ps1

.EXAMPLE
.\tools\sign-release.ps1 -BuildArchive -PreflightOnly
# Read-only input/output checks; never requests a password or creates artifacts.

.EXAMPLE
.\tools\sign-release.ps1 -BuildArchive -IncludeAndroidTest
# Preserve earlier builds with the same versionName in separate versionCode directories.
#>
param(
    [ValidatePattern('^[0-9A-Za-z][0-9A-Za-z.-]*$')]
    [string]$ArtifactVersion,
    [switch]$BuildArchive,
    [switch]$PreflightOnly,
    [switch]$IncludeAndroidTest,
    [switch]$OnlyAndroidTest,
    [string]$ReleaseKeyStore = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.android\OhMyNotification-release.jks'),
    [string]$ReleaseKeyAlias = 'ohmynotification-release',
    [string]$LineagePath = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.android\OhMyNotification-signing-lineage')
)

$ErrorActionPreference = 'Stop'
if ($OnlyAndroidTest) { $IncludeAndroidTest = $true }
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $PSScriptRoot
$versionConfig = Get-Content -LiteralPath (Join-Path $projectRoot 'app\build.gradle.kts') -Raw -Encoding UTF8
$versionMatch = [regex]::Match($versionConfig, 'versionName\s*=\s*"([^"]+)"')
if (-not $versionMatch.Success) { throw 'Could not read the current versionName.' }
$versionCodeMatch = [regex]::Match($versionConfig, 'versionCode\s*=\s*(\d+)')
if (-not $versionCodeMatch.Success) { throw 'Could not read the current versionCode.' }
$versionCode = [int]$versionCodeMatch.Groups[1].Value
if ([string]::IsNullOrWhiteSpace($ArtifactVersion)) { $ArtifactVersion = $versionMatch.Groups[1].Value }
if (-not $OnlyAndroidTest -and $ArtifactVersion -ne $versionMatch.Groups[1].Value) {
    throw 'ArtifactVersion must match the application versionName.'
}
$unsignedApk = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release-unsigned.apk'
$artifactsDirectory = Join-Path $projectRoot 'artifacts'
if ($BuildArchive) {
    $artifactsDirectory = Join-Path $artifactsDirectory "builds\$ArtifactVersion-code$versionCode"
}
$signedApk = Join-Path $artifactsDirectory "OhMyNotification-$ArtifactVersion-release.apk"
$shaFile = "$signedApk.sha256"
$debugKeyStore = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.android\debug.keystore'

foreach ($requiredFile in @($unsignedApk, $ReleaseKeyStore, $debugKeyStore)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required file is missing: $requiredFile"
    }
}
if (-not $OnlyAndroidTest -and ((Test-Path -LiteralPath $signedApk) -or (Test-Path -LiteralPath $shaFile))) {
    throw "The $ArtifactVersion signed artifact already exists. Refusing to overwrite it."
}
if ($IncludeAndroidTest) {
    $testInput = Join-Path $projectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
    $testOutput = Join-Path $artifactsDirectory "OhMyNotification-$ArtifactVersion-androidTest.apk"
    if (-not (Test-Path -LiteralPath $testInput)) { throw 'Build the AndroidTest APK before signing.' }
    if ((Test-Path -LiteralPath $testOutput) -or (Test-Path -LiteralPath "$testOutput.sha256")) {
        throw 'The signed AndroidTest APK or checksum already exists.'
    }
}

function Resolve-SdkPath {
    $localProperties = Join-Path $projectRoot 'local.properties'
    if (-not (Test-Path -LiteralPath $localProperties -PathType Leaf)) {
        throw 'local.properties is missing.'
    }
    $sdkLine = Get-Content -LiteralPath $localProperties -Encoding UTF8 |
        Where-Object { $_.StartsWith('sdk.dir=') } |
        Select-Object -First 1
    if ($null -eq $sdkLine) {
        throw 'sdk.dir is missing from local.properties.'
    }
    return $sdkLine.Substring('sdk.dir='.Length).Trim().Replace('\:', ':').Replace('\\', '\')
}

$apksigner = Join-Path (Resolve-SdkPath) 'build-tools\36.1.0\apksigner.bat'
if (-not (Test-Path -LiteralPath $apksigner -PathType Leaf)) {
    throw 'Android Build Tools 36.1.0 apksigner was not found.'
}

$aapt2 = Join-Path (Split-Path -Parent $apksigner) 'aapt2.exe'
if (-not $OnlyAndroidTest) {
    $badging = (& $aapt2 dump badging $unsignedApk 2>&1) -join [Environment]::NewLine
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect the unsigned APK.' }
    $expectedIdentity = "package: name='io.github.prince_dulb.ohmynotification'.*versionCode='$versionCode'.*versionName='$([regex]::Escape($versionMatch.Groups[1].Value))'"
    if ($badging -notmatch $expectedIdentity -or $badging -match 'application-debuggable') {
        throw 'Unsigned APK identity/build type differs from the current source configuration. Rebuild before signing.'
    }
}
if ($IncludeAndroidTest) {
    $testBadging = (& $aapt2 dump badging $testInput 2>&1) -join [Environment]::NewLine
    if ($LASTEXITCODE -ne 0 -or $testBadging -notmatch "package: name='io.github.prince_dulb.ohmynotification.test'") {
        throw 'AndroidTest input has the wrong package identity.'
    }
}
if ($PreflightOnly) {
    Write-Output "PASS signingPreflight=true versionCode=$versionCode buildArchive=$BuildArchive androidTestOnly=$OnlyAndroidTest"
    exit 0
}

$securePassword = Read-Host 'Release key password' -AsSecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    [Environment]::SetEnvironmentVariable('OMN_RELEASE_KEYSTORE_PASSWORD', $plainPassword, 'Process')
    $plainPassword = $null

    if (-not (Test-Path -LiteralPath $LineagePath -PathType Leaf)) {
        & $apksigner rotate `
            --out $LineagePath `
            --old-signer `
            --ks $debugKeyStore `
            --ks-key-alias androiddebugkey `
            --ks-pass pass:android `
            --key-pass pass:android `
            --set-installed-data true `
            --set-shared-uid false `
            --set-permission false `
            --set-rollback false `
            --set-auth false `
            --new-signer `
            --ks $ReleaseKeyStore `
            --ks-key-alias $ReleaseKeyAlias `
            --ks-pass env:OMN_RELEASE_KEYSTORE_PASSWORD `
            --key-pass env:OMN_RELEASE_KEYSTORE_PASSWORD
        if ($LASTEXITCODE -ne 0) {
            throw 'Failed to create the signing-certificate lineage.'
        }
    }

    New-Item -ItemType Directory -Path $artifactsDirectory -Force | Out-Null
    if (-not $OnlyAndroidTest) {
    & $apksigner sign `
        --ks $debugKeyStore `
        --ks-key-alias androiddebugkey `
        --ks-pass pass:android `
        --key-pass pass:android `
        --next-signer `
        --ks $ReleaseKeyStore `
        --ks-key-alias $ReleaseKeyAlias `
        --ks-pass env:OMN_RELEASE_KEYSTORE_PASSWORD `
        --key-pass env:OMN_RELEASE_KEYSTORE_PASSWORD `
        --lineage $LineagePath `
        --v4-signing-enabled false `
        --out $signedApk `
        $unsignedApk
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to sign the release APK.'
    }

    & $apksigner verify --verbose --print-certs --min-sdk-version 36 $signedApk
    if ($LASTEXITCODE -ne 0) {
        throw 'Signed release APK verification failed.'
    }

    $apkStream = [IO.File]::OpenRead($signedApk)
    try {
        $shaAlgorithm = [Security.Cryptography.SHA256]::Create()
        try {
            $shaBytes = $shaAlgorithm.ComputeHash($apkStream)
        }
        finally {
            $shaAlgorithm.Dispose()
        }
    }
    finally {
        $apkStream.Dispose()
    }
    $sha256 = ([BitConverter]::ToString($shaBytes)).Replace('-', '').ToLowerInvariant()
    [IO.File]::WriteAllText($shaFile, "$sha256  $(Split-Path -Leaf $signedApk)`n", [Text.UTF8Encoding]::new($false))
    Write-Output "PASS apk=$signedApk sha256=$sha256 lineage=$LineagePath"
    }
    if ($IncludeAndroidTest) {
        & $apksigner sign --ks $ReleaseKeyStore --ks-key-alias $ReleaseKeyAlias `
            --ks-pass env:OMN_RELEASE_KEYSTORE_PASSWORD --key-pass env:OMN_RELEASE_KEYSTORE_PASSWORD `
            --v4-signing-enabled false --out $testOutput $testInput
        if ($LASTEXITCODE -ne 0) { throw 'Failed to sign the AndroidTest APK.' }
        & $apksigner verify --verbose --min-sdk-version 36 $testOutput
        if ($LASTEXITCODE -ne 0) { throw 'AndroidTest signature verification failed.' }
        $testStream = [IO.File]::OpenRead($testOutput)
        $testHasher = [Security.Cryptography.SHA256]::Create()
        try { $testDigest = ([BitConverter]::ToString($testHasher.ComputeHash($testStream))).Replace('-', '').ToLowerInvariant() }
        finally { $testStream.Dispose(); $testHasher.Dispose() }
        [IO.File]::WriteAllText("$testOutput.sha256", "$testDigest  $(Split-Path -Leaf $testOutput)`n", [Text.UTF8Encoding]::new($false))
        Write-Output "PASS androidTest=$testOutput sha256=$testDigest"
    }
}
finally {
    [Environment]::SetEnvironmentVariable('OMN_RELEASE_KEYSTORE_PASSWORD', $null, 'Process')
    if ($passwordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
}
