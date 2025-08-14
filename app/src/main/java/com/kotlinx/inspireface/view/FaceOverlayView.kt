package com.kotlinx.inspireface.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Size
import android.view.View
import com.insightface.sdk.inspireface.base.Point2f
import kotlin.math.max
import kotlin.math.min

/**
 * 人脸识别结果绘制
 */
class FaceOverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var pointsLists: MutableList<List<Point2f>> = mutableListOf() //人脸信息
    private var livings: FloatArray? = null //活体信息
    private var names: Array<String>? = null //名称
    private var isFrontCamera = true
    private var previewSize = Size(640, 480)
    private var displaySize = Size(0, 0) // PreviewView 中实际显示区域的尺寸
    private var offsetX = 0f // 水平偏移（黑边）
    private var offsetY = 0f // 垂直偏移（黑边）
    private var scaleFactor = 1f // 实际缩放因子

    // 定义画笔 1号人脸
    val cornerPaint1 = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    // 定义画笔 其他人脸
    val cornerPaint2 = Paint().apply {
        color = Color.LTGRAY
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    // 定义文字画笔
    val textPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
    }

    // 定义画笔 点
    private val pointPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 5f
        style = Paint.Style.FILL
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // 确保兼容性
    }

    // 设置是否为前置摄像头
    fun setFrontCamera(frontCamera: Boolean) {
        isFrontCamera = frontCamera
        invalidate()
    }

    // 设置输入图像的分辨率
    fun setPreviewSize(width: Int, height: Int) {
        previewSize = Size(width, height)
        updateTransformations()
        invalidate()
    }

    // 更新人脸关键点
    fun setFacePoints(facePoints: MutableList<List<Point2f>>, livings: FloatArray? = null, names: Array<String>? = null) {
        pointsLists = facePoints
        this.livings = livings
        this.names = names
        invalidate()
    }

    // 清空绘制内容
    fun clear() {
        pointsLists.clear()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        displaySize = Size(w, h)
        updateTransformations()
    }

    // 计算缩放和偏移以匹配 PreviewView 的 fitCenter 行为
    private fun updateTransformations() {
        if (displaySize.width == 0 || displaySize.height == 0 || previewSize.width == 0 || previewSize.height == 0) {
            return
        }

        val viewAspectRatio = displaySize.width.toFloat() / displaySize.height
        val previewAspectRatio = previewSize.width.toFloat() / previewSize.height

        if (viewAspectRatio > previewAspectRatio) {
            // 上下有黑边
            scaleFactor = displaySize.height.toFloat() / previewSize.height
            val scaledWidth = previewSize.width * scaleFactor
            offsetX = (displaySize.width - scaledWidth) / 2f
            offsetY = 0f
        } else {
            // 左右有黑边
            scaleFactor = displaySize.width.toFloat() / previewSize.width
            val scaledHeight = previewSize.height * scaleFactor
            offsetX = 0f
            offsetY = (displaySize.height - scaledHeight) / 2f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pointsLists.isEmpty()) return

        // 获取屏幕像素密度
        val density = resources.displayMetrics.density

        // 定义L形线段长度（根据密度调整）
        val cornerLength = 10f * density
        cornerPaint1.strokeWidth = 2f * density
        cornerPaint2.strokeWidth = 2f * density
        textPaint.textSize = 12f * density // 根据像素密度动态调整文字大小

        for (i in 0 until pointsLists.size) {
            // 点数据
            val pointsList = pointsLists[i]
            // 活体概率 float
            val living = livings?.get(i) ?: 0f
            // 用户
            val name = names?.get(i) ?: ""

            // 计算人脸框（基于点阵的最上、最下、最左、最右点）
            var minX = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            var minY = Float.MAX_VALUE
            var maxY = Float.MIN_VALUE

            pointsList.forEach { point ->
                minX = min(minX, point.x)
                maxX = max(maxX, point.x)
                minY = min(minY, point.y)
                maxY = max(maxY, point.y)
            }

            // 映射到 View 坐标系
            val left = minX * scaleFactor + offsetX
            val top = minY * scaleFactor + offsetY
            val right = maxX * scaleFactor + offsetX
            val bottom = maxY * scaleFactor + offsetY

            // 绘制四个L形角
            // 左上角
            canvas.drawLine(left, top, left + cornerLength, top, if (i == 0) cornerPaint1 else cornerPaint2)
            canvas.drawLine(left, top, left, top + cornerLength, if (i == 0) cornerPaint1 else cornerPaint2)
            // 右上角
            canvas.drawLine(right - cornerLength, top, right, top, if (i == 0) cornerPaint1 else cornerPaint2)
            canvas.drawLine(right, top, right, top + cornerLength, if (i == 0) cornerPaint1 else cornerPaint2)
            // 左下角
            canvas.drawLine(left, bottom, left + cornerLength, bottom, if (i == 0) cornerPaint1 else cornerPaint2)
            canvas.drawLine(left, bottom - cornerLength, left, bottom, if (i == 0) cornerPaint1 else cornerPaint2)
            // 右下角
            canvas.drawLine(right - cornerLength, bottom, right, bottom, if (i == 0) cornerPaint1 else cornerPaint2)
            canvas.drawLine(right, bottom - cornerLength, right, bottom, if (i == 0) cornerPaint1 else cornerPaint2)

            // 绘制活体概率文字
            if (living > 0f) {
                val text = "活体概率: ${String.format("%.2f", living * 100.0)}%"
                // 计算文字宽度
                val textWidth = textPaint.measureText(text)
                // 计算矩形框宽度
                val rectWidth = right - left
                // 计算居中时的起始x坐标
                val textX = left + (rectWidth - textWidth) / 2
                canvas.drawText(text, textX, bottom + 10 * density, textPaint)
            }
            // 绘制用户名
            if (name.isNotEmpty()) {
                // 计算文字宽度
                val textWidth = textPaint.measureText(name)
                // 计算矩形框宽度
                val rectWidth = right - left
                // 计算居中时的起始x坐标
                val textX = left + (rectWidth - textWidth) / 2
                canvas.drawText(name, textX, top - 2 * density, textPaint)
            }

            // 绘制关键点
//            pointsList.forEach { point ->
//                val x = point.x * scaleFactor + offsetX
//                val y = point.y * scaleFactor + offsetY
//                if (isFrontCamera) {
//                    canvas.save()
//                    //左右镜像 canvas.scale(-1f, 1f, width / 2f, height / 2f)
//                    canvas.drawPoint(x, y, pointPaint)
//                    canvas.restore()
//                } else {
//                    canvas.drawPoint(x, y, pointPaint)
//                }
//            }
        }
    }
}