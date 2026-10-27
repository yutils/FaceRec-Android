package com.kotlinx.inspireface.config

import android.content.Context
import android.util.Log
import com.insightface.sdk.inspireface.InspireFace
import com.kotlinx.inspireface.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 模型选择与模型文件准备。
 *
 * 模型资源来自 inspireface 模块 assets/inspireface，首次使用时复制到应用外部目录，
 * 之后按文件大小校验复用，避免每次启动都复制几十 MB。
 */
object ModelManager {
    private const val TAG = "ModelManager"
    private const val PREFS_NAME = "inspireface_settings"
    private const val KEY_SELECTED_MODEL = "selected_model"
    private const val ASSET_FOLDER = InspireFace.INSPIREFACE_IN_ASSETS_FOLDER_NAME
    private const val COPY_BUFFER_SIZE = 64 * 1024

    val DEFAULT_MODEL = InspireFace.MEGATRON

    data class ModelOption(
        val name: String,
        val label: String,
        val chipLabel: String,
        val description: String,
    )

    val options = listOf(
        ModelOption(
            name = InspireFace.MEGATRON,
            label = "Megatron（高精度 · 61 MB）",
            chipLabel = "Megatron · 高精度",
            description = "使用 R18 特征模型，识别精度更高，检测模型也更大，速度略慢。",
        ),
        ModelOption(
            name = InspireFace.PIKACHU,
            label = "Pikachu（轻量快速 · 17 MB）",
            chipLabel = "Pikachu · 轻量快速",
            description = "使用轻量特征模型，启动和识别速度更快，适合配置较低的设备。",
        ),
    )

    fun selectedModel(context: Context): String {
        val saved = prefs(context).getString(KEY_SELECTED_MODEL, DEFAULT_MODEL)
        return if (options.any { it.name == saved }) saved!! else DEFAULT_MODEL
    }

    fun setSelectedModel(context: Context, name: String) {
        prefs(context).edit().putString(KEY_SELECTED_MODEL, name).apply()
    }

    fun optionOf(name: String): ModelOption {
        return options.firstOrNull { it.name == name } ?: options.first()
    }

    fun labelOf(name: String): String = optionOf(name).label

    /**
     * 确保所选模型已复制到本地并返回文件路径，失败返回 null。
     */
    fun ensureModelFile(context: Context, name: String): File? {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, ASSET_FOLDER)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.e(TAG, "无法创建模型目录: $dir")
            return null
        }

        val target = File(dir, name)
        val assetPath = "$ASSET_FOLDER/$name"
        val assetLength = assetLength(context, assetPath)
        val cacheKey = "model_copied_${name}_${BuildConfig.VERSION_CODE}"
        val prefs = prefs(context)

        if (target.isFile) {
            if (assetLength > 0L && target.length() == assetLength) return target
            // 资源被压缩时无法通过 openFd 取长度，用版本标记兜底
            if (assetLength <= 0L && prefs.getBoolean(cacheKey, false)) return target
        }

        return try {
            val temp = File(dir, "$name.tmp")
            context.assets.open(assetPath).use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output, COPY_BUFFER_SIZE)
                }
            }
            if (assetLength > 0L && temp.length() != assetLength) {
                Log.e(TAG, "模型复制不完整: ${temp.length()} != $assetLength")
                temp.delete()
                return null
            }
            if (target.exists() && !target.delete()) {
                Log.e(TAG, "无法覆盖旧模型文件: $target")
                temp.delete()
                return null
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            prefs.edit().putBoolean(cacheKey, true).apply()
            Log.i(TAG, "模型已就绪: ${target.absolutePath}（${target.length()} 字节）")
            target
        } catch (e: IOException) {
            Log.e(TAG, "复制模型失败: $name", e)
            null
        }
    }

    private fun assetLength(context: Context, assetPath: String): Long {
        return try {
            context.assets.openFd(assetPath).use { it.length }
        } catch (e: IOException) {
            -1L
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
