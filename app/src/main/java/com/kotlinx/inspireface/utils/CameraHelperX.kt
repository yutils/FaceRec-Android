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
import java.util.concurrent.TimeUnit
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
    var cameraSelector: CameraSelector? = null
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
        this.cameraSelector = cameraSelector
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            if (isReleased) return@addListener
            try {
                cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().setResolutionSelector(
                    ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(
                            Size(width, height),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    ).build(),
                )
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                val imageAnalysis = ImageAnalysis.Builder().setResolutionSelector(
                    ResolutionSelector.Builder().setResolutionStrategy(
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

                cameraProvider?.unbindAll()

                if (hasCamera(cameraProvider!!, cameraSelector)) {
                    cameraProvider?.bindToLifecycle(activity, cameraSelector, preview, imageAnalysis)
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
            val matrix = Matrix()
            if (rotationDegrees != 0) {
                matrix.postRotate(rotationDegrees.toFloat())
            }
            if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                matrix.postScale(-1f, 1f, rawBitmap.width / 2f, rawBitmap.height / 2f)
            }
            val bitmap = if (matrix.isIdentity) {
                rawBitmap
            } else {
                Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true).also {
                    if (it !== rawBitmap) rawBitmap.recycle()
                }
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
        executor.shutdown()
        try {
            // 等待当前帧处理完毕，避免 native 层在识别中途被强制打断
            executor.awaitTermination(3, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        recycleLatestBitmap()
        activity.lifecycle.removeObserver(this)
    }
}
