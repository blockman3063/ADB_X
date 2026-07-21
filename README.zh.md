# ADB_X — 无线 ADB 调试增强 Xposed 模块

[![Release](https://img.shields.io/github/v/release/blockman3063/ADB_X?style=flat-square&label=Download&color=1565C0)](https://github.com/blockman3063/ADB_X/releases)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-11%2B-3DDC84?style=flat-square&logo=android)](https://developer.android.com/about/versions/11)
[![Xposed](https://img.shields.io/badge/Xposed-LSPosed-1565C0?style=flat-square)](https://github.com/LSPosed/LSPosed)

> ⚠️ **早期开发版本,问题较多。** 本项目仍处于非常早期的开发阶段,
> 大量边缘情况、缺失功能、编译失败、ROM 间行为差异都可能出现。
> 欢迎反馈问题,但请勿在无法回滚的场景下依赖本项目,
> API 也不保证稳定。

> 📖 **英文文档**:[README.md](README.md)

固定无线调试端口、即时捕获 ADB 配对码、连接信任 WiFi 时自动开启 ADB ——
全部由 LSPosed 模块驱动,无需前台 App 或后台服务。

## 功能

- **固定无线调试端口** — 摆脱 `adb pair` 每次随机端口
- **实时配对码捕获** — 从 system_server hook 直接读取当前配对码,一键复制
- **已保存 Wi-Fi 扫描** — 列出设备记住的所有网络
- **信任网络管理** — 勾选应自动开启 ADB 的 SSID
- **连接信任 Wi-Fi 时自动开启 ADB** — 自动写入 `Settings.Global.ADB_WIFI_ENABLED`;
  离开时自动关闭(可选)
- **中英双语界面** — 运行时切换
- **无前台依赖** — 全部逻辑跑在 `system_server` LSPosed hook 内

## 环境要求

- Android 11 (API 30) 或以上
- LSPosed / Xposed 框架
- Root(KernelSU 或 Magisk)用于 LSPosed scope — system_server hook 需 root 写
  `/data/local/tmp`

## 构建

```bash
# Windows
gradlew.bat assembleRelease

# Linux / macOS
./gradlew assembleRelease
```

签名后的 APK 输出在 `app/build/outputs/apk/release/`。
Debug APK(未签名,可用 `adb install -r`)在 `app/build/outputs/apk/debug/`。

## 安装

1. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. 打开 **LSPosed 管理器** → 启用 **ADB_X** 模块
3. 作用域选择 **Android (system_server)** 和 **设置 (com.android.settings)**
4. 重启,或软重启受影响的进程
5. 启动 **ADB_X** App,选择语言,设置固定端口,勾选信任的 Wi-Fi

## 工作原理

### 固定端口
Hook 拦截 `SystemProperties.set` 对 `service.adb.tls.port` 和
`service.adb.tcp.port` 的调用,在 adbd bind 之前改写为用户指定的固定端口。

### 配对码捕获
在配对对话框构造时(跨多个 Android 版本的候选类 best-effort hook),
临时配对端口写入 `/data/local/tmp/adb_x_pairing_port`,保存的自定义配对码
写入 `/data/local/tmp/adb_x_pairing_code`。App 读两个文件,渲染完整
`adb pair host:port code` 命令,一键复制。

### 自动开启 / 自动关闭
`ConnectivityManager.NetworkCallback` 跑在 `system_server`。
当连接的 Wi-Fi 命中信任 SSID 时,hook 设 `Settings.Global.ADB_WIFI_ENABLED = 1`;
断开时(可选)清零。

### 已保存 Wi-Fi 列表
Hook 把 `WifiManager.getConfiguredNetworks()` 的结果 dump 到
`/data/local/tmp/adb_x_wifi_list`。这是 Android 11+ 上唯一可行的途径 ——
第三方 app 调 `getConfiguredNetworks()` 返回 0 条,system_server 才有完整
可见性。

## 项目结构

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
│       │   ├── MainActivity.kt          (单 Activity host)
│       │   ├── PairingActivity.kt       (全屏配对管理)
│       │   ├── store/Settings.kt        (SharedPreferences + 同步文件)
│       │   ├── ui/                      (3 Fragment + adapter)
│       │   │   ├── StatusFragment.kt
│       │   │   ├── NetworkFragment.kt
│       │   │   ├── SettingsFragment.kt
│       │   │   ├── WifiAdapter.kt
│       │   │   └── StatusIndicatorView.kt
│       │   ├── util/                    (shell + ADB + Wi-Fi 工具)
│       │   │   ├── AdbHelper.kt
│       │   │   ├── LocaleHelper.kt
│       │   │   ├── ShellUtils.kt
│       │   │   └── WifiHelper.kt
│       │   └── xposed/                  (LSPosed hook)
│       │       ├── XposedInit.kt
│       │       ├── AdbSystemHooks.kt    (system_server)
│       │       └── SettingsHooks.kt     (设置 App)
│       └── res/
│           ├── layout/                  (3 Fragment + 2 Activity)
│           ├── menu/bottom_nav.xml      (3 tab 底栏)
│           ├── values/                  (英文 fallback 字符串)
│           ├── values-zh-rCN/           (简体中文)
│           └── values-night/            (深色主题)
├── build.gradle.kts
├── module.prop                         (Xposed 模块元数据)
├── settings.gradle.kts
└── gradle/wrapper/
```

## License

[Apache License 2.0](LICENSE)