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

//人脸识别，相机实时获取
class FaceCameraRegisterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCameraRegisterBinding
    var cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

    //采集下一帧包含人脸的照片
    private var captureNextFaceFrame = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val cameraHelperX = CameraHelperX(this)
        // 请求相机权限
        activityResultRegistry.register("CAMERA-YUJI", ActivityResultContracts.RequestPermission()) {
            // 无权限
            if (!it) return@register Toast.makeText(this, "需要相机权限才能使用此功能", Toast.LENGTH_SHORT).show()
            // 权限已授予，启动相机
            cameraHelperX.startCamera(binding.previewView, cameraSelector)
        }.run { launch(Manifest.permission.CAMERA) }
        // 切换摄像头
        binding.btnSwitchCamera.setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            cameraHelperX.startCamera(binding.previewView, cameraSelector)
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

        //获取每一帧
        cameraHelperX.onBitmapReady = { bitmap ->
            processBitmap(bitmap)
        }
    }

    //处理Bitmap
    private fun processBitmap(bitmap: Bitmap) {
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
            //检测到多张人脸,多张人脸取最大脸
            //if (faces.detectedNum > 1) { return }
            //  判断是否需要处理下一帧
            if (!captureNextFaceFrame) return
            captureNextFaceFrame = false
            if (InspireFaceConfig.session == null) return
            val name = binding.etName.text.toString().trim()
            //保存特征值前先删除重复
            InspireFaceConfig.deleteByName(name)
            //保存
            val feature = InspireFace.ExtractFaceFeature(InspireFaceConfig.session, stream, faces.tokens[0])
            val identity = FaceFeatureIdentity.create(-1, feature)
            if (InspireFace.FeatureHubInsertFeature(identity)) {
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

}
