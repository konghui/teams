# Teams Wrapper (Android)

A native Android shell that wraps the **Microsoft Teams web app**. All business
functionality is reused from Teams Web; the shell adds a native experience on top:
**native Android message notifications, full-screen incoming calls, background
keep-alive, call audio routing**, plus a set of fixes that make the web version
feel at home on a phone.

> ⚠️ Personal/self-use project, self-signed certificate, **not** an official
> Microsoft product.

[中文说明见下方 / Chinese section below ↓](#中文说明)

## ✨ Features

| Capability | Description |
|------|------|
| 💬 Native message notifications | Watches Teams' in-page toasts and mirrors new messages to native Android notifications (works in foreground **and** background) |
| 📞 Full-screen incoming calls | Web call → native full-screen call UI (shows over the lock screen) + ringtone/vibration + answer/decline |
| 🔔 Tap-to-open | Tapping a message notification opens the matching chat **in-page** (no external app, no full reload) |
| 🎙️ Call audio | On connect, switches to `MODE_IN_COMMUNICATION` + takes voice focus → louder call audio, ducked notifications |
| 🔙 Back gesture | Inside a chat, the Android back gesture/button returns to the main list |
| 🪟 Cleaner UI | Hides redundant in-page Teams toasts, fixes call-toolbar clicks, taller chat-list rows |
| 🔋 Background keep-alive | Foreground service + WakeLock + boot auto-start to stay online in the background (best-effort) |
| 🔐 Permission automation | Auto-grants the browser-layer mic/camera request + `navigator.permissions` polyfill |

## ⚠️ Known limitations

- **In-meeting screen share, background blur, and multi-party video** are largely
  unavailable in Android WebView (platform ceiling).
- **Speaker/output device selection** inside Teams is greyed out: WebView does not
  support `setSinkId`; output routing is handled natively instead.
- **Background realtime is best-effort**: a pure web wrapper cannot wake via FCM
  push; once the OS kills the app, notifications may be missed or delayed. Chinese
  OEM ROMs (MIUI/EMUI/ColorOS, …) require manually enabling *auto-start /
  background lock / battery whitelist*.
- **Notifications, calls, and navigation depend on Teams Web's DOM structure**
  (selectors in `app/src/main/assets/inject.js`). A Microsoft redesign can break
  them and the selectors will need updating — the inherent fragility of a wrapper.

## 🏗️ Architecture

```
MainActivity ── WebView (teams.microsoft.com, desktop UA, WebRTC allowed)
     │            └── inject.js  in-page injection:
     │                 • observe toasts → native notifications (carry channelId)
     │                 • detect incoming / connected call state
     │                 • back-to-list, open chat in-page
     │                 • hide redundant toasts, taller rows, allow taps
     ├── TeamsService         foreground keep-alive service
     ├── NotificationHelper   native message / full-screen call notifications
     ├── IncomingCallActivity full-screen lock-screen call UI
     ├── CallController       native actions (answer/decline) back into the page
     ├── CallAudio            call audio mode / focus / speaker
     └── WebAppInterface      JS → native bridge entry point
```

| File | Role |
|------|------|
| `MainActivity.kt` | WebView host: UA spoof, WebRTC perms, file chooser/download, injection, notification routing |
| `assets/inject.js` | In-page script: notification capture, call detection, navigation, UI fixes (**most likely to break on a Teams redesign**) |
| `TeamsService.kt` | Foreground keep-alive service + WakeLock |
| `NotificationHelper.kt` | Native message / full-screen call notifications |
| `IncomingCallActivity.kt` | Full-screen lock-screen call UI |
| `CallController.kt` / `CallAudio.kt` | Answer/decline back into the page / call audio routing |
| `BootReceiver.kt` | Start the service on boot |

## 🔨 Build

Requires JDK 17+ and the Android SDK (platform 34, build-tools 34).

```bash
# For CLI builds, point local.properties at the SDK:
echo "sdk.dir=/path/to/Android/sdk" > local.properties

./gradlew :app:assembleDebug     # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
```

Or open the folder in Android Studio (Gradle Wrapper included).

### Signing

- If `app/release.keystore` exists, release/debug share the same self-signed cert
  (so builds upgrade each other without wiping app data / login).
- The keystore is **not committed** (see `.gitignore`). When absent (e.g. CI), the
  build falls back to debug signing and artifacts are still installable.
- Override via env vars: `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`.

## 📲 Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch, allow **notifications, microphone, camera** and the **battery
optimization whitelist**, then sign in with your Teams account. On Chinese ROMs,
enable *auto-start / background lock / battery whitelist* in system settings.

## 🚀 CI/CD

GitHub Actions (`.github/workflows/android.yml`):

- push to `main` / PR / manual dispatch → builds debug + release APKs and uploads
  them as artifacts.
- push a `v*` tag (e.g. `git tag v2.0 && git push --tags`) → builds and creates a
  GitHub Release with the APK attached.

## Maintenance

If notifications/calls/navigation stop working, first check and update the DOM
selectors in `app/src/main/assets/inject.js` (toast containers,
`Accept with audio`, `layout-navigation-controls`, `channelId` extraction, …) to
match the current Teams Web structure.

---

# 中文说明

一个把 **Microsoft Teams 网页版** 封装进原生外壳的 Android 应用。所有业务功能都复用 Teams Web，
外壳额外提供原生体验:**原生 Android 消息通知、全屏来电、后台保活、通话音频路由**,以及一系列让
Web 版在手机上用起来更顺手的修补。

> ⚠️ 个人自用项目,使用自签名证书,非官方 Microsoft 产品。

## ✨ 功能

| 能力 | 说明 |
|------|------|
| 💬 原生消息通知 | 监听 Teams 页内 toast,把新消息转成原生 Android 通知(前台/后台均可) |
| 📞 全屏来电 | Web 来电 → 原生全屏来电界面(可锁屏显示)+ 响铃震动 + 接听/挂断 |
| 🔔 点通知跳转 | 点消息通知在**页面内**打开对应聊天(不跳外部 App、不整页重载) |
| 🎙️ 通话音频 | 接通后切 `MODE_IN_COMMUNICATION` + 抢语音焦点,通话更响、压低通知音 |
| 🔙 返回手势 | 在聊天里用 Android 返回手势/键直接退回主列表 |
| 🪟 更清爽 | 隐藏 Teams 页内冗余 toast、修复通话工具栏点击、加大聊天列表行高 |
| 🔋 后台保活 | 前台常驻服务 + WakeLock + 开机自启,尽力在后台保持在线 |
| 🔐 权限自动化 | 浏览器层麦克风/摄像头自动放行 + `navigator.permissions` polyfill |

## ⚠️ 已知限制

- **会议中的屏幕共享、虚拟背景、多路视频** 在 Android WebView 里大概率不可用(WebView 平台天花板)。
- **扬声器设备选择**(Teams 里切换输出设备)灰掉:WebView 不支持 `setSinkId`,输出路由改由原生层控制。
- **后台实时性是"尽力而为"**:纯 Web 套壳无法用 FCM 推送唤醒;App 被系统杀掉后通知会漏或延迟。
  国产 ROM(MIUI/EMUI/ColorOS 等)需手动开启"自启动 / 后台锁定 / 省电白名单"。
- **通知/来电/跳转依赖 Teams Web 的 DOM 结构**(见 `app/src/main/assets/inject.js` 中的选择器),
  微软改版可能导致失效,需要更新选择器。这是套壳方案固有的脆弱点。

## 🔨 构建

需要 JDK 17+ 和 Android SDK(platform 34、build-tools 34)。

```bash
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew :app:assembleDebug     # 产物: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease   # 产物: app/build/outputs/apk/release/app-release.apk
```

签名:本地有 `app/release.keystore` 用同一自签证书(互相覆盖升级不清登录);keystore 不入库,
CI 缺失时自动回退 debug 签名。可用环境变量 `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` 覆盖。

## 📲 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次启动依次允许:**通知、麦克风、摄像头** 权限 + **电池优化白名单**,然后用自己的 Teams 账号登录。
国产 ROM 记得在系统设置里给它开 **自启动 / 后台锁定 / 省电白名单**。

## 🚀 CI/CD

GitHub Actions(`.github/workflows/android.yml`):push 到 `main` / PR / 手动触发 → 构建 debug + release
APK 并上传 artifact;打 `v*` 标签 → 自动建 GitHub Release 并附带 APK。

## 维护提示

通知/来电/跳转若失效,优先检查并更新 `app/src/main/assets/inject.js` 里的 DOM 选择器
(toast 容器、`Accept with audio`、`layout-navigation-controls`、`channelId` 提取等),
使其匹配当前 Teams Web 的结构。
