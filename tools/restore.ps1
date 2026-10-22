# restore.ps1 - Undo everything this project changed on the TV.
#
# It:
#   1. Reinstalls the 10 debloated apps for user 0 (pm install-existing).
#      (Durable debloat here used `pm uninstall --user 0`, not disable, because
#       disable-state is NOT saved across reboots on this firmware.)
#   2. Removes the ad-blocking hosts entries (restores /system/etc/hosts).
#   3. Restores the stock CVTE launcher as the default HOME.
#   4. (Optional) Uninstalls the Clean Home launcher app - commented out by default.
#
# Run from the platform-tools folder with the TV connected over ADB.
#   .\tools\restore.ps1

$ErrorActionPreference = "Stop"
$adb = Join-Path $PSScriptRoot "..\adb.exe"
$serial = "192.168.1.53:5555"
$list = Join-Path $PSScriptRoot "..\backup\debloat_list.txt"

& $adb connect $serial | Out-Null
& $adb -s $serial root 2>$null | Out-Null       # userdebug build; needed for hosts restore
Start-Sleep -Seconds 3
& $adb connect $serial | Out-Null

# 1. Reinstall debloated apps -------------------------------------------------
Write-Host "Reinstalling debloated apps..." -ForegroundColor Cyan
Get-Content $list | ForEach-Object {
    $pkg = ($_ -replace '#.*$','').Trim()
    if ($pkg) {
        $r = (& $adb -s $serial shell pm install-existing --user 0 $pkg) 2>&1
        Write-Host ("  {0,-45} {1}" -f $pkg, ($r -replace '\s+$',''))
    }
}

# 2. Remove the ad-block from the hosts file ----------------------------------
Write-Host "Removing ad-block hosts entries..." -ForegroundColor Cyan
& $adb -s $serial shell "mount -o remount,rw /" 2>$null
# Restore from the backup we made, or strip our block if the backup is gone.
& $adb -s $serial shell "if [ -f /system/etc/hosts.cleanhome.bak ]; then cp /system/etc/hosts.cleanhome.bak /system/etc/hosts; else sed -i '/# --- CleanHome/,/# --- end CleanHome block ---/d' /system/etc/hosts; fi"
& $adb -s $serial shell "mount -o remount,ro /" 2>$null
Write-Host "  hosts restored."

# 3. Restore the ORIGINAL launcher skin (undo the navbar-tab removal) ----------
$skin = Join-Path $PSScriptRoot "..\backup\saturn\skin.apk"
if (Test-Path $skin) {
    Write-Host "Restoring original launcher skin (navbar tabs)..." -ForegroundColor Cyan
    $dst = "/vendor/app/Launcher-skin-169/Launcher-skin-169.apk"
    & $adb -s $serial shell "mount -o remount,rw /vendor; mount -o remount,rw /" *> $null
    & $adb -s $serial push $skin /data/local/tmp/skin_orig_restore.apk | Out-Null
    & $adb -s $serial shell "cp /data/local/tmp/skin_orig_restore.apk $dst && chmod 644 $dst && chown root:root $dst; restorecon $dst" *> $null
    & $adb -s $serial shell "mount -o remount,ro /vendor" *> $null
    & $adb -s $serial shell pm clear com.cvte.tv.launcher.saturn *> $null
    Write-Host "  original skin restored (reboot to apply)."
}

# 4. Restore stock launcher as HOME -------------------------------------------
& $adb -s $serial shell cmd package set-home-activity com.cvte.tv.launcher.saturn/.home.MainActivity *> $null

# 3b. Restore the ORIGINAL resource overlay (undo the weather-label text fix) --
$rro = Join-Path $PSScriptRoot "..\backup\saturn\RRO_LauncherSaturn.apk"
if (Test-Path $rro) {
    $rdst = "/system/product/overlay/rroLauncherSaturn/RRO_LauncherSaturn.apk"
    & $adb -s $serial shell "mount -o remount,rw /" *> $null
    & $adb -s $serial push $rro /data/local/tmp/rro_orig_restore.apk | Out-Null
    & $adb -s $serial shell "cp /data/local/tmp/rro_orig_restore.apk $rdst && chmod 644 $rdst && chown root:root $rdst; restorecon $rdst" *> $null
    Write-Host "  original overlay restored (reboot to apply)."
}

# 3c. Restore the original "Smart TV" logo ------------------------------------
$logo = Join-Path $PSScriptRoot "..\backup\saturn\skin_logo_ORIGINAL.png"
if (Test-Path $logo) {
    $ldst = "/vendor/menu_config_xml/skin_logo.png"
    & $adb -s $serial shell "mount -o remount,rw /vendor; mount -o remount,rw /" *> $null
    & $adb -s $serial push $logo /data/local/tmp/skin_logo_restore.png | Out-Null
    & $adb -s $serial shell "cp /data/local/tmp/skin_logo_restore.png $ldst && chmod 644 $ldst && chown root:root $ldst; restorecon $ldst" *> $null
    Write-Host "  'Smart TV' logo restored."
}

# 4. Remove the weather provider app (returns the widget to its stock behavior)
& $adb -s $serial shell pm uninstall com.cleanhome.weather *> $null

# 5. Remove the D-pad Netflix navigation app + restore the virtual mouse for Netflix
Write-Host "Removing D-pad navigation app + restoring mouse for Netflix..." -ForegroundColor Cyan
& $adb -s $serial shell "settings put secure enabled_accessibility_services 'com.hisilicon.rmservice/.HiRMService'" *> $null
& $adb -s $serial uninstall com.cleanhome.dpadnav *> $null
$mouse = Join-Path $PSScriptRoot "..\backup\saturn\mouse_control_ORIGINAL.json"
if (-not (Test-Path $mouse)) { $mouse = Join-Path $PSScriptRoot "..\reference\mouse_control_ORIGINAL.json" }
if (Test-Path $mouse) {
    $mdst = "/vendor/menu_config_xml/mouse_control.json"
    & $adb -s $serial shell "mount -o remount,rw /vendor" *> $null
    & $adb -s $serial push $mouse /data/local/tmp/mc_restore.json | Out-Null
    & $adb -s $serial shell "cp /data/local/tmp/mc_restore.json $mdst && chmod 644 $mdst; restorecon $mdst" *> $null
    Write-Host "  mouse restored for Netflix."
}

Write-Host "`nDone. Reboot:  .\adb.exe -s $serial reboot" -ForegroundColor Yellow
