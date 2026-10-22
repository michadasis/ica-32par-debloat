# Dpad Nav — D‑pad navigation for the Netflix **phone** app on this TV

This TV ships the **phone/tablet** Netflix build (`com.netflix.mediaclient`), not the
Android‑TV (leanback) one — the set isn't Widevine‑L1 certified, so the TV build refuses
with error **‑13** (`KEYBOX NOT LOADED` → L3). The phone app is a touch UI, so the stock
firmware drives it with a **virtual mouse cursor** on the D‑pad, which is miserable to use.

`Dpad Nav` is a tiny **AccessibilityService** that turns the remote's D‑pad into real
focus navigation on top of the Netflix phone app — browsing, the player, and search —
without the mouse.

## What it does

- **Browse screens:** draws a Netflix‑style white focus ring over the on‑screen items
  (read from the accessibility tree), moves it with the D‑pad (spatial navigation that
  auto‑scrolls rows), and clicks with **OK** (`ACTION_CLICK`). The ring hugs each tile,
  clips off‑screen with the tile, and sits *behind* the bottom tab bar.
- **Player:** Netflix's player exposes almost nothing to accessibility, so control is via
  Netflix's **media session** (reliable) plus a few known positions:
  - **←/→** = seek (`MEDIA_REWIND` / `MEDIA_FAST_FORWARD`)
  - **OK** = play/pause (`MEDIA_PLAY_PAUSE`)
  - **Up** = a control grid: **Skip Intro** (node‑click), ◄◄10 / play‑pause / +10►► ,
    a **progress bar** (Left/Right scrub, Up/Down leave), and the bottom bar
    (Speed, Lock, Episodes, Audio & Subtitles → opens the submenu for navigation, Next Ep)
  - **Back** closes the grid, then exits the player
- **Search:** the system keyboard (`LatinIME`) exposes no key nodes, so the keys are added
  as virtual navigation targets at known positions, and **OK edits the search field text
  directly** (`ACTION_SET_TEXT`) — no fragile key‑tapping. Live results update as you type.

Only active while Netflix is foreground; everywhere else it's idle and passes keys through.

## How it stays alive / persists

- **Foreground keep‑alive service** (`KeepAliveService`): this TV's vendor RAM manager
  (HiRMS) force‑stops normal background apps; a foreground service keeps the process at a
  priority HiRMS leaves alone. (Cost: a silent minimal notification.)
- **Boot receiver** (`BootReceiver`): the firmware drops adb‑enabled accessibility services
  on reboot, so on `BOOT_COMPLETED` it re‑adds itself to `enabled_accessibility_services`.
  Needs `WRITE_SECURE_SETTINGS`, granted once over adb (below).
- **Root boot service** (`bootfix/`): a **power‑off** (cold boot) also leaves the app
  *force‑stopped*, and stopped apps never receive `BOOT_COMPLETED` — so the receiver alone
  isn't enough. A tiny root init service (`/vendor/etc/init/dpadnav.rc` →
  `/vendor/bin/dpadnav_boot.sh`) re‑enables the service on `sys.boot_completed` regardless of
  app state. (SELinux on this build is Permissive, so the script can run `settings`.)

## Build & install

```bash
cd dpadnav
./gradlew.bat assembleDebug           # or ./gradlew on *nix
adb -s 192.168.1.53:5555 install -r -g app/build/outputs/apk/debug/app-debug.apk

# grant the permission that lets it re-enable itself on boot
adb -s 192.168.1.53:5555 shell pm grant com.cleanhome.dpadnav android.permission.WRITE_SECURE_SETTINGS

# enable the accessibility service
adb -s 192.168.1.53:5555 shell "settings put secure enabled_accessibility_services \
  'com.hisilicon.rmservice/.HiRMService:com.cleanhome.dpadnav/com.cleanhome.dpadnav.DpadService'; \
  settings put secure accessibility_enabled 1"

# install the root boot service so it survives power-offs (cold boots)
adb -s 192.168.1.53:5555 shell "mount -o remount,rw /vendor"
adb -s 192.168.1.53:5555 push bootfix/dpadnav_boot.sh /vendor/bin/dpadnav_boot.sh
adb -s 192.168.1.53:5555 push bootfix/dpadnav.rc /vendor/etc/init/dpadnav.rc
adb -s 192.168.1.53:5555 shell "chmod 755 /vendor/bin/dpadnav_boot.sh; chmod 644 /vendor/etc/init/dpadnav.rc; \
  restorecon /vendor/bin/dpadnav_boot.sh /vendor/etc/init/dpadnav.rc"
```

It also needs the virtual mouse **off** for Netflix so the D‑pad reaches the service —
`com.netflix.mediaclient` is added to `mMousePackageBlackList` (and removed from
`mFirstShowMousePackageList`) in `/vendor/menu_config_xml/mouse_control.json`.

## Remove

`tools/restore.*` does this for you (uninstalls the app, drops it from the accessibility
list, and restores `mouse_control.json`), or manually:

```bash
adb -s 192.168.1.53:5555 shell "settings put secure enabled_accessibility_services 'com.hisilicon.rmservice/.HiRMService'"
adb -s 192.168.1.53:5555 uninstall com.cleanhome.dpadnav
# restore reference/mouse_control_ORIGINAL.json to /vendor/menu_config_xml/mouse_control.json
```

## Notes / limits

- Netflix playback stays **SD/L3** (the phone app) — this only changes *navigation*, not DRM.
- Key positions (player controls, keyboard) are for this panel's **1280×720** layout; they're
  stored as screen fractions so they scale, but a very different Netflix layout could shift them.
- Tuning knobs live near the top of `DpadService.java` (`CTRL` / `CTRL_KEY` grid, `KEYS` /
  `KEY_CHARS` keyboard map, the off‑axis weight in `pickDirectional`).
