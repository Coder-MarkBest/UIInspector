param([string]$Serial = "")
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$Adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" } else { "" }
if (-not $Adb -or -not (Test-Path $Adb)) {
    $Command = Get-Command adb -ErrorAction SilentlyContinue
    if (-not $Command) { throw "adb not found; configure ANDROID_HOME or PATH." }
    $Adb = $Command.Source
}

& (Join-Path $Root "gradlew.bat") -p $Root :collector:assembleDebug :sample-target:assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed." }

if (-not $Serial) {
    $DeviceLine = & $Adb devices | Where-Object { $_ -match "^\S+\s+device$" } | Select-Object -First 1
    if ($DeviceLine) { $Serial = ($DeviceLine -split "\s+")[0] }
}
if (-not $Serial) { throw "No authorized Android device found." }

& $Adb -s $Serial install -r -t (Join-Path $Root "collector\build\outputs\apk\debug\collector-debug.apk")
if ($LASTEXITCODE -ne 0) { throw "Collector install failed." }
& $Adb -s $Serial install -r -t (Join-Path $Root "sample-target\build\outputs\apk\debug\sample-target-debug.apk")
if ($LASTEXITCODE -ne 0) { throw "Sample install failed." }
& $Adb -s $Serial shell am start -n com.company.uiinspector.sample/.MainActivity

$Uri = "content://com.company.uiinspector.collector/snapshot?scope=global&maxNodes=10000&maxDepth=200"
$SnapshotText = (& $Adb -s $Serial exec-out content read --uri $Uri) -join "`n"
$Snapshot = $SnapshotText | ConvertFrom-Json
if ($Snapshot.schemaVersion -ne 1) { throw "Collector did not return protocol v1 JSON." }

if ($Snapshot.diagnostics.code -contains "accessibility_required") {
    Write-Warning "Collector installed. Enable UI Inspector Collector in Accessibility settings, then capture again."
    & $Adb -s $Serial shell am start -a android.settings.ACCESSIBILITY_SETTINGS
} else {
    Write-Host "Smoke capture succeeded on $Serial."
}
