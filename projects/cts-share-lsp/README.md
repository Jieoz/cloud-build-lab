# CTS Share LSP（圈选分享）

LSPosed 模块：给 Google 圈选即搜（Circle to Search）恢复选区的“分享”按钮。
2026 年起 Google 把圈选结果里的“分享”换成了“Create 🍌”（Nano Banana），本模块把它加回来，
选区图片直接打开系统分享面板，不存相册。

## 来源

核心逻辑移植自 [Entermage/cts-selection-share](https://github.com/Entermage/cts-selection-share)
（Zygisk 模块，GPL-3.0）的 `ShareBootstrap.java`，只改包名和日志标签。
原项目的 Zygisk/JNI 加载器换成 libxposed API 102 入口 `CtsShareModule`：
只注入 `com.google.android.googlequicksearchbox:googleapp` 进程，在 `Application.onCreate` 之后调用
`ShareBootstrap.init`。

## 要求

- Android 11+，LSPosed 2.x（libxposed API 102）
- Google 应用 17.x（16.x 有原项目的有限兼容）

## 使用

1. 安装 APK，在 LSPosed 启用，作用域勾选 Google（默认已勾）。
2. 强制停止 Google 应用（或重启）。
3. 圈选即搜里圈出区域，点“分享”。

分享图片写入 Google 应用私有缓存 `cache/cts-share`，十分钟后或下次 Google 进程启动时删除。


## 构建

`./gradlew :app:assembleDebug`，签名用固定 key（CI secrets `XVC_KEYSTORE_*`，证书 SHA-256 `1815c41d…8d2d`）。

License: GPL-3.0-only（继承上游）。

## 诊断日志

模块 App 里有“诊断日志”开关，默认关，即时生效（remote prefs + 监听，和 yt-translate-probe 同一套）。
开启后 Google `:googleapp` 进程把日志写到 `Download/CtsShare/ctsshare-YYYYMMDD.txt`（每天一个文件），关闭时不写任何文件。
上游原本始终写 Google 私有目录 `files/cts-share-debug.log`，已改为走此开关。

