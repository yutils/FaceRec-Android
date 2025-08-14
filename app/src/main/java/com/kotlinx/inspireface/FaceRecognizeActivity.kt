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

//人脸识别，相册选取
class FaceRecognizeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRecognizeBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecognizeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnChoose.setOnClickListener {
            activityResultRegistry.register("打开手机中的相册", ActivityResultContracts.StartActivityForResult()) { result ->
                if (result?.resultCode != Activity.RESULT_OK) return@register //没有选择照片
                val uri = result.data?.data
                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                binding.imageView.setImageBitmap(bitmap)

                //耗时统计
                val startTime1 = System.currentTimeMillis()
                //-------------------------------------------核心逻辑-------------------------------------------
                val stream = InspireFace.CreateImageStreamFromBitmap(bitmap, InspireFace.CAMERA_ROTATION_0)
                if (InspireFaceConfig.session == null) return@register
                val faces = InspireFace.ExecuteFaceTrack(InspireFaceConfig.session, stream)
                val endTime1 = System.currentTimeMillis() - startTime1
                try {
                    if (faces.detectedNum == 0) {
                        Toast.makeText(this, "未检测到人脸 耗时${endTime1}ms", Toast.LENGTH_SHORT).show()
                        binding.tvResult.text = "未检测到人脸 检测耗时${endTime1}ms"
                        return@register
                    } else {
                        var tvResult = "识别到${faces.detectedNum}张人脸  检测耗时${endTime1}ms"
                        for (i in 0 until faces.detectedNum) {
                            val startTime2 = System.currentTimeMillis()
                            //提取面部特征
                            if (InspireFaceConfig.session == null) return@register
                            val feature = InspireFace.ExtractFaceFeature(InspireFaceConfig.session, stream, faces.tokens[i])
                            //从特征中心搜索人脸特征
                            val result = InspireFace.FeatureHubFaceSearch(feature)
                            val name = InspireFaceConfig.dbHelper?.queryNameById(result.id.toInt())
                            if (name.isNullOrEmpty()) {
                                Toast.makeText(this, "未找到人脸", Toast.LENGTH_SHORT).show(); return@register
                            }
                            val endTime2 = System.currentTimeMillis() - startTime2
                            tvResult = tvResult + "\n人脸${i + 1}:$name ${String.format("%.2f", result.searchConfidence * 100.0)}% 识别:${endTime2}ms "
                        }
                        binding.tvResult.text = tvResult
                    }
                } finally {
                    InspireFace.ReleaseImageStream(stream)
                }
                //-------------------------------------------核心逻辑-------------------------------------------
            }.run { launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }) }
        }
    }
}