# ICA 32PAR — Full System Reference

Reverse-engineered documentation for an **ICA 32PAR** 32" HD LED TV — an obscure
Greek-market white-label set with **no public documentation**. Internally it is a
**CVTE "StarkOS"** Android-TV build on a **HiSilicon Hi3751V350** SoC. This maps the
*whole* system (hardware, firmware, partitions, every app, the CVTE framework, HALs,
config layer, and the ad/launcher/weather internals), not just what was modified.

> Notation: **[kept]** untouched · **[REMOVED]** uninstalled for user 0 (reversible) ·
> **[modified]/[added]** changed/added by the debloat project. Serial/MAC are omitted.

---

## 1. Identity (retail → internal)

| Layer | Value |
|---|---|
| Retail brand / model | **ICA 32PAR** — "32" HD LED TV", Dolby Audio, 45 W, 100–240 V, made in P.R.C. |
| Serial format | `32PAR25…` (model + year `25` + unit) |
| OEM/ODM firmware | **CVTE StarkOS** (CVTE = Shenzhen/Guangzhou TV-board ODM) |
| Firmware customer | `ro.cvte.customer = AIPUDA_529_AN14` (brand integrator "AIPUDA") |
| White-label chain | `persist.gaiaos.product.*` = GAIAOS / manufacturer SHIKUN |
| Internal model id | `ro.cvte.model = MD_AIPUDA_OVERSEA_HI3751V350_14__STARKOS` |
| Ad/content partner | Zeasn / WhaleOS (`persist.sys.wtv.brand = zeasn`, UA `WhaleAds`) |

So: **ICA** (retail) → **AIPUDA/GAIAOS** (integrator) → **CVTE StarkOS** (firmware) →
**HiSilicon Hi3751V350** (silicon) → **Zeasn** (cloud ads/content/weather).

## 2. Hardware

| Component | Detail |
|---|---|
| SoC | **HiSilicon Hi3751V350** — platform `bigfish` (`ro.board.platform`/`ro.hardware`) |
| CPU | ARM **32-bit only** (`armeabi-v7a`, `armeabi`) — no arm64 |
| GPU | **Mali-450** (OpenGL ES 2.0-class; no heavy 3D, avoid runtime blur) |
| RAM | **1 GB** (`ddr3_512X2MB` = 2×512 MB DDR3) |
| Flash | **8 GB** eMMC (`f9830000.emmc`) |
| Mainboard | **CVTE TP.SK529S.PB803D** (alt PB803E), `ro.cvte.deviceversion = DV2` |
| Panel | **LC320DXJ_SHAC** (~32"), backlight duty 75 |
| Tuner | **ATBM253** (DVB demod; supports DTV + ATV) |
| Display | **1280×720 @ 60 Hz** internal; modes 10/15/20/30/60 fps; claims **HDR10 + HLG**, FLAG_SECURE + protected buffers; density **160** (1 px = 1 dp) |
| Audio | **Dolby Audio** (`persist.sys.board.support.dolby = 1`); audio DSP trusted-app `adsp_monitor_ta`; low-latency output |
| Motion | FRC (frame-rate conversion) thread `thread_frc_mana` |
| Remote | IR, enumerates as input device **"Hi keyboard"** (`ro.ir.device.name`); also a virtual **"Hi mouse"** and "Hi Keypad" |
| Connectivity | **Ethernet** (used), Wi-Fi + Wi-Fi Direct, Bluetooth + BLE, USB host |
| Camera/mic | none built in; **external USB camera** supported (`camera.external`); no microphone/touchscreen |
| Inputs | DTV, ATV, AV (composite), HDMI 1/2/3 |

## 3. Firmware & boot

| | |
|---|---|
| Android | **14** (API 34), `UP1A.231105.003.A1` |
| Build type | **userdebug / eng**, **dev-keys** (`eng.builde.20250418…`) → `adb root` works, `ro.debuggable=1` |
| Build fingerprint | `HiDPT/Hi3751V352E_DMO/Hi3751V350:14/UP1A.231105.003.A1/eng.builde.20250418.145603:userdebug/dev-keys` |
| CVTE baseline | `3.2.0`; OTA version `20250418_145146`; upgrade bin `allupgrade_an14_V3520D0E_8G_ddr3_512X2MB_75.bin` |
| OTA server | `myou.cvte.com` (updater `com.cvte.tv.systemupgrade`, APPKEY in `tv_systemota_config.xml`) **[REMOVED]** |
| Region | Greece (`ro.cvte.country.list = GR`), Greek UI, date `dd/MM/yyyy` |
| ADB | **network ADB on 5555, plaintext, no auth** (`ro.adb.secure` unset, `persist.adb.tcp.port = 5555`) — any LAN device can shell in |

### Partitions (eMMC `mmcblk0`)

| Mount | Block | Size | FS | Notes |
|---|---|---|---|---|
| `/` (system-as-root) | p21 | 1.4 G (83% full) | ext4 ro | system + `/system/product`, `/system/system_ext` |
| `/vendor` | p22 | 469 M (69%) | ext4 ro | HALs, `menu_config_xml`, Saturn + skin base APKs |
| `/data` | p27 | 3.7 G | ext4 rw | user data, updated-system-app APKs (`/data/app`) |
| `/cache` | p25 | 856 M | ext4 rw | OTA cache |
| `/atv` | atv | — | ext4 rw | analog-TV / tuner data |
| `/securestore` | securestore | — | ext4 rw | DRM/HDCP keys, secure items |
| `/apex/*` | from p21 | — | — | ~30 standard Android Mainline APEX modules |

### Firmware quirks (undocumented — found the hard way)

1. **Component enable-state is NOT persisted.** `pm disable`/`disable-user` works at runtime
   but is lost on reboot (the launcher/system reset it; `enabled=` isn't written to
   `package-restrictions.xml`). **Durable debloat = `pm uninstall --user 0`** (survives reboot;
   reverse with `pm install-existing`).
2. **A kill-whitelist keeps ad/system processes alive.** `HiRMS_RamManager` (in
   `com.hisilicon.rmservice`) logs *"Ignore KillProcess WhiteList"* for e.g. `com.cvte.industries`,
   so `am force-stop` can't kill protected processes.
3. **The HOME key is hard-locked to the CVTE launcher family.** It is routed to
   `com.cvte.tv.launcher.{saturn,venus,mars,space,haumea,mosaix,fall}` by a manufacturer-gated
   handler, **ignoring** the Android default-home setting (which also doesn't persist). With
   Saturn disabled, HOME falls back to *System Settings*, never to a third-party launcher — so a
   custom launcher **cannot** become Home on this firmware.
4. **Re-signed partition apps are accepted.** APKs on the read-only `/vendor` and `/product`
   partitions are trusted by the partition; replacing one (remount rw, `cp`) with a **debug-signed**
   rebuild is accepted by PackageManager on reboot. This is how the launcher **skin** and the
   **RRO overlay** were edited (a layout override via RRO, however, crash-loops the launcher — do
   not do that; resource/string/font overrides are safe).

## 4. The CVTE TV framework (how the "TV" part works)

The Android apps talk to the panel/tuner/HDMI through a CVTE middleware stack on top of
HiSilicon HALs:

- **Native daemon `cvte-central-control-native`** (`init.svc central-control-native`) — the core
  TV control service.
- **`virtualkeypad`** / **`vendor.ir_user`** — turn IR remote signals into Android key events
  (remote works even with all apps removed).
- **HiSilicon vendor HALs** (all `vendor.hisilicon.hardware.*`):
  `hwdtv@1.0` (DVB tuner), `hwtvmw@1.0` (TV middleware), `hwsysmanager@1.0` (system/panel),
  `hwandroidmediahal@2.0` + `hwnxmediaplayerhal@1.1` (media), plus `hiavplayer` and OMX.
- **DRM/security HALs:** `drm-widevine-hal-1-3` (**Widevine** present), `drm-hal-1-0`,
  `keymaster-3-0`, `gatekeeper-1-0`, `gralloc-2-0`.
- **`com.cvte.tv.api.impl`** — the Java API layer apps use (`HotkeyService`, input, TV ops),
  exposed via the `cn.cvte.tv.api.jar` shared library.
- **`com.cvte.tvservice`** — core TV service.
- **`com.cvte.tv.globalui`** — the on-screen input-source picker and option menus
  (`InputSourceActivity`, actions `cvte.intent.action.SHOW_UI[_WITHOUT_VALUE]`).
- **`com.vendor.vnaudiomanager`** — audio/Dolby routing.
- Vendor tools in `/vendor/bin`: `pqserver` (picture quality), `factoryburnkey` (factory key
  burn), `cvte_usb_upgrade.sh` (USB firmware flash), `sample_atv_rf_play` (ATV RF test).

## 5. The configuration layer — `/vendor/menu_config_xml/`

CVTE drives most of the UI/behaviour from editable config files here (path from
`ro.cvte.config.path`). Documented contents:

| File | What it configures |
|---|---|
| `cvte_launcher_config.json` | Launcher: **Logo** (`skin_logo.png`), **Wallpaper** (`default_wallpaper.png`), QuickBar buttons (account/setting), `StarkStorePackage`, poster dir, MX Player enable. |
| `skin_logo.png` | The top-left **"Smart TV" logo** (240×76). **[modified → transparent]** |
| `default_wallpaper.png` | Home wallpaper (3 MB canyon image). |
| `hotkey.xml` (44 KB) | **Remote-control key map** — every remote button → action. |
| `menu_tree_settings.xml` / `menu_tree_tvsettings.xml` / `menu_tree_quickmenu.xml` / `menu_tree_provision.xml` | The **Settings / TV-Settings / Quick-menu / setup-wizard menu trees**. |
| `tv_assistant_menu_tree.xml` | The "TV Assistant" (electric-manager) menu. |
| `tv_media_center_config.xml` | Media-center / USB-browser config. |
| `api_service_options_config.xml` | TV API options — **sound modes** (Standard/Music/Theater/News/User), **screen modes** (16:9/4:3/Zoom per input). |
| `setting.properties` (77 KB) | Factory default settings for everything. |
| `pq/pq_table_v350*.xml` (0.6 MB + 1.8 MB) + `pq.c` | **Picture-quality calibration** tables for the Hi3751V350 + this panel. |
| `timezones.xml` | Time-zone database. |
| `parent_lock_config.xml` | **Parental-rating** levels per country. |
| `mouse_control.json` | Virtual-mouse app white/black lists (e.g. forces mouse first in Netflix). |
| `maxhubshare.properties` | Screen-share/cast tuning (AirPlay, Google Cast, resolution, decode). |
| `elephant_config` | **Screensaver** ("elephant" photo-frame): image types, animations, blacklist. |
| `stark_store_black_list.json` | Apps **hidden from the StarkOS store** (system apps + the CVTE launcher "planet" family). |
| `tv_systemota_config.xml` | OTA APPKEY. |
| `license/` | OSS **license files** = the CVTE apps' stack: ARouter, ButterKnife, dagger, EventBus, fastjson/gson, greenDAO, Glide/Picasso, **ijkplayer**, netty, **okhttp/retrofit**, nanohttpd, protobuf, zxing. |
| `credential`, `pq/`, `readme.txt`(empty) | misc. |

*Note:* `stark_store_black_list.json` reveals CVTE ships a family of interchangeable launchers —
`saturn` (this one), `venus`, `mars`, `space`, `haumea`, `mosaix`, `fall` — but only **saturn** is
installed here.

## 6. The stock launcher "Saturn" — internals

`com.cvte.tv.launcher.saturn` (`.home.MainActivity`, uid 10052). **Updated-system-app**: base in
`/vendor/app/LauncherSaturn/`, active update in `/data/app`. Signed with the CVTE platform key.

- **Tab bar comes from a separate SKIN app** `com.cvte.tv.launcher.saturn.skin`
  (`/vendor/app/Launcher-skin-169/Launcher-skin-169.apk`). Its `assets/skininfo` JSON holds a
  `layout_json` (which tabs show, by position) and a `data_json` (per-tab `package`, `executive`,
  `fragment`, `label`, icons) + a `title_json` (localized labels). Executives seen:
  `EmptyExecutive`, `OpenAppExecutive`, `StarkStoreDataExecutive`, `CollectAppExecutive`,
  `SourceSelectExecutive`; fragments: Home/Apps/AppStore/Video/Browser/Media/TvAssistant/
  Setting/Source/Add. Loaded by `SkinLoaderViewModel` via `getApplicationInfo(skin).sourceDir`,
  md5-checked. **[skin modified]** → removed the Βίντεο/Μουσική/Βοηθός τηλεόρασης/Περιηγητής tabs.
  (A `TabEntrance` Room DB `tab_entrance_database` exists in code but is unused here.)
- **Resource overlay** `com.aipuda.rro.launcher.saturn` (static RRO,
  `/system/product/overlay/rroLauncherSaturn/`, priority 1, targets Saturn) — the brand's resource
  tweaks. **[modified]** → fixed the weather labels (Μέγιστη/Ελάχιστη) + condensed Greek font.
- **Weather widget** `com.cvte.tv.smartuikit.widget.Weather` (`widget_weather_layout.xml`, font
  `@font/bebas` = Bebas Neue, no Greek glyphs). See §8.
- **Clock/date widget** `widget_time_layout.xml`.
- **Apps grid** opens `com.stark.store/.applist.ui.AppListActivity`.
- **Built-in ads** — see §7. **Weather + ads share the top panel**, cycling.

## 7. The advertising system (deep dive)

Ads come from **two** places, both now neutralised:

1. **`com.cvte.industries`** ("StarkIndustries", `/system/app/StarkIndustries/`) — a **PERSISTENT
   system app** running as uid `system`. Its `.contents.MessengerService`
   (`com.cvte.intent.ACTION_INDUSTRY_MESSENGER`) + `AdManager.startAd` / `AdReceiver` feed the
   home-screen "masthead" ad. Protected by the HiRMS kill-whitelist. **[REMOVED]**
2. **Ad SDKs baked into Saturn itself** — `LnBannerController`/`LnVideoController` +
   `AdConfigFetcher` (**Zeasn/WhaleAds**), **Airmoba** (`AirmobaController`), and **MetaX**
   (`assets/MetaXAdPlugin-1.10.apk`, `metax_ad_sdk.db`). Saturn's assets also hold
   `WhaleTV_AD_SDK_8000643.zip`, `ad_logo.mp4`, `ad_picture.html`, `ad_video.html`.

Ad config is fetched per rule (`SCREENMASTHEAD`, `suspension`), country `gr`, advertisers
`leiniao / aniview_video / whale / MetaX / Airmoba / coolita`, UA
`ZeasnAOSP/14 … DeviceType=2025_WhaleAds_CVTE_Launcher_Saturn`.

**Ad/telemetry servers (blocked in `/system/etc/hosts`):** `ssp.zeasn.tv`, `recommend.zeasn.tv`,
`ping.zeasn.tv`, `log.saas.zeasn.tv`, `saas.zeasn.tv`, `cache.zeasn.tv`, `deviceportal.zeasn.tv`,
`whalelive.zeasn.tv`, `adserver.srafcloud.com`, `on-euads-o.api.leiniao.com`, `api.leiniao.com`.

## 8. The weather pipeline

The widget (`WeatherServer`) tries two local **ContentProviders** in order:

1. `content://com.zeasn.services.general/query` — Zeasn cloud weather (needs `com.zeasn.services.general`, **[REMOVED]**).
2. `content://com.cvte.tv.weather.export.WeatherDataContentProvider/weather` — CVTE path (needs a
   `com.cvte.tv.weather` app that **isn't installed** on this build).

Both were missing → the widget went blank. Columns read: `curTemperature, min, max, weatherText,
weatherIcon, temperatureUnit, location, weekDay, day, weatherTextNight, weatherIconNight,
weatherCode`. `weatherIcon` is an **AccuWeather 1–44 icon number** (icons from `static.cvte.com`);
the CVTE cloud endpoint is `http://tvweb.cvtapi.com/api/util/weatherinfo`.

**Fix:** added **`com.cleanhome.weather`** [added] — a tiny app exposing that exact CVTE provider
authority, serving live data from **Open-Meteo** (free, no key), mapping WMO→AccuWeather codes,
set to Kastoria. Refreshes ~30 min + on boot.

## 9. Software inventory

### 9a. CVTE / StarkOS

| Package | Role | Status |
|---|---|---|
| com.cvte.tvservice | Core TV service (tuner/HDMI/picture/sound engine). | kept |
| com.cvte.tv.api.impl | TV API layer (`HotkeyService`, input, TV ops). | kept |
| com.cvte.tv.globalui | Input-source picker + option menus. | kept |
| com.cvte.tv.media | Media/USB player & browser — Βίντεο/Μουσική/Πολυμέσα/photos; plays `video/*` from USB. | kept |
| com.cvte.tv.launcher.saturn | Stock home launcher (ads + tab bar). | kept |
| com.cvte.tv.launcher.saturn.skin | Launcher skin (tab/wallpaper definitions). | modified |
| com.aipuda.rro.launcher.saturn | Launcher resource overlay. | modified |
| com.aipuda.rro.settings | Settings resource overlay. | kept |
| com.cvte.settings / com.cvte.tv.setting | Settings apps (system + TV). | kept |
| com.cvte.tv.provision | First-boot setup wizard. | kept |
| com.cvte.fac.menu / com.cvte.factory.at / com.cvte.factory.service | Factory menu / auto-test (AT) / factory service. | kept |
| com.cvte.license.at | HDCP / Widevine / CI+ licensing. | kept |
| com.stark.store | StarkOS app store. | kept |
| com.cvte.industries | Home-screen ad/content engine. | REMOVED |
| com.cvte.tv.ucservice | "User center" account + telemetry (`TvAuthService`/`TvUcBlockService`). | REMOVED |
| com.cvte.tv.steward | "TV manager / cleaner / booster". | REMOVED |
| com.cvte.tv.tools | CVTE background tools. | REMOVED |
| com.cvte.tv.systemupgrade | OTA updater. | REMOVED |
| com.cvte.dssshare.dlna | DLNA casting receiver. | REMOVED |
| com.metax.browser.cvte | Preloaded web browser (+ MetaX ad SDK). | REMOVED |
| com.stark.bytelloremoteserver | Bytello phone-as-remote server. | REMOVED |
| com.cleanhome.weather | Weather provider (Open-Meteo). | added |

### 9b. HiSilicon (SoC vendor)

| Package | Role | Status |
|---|---|---|
| com.hisilicon.rmservice | Remote/RAM manager — bound accessibility remote-helper (`HiRMService`), `HiRMS_RamManager` kill-whitelist + process monitor. **Load-bearing.** | kept |
| com.hisilicon.higallery | Photo viewer (images only). | REMOVED |
| com.hisilicon.miracast | Miracast receiver. | REMOVED |
| (native) | `cvte-central-control-native`, `virtualkeypad`, the `vendor.hisilicon.hardware.*` HALs. | kept |

### 9c. Other vendor / white-label

| Package | Role | Status |
|---|---|---|
| com.zeasn.services.general | Zeasn/WhaleOS ad + content + **weather** cloud provider. | REMOVED |
| com.maxhub.screenshare.server.tv | MaxHub screen-share server. | REMOVED |
| com.screenshare.platform | CVTE screen-share platform (`cvte.screenshare.support.server.*`). | REMOVED |
| com.vendor.vnaudiomanager | Vendor audio/Dolby routing. | kept |

### 9d. Google Mobile Services

Play Store `com.android.vending`, Play Services `com.google.android.gms`, GSF
`com.google.android.gsf`, `…feedback`, `…apps.restore`, `…onetimeinitializer`, `…ext.shared` —
all **kept**. Removed: `com.google.android.partnersetup` (preload telemetry) and
`com.google.android.gms.location.history`.

### 9e. AOSP core (Android 14) — all **kept**

Distribution: **32** in `/system/priv-app`, **19** `/system/app`, **4** `/system/product/app`,
**1** `product/priv-app`, **4** `system_ext`, **5** `/vendor/app`, **15** `/system/product/overlay`,
**8** updated in `/data/app`, plus framework/APEX.

Key ones: `android` (framework), `com.android.systemui`, `com.android.inputmethod.latin`
(on-screen keyboard), `com.android.webview`, `com.android.bluetooth`, `com.android.networkstack`
(+ tethering), the content **providers** (`media`, `downloads`, `settings`, **`tv`** = Live-TV/EPG,
`userdictionary`), `com.android.packageinstaller`, `com.android.permissioncontroller`,
`com.android.externalstorage`/`mtp` (USB), `com.android.settings.intelligence`, `com.android.se`,
`com.android.musicfx`, `com.android.dreams.phototable` (screensaver), plus the Privacy-Sandbox/
ad-services, on-device-personalization and federated-compute framework stubs. Resource-only
overlays: `com.android.theme.font.notoserifsource`, the `…display.cutout.emulation.*` and
`…systemui.navbar.*` sets (all disabled), and `com.android.systemui:accent/neutral/dynamic`
(Material-You theming, enabled).

### 9f. Declared features

`type.television`, `software.leanback` (+ `leanback_only`), `ethernet`, `wifi` (+ `wifi.direct`),
`bluetooth` (+ `_le`), `usb.host`, `camera.external`, `audio.low_latency`/`output`,
`screen.landscape`. **No** touchscreen, microphone, or built-in camera.

---

## 10. What this project changed (and how to undo)

- **Removed 15 apps** for user 0 (ads/telemetry/updater/browser/cleaners/casting/gallery/phone-remote).
- **Blocked ad servers** in `/system/etc/hosts`.
- **Launcher skin**: dropped 4 navbar tabs. **Launcher overlay**: fixed weather labels + condensed font.
- **"Smart TV" logo** replaced with a transparent image.
- **Added** `com.cleanhome.weather` for real local weather.

All reversible — see **[`README.md`](../README.md)** (restore scripts) and
**[`TROUBLESHOOTING.md`](TROUBLESHOOTING.md)** (recovery, factory codes 2580 / 1147).
Raw survey dumps are under `backup/survey/` in the working tree.
