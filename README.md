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
a trusted Wi-Fi network — all driven by an LSPosed module, with no
foreground app or background service required.

## Features

- **Fixed wireless-debugging port** — no more random port from `adb pair`
- **Live pairing-code capture** — read the current pairing code straight
  from the system-server hook, copy to clipboard
- **Saved-Wi-Fi scan** — list every Wi-Fi your device remembers
- **Trusted networks** — tick the SSIDs that should re-enable ADB
- **Auto-enable on trusted Wi-Fi** — flips `Settings.Global.ADB_WIFI_ENABLED`
  on when you join one, off when you leave (optional)
- **Bilingual UI** — English or Simplified Chinese, switchable at runtime
- **Foreground-free** — all logic runs in `system_server` via LSPosed

## Requirements

- Android 11 (API 30) or newer
- LSPosed / Xposed framework
- Root (KernelSU or Magisk) for the LSPosed scope — the system-server
  hook needs root to write to `/data/local/tmp`

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
3. Set the scope to **Android (system_server)** and **Settings
   (com.android.settings)**
4. Reboot, or soft-restart the affected processes
5. Launch the **ADB_X** app, pick your language, set the fixed port,
   tick the Wi-Fi networks you trust

## How it works

### Fixed port
The hook intercepts `SystemProperties.set` for `service.adb.tls.port`
and `service.adb.tcp.port` and rewrites the value to the user-chosen
fixed port before adbd binds to it.

### Pairing-code capture
On the pairing dialog's construction (best-effort hook across several
candidate classes for different Android versions), the temporary
pairing port is written to `/data/local/tmp/adb_x_pairing_port`; the
saved custom code is written to `/data/local/tmp/adb_x_pairing_code`.
The app reads both files and renders the full
`adb pair host:port code` command with a copy-to-clipboard button.

### Auto-enable / auto-disable
A `ConnectivityManager.NetworkCallback` runs inside `system_server`.
When the active Wi-Fi matches one of the trusted SSIDs the hook sets
`Settings.Global.ADB_WIFI_ENABLED = 1`; on disconnect, optionally
clears it again.

### Saved-Wi-Fi list
The hook dumps `WifiManager.getConfiguredNetworks()` into
`/data/local/tmp/adb_x_wifi_list`. This is the only path that works on
Android 11+ where `getConfiguredNetworks()` returns 0 networks from
third-party apps — system_server has full visibility that apps don't.

## Project layout

```
ADB_X/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/xposed_init
│       ├── kotlin/top/cbug/adbx/
│       │   ├── App.kt
│       │   ├── BootReceiver.kt
│       │   ├── MainActivity.kt          (single-activity host)
│       │   ├── PairingActivity.kt       (full-screen pairing manager)
│       │   ├── store/Settings.kt        (SharedPreferences + sync file)
│       │   ├── ui/                      (3 fragments + adapters)
│       │   │   ├── StatusFragment.kt
│       │   │   ├── NetworkFragment.kt
│       │   │   ├── SettingsFragment.kt
│       │   │   ├── WifiAdapter.kt
│       │   │   └── StatusIndicatorView.kt
│       │   ├── util/                    (shell + ADB + Wi-Fi helpers)
│       │   │   ├── AdbHelper.kt
│       │   │   ├── LocaleHelper.kt
│       │   │   ├── ShellUtils.kt
│       │   │   └── WifiHelper.kt
│       │   └── xposed/                  (LSPosed hooks)
│       │       ├── XposedInit.kt
│       │       ├── AdbSystemHooks.kt    (system_server)
│       │       └── SettingsHooks.kt     (Settings app)
│       └── res/
│           ├── layout/                  (3 fragments + 2 activities)
│           ├── menu/bottom_nav.xml      (3-tab navigation)
│           ├── values/                  (English fallback strings)
│           ├── values-zh-rCN/           (Simplified Chinese)
│           └── values-night/            (dark theme)
├── build.gradle.kts
├── module.prop                         (Xposed module metadata)
├── settings.gradle.kts
└── gradle/wrapper/
```

## License

[Apache License 2.0](LICENSE)