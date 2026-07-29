package com.kotlinx.inspireface

import android.Manifest
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import com.insightface.sdk.inspireface.InspireFace
import com.insightface.sdk.inspireface.base.FaceFeatureIdentity
import com.insightface.sdk.inspireface.base.Point2f
import com.kotlinx.inspireface.config.InspireFaceConfig
import com.kotlinx.inspireface.databinding.ActivityCameraRegisterBinding
import com.kotlinx.inspireface.utils.CameraHelperX

// 人脸录入，相机实时获取
class FaceCameraRegisterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCameraRegisterBinding
    private lateinit var cameraHelperX: CameraHelperX
    private var cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    private var captureNextFaceFrame = false
    private var pendingRegisterName: String? = null
    private var noFaceFrameCount = 0
    private companion object {
        const val MAX_NO_FACE_FRAMES = 90 // 约 3 秒无检测到人脸则超时
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraRegisterBinding.inflate(layoutInflater)
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

        binding.btnRegister.setOnClickListener {
            val name = binding.etName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "请输入姓名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.btnRegister.isEnabled = false
            binding.etName.isEnabled = false
            pendingRegisterName = name
            captureNextFaceFrame = true
            noFaceFrameCount = 0
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

    private fun resetRegisterState(message: String? = null) {
        captureNextFaceFrame = false
        pendingRegisterName = null
        runOnUiThreadSafe {
            message?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
            binding.btnRegister.isEnabled = true
            binding.etName.isEnabled = true
        }
    }

    private fun processBitmap(bitmap: Bitmap) {
        val startTime = System.currentTimeMillis()
        val stream = InspireFace.CreateImageStreamFromBitmap(bitmap, InspireFace.CAMERA_ROTATION_0)
        try {
            val session = InspireFaceConfig.session ?: return
            val faces = InspireFace.ExecuteFaceTrack(session, stream)

            if (faces.detectedNum == 0) {
                runOnUiThreadSafe { binding.faceOverlayView.clear() }
                if (captureNextFaceFrame && ++noFaceFrameCount >= MAX_NO_FACE_FRAMES) {
                    resetRegisterState("未检测到人脸，请正对摄像头重试")
                }
                return
            }
            noFaceFrameCount = 0

            val faceList = mutableListOf<List<Point2f>>()
            for (i in 0 until faces.detectedNum) {
                val facePoints = InspireFace.GetFaceDenseLandmarkFromFaceToken(faces.tokens[i])
                faceList.add(facePoints.toList())
            }
            runOnUiThreadSafe {
                binding.faceOverlayView.setPreviewSize(bitmap.width, bitmap.height)
                binding.faceOverlayView.setFacePoints(faceList)
            }

            if (!captureNextFaceFrame) return
            val name = pendingRegisterName ?: return
            val currentSession = InspireFaceConfig.session ?: return

            InspireFaceConfig.deleteByName(name)
            val feature = InspireFace.ExtractFaceFeature(currentSession, stream, faces.tokens[0])
            val identity = FaceFeatureIdentity.create(-1, feature)
            if (InspireFace.FeatureHubInsertFeature(identity)) {
                InspireFaceConfig.dbHelper?.insertName(identity.id.toInt(), name)
                resetRegisterState("录入成功")
                runOnUiThreadSafe { binding.etName.setText("") }
            } else {
                Log.w("FaceCameraRegister", "FeatureHubInsertFeature 失败")
                resetRegisterState("录入失败，请正对摄像头重试")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            if (captureNextFaceFrame) {
                resetRegisterState("录入异常，请重试")
            }
        } finally {
            InspireFace.ReleaseImageStream(stream)
        }
        Log.i("processImage", "耗时：${System.currentTimeMillis() - startTime} ms")
    }

    override fun onDestroy() {
        captureNextFaceFrame = false
        pendingRegisterName = null
        cameraHelperX.onBitmapReady = null
        super.onDestroy()
    }
}
