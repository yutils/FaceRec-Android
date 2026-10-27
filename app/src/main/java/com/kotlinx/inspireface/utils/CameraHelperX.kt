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
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.also

/**
 * CameraX 简易封装
 * @author yujing 2025年8月20日17点55分
 */
class CameraHelperX(val activity: AppCompatActivity) : DefaultLifecycleObserver {
    private val executor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private val isProcessing = AtomicBoolean(false)
    @Volatile
    private var isReleased = false

    private var latestBitmap: Bitmap? = null
    @Volatile
    private var isFrontCamera = false
    var onBitmapReady: ((Bitmap) -> Unit)? = null

    init {
        activity.lifecycle.addObserver(this)
    }

    fun getBitmap(): Bitmap? = latestBitmap

    fun startCamera(
        previewView: PreviewView,
        cameraSelector: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA,
        width: Int = 640,
        height: Int = 480,
    ) {
        if (isReleased) return
        isFrontCamera = cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            if (isReleased) return@addListener
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(width, height),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        ).build(),
                )
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                val imageAnalysis = ImageAnalysis.Builder().setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(width, height),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        ).build(),
                )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    processImage(imageProxy)
                }

                provider.unbindAll()

                if (hasCamera(provider, cameraSelector)) {
                    provider.bindToLifecycle(activity, cameraSelector, preview, imageAnalysis)
                } else if (!isReleased) {
                    activity.runOnUiThread {
                        Toast.makeText(activity, "未找到可用的摄像头", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (!isReleased) {
                    activity.runOnUiThread {
                        Toast.makeText(activity, "启动相机失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    private fun hasCamera(cameraProvider: ProcessCameraProvider, cameraSelector: CameraSelector): Boolean {
        return try {
            cameraProvider.hasCamera(cameraSelector)
        } catch (e: CameraInfoUnavailableException) {
            e.printStackTrace()
            false
        }
    }

    private fun processImage(imageProxy: ImageProxy) {
        if (isReleased || onBitmapReady == null) {
            imageProxy.close()
            return
        }
        // 上一帧仍在识别中则跳过，避免帧堆积导致内存和 native 资源泄漏
        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        try {
            val rawBitmap = imageProxy.toBitmap()
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            var bitmap = rawBitmap
            if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(
                    rawBitmap,
                    0,
                    0,
                    rawBitmap.width,
                    rawBitmap.height,
                    matrix,
                    true,
                )
                if (rotated !== rawBitmap) rawBitmap.recycle()
                bitmap = rotated
            }
            // PreviewView 的前置预览是镜像的；先旋转到显示方向，再镜像，保证人脸框与预览一致
            if (isFrontCamera) {
                val matrix = Matrix().apply {
                    setScale(-1f, 1f)
                    postTranslate(bitmap.width.toFloat(), 0f)
                }
                val mirrored = Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height,
                    matrix,
                    true,
                )
                if (mirrored !== bitmap) bitmap.recycle()
                bitmap = mirrored
            }
            recycleLatestBitmap()
            latestBitmap = bitmap
            onBitmapReady?.invoke(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isProcessing.set(false)
            imageProxy.close()
        }
    }

    private fun recycleLatestBitmap() {
        latestBitmap?.let {
            if (!it.isRecycled) it.recycle()
        }
        latestBitmap = null
    }

    override fun onDestroy(owner: LifecycleOwner) {
        isReleased = true
        onBitmapReady = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        executor.shutdown()
        // 不能在这里 recycle latestBitmap：当前分析帧可能仍在回调中读取它
        latestBitmap = null
        activity.lifecycle.removeObserver(this)
    }
}
