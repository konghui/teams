# Teams Wrapper (Android)

一个把 **Microsoft Teams 网页版** 封装进原生外壳的 Android 应用。所有业务功能都复用 Teams Web，
外壳额外提供原生体验：**原生 Android 消息通知、全屏来电、后台保活、通话音频路由**，以及一系列让
Web 版在手机上用起来更顺手的修补。

> ⚠️ 这是个人自用项目，使用自签名证书，非官方 Microsoft 产品。

## ✨ 功能

| 能力 | 说明 |
|------|------|
| 💬 原生消息通知 | 监听 Teams 页内 toast，把新消息转成原生 Android 通知（前台/后台均可） |
| 📞 全屏来电 | Web 来电 → 原生全屏来电界面（可锁屏显示）+ 响铃震动 + 接听/挂断 |
| 🔔 点通知跳转 | 点消息通知在**页面内**打开对应聊天（不跳外部 App、不整页重载） |
| 🎙️ 通话音频 | 接通后切 `MODE_IN_COMMUNICATION` + 抢语音焦点，通话更响、压低通知音 |
| 🔙 返回手势 | 在聊天里用 Android 返回手势/键直接退回主列表 |
| 🪟 更清爽 | 隐藏 Teams 页内冗余 toast、修复通话工具栏点击、加大聊天列表行高 |
| 🔋 后台保活 | 前台常驻服务 + WakeLock + 开机自启，尽力在后台保持在线 |
| 🔐 权限自动化 | 浏览器层麦克风/摄像头自动放行 + `navigator.permissions` polyfill |

## ⚠️ 已知限制

- **会议中的屏幕共享、虚拟背景、多路视频** 在 Android WebView 里大概率不可用（WebView 平台天花板）。
- **扬声器设备选择**（Teams 里切换输出设备）灰掉：WebView 不支持 `setSinkId`，输出路由改由原生层控制。
- **后台实时性是"尽力而为"**：纯 Web 套壳无法用 FCM 推送唤醒；App 被系统杀掉后通知会漏或延迟。
  国产 ROM（MIUI/EMUI/ColorOS 等）需手动开启"自启动 / 后台锁定 / 省电白名单"。
- **通知/来电/跳转依赖 Teams Web 的 DOM 结构**（见 `app/src/main/assets/inject.js` 中的选择器），
  微软改版可能导致失效，需要更新选择器。这是套壳方案固有的脆弱点。

## 🏗️ 架构

```
MainActivity ── WebView (teams.microsoft.com, 桌面 UA, WebRTC 放行)
     │            └── inject.js  页内注入：
     │                 • 监听 toast → 原生通知（带 channelId 用于跳转）
     │                 • 检测来电 / 接通状态
     │                 • 返回到列表、页面内打开聊天
     │                 • 隐藏冗余 toast、加大行高、放行 tap
     ├── TeamsService         前台常驻服务，后台保活
     ├── NotificationHelper   原生消息通知 / 全屏来电通知
     ├── IncomingCallActivity 锁屏全屏来电界面
     ├── CallController       原生动作（接听/挂断）回灌页面
     ├── CallAudio            通话音频模式 / 焦点 / 扬声器
     └── WebAppInterface      JS → 原生 桥接入口
```

| 文件 | 作用 |
|------|------|
| `MainActivity.kt` | WebView 宿主：UA 伪装、WebRTC 权限、文件选择/下载、注入、通知跳转 |
| `assets/inject.js` | 页内脚本：通知捕获、来电检测、导航、UI 修补（**最易随 Teams 改版失效**） |
| `TeamsService.kt` | 前台常驻服务 + WakeLock |
| `NotificationHelper.kt` | 原生消息 / 全屏来电通知 |
| `IncomingCallActivity.kt` | 锁屏全屏来电界面 |
| `CallController.kt` / `CallAudio.kt` | 接听挂断回灌页面 / 通话音频路由 |
| `BootReceiver.kt` | 开机自启服务 |

## 🔨 构建

需要 JDK 17+ 和 Android SDK（platform 34、build-tools 34）。

```bash
# 命令行构建需在根目录放 local.properties 指向 SDK：
echo "sdk.dir=/path/to/Android/sdk" > local.properties

./gradlew :app:assembleDebug     # 产物: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease   # 产物: app/build/outputs/apk/release/app-release.apk
```

或直接用 Android Studio 打开本目录（已带 Gradle Wrapper）。

### 签名

- 本地若存在 `app/release.keystore`，release/debug 用同一自签证书（互相覆盖升级不清数据）。
- 该 keystore **不入库**（见 `.gitignore`）。缺失时（如 CI）自动回退到 debug 签名，产物仍可安装。
- 可用环境变量覆盖：`KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`。

## 📲 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次启动依次允许：**通知、麦克风、摄像头** 权限 + **电池优化白名单**，然后用自己的 Teams 账号登录。
国产 ROM 记得在系统设置里给它开 **自启动 / 后台锁定 / 省电白名单**。

## 🚀 CI/CD

GitHub Actions（`.github/workflows/android.yml`）：

- push 到 `main` / PR / 手动触发 → 构建 debug + release APK 并作为 artifact 上传。
- 打 `v*` 标签（如 `git tag v2.0 && git push --tags`）→ 自动构建并创建 GitHub Release，附带 APK。

## 维护提示

通知/来电/跳转若失效，优先检查并更新 `app/src/main/assets/inject.js` 里的 DOM 选择器
（toast 容器、`Accept with audio`、`layout-navigation-controls`、`channelId` 提取等），
使其匹配当前 Teams Web 的结构。
