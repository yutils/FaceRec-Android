package com.kotlinx.inspireface

import android.Manifest
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import com.insightface.sdk.inspireface.InspireFace
import com.insightface.sdk.inspireface.base.Point2f
import com.kotlinx.inspireface.config.InspireFaceConfig
import com.kotlinx.inspireface.databinding.ActivityCameraRecognizeBinding
import com.kotlinx.inspireface.utils.CameraHelperX

// 人脸识别，相机实时获取
class FaceCameraRecognizeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCameraRecognizeBinding
    private lateinit var cameraHelperX: CameraHelperX
    private var cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraRecognizeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        InspireFaceConfig.ensureInitialized(MyApp.instance)

        cameraHelperX = CameraHelperX(this)
        activityResultRegistry.register("CAMERA-YUJI", ActivityResultContracts.RequestPermission()) {
            if (!it) {
                Toast.makeText(this, "需要相机权限才能使用此功能", Toast.LENGTH_SHORT).show()
                return@register
            }
            cameraHelperX.startCamera(binding.previewView, cameraSelector)
        }.run { launch(Manifest.permission.CAMERA) }

        binding.btnSwitchCamera.setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            cameraHelperX.startCamera(binding.previewView, cameraSelector)
        }

        binding.btnChangeDB.setOnClickListener {
            val basePath = application.let {
                it.getExternalFilesDir("")?.absolutePath ?: it.filesDir.path
            }
            if (InspireFaceConfig.dbHelper?.dbPath?.endsWith("face_name_1.db") == false) {
                InspireFaceConfig.switchDB(
                    "$basePath/face_characteristic_1.db",
                    "$basePath/face_name_1.db",
                )
            } else {
                InspireFaceConfig.switchDB(
                    "$basePath/face_characteristic_2.db",
                    "$basePath/face_name_2.db",
                )
            }
            Toast.makeText(this, "切换数据库成功", Toast.LENGTH_SHORT).show()
        }

        cameraHelperX.onBitmapReady = lambda@{ bitmap ->
            if (isDestroyed || isFinishing) return@lambda
            // 互斥锁：防止帧处理过程中 session 被释放导致 native 崩溃
            InspireFaceConfig.mutex {
                if (!isDestroyed && !isFinishing) {
                    processBitmap(bitmap)
                }
            }
        }
    }

    private fun runOnUiThreadSafe(action: () -> Unit) {
        if (isDestroyed || isFinishing) return
        runOnUiThread {
            if (!isDestroyed && !isFinishing) action()
        }
    }

    private fun processBitmap(bitmap: Bitmap) {
        val startTime1 = System.currentTimeMillis()
        val stream = InspireFace.CreateImageStreamFromBitmap(bitmap, InspireFace.CAMERA_ROTATION_0)
        try {
            val session = InspireFaceConfig.session ?: return
            val faces = InspireFace.ExecuteFaceTrack(session, stream)
            val endTime1 = System.currentTimeMillis() - startTime1

            if (faces.detectedNum == 0) {
                runOnUiThreadSafe {
                    binding.tvResult.text = "未检测到人脸 检测耗时${endTime1}ms"
                    binding.faceOverlayView.clear()
                }
                return
            }

            var tvResult = "识别到${faces.detectedNum}张人脸 检测耗时${endTime1}ms"
            val names = Array(faces.detectedNum) { "未知" }
            for (i in 0 until faces.detectedNum) {
                val startTime2 = System.currentTimeMillis()
                val currentSession = InspireFaceConfig.session ?: return
                val feature = InspireFace.ExtractFaceFeature(currentSession, stream, faces.tokens[i])
                val result = InspireFace.FeatureHubFaceSearch(feature)
                val name = if (result.id >= 0) {
                    InspireFaceConfig.dbHelper?.queryNameById(result.id.toInt())
                } else {
                    null
                }
                names[i] = "${name ?: "未知"} ${String.format("%.2f", result.searchConfidence * 100.0)}%"
                if (!name.isNullOrEmpty()) {
                    val endTime2 = System.currentTimeMillis() - startTime2
                    tvResult += "\n人脸${i + 1}:$name ${String.format("%.2f", result.searchConfidence * 100.0)}% 识别:${endTime2}ms "
                }
            }

            val living = FloatArray(faces.detectedNum) { 0.0f }
            val faceList = mutableListOf<List<Point2f>>()
            for (i in 0 until faces.detectedNum) {
                val facePoints = InspireFace.GetFaceDenseLandmarkFromFaceToken(faces.tokens[i])
                faceList.add(facePoints.toList())
            }

            runOnUiThreadSafe {
                binding.faceOverlayView.setPreviewSize(bitmap.width, bitmap.height)
                binding.tvResult.text = tvResult
                binding.faceOverlayView.setFacePoints(faceList, living, names)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            InspireFace.ReleaseImageStream(stream)
        }
    }

    override fun onDestroy() {
        cameraHelperX.onBitmapReady = null
        super.onDestroy()
    }
}
