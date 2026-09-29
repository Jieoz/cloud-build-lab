# rimet-mock — 钉钉模拟定位（LSPosed 2.3 / libxposed API 102 适配版）

> 仅供开发调试与学习。这是对已停更的闭源模块 `com.fuck.android.rimet`（锤锤）定位手法的
> **clean-room 重写**：不复制其代码、不打包任何第三方 SDK，全部逻辑基于对其行为的独立理解重新实现。

## 为什么重写而不是"升级"

原模块 `com.fuck.android.rimet` 是**闭源**的——上游仓库只有 README/SCOPE/SUMMARY，
只发布编译好的 APK。它还**打包了整套高德定位 SDK（269 个类）**，需要高德 API key。
反编译产物无法直接重新编译，所以"适配 LSPosed 2.3"只有一条诚实可行的路：理解其 hook 机制后
从零重写。（详细逆向分析见 `Jieoz/rimet-analysis` 私有仓库。）

## 功能对齐：不缩水

目标是"在原模块基础上优化、不减少特性"。除**地址自动反查**外，原模块的能力全部保留：

| 特性 | 原版 0.4-beta5 | 本重写 |
|---|---|---|
| 多套位置 profile、可切换激活 | ✅ | ✅ |
| 替换钉钉读到的 GPS 坐标 | ✅ | ✅ |
| 改写全部 ~24 个 AMap 字段（地址/POI/adCode/区划…） | ✅ | ✅ 反射逐一写入 |
| 坐标随机抖动（~0.1m 漂移） | ✅ | ✅ 同算法 |
| WiFi/基站一致快照回放（防交叉校验） | ✅ | ✅ 捕获真实快照并回放 |
| 调试日志开关（写文件，默认关，关闭零开销） | ❌ | ✅ |
| 地址文字**自动反查** | ✅（靠高德 SDK+key） | ⚠️ 改为手动填写 |

**唯一差别**：地址文字（"XX市XX区XX路"）不能由坐标自动翻译，需在编辑页手填。
这不影响打卡——钉钉考勤范围按**经纬度距离**判定，地址字符串仅用于显示。要恢复自动反查，
需注册一个绑定本包名+签名的免费高德 key（当前刻意不引入，以免 key 过期维护负担）。

## 0.2 修复：配置写入通道（重要）

0.1 有一个致命 bug：app 侧发布配置走的是 **hook 侧** `XposedInterface.getRemotePreferences()`。
libxposed 有两套偏好接口且不可混用——hook 侧接口在 app 进程里只是**只读视图**，
写入它静默丢失，钉钉永远读不到保存的 profile。0.2 起改为与兄弟模块一致的二接口规则：

- **app 写**：`XposedService.getRemotePreferences()`（经 `XposedServiceHelper` 绑定），打包 AAR；
- **宿主读**：hook 侧 `XposedInterface.getRemotePreferences()`，框架提供；
- 发布失败不再静默：UI 直接提示（含原因），钉钉读到的仍是旧配置。

## 相比原版的架构改进

| 维度 | 原版 | 本重写 |
|---|---|---|
| Xposed API | 旧 API（`assets/xposed_init` + `IXposedHookLoadPackage`） | libxposed API 102（`java_init.list` + `XposedModule`） |
| 配置跨进程 | `XSharedPreferences`（2.3 移除）+ ContentProvider 兜底 | app 写 `XposedService` + 宿主读 hook 接口（API 102 原生，单键 JSON，无 schema 漂移） |
| 高德 SDK | 打包整套（269 类）+ 需 API key | **零打包**，hook 时从宿主 classloader 反射构造 `AMapLocation` |
| WiFi/基站快照 | Parcel.marshall 整份 Profile（跨系统版本易碎） | 仅 marshal 短命的 framework 对象（同机同版本回放，无版本耦合） |
| 定位回调热路径 | 每次回调全量反序列化配置 JSON | 3 秒 TTL 内存缓存，回调只读内存 |
| LSPosed 状态 | 2.2 标"不受支持"，2.3 部分失效 | 全绿，无 legacy 元数据 |

CI 已断言：APK 内**不含** `assets/xposed_init`、**不含** `com/amap/api/`、**不含**打包的
libxposed api，且 `java_init.list`/`module.prop`/`scope.list` 契约正确。

## Hook 点

模块进入宿主（钉钉）进程后，从宿主 classloader 解析高德类并挂钩：

- `AMapLocationClient#getLastKnownLocation()` → 返回按激活 profile 反射构造的完整 `AMapLocation`（含全部地址字段）
- `AMapLocationClient#setLocationListener(listener)` → `Proxy` 包裹监听器，把每次推送的定位
  改写成目标（全字段 + 可选抖动）再交给宿主
- 勾选"回放 WiFi/基站快照"时：`WifiManager#isWifiEnabled/getScanResults/getConnectionInfo` 与
  `TelephonyManager#getAllCellInfo/getNetworkOperator` 返回编辑时捕获的**真实且自洽**的快照
  （Base64 marshalled framework 对象），而非空列表——比"清空"更不易被识破

`WifiInfo.CREATOR` 属隐藏 API，运行时反射取字段解析，编译期不引用。

## 调试日志

设置页「调试日志」开关，与其他模块同一套契约：

- **默认关**；关闭时无队列、无线程、**零写盘**——运行路径每次回调只剩一次布尔判断；
- 开启后需**彻底重启钉钉**（force-stop 再打开）生效：开关在宿主进程启动时采样一次；
- 日志由宿主（钉钉）进程自己写到 `Download/RimetMock/rimetmock-<日期>-<进程随机后缀>.txt`
  （API 29+ 走 MediaStore；兼容落到应用外部目录 `Android/data/com.jieoz.rimetmock/files/RimetMock/`）；
- Application.onCreate 之前的早期行先排队、绑定后补写；
- 安装期/框架级诊断不受开关影响，始终进 LSPosed 管理器日志（tag `RimetMock`），量极小。

## 验证状态（诚实边界）

- **已验证**：云 CI 全绿（APK 契约、签名指纹、"零 SDK/零 api 打包"经反编译确认），
  全部 `com.jieoz.rimetmock.*` 类落在 dex 中。
- **未验证**：真机钉钉内的实际改定位效果——定位 hook 反射操作宿主 `AMapLocation` 这段需 root+钉钉
  真机，本地无此环境。装机实测先开调试日志，再看 `Download/RimetMock/` 与 LSPosed 日志。

## 使用

1. LSPosed 启用本模块，作用域勾选**钉钉**和**本应用**（自身在作用域内才能显示"已激活"）。
2. 打开"锤锤Mock" → 新建位置 → 填经纬度（GCJ-02/高德坐标系），或点"读取当前定位与环境"预填并
   捕获 WiFi/基站快照；地址字段可选手填 → 保存。
3. 回列表勾选该位置为激活项，打开顶部总开关。
4. 回钉钉重新触发定位生效。
5. 需要排查时打开「调试日志」→ 彻底重启钉钉 → 复现 → 把 `Download/RimetMock/` 里的文件发回来。

## 构建

通过 `cloud-build-lab` 云 CI 构建（本机 arm64 无法链接 x86_64 的 AAPT2）。
`compileOnly 'io.github.libxposed:api:102.0.0'` 由框架运行时提供，不打包进 APK；
`XposedService` AAR 打包进 APK（普通库，非框架 api）。
