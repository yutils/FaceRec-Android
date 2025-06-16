package com.kotlinx.inspireface

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.kotlinx.inspireface.config.InspireFaceConfig
import com.kotlinx.inspireface.databinding.ActivityCameraRecognizeBinding
import com.kotlinx.inspireface.db.FaceDatabaseHelper
import com.insightface.sdk.inspireface.InspireFace
import com.insightface.sdk.inspireface.base.Point2f
import java.util.concurrent.Executors

class FaceCameraRecognizeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCameraRecognizeBinding
    private lateinit var dbHelper: FaceDatabaseHelper
    private val executor = Executors.newSingleThreadExecutor()
    private var isFrontCamera = true
    private var cameraProvider: ProcessCameraProvider? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraRecognizeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        dbHelper = FaceDatabaseHelper(this)
        // 设置 PreviewView 水平翻转（镜像）
        // binding.previewView.scaleX = if (lensFacing) -1f else 1f

        // 请求相机权限
        activityResultRegistry.register("CAMERA-YUJI", ActivityResultContracts.RequestPermission()) {
            // 无权限
            if (!it) return@register Toast.makeText(this, "需要相机权限才能使用此功能", Toast.LENGTH_SHORT).show()
            // 权限已授予，启动相机
            startCamera()
        }.run { launch(Manifest.permission.CAMERA) }
        // 切换摄像头
        binding.btnSwitchCamera.setOnClickListener {
            isFrontCamera = !isFrontCamera
            startCamera() // 重启相机以应用新设置
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                // 获取相机提供者
                cameraProvider = cameraProviderFuture.get()

                // 构建预览用例
                val preview = Preview.Builder().setResolutionSelector(
                    ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480), // 尝试目标分辨率
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER //FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER如果设备不支持指定的目标分辨率，CameraX 会优先选择最接近且高于目标分辨率的分辨率。 FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER如果设备不支持指定的目标分辨率，CameraX 会优先选择最接近且低于目标分辨率的分辨率。
                        )
                    ).build()
                )
                    .build()
                    .also {
                        it.setSurfaceProvider(binding.previewView.surfaceProvider)
                    }

                // 构建图像分析用例，使用 ResolutionSelector
                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480), // 尝试目标分辨率
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER  //FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER如果设备不支持指定的目标分辨率，CameraX 会优先选择最接近且高于目标分辨率的分辨率。 FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER如果设备不支持指定的目标分辨率，CameraX 会优先选择最接近且低于目标分辨率的分辨率。
                            )
                        ).build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(executor) { imageProxy ->

                    //获取 bitmap
                    var bitmap = imageProxy.toBitmap()
                    //获取图像旋转角度
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    // 旋转 bitmap
                    val matrix = Matrix()
                    if (rotationDegrees != 0) {
                        matrix.postRotate(rotationDegrees.toFloat())
                    }
                    // 如果是前置摄像头，可能需要镜像翻转
                    if (isFrontCamera) {
                        matrix.postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
                    }
                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    try {
                        if (isDestroyed) return@setAnalyzer
                        processImage(bitmap)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        imageProxy.close()
                    }
                }

                // 解绑之前的所有用例
                cameraProvider?.unbindAll()

                // 尝试绑定所选摄像头
                val cameraSelector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

                // 检查设备是否有所选摄像头
                if (hasCamera(cameraProvider!!, cameraSelector)) {
                    // 有所选摄像头，绑定
                    cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                } else {
                    // 没有所选摄像头，尝试切换到另一个
                    val alternativeSelector = if (isFrontCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                    if (hasCamera(cameraProvider!!, alternativeSelector)) {
                        // 有另一个摄像头，绑定并提示用户
                        isFrontCamera = !isFrontCamera // 更新摄像头方向
                        cameraProvider?.bindToLifecycle(this, alternativeSelector, preview, imageAnalysis)
                        runOnUiThread {
                            Toast.makeText(this, "设备没有${if (!isFrontCamera) "前置" else "后置"}摄像头，已切换到${if (isFrontCamera) "前置" else "后置"}摄像头", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        // 没有任何摄像头
                        runOnUiThread {
                            Toast.makeText(this, "设备没有可用摄像头", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "启动相机失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }, ContextCompat.getMainExecutor(this))
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

    private fun processImage(bitmap: Bitmap) {
        val startTime1 = System.currentTimeMillis()
        //-------------------------------------------核心逻辑-------------------------------------------
        val stream = InspireFace.CreateImageStreamFromBitmap(bitmap, InspireFace.CAMERA_ROTATION_0)
        if (InspireFaceConfig.session == null) return
        val faces = InspireFace.ExecuteFaceTrack(InspireFaceConfig.session, stream)
        val endTime1 = System.currentTimeMillis() - startTime1
        try {
            if (faces.detectedNum == 0) {
                runOnUiThread {
                    binding.tvResult.text = "未检测到人脸 检测耗时${endTime1}ms"
                    binding.faceOverlayView.clear()
                }
            } else {
                var tvResult = "识别到${faces.detectedNum}张人脸 检测耗时${endTime1}ms"
                runOnUiThread {
                    binding.faceOverlayView.setPreviewSize(bitmap.width, bitmap.height)
                }

                val names = Array(faces.detectedNum) { "未知" }
                for (i in 0 until faces.detectedNum) {
                    val startTime2 = System.currentTimeMillis()
                    //提取面部特征
                    if (InspireFaceConfig.session == null) return
                    val feature = InspireFace.ExtractFaceFeature(InspireFaceConfig.session, stream, faces.tokens[i])
                    //从特征中心搜索人脸特征
                    val result = InspireFace.FeatureHubFaceSearch(feature)
                    val name = dbHelper.queryName(result.id.toInt())
                    names[i] = "$name ${String.format("%.2f", result.searchConfidence * 100.0)}%"
                    if (name.isEmpty()) {
                        runOnUiThread {
                            Toast.makeText(this, "未匹配到用户", Toast.LENGTH_SHORT).show()
                        }
                        continue
                    }
                    val endTime2 = System.currentTimeMillis() - startTime2
                    tvResult = tvResult + "\n人脸${i + 1}:$name ${String.format("%.2f", result.searchConfidence * 100.0)}% 识别:${endTime2}ms "
                }
                runOnUiThread {
                    binding.tvResult.text = tvResult
                }

                val living = FloatArray(faces.detectedNum) { 0.0f }
//                //活体分析（管线分析）
//                val cp = InspireFace.CreateCustomParameter().enableLiveness(true)
//                //判断多人脸管线分析是否成功，然后获取每张脸活体概率
//                if (InspireFaceConfig.session == null) return
//                if (InspireFace.MultipleFacePipelineProcess(InspireFaceConfig.session, stream, faces, cp)) {
//                    val confidence = InspireFace.GetRGBLivenessConfidence(InspireFaceConfig.session).confidence
//                    for (i in 0 until faces.detectedNum) {
//                        living[i] = confidence[i]
//                    }
//                }

                //绘制人脸关键点
                val faceList = mutableListOf<List<Point2f>>()
                for (i in 0 until faces.detectedNum) {
                    // 获取人脸关键点
                    val facePoints = InspireFace.GetFaceDenseLandmarkFromFaceToken(faces.tokens[i])
                    faceList.add(facePoints.toList())
                }
                // 在UI线程更新视图
                runOnUiThread {
                    binding.faceOverlayView.setFacePoints(faceList, living, names)
                }
            }
        } finally {
            InspireFace.ReleaseImageStream(stream)
        }
        //-------------------------------------------核心逻辑-------------------------------------------
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        cameraProvider?.unbindAll()
    }
}
