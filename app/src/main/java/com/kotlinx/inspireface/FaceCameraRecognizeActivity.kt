package com.kotlinx.inspireface

import android.Manifest
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import com.insightface.sdk.inspireface.InspireFace
import com.insightface.sdk.inspireface.base.Point2f
import com.kotlinx.inspireface.config.InspireFaceConfig
import com.kotlinx.inspireface.databinding.ActivityCameraRecognizeBinding
import com.kotlinx.inspireface.utils.CameraHelperX
import java.util.concurrent.Executors

//人脸识别，相机实时获取
class FaceCameraRecognizeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCameraRecognizeBinding
    var cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraRecognizeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 设置 PreviewView 水平翻转（镜像）
        // binding.previewView.scaleX = if (lensFacing) -1f else 1f
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
                    val name = InspireFaceConfig.dbHelper?.queryNameById(result.id.toInt())
                    names[i] = "$name ${String.format("%.2f", result.searchConfidence * 100.0)}%"
                    if (name.isNullOrEmpty()) {
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
}
