package com.kotlinx.inspireface

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.isVisible
import com.kotlinx.inspireface.config.InspireFaceConfig
import com.kotlinx.inspireface.databinding.ActivityRegisterBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat

// 人脸注册,相册录入
class FaceRegisterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRegisterBinding

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
        binding.imageView.setImageBitmap(bitmap)
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "需要给予读取文件权限", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        binding.btnBatchProcess.isEnabled = false
        CoroutineScope(Dispatchers.Main).launch {
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath
            batchProcessImages("$picturesDir/342601B/")
            binding.tvProgress.text = "读取文件夹中图片：${picturesDir}/342601B/"
            binding.btnBatchProcess.isEnabled = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        InspireFaceConfig.ensureInitialized(MyApp.instance)

        binding.btnChoose.setOnClickListener {
            pickImageLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" })
        }

        binding.btnSubmit.setOnClickListener {
            val bitmap = binding.imageView.drawable?.toBitmap() ?: run {
                Toast.makeText(this, "请先选择照片", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val name = binding.etName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "请输入姓名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val isSuccess = InspireFaceConfig.mutex {
                InspireFaceConfig.deleteByName(name)
                InspireFaceConfig.saveCharacteristicAndName(bitmap, name)
            } ?: false
            Toast.makeText(
                this,
                if (isSuccess) "录入成功" else "录入失败，请确保仅一张人脸且光线充足",
                Toast.LENGTH_SHORT,
            ).show()
            if (isSuccess) {
                binding.etName.setText("")
                binding.imageView.setImageBitmap(null)
            }
        }

        binding.btnBatchProcess.setOnClickListener {
            storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private suspend fun batchProcessImages(path: String) {
        withContext(Dispatchers.IO) {
            val directory = File(path)
            if (!directory.exists()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FaceRegisterActivity, "目录不存在", Toast.LENGTH_SHORT).show()
                }
                return@withContext
            }

            val imageFiles = mutableListOf<File>()
            collectImageFiles(directory, imageFiles)

            withContext(Dispatchers.Main) {
                binding.progressBar.max = imageFiles.size
                binding.progressBar.progress = 0
                binding.progressBar.isVisible = true
                binding.tvProgress.text = ""
            }

            imageFiles.forEachIndexed { index, file ->
                try {
                    if (isDestroyed) return@withContext
                    val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    val name = file.nameWithoutExtension
                    val isSuccess = InspireFaceConfig.mutex {
                        InspireFaceConfig.saveCharacteristicAndName(bitmap, name)
                    } ?: false
                    withContext(Dispatchers.Main) {
                        binding.progressBar.progress = index + 1
                        val percent = DecimalFormat("0.00").format((index + 1) * 100.0 / imageFiles.size)
                        val progress = "当前进度: $percent% （ ${index + 1} / ${imageFiles.size} ）    当前图片：${file.name} ${if (isSuccess) "成功" else "失败"}"
                        binding.tvProgress.text = progress
                        Log.d("InspireFace", progress)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        val percent = DecimalFormat("0.00").format((index + 1) * 100.0 / imageFiles.size)
                        val progress = "失败，当前进度: $percent% （ ${index + 1} / ${imageFiles.size} ）    当前图片：${file.name}"
                        binding.tvProgress.text = progress
                        Log.d("InspireFace", progress)
                    }
                }
            }
            if (isDestroyed) return@withContext
            withContext(Dispatchers.Main) {
                binding.progressBar.isVisible = false
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
}
