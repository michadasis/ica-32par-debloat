# HiRMS whitelist overlay (`hirms_rro`)

A tiny **static RRO** (runtime resource overlay) that keeps this TV's vendor RAM
manager from force‑killing the D‑pad nav app.

## Why it's needed

`com.hisilicon.rmservice` (**HiRMS**) force‑stops background apps under memory
pressure — notably ~90 s after every boot. It decides what to spare from a
**hardcoded string‑array whitelist** baked into `HiRMService.apk`
(`array/str_app_whitelist`, resource `0x7f010008`), matching by **process name**
and ignoring oom priority — so a foreground service does *not* protect against
it. There's no config file or shared‑pref to edit.

The clean fix is to overlay just that one array. This RRO targets
`com.hisilicon.rmservice` and replaces `str_app_whitelist` with the **original 33
entries plus `com.cleanhome.dpadnav`**. HiRMS keeps working exactly as before for
everything else; it just stops killing the nav app.

## How it's built & installed

No Gradle — it's a resources‑only APK built straight with `aapt2`:

```bash
AAPT2=".../build-tools/34.0.0/aapt2.exe"
ANDROID_JAR=".../platforms/android-35/android.jar"

"$AAPT2" compile -o compiled.zip --dir res
"$AAPT2" link -o hirms_rro.apk --manifest AndroidManifest.xml -I "$ANDROID_JAR" compiled.zip

# sign with the platform debug key (the device is userdebug / dev-keys)
zipalign -p -f 4 hirms_rro.apk hirms_rro_aligned.apk
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
  --ks-key-alias androiddebugkey --key-pass pass:android hirms_rro_aligned.apk

# install as a static overlay on a system overlay partition, then reboot
adb -s 192.168.1.53:5555 shell "mount -o remount,rw /; mkdir -p /system/product/overlay/rroHirms"
adb -s 192.168.1.53:5555 push hirms_rro_aligned.apk /system/product/overlay/rroHirms/HirmsRro.apk
adb -s 192.168.1.53:5555 shell "chmod 644 /system/product/overlay/rroHirms/HirmsRro.apk; \
  chown root:root /system/product/overlay/rroHirms/HirmsRro.apk; restorecon -R /system/product/overlay/rroHirms"
adb -s 192.168.1.53:5555 reboot
```

`isStatic="true"` in a system overlay partition means it auto‑enables at boot with
no `cmd overlay enable` needed. Verify after reboot:

```bash
adb shell "cmd overlay list com.hisilicon.rmservice"   # expect [x] com.aipuda.rro.hirms
adb shell "cmd overlay dump com.aipuda.rro.hirms"       # idmap maps 0x7f010008 -> our array
```

## Notes

- The whitelist entries in `res/values/arrays.xml` were read out of the stock
  `HiRMService.apk` on this firmware; they must stay in sync with that build, so
  if the firmware updates HiRMS, re‑dump the array and rebuild.
- Removed by `tools/restore.*` (deletes `/system/product/overlay/rroHirms/`).
