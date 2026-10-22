# 项目长期记忆

## 项目定位
基于开源 InspireFace 的人脸识别 Demo（Kotlin + CameraX），`app` 为 UI 层，`inspireface` 为本地 SDK 模块（源码 + so + 模型资源）。

## SDK 约定
- 当前内置 **InspireFace Android SDK 1.2.4.post1**（native 1.2.4），上游 tag `v1.2.4.post1`。
- ABI：`arm64-v8a`、`armeabi-v7a`、`x86_64`。**上游不提供 32 位 x86**，模拟器用 x86_64。
- **minSdk 必须 ≥ 24**（1.2.4 native 库要求，`app` 与 `inspireface` 需同步）。
- 模型只保留 `Pikachu`（t4.0），不打包 `Megatron`（61MB），保持 APK 体积。
- 升级 SDK 时须同时替换：`libs/*/libInspireFace.so`、`src/main/java` 全量源码、`src/main/assets/inspireface/Pikachu`、`version.txt`、`consumer-rules.pro`。
- 1.2.4 起 native API 失败会返回 `null`（ExecuteFaceTrack / ExtractFaceFeature / FeatureHubFaceSearch / 关键点），无匹配为 `id=-1` 且 `searchConfidence=-1`，调用处必须判空。

## 测试环境
- 设备：雷电模拟器（`emulator-5554`，x86_64 / Android 14）。另有 RK3588 工控机、三星手机等真机常连。
- 雷电的 CameraX `ImageAnalysis` 帧转换会花屏/错位，导致摄像头实时页检测不到人脸；**验证算法请走"相册/照片识别"链路或仪器化测试**，不要据此判断 SDK 异常。
- 仪器化测试：`ANDROID_SERIAL=emulator-5554 ./gradlew :inspireface:connectedDebugAndroidTest`（13 项，覆盖检测/特征/FeatureHub/抓拍）。
- adb 在 Git Bash 下需 `MSYS_NO_PATHCONV=1` 才能正确传 `/sdcard` 路径；截图用 `adb exec-out screencap -p > file`。

## 网络
- 下载 GitHub 走代理 `127.0.0.1:7897`；大文件（>100MB）用后台任务下载，避免超时。

## 构建
- JDK 21 + Gradle 9.6.1 + AGP 9.3.1；`assembleDebug`、`:inspireface:assembleRelease` 均可用。
