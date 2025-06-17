package com.kotlinx.inspireface

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
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
import com.insightface.sdk.inspireface.InspireFace
import com.insightface.sdk.inspireface.base.FaceFeatureIdentity
import com.insightface.sdk.inspireface.base.Point2f
import com.kotlinx.inspireface.config.InspireFaceConfig
import com.kotlinx.inspireface.databinding.ActivityCameraRegisterBinding
import java.util.concurrent.Executors

class FaceCameraRegisterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCameraRegisterBinding
    private val executor = Executors.newSingleThreadExecutor()
    private var isFrontCamera = true
    private var cameraProvider: ProcessCameraProvider? = null

    //采集下一帧包含人脸的照片
    private var captureNextFaceFrame = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 设置 PreviewView 水平翻转（镜像）
        //binding.previewView.scaleX = -1f

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
        //  注册人脸
        binding.btnRegister.setOnClickListener {
            val name = binding.etName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "请输入姓名", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            binding.btnRegister.isEnabled = false
            binding.etName.isEnabled = false
            captureNextFaceFrame = true
        }

        //切换数据库
        binding.btnChangeDB.setOnClickListener {
            if (InspireFaceConfig.dbHelper?.dbPath?.endsWith("face_name_1.db") == false) {
                val persistenceDbPath: String = application.let { (it.getExternalFilesDir("")?.absolutePath ?: it.filesDir.path) + "/face_characteristic_1.db" }
                val faceNameDbPath: String = application.let { (it.getExternalFilesDir("")?.absolutePath ?: it.filesDir.path) + "/face_name_1.db" }
                InspireFaceConfig.setDB(persistenceDbPath, faceNameDbPath)
            } else {
                val persistenceDbPath: String = application.let { (it.getExternalFilesDir("")?.absolutePath ?: it.filesDir.path) + "/face_characteristic_2.db" }
                val faceNameDbPath: String = application.let { (it.getExternalFilesDir("")?.absolutePath ?: it.filesDir.path) + "/face_name_2.db" }
                InspireFaceConfig.setDB(persistenceDbPath, faceNameDbPath)
            }
            Toast.makeText(this, "切换数据库成功", Toast.LENGTH_SHORT).show()
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
                    .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

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
                    // 获取 bitmap
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
        //-------------------------------------------核心逻辑-------------------------------------------
        val startTime = System.currentTimeMillis()
        val stream = InspireFace.CreateImageStreamFromBitmap(bitmap, InspireFace.CAMERA_ROTATION_0)
        if (InspireFaceConfig.session == null) return
        val faces = InspireFace.ExecuteFaceTrack(InspireFaceConfig.session, stream)
        try {
            // 未检测到人脸
            if (faces.detectedNum == 0) {
                runOnUiThread {
                    binding.faceOverlayView.clear()
                }
                return
            }
            runOnUiThread {
                binding.faceOverlayView.setPreviewSize(bitmap.width, bitmap.height)
            }
            val faceList = mutableListOf<List<Point2f>>()
            for (i in 0 until faces.detectedNum) {
                // 获取人脸关键点
                val facePoints = InspireFace.GetFaceDenseLandmarkFromFaceToken(faces.tokens[i])
                faceList.add(facePoints.toList())
            }
            // 在UI线程更新视图
            runOnUiThread {
                binding.faceOverlayView.setFacePoints(faceList)
            }
            //检测到多张人脸
            if (faces.detectedNum > 1) {
                return
            }
            //  判断是否需要处理下一帧
            if (!captureNextFaceFrame) return
            captureNextFaceFrame = false
            if (InspireFaceConfig.session == null) return
            val feature = InspireFace.ExtractFaceFeature(InspireFaceConfig.session, stream, faces.tokens[0])
            val identity = FaceFeatureIdentity.create(-1, feature)
            if (InspireFace.FeatureHubInsertFeature(identity)) {
                val name = binding.etName.text.toString().trim()
                InspireFaceConfig.dbHelper?.insertName(identity.id.toInt(), name)
                runOnUiThread {
                    Toast.makeText(this, "录入成功", Toast.LENGTH_SHORT).show()
                    binding.etName.setText("")
                    binding.btnRegister.isEnabled = true
                    binding.etName.isEnabled = true
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this, "录入失败", Toast.LENGTH_SHORT).show()
                    binding.btnRegister.isEnabled = true
                    binding.etName.isEnabled = true
                }
            }
        } finally {
            InspireFace.ReleaseImageStream(stream)
        }
        Log.i("processImage", "耗时：${System.currentTimeMillis() - startTime} ms")

        //-------------------------------------------核心逻辑-------------------------------------------
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        cameraProvider?.unbindAll()
    }
}
