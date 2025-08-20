package com.kotlinx.inspireface.utils

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import kotlin.also

/**
 * CameraX 简易封装
 * @author yujing 2025年8月20日17点55分
 * 权限要求：
 * <uses-permission android:name="android.permission.CAMERA" />
 * <uses-feature android:name="android.hardware.camera" android:required="false" />
 * <uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />
 */
/*
引入：
implementation 'androidx.camera:camera-extensions:1.3.1'
implementation 'androidx.camera:camera-camera2:1.3.1'
implementation 'androidx.camera:camera-lifecycle:1.3.1'
implementation 'androidx.camera:camera-core:1.3.1'
implementation 'androidx.camera:camera-view:1.3.1'

用法示例：
var cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
val cameraHelperX = CameraHelperX(this)
// 请求相机权限
activityResultRegistry.register("CAMERA-YUJI", ActivityResultContracts.RequestPermission()) {
    if (!it) return@register Toast.makeText(this, "需要相机权限才能使用此功能", Toast.LENGTH_SHORT).show()
    // 权限已授予，启动相机
    cameraHelperX.startCamera(binding.previewView, cameraSelector)
}.run { launch(Manifest.permission.CAMERA) }

// 切换摄像头
binding.btnSwitchCamera.setOnClickListener {
    cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
    cameraHelperX.startCamera(binding.previewView, cameraSelector)
}
//实时获取每一帧
cameraHelperX.onBitmapReady = { bitmap->

}

// 获取一张bitmap
val bitmap = cameraHelperX.getBitmap()
 */
class CameraHelperX(val activity: AppCompatActivity) : DefaultLifecycleObserver {
    private val executor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null

    // 保存最新的 Bitmap，供外部获取
    private var latestBitmap: Bitmap? = null

    // 当前摄像头方向
    var cameraSelector: CameraSelector? = null

    // 获取最新的 Bitmap
    fun getBitmap(): Bitmap? = latestBitmap

    //回调函数，当捕获的图像转换为 Bitmap 时调用
    var onBitmapReady: ((Bitmap) -> Unit)? = null

    init {
        activity.lifecycle.addObserver(this)
    }

    //这个方法不用看
    fun startCamera(previewView: PreviewView, cameraSelector: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA, width: Int = 640, height: Int = 480) {
        this.cameraSelector = cameraSelector
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            try {
                // 获取相机提供者
                cameraProvider = cameraProviderFuture.get()

                // 构建预览用例
                val preview = Preview.Builder().setResolutionSelector(
                    ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(
                            Size(width, height), // 尝试目标分辨率
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER //FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER如果设备不支持指定的目标分辨率，CameraX 会优先选择最接近且高于目标分辨率的分辨率。 FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER如果设备不支持指定的目标分辨率，CameraX 会优先选择最接近且低于目标分辨率的分辨率。
                        )
                    ).build()
                )
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                // 构建图像分析用例，使用 ResolutionSelector
                val imageAnalysis = ImageAnalysis.Builder().setResolutionSelector(
                    ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(
                            Size(width, height), // 尝试目标分辨率
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER  //FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER如果设备不支持指定的目标分辨率，CameraX 会优先选择最接近且高于目标分辨率的分辨率。 FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER如果设备不支持指定的目标分辨率，CameraX 会优先选择最接近且低于目标分辨率的分辨率。
                        )
                    ).build()
                )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    processImage(imageProxy)
                }

                // 解绑之前的所有用例
                cameraProvider?.unbindAll()

                // 检查设备是否有所选摄像头
                if (hasCamera(cameraProvider!!, cameraSelector)) {
                    // 有所选摄像头，绑定
                    val camera = cameraProvider?.bindToLifecycle(activity, cameraSelector, preview, imageAnalysis)
                    //val cameraInfo = camera?.cameraInfo
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity.runOnUiThread {
                    Toast.makeText(activity, "启动相机失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    //判断是否有该摄像头
    private fun hasCamera(cameraProvider: ProcessCameraProvider, cameraSelector: CameraSelector): Boolean {
        return try {
            cameraProvider.hasCamera(cameraSelector)
        } catch (e: CameraInfoUnavailableException) {
            e.printStackTrace()
            false
        }
    }

    //将ImageProxy转换为Bitmap
    private fun processImage(imageProxy: ImageProxy) {
        try {
            // 获取 bitmap
            var bitmap = imageProxy.toBitmap()
            // 获取图像旋转角度
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            // 旋转 bitmap
            val matrix = Matrix()
            if (rotationDegrees != 0) {
                matrix.postRotate(rotationDegrees.toFloat())
            }
            // 如果是前置摄像头，可能需要镜像翻转
            if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                matrix.postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
            }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            latestBitmap = bitmap
            onBitmapReady?.invoke(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            imageProxy.close()
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        executor.shutdown()
        cameraProvider?.unbindAll()
    }
}