# rimet-mock — 钉钉模拟定位（LSPosed 2.3 / libxposed API 102 适配版）

> 仅供开发调试与学习。这是对已停更的闭源模块 `com.fuck.android.rimet`（锤锤）定位手法的
> **clean-room 重写**：不复制其代码、不打包任何第三方 SDK，全部逻辑基于对其行为的独立理解重新实现。

## 为什么重写而不是"升级"

原模块 `com.fuck.android.rimet` 是**闭源**的——上游仓库只有 README/SCOPE/SUMMARY，
只发布编译好的 APK。它还**打包了整套高德定位 SDK（269 个类）**，需要高德 API key。
反编译产物无法直接重新编译，所以"适配 LSPosed 2.3"只有一条诚实可行的路：理解其 hook 机制后
从零重写一个最小模块。（详细逆向分析见 `Jieoz/rimet-analysis` 私有仓库。）

## 相比原版的设计改进

| 维度 | 原版 0.4-beta5 | 本重写 0.1-lsp102 |
|---|---|---|
| Xposed API | 旧 API（`assets/xposed_init` + `IXposedHookLoadPackage`） | libxposed API 102（`java_init.list` + `XposedModule`） |
| 配置跨进程 | `XSharedPreferences`（2.3 移除）+ ContentProvider 兜底 | `getRemotePreferences`（API 102 原生，无兼容包袱） |
| 高德 SDK | 打包整套 SDK，需 API key | **零打包**，hook 时从宿主 classloader 反射构造 `AMapLocation` |
| 环境快照 | Parcel.marshall 序列化整份 Profile（跨系统版本易失效） | 只存目标经纬度，无序列化兼容问题 |
| LSPosed 状态 | 2.2 标"不受支持"，2.3 部分失效 | 全绿，无 legacy 元数据 |

CI 已断言：APK 内**不含** `assets/xposed_init`、**不含** `com/amap/api/`、**不含**打包的
libxposed api，且 `java_init.list` / `module.prop` / `scope.list` 契约正确。

## Hook 点

模块进入宿主（钉钉）进程后，从宿主 classloader 解析高德类并挂钩：

- `AMapLocationClient#getLastKnownLocation()` → 返回按目标坐标反射构造的 `AMapLocation`
- `AMapLocationClient#setLocationListener(listener)` → 用 `Proxy` 包裹监听器，
  把每次推送的定位改写成目标坐标后再交给宿主
- 可选（勾选"屏蔽 WiFi/基站"）：framework 的 `WifiManager#getScanResults/getConnectionInfo` 与
  `TelephonyManager#getAllCellInfo` 返回空，防止宿主拿真实无线环境交叉校验假 GPS

定位 hook 反射操作宿主 `AMapLocation` 这段依赖真机钉钉环境，需装机实测；
WiFi/基站 hook 用 framework 类，可静态确认。

## 使用

1. LSPosed 中启用本模块，作用域勾选**钉钉**和**本应用**（自身在作用域内才能显示"已激活"）。
2. 打开"锤锤Mock"，填目标经纬度（GCJ-02/高德坐标系），或点"读取当前定位"预填，勾选"启用模拟定位"，保存。
3. 回钉钉重新触发定位生效。

## 构建

通过 `cloud-build-lab` 云 CI 构建（本机 arm64 无法链接 x86_64 的 AAPT2）。
`compileOnly 'io.github.libxposed:api:102.0.0'` 由框架在运行时提供，不打包进 APK。
