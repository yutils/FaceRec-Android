# 基于开源软件 inspireface 的人脸识别demo

### 本项目【完全离线】不需要联网激活什么的

参考

**本项目是基于开源项目 https://github.com/HyperInspire/inspireface-android-sdk 适配的UI界面**

**本项目是基于开源项目 https://github.com/HyperInspire/InspireFace**


## SDK 版本

`inspireface` 模块当前内置 **InspireFace Android SDK 1.2.4.post1**（native 1.2.4，C API Level 2），
取自上游 tag `v1.2.4.post1`：

| 项目 | 说明 |
| --- | --- |
| ABI | `arm64-v8a`、`armeabi-v7a`、`x86_64`（x86_64 用于模拟器调试，上游自 1.2.4 起提供） |
| minSdk | **24**（1.2.4 起 native 库最低要求，`app` 与 `inspireface` 模块已同步） |
| 模型 | 只保留 `Pikachu`（t4.0），未打包 61MB 的 `Megatron` |
| 头文件/清单 | `inspireface/sdk/1.2.4/`（C 头文件、API manifest、SHA256SUMS） |

升级要点：新版本在 native 失败时可能返回 `null`（`ExecuteFaceTrack` / `ExtractFaceFeature` /
`FeatureHubFaceSearch`），无匹配时 `FeatureHubFaceSearch` 返回 `id = -1`、`searchConfidence = -1`，
业务代码已按此补齐空判断。

### 更新说明（1.1.13 → 1.2.4.post1）

1. 替换 `inspireface/libs/<abi>/libInspireFace.so`，新增 `x86_64`
2. 同步 `inspireface/src/main/java` 全部 Java 源码（新增 JNI 全量接口、`FaceCapture`、快照等）
3. 同步模型资源 `Pikachu`（t4.0）与 `version.txt`，更新 `consumer-rules.pro`（JNI 类保留规则）
4. `inspireface` 与 `app` 的 `minSdk` 由 22 提升到 24，`abiFilters` 增加 `x86_64`


## 项目目的
    为了研究人脸识别app，以及为了体验做出demo的成就感。
    之前版本没有UI太痛苦了

## 改进地方
1. 做了UI界面 ，适配横屏安卓工控机，也适配手机app
2. 为了优化APP体积，只保留InspireFace.PIKACHU 模型删除了 InspireFace.Megatron。觉得需要自己添加
3. 添加通过相册录入人脸，也可以批量读取文件夹内人脸
4. 选择照片人脸识别
5. 通过摄像头录入人脸信息
6. 通过摄像头实时识别人脸，并且在界面上框选人脸和显示名称
7. 自建本地数据存放人脸id对应名称
8. 可以查用户列表和删除用户特征值
9. 采用kotlin语言开发


## 页面预览

| ![预览](doc/01.jpg) | ![预览](doc/02.jpg) |
| --- | --- |
| ![预览](doc/03.jpg) | ![预览](doc/04.jpg) |


大家可以自己下载玩一玩，[在release里面有apk文件。](https://github.com/yutils/FaceRec-Android/releases)
觉得不错给我个star吧

### 实际项目中把用户名换成用户id就是

哦，还有一个重要的事情，我之前在退出摄像头页面的时候释放代码就崩溃了，研究了半天
```kotlin
InspireFace.ReleaseSession(session)
InspireFace.FeatureHubDataDisable()
InspireFace.GlobalTerminate()
```
因为我在ondestroy()里面调用了以上代码，把InspireFace释放了，但是最后一帧的bitmap还没处理完毕，一旦调用InspireFace里面的其他方法就会崩溃，大家谨记谨记，一定得处理完毕后才能释放资源。


或者在处理的逻辑加上互斥锁 `InspireFaceConfig.mutex` ，要求关闭时得处理完当前帧
```kotlin
imageAnalysis.setAnalyzer(executor) { imageProxy ->
    InspireFaceConfig.mutex {   //互斥锁，防止这帧没处理完 InspireFace.ReleaseSession(session) 导致崩溃
        val bitmap = imageToBitmap(imageProxy)
        bitmap?.let { processBitmap(it) }
    }
}
```


对了，给我个star吧
