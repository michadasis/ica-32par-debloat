# Clean Home — AIPUDA / CVTE Android TV debloat + launcher

Work done over ADB on a **HiSilicon Hi3751V350 / CVTE StarkOS** Android 14 TV
(board `TP.SK529S.PB803D`, 1 GB RAM, 32-bit `armeabi-v7a`, Greek locale).
IP during setup: `192.168.1.53` (wired Ethernet), port 5555.

> ### About this repo / read first
> - This documents and reproduces a debloat + de-ad + home cleanup for one specific
>   white-label CVTE/HiSilicon Android-TV model. Other CVTE "StarkOS/Saturn" sets are
>   similar but **not identical** — package names, IDs and the skin/overlay internals
>   can differ. Verify against your own device before running anything.
> - **Risk:** some steps modify `/system` and `/vendor` and re-sign system overlays.
>   A bad launcher overlay can crash-loop the home screen (recoverable). Keep a USB
>   mouse/keyboard attached and know your factory-menu codes. **Use at your own risk.**
> - **Not included (on purpose):** the device backups under `backup/` — they hold the
>   **proprietary stock APKs** (CVTE launcher/skin/overlay — not redistributable) plus
>   device-specific data (serial, MAC, screenshots). The restore scripts' *app* and
>   *hosts* restores work without them; the *APK/overlay/logo* restores need backups
>   **you** make before modifying (the project's "Back up first" step).
> - Edit the hardcoded **IP `192.168.1.53`** and paths in `tools/*` for your setup.
> - `tools/*.ps1` are PowerShell (Windows); `tools/restore.sh` is the bash equivalent.
> - Full hardware spec + every preinstalled app: **[`docs/TV_SYSTEM_REFERENCE.md`](docs/TV_SYSTEM_REFERENCE.md)**.
>   Recovery: **[`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md)**.

## What was changed (all three are durable — they survive reboots)

1. **Debloat** — 10 apps removed for user 0 (`pm uninstall --user 0`; the APKs stay
   in `/system`, so it's fully reversible). On this firmware `pm disable` does **not**
   survive a reboot, which is why uninstall-for-user was used instead.
2. **Home-screen ads removed** — the stock launcher has ad SDKs (Zeasn/WhaleAds +
   Airmoba) baked in. Their servers are blocked in `/system/etc/hosts`
   (see `reference/hosts_adblock.txt`).
3. **Clean Home launcher** — a small native Android TV launcher was built and tested,
   but **removed from the TV** (see below). The project still lives in `launcher/` and
   can be rebuilt/installed anytime with `tools\build_and_install.ps1`.

### Why the launcher was removed

This firmware **hard-locks the Home button to CVTE's own launcher family**
(`saturn`/`venus`/`mars`/…) behind a manufacturer signature check. A third-party
launcher cannot take over Home by any safe method — `set-home-activity` works but the
Home key ignores it, and the setting doesn't even persist a reboot. Disabling the stock
launcher just makes Home fall back to System Settings, never to ours. Since it could
only ever run as a regular app (not the real Home), it was uninstalled as unnecessary.

Because Home stays on the stock launcher, the **ad block (#2) is what keeps your home
screen clean day-to-day.** If you ever want the launcher back as an app, run
`tools\build_and_install.ps1`.

## The 15 debloated apps (in `reference/debloat_list.txt`)

| Package | What it was |
|---|---|
| com.cvte.industries | home-screen ad/content engine (StarkIndustries) |
| com.cvte.tv.ucservice | CVTE account/telemetry |
| com.zeasn.services.general | Zeasn ad/data platform |
| com.google.android.gms.location.history | Google location history |
| com.google.android.partnersetup | partner/preload telemetry |
| com.metax.browser.cvte | preloaded web browser |
| com.cvte.tv.steward | "cleaner/booster" |
| com.cvte.tv.tools | CVTE tools |
| com.stark.bytelloremoteserver | phone-as-remote server |
| com.cvte.tv.systemupgrade | OTA updater |
| com.cvte.dssshare.dlna | DLNA casting |
| com.hisilicon.miracast | Miracast screen mirroring |
| com.maxhub.screenshare.server.tv | MaxHub screen-share server |
| com.screenshare.platform | CVTE screen-share platform |
| com.hisilicon.higallery | photo viewer (photos only; no video) |

Kept on purpose: TV/HDMI engine, remote & keyboard services, Bluetooth, Settings,
Play Store/Services, StarkOS store, the **Video app** (`com.cvte.tv.media` — plays
`video/*` from USB, e.g. One Pace), and all 5 streaming apps (YouTube, Netflix,
Spotify, HBO Max, Disney+).

## How to undo things

- **Re-enable all debloated apps + remove the ad block:** run `tools\restore.ps1`
  (PowerShell) or `tools/restore.sh` (Git Bash), then reboot. Idempotent.
- **Re-enable one app only:**
  `.\adb.exe -s 192.168.1.53:5555 shell pm install-existing --user 0 <package>`
- **Remove the ad block only:** restore the hosts file —
  `tools\restore.ps1` does this, or manually delete the `# --- CleanHome … ---` block
  from `/system/etc/hosts` (needs `adb root` + `mount -o remount,rw /`). The original
  file is backed up on the TV at `/system/etc/hosts.cleanhome.bak` and locally at
  `backup/hosts.original.txt`.
- **Re-apply the ad block** (e.g. if ever reset): `tools\adblock.ps1`, then reboot.
- **Uninstall the launcher:** `.\adb.exe -s 192.168.1.53:5555 shell pm uninstall com.cleanhome.tv`

## Changing the launcher

Project: `launcher/`. Rebuild + reinstall with `tools\build_and_install.ps1`
(uses the bundled Gradle wrapper; needs the Android SDK already on this PC).

- **Dock apps / order:** edit `launcher/app/src/main/res/values/config.xml` →
  `dock_packages` (list the 4 packages in the left-to-right order you want). Anything
  launchable that is NOT in that list shows in the grid below. Rebuild + install.
- **Hide an app from the grid:** add its package to `hidden_packages` in the same file.
- **Background / wallpaper:** the design is intentionally pitch-black. To use an image,
  put a drawable in `launcher/app/src/main/res/drawable/` and set it as the background of
  `@+id/root` in `res/layout/activity_main.xml` (keep it dark so text stays readable).
- **Language:** starts in Greek; the top-right **EL/EN** pill toggles it live (remembered).
- **Font:** Outfit (OFL), `res/font/outfit_variable.ttf`.
- **Inputs pill:** opens the TV's native source picker (HDMI/AV/DTV). Apps can't switch
  inputs directly on this firmware, so the native picker is used — it is the real TV
  input framework.

## Weather widget (real data from our own source)

The widget's weather came from `com.zeasn.services.general` (debloated) with a CVTE
fallback app that isn't installed — so it went blank. Fixed by a tiny app we built,
**`com.cleanhome.weather`** (project: `weatherprovider/`), which answers the exact
ContentProvider the launcher reads
(`content://com.cvte.tv.weather.export.WeatherDataContentProvider/weather`) with live
data from **Open-Meteo** (free, no API key). Currently set to **Kastoria**.

- **Change the city:** edit `LAT`/`LON`/`CITY` at the top of
  `weatherprovider/app/src/main/java/com/cleanhome/weather/WeatherProvider.java`, then
  rebuild + install: `weatherprovider\gradlew.bat assembleDebug` and
  `adb -s 192.168.1.53:5555 install -r weatherprovider\app\build\outputs\apk\debug\app-debug.apk`,
  then `adb ... shell am start -n com.cleanhome.weather/.InitActivity`.
- Refreshes every ~30 min (on query) and on boot. No key, no tracking.
- Remove it with `adb ... shell pm uninstall com.cleanhome.weather` (widget goes blank again).

**Label fix + one line:** the stock Greek labels were wrong (`ΜΕΓΙΣΤΟΣ` and `ΛΕΠΤΟ` = "minute").
Corrected to **Μέγιστη / Ελάχιστη** via string overrides in the launcher's resource overlay
(`com.aipuda.rro.launcher.saturn`, `/system/product/overlay/rroLauncherSaturn/…`). The widget font
(`bebas`, Bebas Neue — no Greek glyphs, so Greek wrapped) is also overridden in that overlay with
**Fira Sans Condensed** so the labels fit on one line (this makes the whole widget use one condensed
font). Overlay re-signed with a debug key and redeployed; original backed up at
`backup/saturn/RRO_LauncherSaturn.apk`; `tools/restore.*` restore it.
Note: do **not** try to fix the wrapping by overriding the widget *layout* — a layout override made
the launcher crash-loop on the home screen (recovered by restoring the overlay).

**"Smart TV" logo removed:** `/vendor/menu_config_xml/skin_logo.png` was replaced with a transparent
image (original at `backup/saturn/skin_logo_ORIGINAL.png`; restored by `tools/restore.*`).

See also **`docs/TV_SYSTEM_REFERENCE.md`** — full hardware spec + every system app explained.

## Navbar tabs (removed Βίντεο, Μουσική, Βοηθός τηλεόρασης, Περιηγητής)

The stock launcher's bottom menu tabs come from its **skin** APK
(`/vendor/app/Launcher-skin-169/Launcher-skin-169.apk`), in `assets/skininfo`
(a JSON `layout_json` list). I removed those four tabs by deleting their entries,
re-signing the skin with a debug key, and replacing it in `/vendor` (PM accepts a
resigned `/vendor` base app). Current tabs: Source, Apps, Home, App Store,
Πολυμέσα, Settings, YouTube, Netflix, Add.

- Modified signed skin: `backup/saturn/skin_aligned.apk`; decoded project: `backup/saturn/skin_dec/`; pristine original: `backup/saturn/skin.apk`.
- To change tabs again: edit `backup/saturn/skin_dec/assets/skininfo` (drop/re-add layout_json entries by their `id`; see `backup/saturn/skin_dec/assets/DKNRsm…` for id→label), then `apktool b`, zipalign, apksigner (debug key), push to the `/vendor` path, `pm clear com.cvte.tv.launcher.saturn`, reboot.
- `tools/restore.ps1` / `.sh` restore the **original** skin (all tabs back).
- Caveat: a firmware OTA could overwrite the skin — but the OTA updater was removed, so this is unlikely.

## Netflix D‑pad navigation (`dpadnav/`)

The TV ships the **phone** Netflix app (the Android‑TV build is blocked — the set isn't
Widevine‑L1 certified, error ‑13 / `KEYBOX NOT LOADED`), and the firmware drives that touch
UI with a clunky **virtual mouse** on the D‑pad. `dpadnav/` is a small **AccessibilityService**
that replaces the mouse with real D‑pad focus navigation for Netflix — browse, the full player
grid (Skip Intro, play/pause, ±10, progress‑bar scrub, next episode, audio/subtitle submenu),
and an on‑screen‑keyboard for **search** (types by setting the field text directly). It shows a
Netflix‑style white focus ring and is idle outside Netflix.

- It survives reboots on its own: a `BootReceiver` re‑enables the service on boot (needs
  `WRITE_SECURE_SETTINGS`, granted once over adb), and a foreground keep‑alive service stops
  the vendor RAM manager (HiRMS) from killing it.
- Needs the virtual mouse **off** for Netflix (`com.netflix.mediaclient` blacklisted in
  `/vendor/menu_config_xml/mouse_control.json`; original at `reference/mouse_control_ORIGINAL.json`).
- Build/install/remove and the full key map are in **`dpadnav/README.md`**.
- `tools/restore.*` uninstall it and restore the mouse for Netflix.

## Known limitations / not 100% guaranteed

- Netflix stays SD (phone app / Widevine L3); `dpadnav` only changes navigation, not DRM.
- Launcher cannot be the real Home (firmware lock — see above).
- Grid shows up to ~3 rows cleanly; it does not internally scroll for very large app
  counts (fine for the current ~10 apps).
- Clock updates on a 15 s tick (minute display can lag a few seconds).
- Ad block depends on the hosts file; a firmware **OTA could reset it** — but the OTA
  updater was removed, so this is unlikely. Re-run `tools\adblock.ps1` if needed.

## Security note — network ADB

This TV runs ADB over the network on port 5555 **with no authentication**
(`ro.adb.secure` unset, `persist.adb.tcp.port=5555`). Anyone on your LAN can get a shell.
When you're done making changes, close it (see `tools\close_adb.ps1`): it disables
network ADB, clears the persistent port, and reboots. Re-enable later from the TV's
Developer options, or with a USB cable + `adb tcpip 5555`.

## Files

```
README.md          this file
docs/              TV_SYSTEM_REFERENCE.md (hardware + apps), TROUBLESHOOTING.md
tools/             restore.ps1/.sh, build_and_install.ps1, adblock.ps1, close_adb.ps1
reference/         debloat_list.txt (the 15 removed pkgs), hosts_adblock.txt, mouse_control_ORIGINAL.json
launcher/          the Clean Home Android TV launcher (Gradle source)
weatherprovider/   the weather ContentProvider app (Gradle source)
dpadnav/           Netflix D-pad navigation accessibility service (Gradle source)
```
(Device backups of the stock APKs aren't in the repo — see "About this repo" above.)
