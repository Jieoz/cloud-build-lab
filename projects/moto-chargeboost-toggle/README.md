# Moto ChargeBoost Toggle

给 Motorola 手机上藏得很深的“疾速快充”做一个更顺手的入口。

## 当前方案

这不是系统级直改开关的特权 app；当前实现走的是更现实的三段式：

1. **Quick Settings 磁贴（更顺手）**
   - 安装后可把本 app 的磁贴加到下拉快捷开关里
   - 一点就能直接进入“疾速快充”页面

2. **快捷直达页面**
   - 直接打开：
     - `com.motorola.coresettingsext/com.motorola.coresettingsext.CoreSettingsExt$ChargeBoostActivity`
   - 备用入口：
     - `com.motorola.moto/com.motorola.showtipsv5.features.tiplist.acitivity.ListTipsV5Activity`

3. **无障碍辅助自动点击**（可选）
   - 用户手动开启本 app 的无障碍服务后
   - 点击“一键切换”会先跳到目标页面，再尝试自动点击“疾速快充”开关

## 能力边界

- **最稳的能力**：一键直达“疾速快充”页面
- **增强能力**：借助无障碍服务自动点开关
- **大概率做不到的能力**：普通第三方 app 在后台无授权直接修改这个系统私有开关

## 需要你实机确认的点

因为不同 Moto 机型 / ROM 版本 UI 可能略有差异，下面两点需要实机验证：

1. 直接打开 `ChargeBoostActivity` 是否成功
2. 页面里“疾速快充”文案是否正好就是这四个字，且附近控件可被无障碍点击

## 后续可增强

如果你愿意继续打磨，下一步可以加：
- 桌面快捷开关小组件（Widget）
- Quick Settings 磁贴（下拉控制中心一键进）
- 针对你这台机子的页面结构做更精准的点击策略
- 如果 adb/授权条件允许，研究是否存在更底层可调用接口
