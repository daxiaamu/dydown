param([string]$Adb = 'adb', [string]$Serial = '', [switch]$DexKit)
$deviceArgs = @()
if ($Serial) { $deviceArgs = @('-s', $Serial) }
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$probeApk = Join-Path $taskRoot 'app/build/outputs/apk/debug/app-debug.apk'
if (!(Test-Path -LiteralPath $probeApk)) { throw 'Build :app:assembleDebug first.' }
& $Adb @deviceArgs push $probeApk /data/local/tmp/dydown-probe.apk
if ($LASTEXITCODE -ne 0) { throw 'adb push failed' }
$packageOutput = & $Adb @deviceArgs shell pm path com.ss.android.ugc.aweme
$packageLine = $packageOutput | Where-Object { $_ -match '^package:.*base\.apk$' } | Select-Object -First 1
if (!$packageLine) { throw 'Target application is not installed on the selected device.' }
$hostApk = $packageLine.Trim().Substring(8)
$probeOutput = & $Adb @deviceArgs shell "CLASSPATH=/data/local/tmp/dydown-probe.apk app_process /system/bin com.daxiaamu.dydown.CompatibilityProbe $hostApk" 2>&1
$probeOutput | Write-Output
if ($LASTEXITCODE -ne 0 -or !($probeOutput -match '^ALL COMPATIBILITY CHECKS PASSED$')) { throw 'Compatibility checks failed.' }

if ($DexKit) {
    $abi = (& $Adb @deviceArgs shell getprop ro.product.cpu.abi).Trim()
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($probeApk)
    $nativeTemp = [System.IO.Path]::GetTempFileName()
    try {
        $entry = $archive.GetEntry("lib/$abi/libdexkit.so")
        if (!$entry) { throw "Missing DexKit library for $abi" }
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $nativeTemp, $true)
        & $Adb @deviceArgs shell mkdir -p /data/local/tmp/dydown-probe-libs
        if ($LASTEXITCODE -ne 0) { throw 'Native directory creation failed' }
        & $Adb @deviceArgs push $nativeTemp /data/local/tmp/dydown-probe-libs/libdexkit.so
        if ($LASTEXITCODE -ne 0) { throw 'Native library push failed' }
    } finally {
        $archive.Dispose()
        Remove-Item -LiteralPath $nativeTemp
    }
    $dexOutput = & $Adb @deviceArgs shell "CLASSPATH=/data/local/tmp/dydown-probe.apk app_process -Djava.library.path=/data/local/tmp/dydown-probe-libs /system/bin com.daxiaamu.dydown.DexKitProbe $hostApk" 2>&1
    $dexOutput | Write-Output
    if ($LASTEXITCODE -ne 0 -or !($dexOutput -match '^ALL DEXKIT CHECKS PASSED$')) { throw 'DexKit checks failed.' }
}
