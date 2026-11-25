# ADB_X — Wireless ADB enhancement Xposed module

[![Release](https://img.shields.io/github/v/release/blockman3063/ADB_X?style=flat-square&label=Download&color=1565C0)](https://github.com/blockman3063/ADB_X/releases)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-11%2B-3DDC84?style=flat-square&logo=android)](https://developer.android.com/about/versions/11)
[![Xposed](https://img.shields.io/badge/Xposed-LSPosed-1565C0?style=flat-square)](https://github.com/LSPosed/LSPosed)

> ⚠️ **Early development — expect bugs.** This project is in a very early
> stage of development. Expect rough edges, missing features, broken
> builds, and behavior that varies wildly between ROMs. Please report
> issues you hit, but do not rely on it for anything you can't recover
> from, and please don't expect stable APIs yet.

> 📖 **Looking for Chinese documentation?** See [README.zh.md](README.zh.md).

ADB_X pins the wireless-debugging port, captures the ADB pairing code
the moment it appears, and turns ADB on automatically when you join
a trusted Wi-Fi network. Most work is done with root + a small KernelSU
boot module; LSPosed is used for one thing only — reading the pairing
code out of the Settings dialog.

## Features

- **Live connect port + optional fixed TCP mode** — shows the real adbd port
  (random by design) with a copyable connect command, or pin it to 5555 via
  legacy TCP mode
- **Live pairing-code capture** — the Settings-process hook reads the
  real code the moment the pairing dialog shows it, copy to clipboard
- **Saved-Wi-Fi scan** — list every Wi-Fi your device remembers, split
  into connected / saved / available sections
- **Trusted networks** — tick the SSIDs that should re-enable ADB
- **Auto-enable on trusted Wi-Fi** — flips `Settings.Global.ADB_WIFI_ENABLED`
  on when you join one
- **Wired (USB) tab** — trust already-authorized ADB host keys (by MD5
  fingerprint) and arm ADB-over-USB independently of the wireless toggle.
  Trust is to an ADB *key*, not a physical PC — the UI says so plainly.
- **Bilingual UI** — English or Simplified Chinese, switchable at runtime
- **Foreground-free** — a KernelSU boot module handles the boot-time
  toggle and a foreground daemon handles Wi-Fi changes; neither needs
  the app UI open
- **Reboot-free hook edits** — the only LSPosed scope is Settings, so
  hook changes take effect after a force-stop of Settings, no full reboot

## Requirements

- Android 11 (API 30) or newer
- LSPosed / Xposed framework (only for pairing-code capture)
- Root (KernelSU or Magisk) for the boot module and shell helpers

## Build

```bash
# Windows
gradlew.bat assembleRelease

# Linux / macOS
./gradlew assembleRelease
```

The signed APK lands in `app/build/outputs/apk/release/`.
The debug APK (un-signed, installable via `adb install -r`) is in
`app/build/outputs/apk/debug/`.

## Install

1. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. Open **LSPosed Manager** → enable the **ADB_X** module
3. Set the scope to **Settings (com.android.settings) only** — do not
   select Android / system_server; that branch was removed
4. Force-stop Settings once so the hook is injected (no full reboot)
5. Launch the **ADB_X** app, pick your language, set the fixed port,
   tick the Wi-Fi networks you trust
6. Turn on "Start on boot" to install the KernelSU boot module

## How it works

### Connect port & fixed-port mode
Android 11+ TLS wireless debugging picks a **random port each time adbd
starts**; there is no way to pin it — `service.adb.tls.port` is a legacy prop
adbd ignores. The app therefore reads the real port from
`dumpsys adb` → `adb_wifi.tls_port` and shows a copyable
`adb connect IP:port` line that always matches reality.

If you genuinely need a stable port, enable **fixed TCP mode**: the app sets
`service.adb.tcp.port` and restarts adbd (the equivalent of `adb tcpip`),
then verifies the prop read-back. This drops the current wireless link for a
moment and is unencrypted, so reconnect to `IP:5555` after enabling. Use the
same switch to return to encrypted TLS mode (random port again).

### Pairing-code capture
Pairing is started by the user in Developer options. The Settings-process
hook intercepts `AdbWirelessDialogController.setPairingCode(String)` and
publishes the real code + timestamp to `Settings.Global` (the Settings
process cannot write `/data/local/tmp` nor exec `su`). The app reads it
back with a short TTL and renders the full `adb pair host:port code`
command. The live port comes from `dumpsys adb` → `adb_wifi.tls_port`;
stale `dumpsys wifi` fields are deliberately ignored.

### Auto-enable on trusted Wi-Fi
A foreground daemon plus a boot-time KernelSU module (`service.sh` +
`adbx-boot-eval`) do the toggle with root, bypassing Android 14+
background-receiver limits. A `ConnectivityManager.NetworkCallback` in
the app handles live SSID changes. Disconnects are left to Android —
adbd keeps its endpoint alive across SSID transitions, and disabling
behind the user's back would drop a live session.

### Saved-Wi-Fi list
Android 11+ hides `WifiManager.getConfiguredNetworks()` from third-party
apps (it returns an empty list), so the Settings-process hook runs
`dumpsys wifi` off the main thread and publishes the SSIDs through
`Settings.Global` in `adb_x_wifi_list_*` chunks. The app reassembles
them; `adb_x_wifi_list_count` also doubles as the hook-working signal.

## Project layout

```
ADB_X/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/xposed_init
│       ├── kotlin/top/cbug/adbx/
│       │   ├── App.kt                   (Application, loads settings)
│       │   ├── MainActivity.kt          (single-activity host, 4 tabs)
│       │   ├── PairingActivity.kt       (full-screen pairing manager)
│       │   ├── BootLogger.kt            (in-app boot diagnostic log)
│       │   ├── BootReceiver.kt          (boot-time trusted-SSID evaluate)
│       │   ├── WifiStateReceiver.kt     (Wi-Fi state change evaluate)
│       │   ├── UsbStateReceiver.kt      (USB connect event + trusted-key evaluate)
│       │   ├── PairingReceiver.kt       (wireless-debug discover action)
│       │   ├── TrustedWifiService.kt    (foreground auto-toggle daemon)
│       │   ├── store/Settings.kt        (SharedPreferences + config mirror)
│       │   ├── ui/                      (4 fragments + adapters)
│       │   │   ├── StatusFragment.kt
│       │   │   ├── NetworkFragment.kt
│       │   │   ├── WiredFragment.kt
│       │   │   ├── SettingsFragment.kt
│       │   │   ├── WifiSettingsActivity.kt
│       │   │   ├── WifiAdapter.kt
│       │   │   └── StatusIndicatorView.kt
│       │   ├── util/                    (shell + ADB + Wi-Fi helpers)
│       │   │   ├── AdbHelper.kt
│       │   │   ├── LocaleHelper.kt
│       │   │   ├── ShellUtils.kt
│       │   │   ├── WifiHelper.kt
│       │   │   ├── AdbKeyHelper.kt      (reads adb_keys, AOSP MD5 fingerprint)
│       │   │   └── XposedStatus.kt
│       │   └── xposed/                  (LSPosed hooks)
│       │       ├── XposedInit.kt
│       │       ├── AdbSystemHooks.kt    (settings-process hook: WiFi list)
│       │       └── SettingsHooks.kt     (Settings app)
│       └── res/
│           ├── layout/                  (4 fragments + 2 activities)
│           ├── menu/bottom_nav.xml      (4-tab navigation)
│           ├── values/                  (English fallback strings)
│           ├── values-zh-rCN/           (Simplified Chinese)
│           └── values-night/            (dark theme)
├── build.gradle.kts
├── module.prop                         (Xposed module metadata)
├── scripts/bump_version.sh             (version bump + tag helper)
├── settings.gradle.kts
└── gradle/wrapper/
```

## License

[Apache License 2.0](LICENSE)