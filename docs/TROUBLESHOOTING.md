# Troubleshooting

## Connecting
- Connect: `.\adb.exe connect 192.168.1.53:5555` (TV on wired Ethernet).
- Git Bash mangles `/data/...` paths into `C:/...`. Prefix shell sessions with
  `export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'` (PowerShell is unaffected).
- This is a `userdebug` build, so `adb root` works and is needed for the hosts file and
  for `pm disable` on protected apps. Root drops on every reboot — re-run `adb root`.

## Installing APKs
- `adb install` can hang on Android 14 waiting for an on-screen OK. Instead push + install:
  `adb push app.apk /data/local/tmp/x.apk` then `adb shell pm install -r /data/local/tmp/x.apk`.
- Sideloading is allowed on this TV (no `INSTALL_FAILED_VERIFICATION_FAILURE`).

## Package state doesn't stick after reboot
- On this firmware, `pm disable` / `pm disable-user` is **not saved to disk** — every
  app is re-enabled on boot. Use `pm uninstall --user 0 <pkg>` (persists) and
  `pm install-existing --user 0 <pkg>` to reverse. The APKs are never deleted.

## Verifying what's on screen
- Screenshots: `adb exec-out screencap -p > shot.png`.
- Live TV / HDMI inputs screenshot as black/empty — that's the hardware overlay, not a
  fault. Check the foreground instead:
  `adb shell "dumpsys activity activities | grep -m1 topResumedActivity"`.
- Injected key events (`input keyevent`) prove the UI responds, but the physical remote's
  HOME key is routed by CVTE firmware to the stock launcher regardless of the default-home
  setting — that's why a custom launcher can't take over Home here.

## Ads came back
- Reboot clears the DNS cache; the hosts block then applies. If ads return, a new ad
  domain may be in use — capture it with
  `adb shell "logcat -d | grep -iE 'okhttp|Host:|AdController|startAd'"`, add it to
  `tools\adblock.ps1`, re-run it, and reboot.

## Recovery
- Factory menu codes (from the remote): **2580** or **1147**.
- Keep a USB mouse/keyboard attached in case a remote service is interrupted.
- Everything done here is reversible with `tools\restore.ps1`.
