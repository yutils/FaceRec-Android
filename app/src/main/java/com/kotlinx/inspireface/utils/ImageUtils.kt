package com.kotlinx.inspireface.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * 相册/批量采集图片解码：
 * - 按最大边降采样，避免大图 OOM
 * - 根据 EXIF 修正方向
 * - 失败返回 null，由调用方提示
 */
object ImageUtils {
    private const val TAG = "ImageUtils"
    const val MAX_DIMENSION = 1280

    fun decodeBitmap(context: Context, uri: Uri, maxDimension: Int = MAX_DIMENSION): Bitmap? {
        return try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null

            val orientation = runCatching {
                resolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                }
            }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

            applyExifAndScale(bitmap, orientation, maxDimension)
        } catch (e: Throwable) {
            Log.e(TAG, "解码图片失败: $uri", e)
            null
        }
    }

    fun decodeBitmap(file: File, maxDimension: Int = MAX_DIMENSION): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            val orientation = runCatching {
                ExifInterface(file.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

            applyExifAndScale(bitmap, orientation, maxDimension)
        } catch (e: Throwable) {
            Log.e(TAG, "解码图片失败: ${file.absolutePath}", e)
            null
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        val maxSide = maxOf(width, height)
        while (maxSide / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun applyExifAndScale(source: Bitmap, orientation: Int, maxDimension: Int): Bitmap {
        var result = source
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                matrix.setScale(-1f, 1f)
                matrix.postTranslate(source.width.toFloat(), 0f)
            }
            ExifInterface.ORIENTATION_ROTATE_180 -> {
                matrix.setRotate(180f, source.width / 2f, source.height / 2f)
            }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setScale(1f, -1f)
                matrix.postTranslate(0f, source.height.toFloat())
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
                matrix.postTranslate(source.height.toFloat(), 0f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
                matrix.postTranslate(source.width.toFloat(), 0f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }

        if (!matrix.isIdentity) {
            val transformed = Bitmap.createBitmap(
                source,
                0,
                0,
                source.width,
                source.height,
                matrix,
                true,
            )
            if (transformed !== source) {
                source.recycle()
                result = transformed
            }
        }

        val maxSide = maxOf(result.width, result.height)
        if (maxSide > maxDimension) {
            val scale = maxDimension.toFloat() / maxSide
            val resized = Bitmap.createScaledBitmap(
                result,
                (result.width * scale).toInt().coerceAtLeast(1),
                (result.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            if (resized !== result) {
                result.recycle()
                result = resized
            }
        }
        return result
    }
}
