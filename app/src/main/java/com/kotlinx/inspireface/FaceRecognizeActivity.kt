package com.kotlinx.inspireface

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import com.insightface.sdk.inspireface.InspireFace
import com.kotlinx.inspireface.config.InspireFaceConfig
import com.kotlinx.inspireface.databinding.ActivityRecognizeBinding
import com.kotlinx.inspireface.utils.ImageUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 人脸识别，相册选取
class FaceRecognizeActivity : BaseActivity() {
    private lateinit var binding: ActivityRecognizeBinding
    private var selectedBitmap: Bitmap? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val bitmap = ImageUtils.decodeBitmap(this, uri)
        if (bitmap == null) {
            Toast.makeText(this, "图片读取失败，请换一张照片", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        setSelectedBitmap(bitmap)
        binding.tvResult.text = "正在识别…"
        binding.btnChoose.isEnabled = false
        CoroutineScope(Dispatchers.IO).launch {
            try {
                recognizeBitmap(bitmap)
            } finally {
                withContext(Dispatchers.Main) {
                    if (!isDestroyed && !isFinishing) {
                        binding.btnChoose.isEnabled = true
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecognizeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, "人脸识别")
        applyInsets(binding.root, ime = true)
        InspireFaceConfig.ensureInitialized(MyApp.instance)

        binding.btnChoose.setOnClickListener {
            pickImageLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" })
        }
    }

    private fun setSelectedBitmap(bitmap: Bitmap?) {
        selectedBitmap = bitmap
        binding.imageView.setImageBitmap(bitmap)
        binding.imagePlaceholder.isVisible = bitmap == null
    }

    private fun runOnUiThreadSafe(action: () -> Unit) {
        if (isDestroyed || isFinishing) return
        runOnUiThread {
            if (!isDestroyed && !isFinishing) action()
        }
    }

    private fun recognizeBitmap(bitmap: Bitmap) {
        InspireFaceConfig.mutex {
            val startTime1 = System.currentTimeMillis()
            val stream = InspireFace.CreateImageStreamFromBitmap(bitmap, InspireFace.CAMERA_ROTATION_0)
                ?: run {
                    runOnUiThreadSafe { binding.tvResult.text = "识别失败：图片处理异常" }
                    return@mutex
                }
            try {
                val session = InspireFaceConfig.session ?: run {
                    runOnUiThreadSafe { binding.tvResult.text = "SDK 未初始化，请到设置中检查模型" }
                    return@mutex
                }
                val faces = InspireFace.ExecuteFaceTrack(session, stream) ?: run {
                    runOnUiThreadSafe { binding.tvResult.text = "识别失败，请重试" }
                    return@mutex
                }
                val endTime1 = System.currentTimeMillis() - startTime1

                if (faces.detectedNum == 0) {
                    runOnUiThreadSafe {
                        Toast.makeText(this, "未检测到人脸 耗时${endTime1}ms", Toast.LENGTH_SHORT).show()
                        binding.tvResult.text = "未检测到人脸 检测耗时${endTime1}ms"
                    }
                    return@mutex
                }

                var tvResult = "识别到${faces.detectedNum}张人脸  检测耗时${endTime1}ms"
                for (i in 0 until faces.detectedNum) {
                    val startTime2 = System.currentTimeMillis()
                    val currentSession = InspireFaceConfig.session ?: return@mutex
                    val feature = InspireFace.ExtractFaceFeature(currentSession, stream, faces.tokens[i])
                        ?: continue
                    val searchResult = InspireFace.FeatureHubFaceSearch(feature) ?: continue
                    val name = if (searchResult.feature != null) {
                        InspireFaceConfig.dbHelper?.queryNameById(searchResult.id.toInt())
                    } else {
                        null
                    }
                    if (name.isNullOrEmpty()) {
                        runOnUiThreadSafe {
                            Toast.makeText(this, "未找到匹配用户", Toast.LENGTH_SHORT).show()
                        }
                        tvResult += "\n人脸${i + 1}:未知"
                        continue
                    }
                    val endTime2 = System.currentTimeMillis() - startTime2
                    val percent = runCatching {
                        InspireFace.CosineSimilarityConvertToPercentage(searchResult.searchConfidence) * 100.0
                    }.getOrDefault(searchResult.searchConfidence * 100.0)
                    tvResult += "\n人脸${i + 1}:$name ${String.format("%.1f", percent)}% 识别:${endTime2}ms "
                }
                runOnUiThreadSafe { binding.tvResult.text = tvResult }
            } finally {
                InspireFace.ReleaseImageStream(stream)
            }
        }
    }

    override fun onDestroy() {
        if (::binding.isInitialized) {
            setSelectedBitmap(null)
        }
        super.onDestroy()
    }
}
