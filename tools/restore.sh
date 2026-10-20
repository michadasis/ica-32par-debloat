#!/usr/bin/env bash
# restore.sh - Undo everything this project changed on the TV.
#   1. Reinstalls the 10 debloated apps for user 0 (pm install-existing).
#   2. Removes the ad-blocking hosts entries (restores /system/etc/hosts).
#   3. Restores the stock CVTE launcher as the default HOME.
# Run from the platform-tools folder with the TV connected over ADB.
set -u
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
HERE="$(cd "$(dirname "$0")/.." && pwd)"
ADB="$HERE/adb.exe"
SERIAL="192.168.1.53:5555"
LIST="$HERE/backup/debloat_list.txt"

"$ADB" connect "$SERIAL" >/dev/null 2>&1
"$ADB" -s "$SERIAL" root >/dev/null 2>&1
sleep 3
"$ADB" connect "$SERIAL" >/dev/null 2>&1

echo "Reinstalling debloated apps..."
while IFS= read -r line; do
  p="$(echo "${line%%#*}" | tr -d '\r' | xargs)"; [ -z "$p" ] && continue
  r=$("$ADB" -s "$SERIAL" shell pm install-existing --user 0 "$p" 2>&1 | tr -d '\r')
  printf "  %-45s %s\n" "$p" "$r"
done < "$LIST"

echo "Removing ad-block hosts entries..."
"$ADB" -s "$SERIAL" shell "mount -o remount,rw /" 2>/dev/null
"$ADB" -s "$SERIAL" shell "if [ -f /system/etc/hosts.cleanhome.bak ]; then cp /system/etc/hosts.cleanhome.bak /system/etc/hosts; else sed -i '/# --- CleanHome/,/# --- end CleanHome block ---/d' /system/etc/hosts; fi"
"$ADB" -s "$SERIAL" shell "mount -o remount,ro /" 2>/dev/null

# Restore the ORIGINAL launcher skin (undo navbar-tab removal)
SKIN="$HERE/backup/saturn/skin.apk"
if [ -f "$SKIN" ]; then
  echo "Restoring original launcher skin..."
  DST="/vendor/app/Launcher-skin-169/Launcher-skin-169.apk"
  "$ADB" -s "$SERIAL" shell "mount -o remount,rw /vendor; mount -o remount,rw /" >/dev/null 2>&1
  "$ADB" -s "$SERIAL" push "$SKIN" /data/local/tmp/skin_orig_restore.apk >/dev/null
  "$ADB" -s "$SERIAL" shell "cp /data/local/tmp/skin_orig_restore.apk $DST && chmod 644 $DST && chown root:root $DST; restorecon $DST" >/dev/null 2>&1
  "$ADB" -s "$SERIAL" shell "mount -o remount,ro /vendor" >/dev/null 2>&1
  "$ADB" -s "$SERIAL" shell pm clear com.cvte.tv.launcher.saturn >/dev/null 2>&1
fi

"$ADB" -s "$SERIAL" shell cmd package set-home-activity com.cvte.tv.launcher.saturn/.home.MainActivity >/dev/null 2>&1
# Restore the ORIGINAL resource overlay (undo the weather-label text fix)
RRO="$HERE/backup/saturn/RRO_LauncherSaturn.apk"
if [ -f "$RRO" ]; then
  RDST="/system/product/overlay/rroLauncherSaturn/RRO_LauncherSaturn.apk"
  "$ADB" -s "$SERIAL" shell "mount -o remount,rw /" >/dev/null 2>&1
  "$ADB" -s "$SERIAL" push "$RRO" /data/local/tmp/rro_orig_restore.apk >/dev/null
  "$ADB" -s "$SERIAL" shell "cp /data/local/tmp/rro_orig_restore.apk $RDST && chmod 644 $RDST && chown root:root $RDST; restorecon $RDST" >/dev/null 2>&1
fi

# Restore the original "Smart TV" logo
LOGO="$HERE/backup/saturn/skin_logo_ORIGINAL.png"
if [ -f "$LOGO" ]; then
  LDST="/vendor/menu_config_xml/skin_logo.png"
  "$ADB" -s "$SERIAL" shell "mount -o remount,rw /vendor; mount -o remount,rw /" >/dev/null 2>&1
  "$ADB" -s "$SERIAL" push "$LOGO" /data/local/tmp/skin_logo_restore.png >/dev/null
  "$ADB" -s "$SERIAL" shell "cp /data/local/tmp/skin_logo_restore.png $LDST && chmod 644 $LDST && chown root:root $LDST; restorecon $LDST" >/dev/null 2>&1
fi

# Remove the weather provider app (returns the widget to stock behavior)
"$ADB" -s "$SERIAL" shell pm uninstall com.cleanhome.weather >/dev/null 2>&1
echo "Done. Reboot:  \"$ADB\" -s $SERIAL reboot"
