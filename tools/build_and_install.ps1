# build_and_install.ps1 - Build the Clean Home launcher and install it on the TV.
# Run from the platform-tools folder with the TV connected over ADB.
#   .\tools\build_and_install.ps1

$ErrorActionPreference = "Stop"
$root   = Join-Path $PSScriptRoot ".."
$adb    = Join-Path $root "adb.exe"
$proj   = Join-Path $root "launcher"
$serial = "192.168.1.53:5555"
$apk    = Join-Path $proj "app\build\outputs\apk\debug\app-debug.apk"

Write-Host "Building launcher (Gradle)..." -ForegroundColor Cyan
Push-Location $proj
try { & (Join-Path $proj "gradlew.bat") assembleDebug --console=plain }
finally { Pop-Location }
if (-not (Test-Path $apk)) { throw "APK not found at $apk" }

Write-Host "Installing on TV..." -ForegroundColor Cyan
& $adb connect $serial | Out-Null
& $adb -s $serial push $apk /data/local/tmp/cleanhome.apk | Out-Null
# pm install over the shell (adb install can hang on Android 14 waiting for an on-screen OK)
& $adb -s $serial shell pm install -r /data/local/tmp/cleanhome.apk

Write-Host "`nInstalled as com.cleanhome.tv." -ForegroundColor Green
Write-Host "Open it from the stock launcher's app list, or:" -ForegroundColor Yellow
Write-Host "  .\adb.exe -s $serial shell monkey -p com.cleanhome.tv -c android.intent.category.LAUNCHER 1"
