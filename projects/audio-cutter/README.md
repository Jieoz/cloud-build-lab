# 音频剪切 (Audio Cutter)

一个只做一件事的安卓 App：**把一段音频里你要的那段留下来，其余剪掉，导出保存。**

## 功能

- 选择本机任意音频文件（mp3 / m4a / aac / wav / ogg 等，凡是系统能播放的都行）
- 拖动双向滑块选择「保留区间」，显示精确到 10 毫秒的时间
- 「试听选段」在剪切前先听一遍选中的部分
- 一键剪切并导出为 `.m4a`，保存到 `音乐/AudioCutter/`

没有变调、混音、降噪那些花活——只有剪切。

## 技术实现

- 纯 Kotlin，无第三方音频库，用系统 `MediaExtractor` + `MediaCodec` + `MediaMuxer` 解码后重编码为 AAC。
- 一条代码路径处理所有输入格式，不按格式分支。
- `minSdk 24`，`targetSdk 35`。
- 输出走 MediaStore（Android 10+ 免存储权限）。

## 构建

云端 GitHub Actions 构建，产物为已签名 APK。手动触发 `Build Audio Cutter APK` 工作流并勾选 `release` 即产出正式 Release。
