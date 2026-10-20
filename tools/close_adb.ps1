# close_adb.ps1 - Close network ADB when you're finished.
#
# This TV exposes ADB on the LAN over port 5555 with NO authentication
# (ro.adb.secure unset, persist.adb.tcp.port=5555). Run this when done so nobody
# else on your network can get a shell. It:
#   - clears the persistent TCP-ADB port (so network ADB won't come back on boot)
#   - revokes all authorized ADB keys
#   - turns off wireless + USB ADB
# After it runs you'll be disconnected (that's expected). Re-enable later from the
# TV: Settings > Developer options > USB/Network debugging.
#
# Run from the platform-tools folder.

$adb = Join-Path $PSScriptRoot "..\adb.exe"
$serial = "192.168.1.53:5555"

& $adb connect $serial | Out-Null
& $adb -s $serial root 2>$null | Out-Null
Start-Sleep -Seconds 3
& $adb connect $serial | Out-Null

Write-Host "Clearing persistent network-ADB port and revoking keys..." -ForegroundColor Cyan
& $adb -s $serial shell "setprop persist.adb.tcp.port ''" 2>$null
& $adb -s $serial shell "setprop service.adb.tcp.port ''" 2>$null
& $adb -s $serial shell "rm -f /data/misc/adb/adb_keys" 2>$null
& $adb -s $serial shell "settings put global adb_wifi_enabled 0" 2>$null

Write-Host "Disabling ADB (this disconnects now)..." -ForegroundColor Cyan
& $adb -s $serial shell "settings put global adb_enabled 0" 2>$null
Start-Sleep -Seconds 2
& $adb disconnect $serial 2>$null | Out-Null
& $adb kill-server 2>$null | Out-Null

Write-Host "`nDone. Now reboot the TV from the remote (power off/on), then verify from this PC:" -ForegroundColor Yellow
Write-Host "  (the connection should be refused / time out)"
Write-Host "  Test-NetConnection 192.168.1.53 -Port 5555   # TcpTestSucceeded should be False"
