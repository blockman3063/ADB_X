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
- **已保存 Wi-Fi 扫描** — 列出设备记住的所有网络,分「已连接 / 已保存 / 可见」三段
- **信任网络管理** — 勾选应自动开启 ADB 的 SSID
- **连接信任 Wi-Fi 时自动开启 ADB** — 自动写入 `Settings.Global.ADB_WIFI_ENABLED`
- **有线(USB)页面** — 按序列号信任主机,USB ADB 与无线开关互不干扰
- **中英双语界面** — 运行时切换
- **基本无需前台依赖** — 核心开关逻辑跑在 `system_server` LSPosed hook 内;
  界面关闭后由一个轻量前台守护进程维持

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

### 连接信任 Wi-Fi 时自动开启
`ConnectivityManager.NetworkCallback` 同时跑在 `system_server`(经 LSPosed)
和 App 进程内(前台守护进程 + 静态注册的 `WifiStateReceiver`)。
当连接的 Wi-Fi 命中信任 SSID 时开启无线 ADB。
断开时故意不做处理 —— adbd 会在 SSID 切换间保持端点存活,
背着用户关掉 ADB 反而会切断正在进行的会话。

### 已保存 Wi-Fi 列表
Android 11+ 对第三方 app 隐藏了 `WifiManager.getConfiguredNetworks()`
(直接返回空列表),因此改由 `system_server` 内的 hook 读取网络列表,
再经 `Settings.Global` 的 `adb_x_wifi_list_*` 分片发布,App 端重组。
在 settings provider 受限的 ROM 上回退到磁盘上的 marker 文件。

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
│       │   ├── UsbStateReceiver.kt      (USB 插拔时评估)
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
│       │   │   ├── WiredUsbHelper.kt
│       │   │   └── XposedStatus.kt
│       │   └── xposed/                  (LSPosed hook)
│       │       ├── XposedInit.kt
│       │       ├── AdbSystemHooks.kt    (system_server + settings)
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