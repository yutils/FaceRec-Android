package com.kotlinx.inspireface

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.insightface.sdk.inspireface.InspireFace
import com.kotlinx.inspireface.config.InspireFaceConfig
import com.kotlinx.inspireface.databinding.ActivityRecognizeBinding

// 人脸识别，相册选取
class FaceRecognizeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRecognizeBinding

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
        binding.imageView.setImageBitmap(bitmap)
        recognizeBitmap(bitmap)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecognizeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        InspireFaceConfig.ensureInitialized(MyApp.instance)

        binding.btnChoose.setOnClickListener {
            pickImageLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" })
        }
    }

    private fun recognizeBitmap(bitmap: android.graphics.Bitmap) {
        InspireFaceConfig.mutex {
            val startTime1 = System.currentTimeMillis()
            val stream = InspireFace.CreateImageStreamFromBitmap(bitmap, InspireFace.CAMERA_ROTATION_0) ?: return@mutex
            try {
                val session = InspireFaceConfig.session ?: return@mutex
                val faces = InspireFace.ExecuteFaceTrack(session, stream) ?: return@mutex
                val endTime1 = System.currentTimeMillis() - startTime1

                if (faces.detectedNum == 0) {
                    runOnUiThread {
                        Toast.makeText(this, "未检测到人脸 耗时${endTime1}ms", Toast.LENGTH_SHORT).show()
                        binding.tvResult.text = "未检测到人脸 检测耗时${endTime1}ms"
                    }
                    return@mutex
                }

                var tvResult = "识别到${faces.detectedNum}张人脸  检测耗时${endTime1}ms"
                for (i in 0 until faces.detectedNum) {
                    val startTime2 = System.currentTimeMillis()
                    val currentSession = InspireFaceConfig.session ?: return@mutex
                    //提取面部特征
                    val feature = InspireFace.ExtractFaceFeature(currentSession, stream, faces.tokens[i]) ?: continue
                    //从特征中心搜索人脸特征
                    val searchResult = InspireFace.FeatureHubFaceSearch(feature) ?: continue
                    val name = if (searchResult.id >= 0) {
                        InspireFaceConfig.dbHelper?.queryNameById(searchResult.id.toInt())
                    } else {
                        null
                    }
                    if (name.isNullOrEmpty()) {
                        runOnUiThread {
                            Toast.makeText(this, "未找到匹配用户", Toast.LENGTH_SHORT).show()
                        }
                        // 1.2.4 起无匹配时置信度为 -1，不再显示百分比
                        tvResult += "\n人脸${i + 1}:未知"
                        continue
                    }
                    val endTime2 = System.currentTimeMillis() - startTime2
                    tvResult += "\n人脸${i + 1}:$name ${String.format("%.2f", searchResult.searchConfidence * 100.0)}% 识别:${endTime2}ms "
                }
                runOnUiThread { binding.tvResult.text = tvResult }
            } finally {
                InspireFace.ReleaseImageStream(stream)
            }
        }
    }
}
