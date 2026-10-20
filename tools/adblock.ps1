# adblock.ps1 - (Re)apply the home-screen ad block by adding the ad/telemetry
# servers to the TV's hosts file. Already applied by the engagement; use this to
# re-apply if the hosts file is ever reset. Reversible with tools\restore.ps1.
# Run from the platform-tools folder with the TV connected over ADB.

$ErrorActionPreference = "Stop"
$adb = Join-Path $PSScriptRoot "..\adb.exe"
$serial = "192.168.1.53:5555"

$domains = @(
  "ssp.zeasn.tv","recommend.zeasn.tv","ping.zeasn.tv","log.saas.zeasn.tv",
  "saas.zeasn.tv","cache.zeasn.tv","deviceportal.zeasn.tv","whalelive.zeasn.tv",
  "adserver.srafcloud.com","on-euads-o.api.leiniao.com","api.leiniao.com"
)

& $adb connect $serial | Out-Null
& $adb -s $serial root 2>$null | Out-Null
Start-Sleep -Seconds 3
& $adb connect $serial | Out-Null

& $adb -s $serial shell "cp -n /system/etc/hosts /system/etc/hosts.cleanhome.bak" 2>$null
& $adb -s $serial shell "mount -o remount,rw /"
& $adb -s $serial shell "sed -i '/# --- CleanHome/,/# --- end CleanHome block ---/d' /system/etc/hosts"
& $adb -s $serial shell "echo '' >> /system/etc/hosts; echo '# --- CleanHome ad/telemetry block. Remove to restore. ---' >> /system/etc/hosts"
foreach ($d in $domains) {
  & $adb -s $serial shell "echo '127.0.0.1 $d' >> /system/etc/hosts"
  & $adb -s $serial shell "echo '::1 $d' >> /system/etc/hosts"
}
& $adb -s $serial shell "echo '# --- end CleanHome block ---' >> /system/etc/hosts"
& $adb -s $serial shell "mount -o remount,ro /"

Write-Host "Ad block applied. Reboot to clear DNS cache:" -ForegroundColor Green
Write-Host "  .\adb.exe -s $serial reboot"
