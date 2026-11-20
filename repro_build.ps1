# Reproducible build & verify script for Forensic Camera v3.4.5 (versionCode 11).
#
# Requirement: same source + same toolchain => byte-identical unsigned APK;
# v2-only signing with the fixed release key => byte-identical signed APK.
#
# Fixed toolchain (see BUILD-ENV.txt):
#   JDK 17.0.20.101 (Temurin) / Gradle 8.7 / AGP 8.5.2 / Kotlin 1.9.24
#   Android SDK platform 34 / build-tools 34.0.0 / NDK 26.1.10909125
#   CMake 4.3.1 official Kitware (pip wheel). NOTE: Android SDK CMake
#   3.22.1 / 3.31.6 / 4.1.2 crash with 0xC0000409 on this Windows host.
#
# Usage (run from an ASCII-only path):
#   powershell -ExecutionPolicy Bypass -File repro_build.ps1 `
#       -SdkDir C:\Users\me\AppData\Local\Android\Sdk `
#       -CMakeDir C:\tools\cmake-4.3.1\cmake\data `
#       -KeystoreInfo C:\secure\keystore-info.txt `
#       -OutDir C:\dist
#
# The private release keystore and keystore-info.txt are NOT distributed.
# Third parties can still verify reproducibility of the unsigned APK, then
# compare the delivered APK's signer certificate with the on-chain value:
#   apksigner verify --print-certs forensic-camera-v3.4.5-release.apk
param(
    [string]$SdkDir = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$CMakeDir = "C:\fcbuild\tools\cmake-4.3.1\cmake\data",
    [string]$KeystoreInfo = "",
    [string]$OutDir = "",
    [string]$ExpectedUnsigned = "EE334032727FB3AAE428D6DE56ADAB5D4140A7B8BA0A99125FEC7F7BA0D4F56D",
    [string]$ExpectedSigned = "330055C2DC572134CD53F2BE4AB5CB18B12C25AF9A2B44C9BE9FE98AB0FB2A87",
    [string]$ExpectedCert = "09bfeeb368b715d6591ce1a795b916b08cbd493d43e05131b1cdbde748e00c1e"
)
$ErrorActionPreference = "Stop"
$proj = $PSScriptRoot

function Write-Step([string]$msg) { Write-Host "`n== $msg ==" }

if ($proj -match '[^\x00-\x7F]') {
    throw "Project path must be ASCII-only (CMake/ninja are unstable with non-ASCII paths): $proj"
}
if (-not (Test-Path -LiteralPath "$proj\gradlew.bat")) {
    throw "gradlew.bat not found; run this script from the project root."
}

Write-Step "Toolchain checks"
if (-not (Test-Path -LiteralPath "$CMakeDir\bin\cmake.exe")) {
    throw "CMake not found at $CMakeDir. Install official CMake 4.3.1 (pip install cmake==4.3.1) and pass -CMakeDir <...\cmake\data>."
}
$cmakeVer = (& "$CMakeDir\bin\cmake.exe" --version | Select-Object -First 1)
if ($cmakeVer -notmatch '4\.3\.1') { throw "Expected CMake 4.3.1, got: $cmakeVer" }
Write-Host "CMake: $cmakeVer"

$javaOut = (& cmd.exe /c "java -version 2>&1" | Out-String)
if ($javaOut -notmatch '17\.0\.20') { throw "Expected JDK 17.0.20.x, got:`n$javaOut" }
Write-Host ($javaOut.Trim().Split("`n")[0])

$apksigner = Join-Path $SdkDir "build-tools\34.0.0\apksigner.bat"
if (-not (Test-Path -LiteralPath $apksigner)) { throw "apksigner 34.0.0 not found: $apksigner" }
$apkVer = (& $apksigner --version)
Write-Host "apksigner: $apkVer"

$ndk = Join-Path $SdkDir "ndk\26.1.10909125"
if (-not (Test-Path -LiteralPath $ndk)) { throw "NDK 26.1.10909125 not found: $ndk" }
Write-Host "NDK: $ndk"

Write-Step "Writing machine-local local.properties"
$sdkEsc = $SdkDir.Replace('\', '\\').Replace(':', '\:')
$cmakeEsc = $CMakeDir.Replace('\', '\\').Replace(':', '\:')
$lp = "sdk.dir=$sdkEsc`r`ncmake.dir=$cmakeEsc`r`n"
[System.IO.File]::WriteAllText("$proj\local.properties", $lp, (New-Object System.Text.UTF8Encoding($false)))

$tempRoot = Join-Path $env:TEMP "fc-repro-v345"
Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null

function Invoke-CleanBuild([string]$tag) {
    Write-Step "Clean build $tag"
    Remove-Item -LiteralPath "$proj\.gradle" -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath "$proj\app\build" -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath "$proj\app\.cxx" -Recurse -Force -ErrorAction SilentlyContinue
    Push-Location $proj
    try {
        & "$proj\gradlew.bat" --no-daemon --no-parallel --no-build-cache clean :app:assembleRelease --rerun-tasks | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "Gradle assembleRelease failed ($tag)" }
    } finally {
        Pop-Location
    }
    $apk = "$proj\app\build\outputs\apk\release\app-release-unsigned.apk"
    if (-not (Test-Path -LiteralPath $apk)) { throw "Unsigned APK missing after build ($tag)" }
    $dst = Join-Path $tempRoot "unsigned-$tag.apk"
    Copy-Item -LiteralPath $apk -Destination $dst -Force
    return $dst
}

$uA = Invoke-CleanBuild "A"
$uB = Invoke-CleanBuild "B"
$hUA = (Get-FileHash -Algorithm SHA256 -LiteralPath $uA).Hash
$hUB = (Get-FileHash -Algorithm SHA256 -LiteralPath $uB).Hash
Write-Host "UNSIGNED A = $hUA"
Write-Host "UNSIGNED B = $hUB"
if ($hUA -ne $hUB) { throw "REPRODUCIBILITY FAILED: unsigned APKs differ between clean builds." }
if ($ExpectedUnsigned -and $hUA -ne $ExpectedUnsigned) {
    throw "Unsigned hash does not match expected $ExpectedUnsigned. Source or toolchain changed."
}
Write-Host "UNSIGNED MATCH: OK"

$finalUnsigned = $uA
$finalSigned = $null
$hSigned = $null

if ($KeystoreInfo) {
    Write-Step "Signing with private release key (v2 only)"
    if (-not (Test-Path -LiteralPath $KeystoreInfo)) { throw "keystore-info.txt not found: $KeystoreInfo" }
    $info = @{}
    Get-Content -LiteralPath $KeystoreInfo | ForEach-Object {
        if ($_ -match '^(\w+)=(.*)$') { $info[$Matches[1]] = $Matches[2] }
    }
    foreach ($k in @('keystore', 'alias', 'storepass', 'keypass')) {
        if (-not $info.ContainsKey($k) -or [string]::IsNullOrEmpty($info[$k])) { throw "keystore-info.txt missing $k" }
    }
    if (-not (Test-Path -LiteralPath $info['keystore'])) { throw "keystore not found: $($info['keystore'])" }

    $sA = Join-Path $tempRoot "signed-A.apk"
    $sB = Join-Path $tempRoot "signed-B.apk"
    Copy-Item -LiteralPath $uA -Destination $sA -Force
    Copy-Item -LiteralPath $uB -Destination $sB -Force
    foreach ($f in @($sA, $sB)) {
        $oldEap = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        & $apksigner sign --ks $info['keystore'] --ks-pass "pass:$($info['storepass'])" `
            --ks-key-alias $info['alias'] --key-pass "pass:$($info['keypass'])" `
            --v1-signing-enabled false --v2-signing-enabled true `
            --v3-signing-enabled false --v4-signing-enabled false $f
        $signCode = $LASTEXITCODE
        $ErrorActionPreference = $oldEap
        if ($signCode -ne 0) { throw "apksigner failed: $f" }
    }
    $hSA = (Get-FileHash -Algorithm SHA256 -LiteralPath $sA).Hash
    $hSB = (Get-FileHash -Algorithm SHA256 -LiteralPath $sB).Hash
    Write-Host "SIGNED A = $hSA"
    Write-Host "SIGNED B = $hSB"
    if ($hSA -ne $hSB) { throw "REPRODUCIBILITY FAILED: signed APKs differ." }
    if ($ExpectedSigned -and $hSA -ne $ExpectedSigned) {
        throw "Signed hash does not match expected $ExpectedSigned."
    }
    $oldEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $certOut = (& $apksigner verify --print-certs $sA 2>&1 | Out-String)
    $ErrorActionPreference = $oldEap
    $certLine = ($certOut -split "`r?`n" | Where-Object { $_ -match "certificate SHA-256 digest" } | Select-Object -First 1)
    if (-not $certLine) { throw "Could not read signer certificate." }
    $cert = ($certLine -split ':', 2)[1].Trim()
    Write-Host "SIGNER CERT = $cert"
    if ($ExpectedCert -and $cert -ne $ExpectedCert) {
        throw "Signer certificate does not match expected $ExpectedCert."
    }
    Write-Host "SIGNED MATCH: OK"
    $finalSigned = $sA
    $hSigned = $hSA
} else {
    Write-Host "`nNo -KeystoreInfo supplied: unsigned reproducibility verified; signing skipped."
}

if ($OutDir) {
    Write-Step "Copying artifacts to $OutDir"
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    Copy-Item -LiteralPath $finalUnsigned -Destination (Join-Path $OutDir "forensic-camera-v3.4.5-unsigned.apk") -Force
    if ($finalSigned) {
        Copy-Item -LiteralPath $finalSigned -Destination (Join-Path $OutDir "forensic-camera-v3.4.5-release.apk") -Force
    }
}

Write-Step "Summary"
Write-Host "UNSIGNED SHA-256 = $hUA"
if ($hSigned) { Write-Host "SIGNED   SHA-256 = $hSigned" }
if ($cert)    { Write-Host "SIGNER CERT      = $cert" }
Write-Host "Reproducible build verification completed."
