package com.kotlinx.inspireface

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import com.kotlinx.inspireface.config.InspireFaceConfig
import com.kotlinx.inspireface.databinding.ActivityRegisterBinding
import com.kotlinx.inspireface.utils.ImageUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat

// 人脸注册,相册录入
class FaceRegisterActivity : BaseActivity() {
    private lateinit var binding: ActivityRegisterBinding
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
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "需要照片访问权限才能批量采集", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        binding.btnBatchProcess.isEnabled = false
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val folder = batchFolderPath()
                binding.tvProgress.text = "读取文件夹中图片：$folder"
                batchProcessImages(folder)
            } finally {
                binding.btnBatchProcess.isEnabled = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, "人脸录入")
        applyInsets(binding.root, ime = true)
        InspireFaceConfig.ensureInitialized(MyApp.instance)

        binding.btnChoose.setOnClickListener {
            pickImageLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" })
        }

        binding.btnSubmit.setOnClickListener {
            val bitmap = selectedBitmap ?: run {
                Toast.makeText(this, "请先选择照片", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val name = binding.etName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "请输入姓名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.btnSubmit.isEnabled = false
            binding.btnChoose.isEnabled = false
            binding.btnSubmit.text = "录入中…"
            CoroutineScope(Dispatchers.IO).launch {
                val isSuccess = runCatching {
                    InspireFaceConfig.mutex {
                        InspireFaceConfig.saveCharacteristicAndName(bitmap, name)
                    } ?: false
                }.getOrDefault(false)
                withContext(Dispatchers.Main) {
                    if (isDestroyed || isFinishing) return@withContext
                    binding.btnSubmit.isEnabled = true
                    binding.btnChoose.isEnabled = true
                    binding.btnSubmit.text = "提交录入"
                    Toast.makeText(
                        this@FaceRegisterActivity,
                        if (isSuccess) "录入成功" else "录入失败，请确保仅一张人脸且光线充足",
                        Toast.LENGTH_SHORT,
                    ).show()
                    if (isSuccess) {
                        binding.etName.setText("")
                        setSelectedBitmap(null)
                    }
                }
            }
        }

        binding.btnBatchProcess.setOnClickListener {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            storagePermissionLauncher.launch(permission)
        }
    }

    private fun setSelectedBitmap(bitmap: Bitmap?) {
        selectedBitmap = bitmap
        binding.imageView.setImageBitmap(bitmap)
        binding.imagePlaceholder.isVisible = bitmap == null
    }

    private fun batchFolderPath(): String {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        return "${picturesDir.absolutePath}/342601B/"
    }

    private suspend fun batchProcessImages(path: String) {
        withContext(Dispatchers.IO) {
            val directory = File(path)
            if (!directory.isDirectory) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FaceRegisterActivity, "目录不存在", Toast.LENGTH_SHORT).show()
                }
                return@withContext
            }

            val imageFiles = mutableListOf<File>()
            collectImageFiles(directory, imageFiles)
            if (imageFiles.isEmpty()) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.isVisible = false
                    binding.tvProgress.text = "目录中没有可处理的图片"
                    Toast.makeText(this@FaceRegisterActivity, "目录中没有可处理的图片", Toast.LENGTH_SHORT).show()
                }
                return@withContext
            }

            withContext(Dispatchers.Main) {
                binding.progressBar.max = imageFiles.size
                binding.progressBar.progress = 0
                binding.progressBar.isVisible = true
            }

            try {
                imageFiles.forEachIndexed { index, file ->
                    if (isDestroyed) return@withContext
                    var bitmap: Bitmap? = null
                    val isSuccess = try {
                        bitmap = ImageUtils.decodeBitmap(file)
                        val targetBitmap = bitmap
                        if (targetBitmap == null) {
                            false
                        } else {
                            InspireFaceConfig.mutex {
                                InspireFaceConfig.saveCharacteristicAndName(
                                    targetBitmap,
                                    file.nameWithoutExtension,
                                )
                            } ?: false
                        }
                    } catch (e: Exception) {
                        Log.e("FaceRegister", "批量处理失败: ${file.name}", e)
                        false
                    } finally {
                        bitmap?.recycle()
                    }

                    withContext(Dispatchers.Main) {
                        binding.progressBar.progress = index + 1
                        val percent = DecimalFormat("0.00").format((index + 1) * 100.0 / imageFiles.size)
                        val progress = "当前进度: $percent% （ ${index + 1} / ${imageFiles.size} ）    当前图片：${file.name} ${if (isSuccess) "成功" else "失败"}"
                        binding.tvProgress.text = progress
                        Log.d("InspireFace", progress)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.progressBar.isVisible = false
                }
            }

            if (isDestroyed) return@withContext
            withContext(Dispatchers.Main) {
                binding.tvProgress.text = "批量处理完成"
                Toast.makeText(this@FaceRegisterActivity, "批量处理完成", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun collectImageFiles(directory: File, imageFiles: MutableList<File>) {
        if (!directory.exists() || !directory.isDirectory || !directory.canRead()) {
            Log.e("ImageCollector", "失败，无法读取目录: ${directory.absolutePath}")
            return
        }
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                collectImageFiles(file, imageFiles)
            } else if (file.extension.lowercase() in listOf("jpg", "jpeg", "png")) {
                imageFiles.add(file)
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
