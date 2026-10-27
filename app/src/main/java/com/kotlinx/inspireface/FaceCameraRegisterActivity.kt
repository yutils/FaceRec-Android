package com.kotlinx.inspireface

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.core.content.ContextCompat
import com.insightface.sdk.inspireface.InspireFace
import com.insightface.sdk.inspireface.base.Point2f
import com.kotlinx.inspireface.config.InspireFaceConfig
import com.kotlinx.inspireface.databinding.ActivityCameraRegisterBinding
import com.kotlinx.inspireface.utils.CameraHelperX

// 人脸录入，相机实时获取
class FaceCameraRegisterActivity : BaseActivity() {
    override val lightStatusBarIcons: Boolean = true

    private lateinit var binding: ActivityCameraRegisterBinding
    private lateinit var cameraHelperX: CameraHelperX
    private var cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    private var captureNextFaceFrame = false
    private var pendingRegisterName: String? = null
    private var noFaceFrameCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureTimeout = Runnable {
        if (captureNextFaceFrame) {
            resetRegisterState("录入超时，请重试")
        }
    }
    private companion object {
        const val MAX_NO_FACE_FRAMES = 90 // 约 3 秒无检测到人脸则超时
        const val CAPTURE_TIMEOUT_MS = 10_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        InspireFaceConfig.ensureInitialized(MyApp.instance)
        binding.btnBack.setOnClickListener { finish() }
        applyInsets(binding.topBar, bottom = false)
        applyInsets(binding.panel, top = false, bottom = true, ime = true)

        cameraHelperX = CameraHelperX(this)
        activityResultRegistry.register("CAMERA-YUJI", ActivityResultContracts.RequestPermission()) {
            if (!it) {
                Toast.makeText(this, "需要相机权限才能使用此功能", Toast.LENGTH_SHORT).show()
                binding.tvStatus.text = "未获得相机权限"
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.error))
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
            binding.tvStatus.text = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                "已切换到前置摄像头"
            } else {
                "已切换到后置摄像头"
            }
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.brand_primary))
        }

        binding.btnRegister.setOnClickListener {
            val name = binding.etName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "请输入姓名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(binding.etName.windowToken, 0)
            binding.etName.clearFocus()
            binding.btnRegister.isEnabled = false
            binding.btnRegister.text = "正在录入…"
            binding.etName.isEnabled = false
            binding.tvStatus.text = "请正对摄像头，保持面部清晰"
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.brand_primary))
            pendingRegisterName = name
            captureNextFaceFrame = true
            noFaceFrameCount = 0
            mainHandler.removeCallbacks(captureTimeout)
            mainHandler.postDelayed(captureTimeout, CAPTURE_TIMEOUT_MS)
        }

        binding.btnChangeDB.setOnClickListener {
            val basePath = application.let {
                it.getExternalFilesDir("")?.absolutePath ?: it.filesDir.path
            }
            val isFirstDb = InspireFaceConfig.dbHelper?.dbPath?.endsWith("face_name_1.db") ?: false
            val success = if (isFirstDb) {
                InspireFaceConfig.switchDB(
                    "$basePath/face_characteristic_2.db",
                    "$basePath/face_name_2.db",
                )
            } else {
                InspireFaceConfig.switchDB(
                    "$basePath/face_characteristic_1.db",
                    "$basePath/face_name_1.db",
                )
            }
            val message = if (success) "已切换数据库" else "切换数据库失败"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            binding.tvStatus.text = message
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(this, if (success) R.color.success else R.color.error),
            )
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

    private fun resetRegisterState(message: String? = null, success: Boolean = false) {
        mainHandler.removeCallbacks(captureTimeout)
        captureNextFaceFrame = false
        pendingRegisterName = null
        runOnUiThreadSafe {
            message?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
            binding.btnRegister.isEnabled = true
            binding.btnRegister.text = "开始录入"
            binding.etName.isEnabled = true
            binding.tvStatus.text = message ?: "输入姓名后点击开始录入"
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(
                    this,
                    when {
                        success -> R.color.success
                        message != null -> R.color.error
                        else -> R.color.text_secondary
                    },
                ),
            )
        }
    }

    private fun processBitmap(bitmap: Bitmap) {
        val startTime = System.currentTimeMillis()
        val stream = InspireFace.CreateImageStreamFromBitmap(bitmap, InspireFace.CAMERA_ROTATION_0) ?: return
        try {
            val session = InspireFaceConfig.session ?: return
            val faces = InspireFace.ExecuteFaceTrack(session, stream) ?: return

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
                val facePoints = InspireFace.GetFaceDenseLandmarkFromFaceToken(faces.tokens[i]) ?: continue
                faceList.add(facePoints.toList())
            }
            runOnUiThreadSafe {
                binding.faceOverlayView.setPreviewSize(bitmap.width, bitmap.height)
                binding.faceOverlayView.setFacePoints(faceList)
            }

            if (!captureNextFaceFrame) return
            val name = pendingRegisterName ?: return
            val currentSession = InspireFaceConfig.session ?: return

            if (faces.detectedNum > 1) {
                resetRegisterState("检测到多张人脸，请保持画面中只有一人")
                return
            }

            val feature = InspireFace.ExtractFaceFeature(currentSession, stream, faces.tokens[0]) ?: run {
                Log.w("FaceCameraRegister", "ExtractFaceFeature 返回空")
                resetRegisterState("录入失败，请正对摄像头重试")
                return
            }
            if (InspireFaceConfig.saveFeature(feature, name)) {
                resetRegisterState("录入成功，可继续录入下一位", success = true)
                runOnUiThreadSafe { binding.etName.setText("") }
            } else {
                Log.w("FaceCameraRegister", "保存人脸特征失败")
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
        mainHandler.removeCallbacks(captureTimeout)
        captureNextFaceFrame = false
        pendingRegisterName = null
        if (::cameraHelperX.isInitialized) {
            cameraHelperX.onBitmapReady = null
        }
        super.onDestroy()
    }
}
