param([string]$Adb = 'adb', [string]$Serial = '')
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
