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
主要靠 root + 一个轻量 KernelSU 开机模块完成;LSPosed 只负责一件事:
从设置对话框里读出配对码。

## 功能

- **实时连接端口 + 可选固定 TCP 模式** — 显示 adbd 真实端口(默认随机)并可一键复制连接命令,或用传统 TCP 模式固定到 5555
- **实时配对码捕获** — 设置进程 hook 在配对弹窗出现的瞬间读取真实配对码,一键复制
- **已保存 Wi-Fi 扫描** — 列出设备记住的所有网络,分「已连接 / 已保存 / 可见」三段
- **信任网络管理** — 勾选应自动开启 ADB 的 SSID
- **连接信任 Wi-Fi 时自动开启 ADB** — 自动写入 `Settings.Global.ADB_WIFI_ENABLED`
- **有线(USB)页面** — 按 MD5 指纹信任已授权的 ADB 主机密钥,USB ADB 与无线开关互不干扰。
  信任对象是 ADB 密钥而非物理电脑 —— 界面里已明确说明。
- **中英双语界面** — 运行时切换
- **无需前台** — KernelSU 开机模块负责开机自启,前台守护进程负责 Wi-Fi 变化;
  都不需要 App 界面打开
- **改 hook 不用重启** — LSPosed 作用域只有设置,改 hook 后强停设置即可生效

## 环境要求

- Android 11 (API 30) 或以上
- LSPosed / Xposed 框架(仅用于捕获配对码)
- Root(KernelSU 或 Magisk),用于开机模块和 shell 操作

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
3. 作用域只选 **设置 (com.android.settings)** —— 不要选 Android / system_server,该分支已移除
4. 强停一次「设置」以便注入 hook(无需整机重启)
5. 启动 **ADB_X** App,选择语言,设置固定端口,勾选信任的 Wi-Fi
6. 打开「开机自启」以安装 KernelSU 开机模块

## 工作原理

### 连接端口与固定端口模式
Android 11+ 的 TLS 无线调试**每次 adbd 启动都会随机选端口**,无法固定 ——
`service.adb.tls.port` 是 adbd 根本不读的旧 prop。所以 App 从
`dumpsys adb` → `adb_wifi.tls_port` 读真实端口,显示可一键复制的
`adb connect IP:port`,永远和实际一致。

如果确实需要稳定端口,打开**固定 TCP 模式**:App 设置 `service.adb.tcp.port`
并重启 adbd(等价于 `adb tcpip`),读回 prop 验证。切换会短暂断连且明文传输,
开启后请重新连接 `IP:5555`。再次关闭该开关即回到加密的 TLS 模式(端口又变随机)。

### 配对码捕获
配对由用户在开发者选项里手动发起。设置进程 hook 拦截
`AdbWirelessDialogController.setPairingCode(String)`,把真实配对码和时间戳
写进 `Settings.Global`(设置进程无权写 `/data/local/tmp`,也无法执行 `su`)。
App 带短 TTL 读回,渲染完整的 `adb pair host:port code` 命令。
实时端口来自 `dumpsys adb` → `adb_wifi.tls_port`;陈旧的 `dumpsys wifi` 字段一律忽略。

### 连接信任 Wi-Fi 时自动开启
前台守护进程 + KernelSU 开机模块(`service.sh` + `adbx-boot-eval`)用 root 完成开关,
绕过 Android 14+ 的后台广播限制。实时 SSID 变化由 App 进程内的
`ConnectivityManager.NetworkCallback` 处理。断开时故意不做处理 ——
adbd 会在 SSID 切换间保持端点存活,背着用户关掉 ADB 反而会切断正在进行的会话。

### 已保存 Wi-Fi 列表
Android 11+ 对第三方 app 隐藏了 `WifiManager.getConfiguredNetworks()`
(直接返回空列表),因此设置进程 hook 在后台线程跑 `dumpsys wifi`,
再经 `Settings.Global` 的 `adb_x_wifi_list_*` 分片发布,App 端重组。
`adb_x_wifi_list_count` 同时用作 hook 是否真的工作的信号。

## 项目结构

```
ADB_X/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/xposed_init
│       ├── kotlin/top/cbug/adbx/
│       │   ├── App.kt                   (Application,加载设置)
│       │   ├── MainActivity.kt          (单 Activity host,4 tab)
│       │   ├── PairingActivity.kt       (全屏配对管理)
│       │   ├── BootLogger.kt            (App 内开机诊断日志)
│       │   ├── BootReceiver.kt          (开机时评估信任 SSID)
│       │   ├── WifiStateReceiver.kt     (Wi-Fi 状态变化时评估)
│       │   ├── UsbStateReceiver.kt      (USB 连接事件 + 受信任密钥评估)
│       │   ├── PairingReceiver.kt       (无线调试发现广播)
│       │   ├── TrustedWifiService.kt    (前台自动开关守护进程)
│       │   ├── store/Settings.kt        (SharedPreferences + 配置镜像)
│       │   ├── ui/                      (4 Fragment + adapter)
│       │   │   ├── StatusFragment.kt
│       │   │   ├── NetworkFragment.kt
│       │   │   ├── WiredFragment.kt
│       │   │   ├── SettingsFragment.kt
│       │   │   ├── WifiSettingsActivity.kt
│       │   │   ├── WifiAdapter.kt
│       │   │   └── StatusIndicatorView.kt
│       │   ├── util/                    (shell + ADB + Wi-Fi 工具)
│       │   │   ├── AdbHelper.kt
│       │   │   ├── LocaleHelper.kt
│       │   │   ├── ShellUtils.kt
│       │   │   ├── WifiHelper.kt
│       │   │   ├── AdbKeyHelper.kt      (读取 adb_keys,计算 AOSP 同款 MD5 指纹)
│       │   │   └── XposedStatus.kt
│       │   └── xposed/                  (LSPosed hook)
│       │       ├── XposedInit.kt
│       │       ├── AdbSystemHooks.kt    (设置进程 hook:Wi-Fi 列表)
│       │       └── SettingsHooks.kt     (设置 App)
│       └── res/
│           ├── layout/                  (4 Fragment + 2 Activity)
│           ├── menu/bottom_nav.xml      (4 tab 底栏)
│           ├── values/                  (英文 fallback 字符串)
│           ├── values-zh-rCN/           (简体中文)
│           └── values-night/            (深色主题)
├── build.gradle.kts
├── module.prop                         (Xposed 模块元数据)
├── scripts/bump_version.sh             (版本号与 tag 助手)
├── settings.gradle.kts
└── gradle/wrapper/
```

## License

[Apache License 2.0](LICENSE)