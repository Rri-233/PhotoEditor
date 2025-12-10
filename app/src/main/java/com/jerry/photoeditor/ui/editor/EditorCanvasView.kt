package com.jerry.photoeditor.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Basic canvas view that can display a bitmap and supports pinch-zoom and panning.
 * Scale is clamped between 0.5x and 2x (relative to fit-center base scale).
 */
class EditorCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bitmap: Bitmap? = null

    // Base scale to fit the image into view
    private var baseScale: Float = 1f

    // User-controlled additional scale (0.5x–2x relative to baseScale)
    private var userScale: Float = 1f

    private var translationX: Float = 0f
    private var translationY: Float = 0f

    private val matrix = Matrix()

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            userScale *= factor
            userScale = max(0.5f, min(2f, userScale))
            clampTranslation()
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            translationX -= distanceX
            translationY -= distanceY
            clampTranslation()
            invalidate()
            return true
        }
    })

    // 简单裁剪框（以图片坐标保存），为 null 表示不显示裁剪框
    private var cropRectImage: RectF? = null

    // 裁剪比例：null 表示自由，>0 表示宽/高固定比例
    var cropAspectRatio: Float? = null

    private val cropBorderPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val cropShadePaint = Paint().apply {
        color = 0x88000000.toInt()
        style = Paint.Style.FILL
    }
    // 网格线 Paint 预分配，避免在 onDraw 中分配
    private val cropGridPaint = Paint().apply {
        color = Color.WHITE
        alpha = 128
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private enum class CropHandle { NONE, MOVE, LEFT, RIGHT, TOP, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private var activeCropHandle: CropHandle = CropHandle.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val handleHitSlopPx = 32f

    // 控制是否允许用户交互（缩放、平移）
    private var isInteractionEnabled = true

    fun setInteractionEnabled(enabled: Boolean) {
        isInteractionEnabled = enabled
    }

    fun setBitmap(bm: Bitmap?) {
        bitmap = bm
        computeBaseScale()
        invalidate()
    }

    fun setCropRect(rect: RectF?) {
        cropRectImage = rect
        invalidate()
    }

    fun getCropRectInImageCoords(): RectF? = cropRectImage

    private fun computeBaseScale() {
        val bm = bitmap ?: return
        if (width == 0 || height == 0) return
        val scaleX = width.toFloat() / bm.width
        val scaleY = height.toFloat() / bm.height
        baseScale = min(scaleX, scaleY)
        userScale = 1f
        translationX = 0f
        translationY = 0f
    }

    private fun clampTranslation() {
        val bm = bitmap ?: return
        val totalScale = baseScale * userScale
        if (totalScale <= 0f) return

        val scaledWidth = bm.width * totalScale
        val scaledHeight = bm.height * totalScale

        val halfWidth = width / 2f
        val halfHeight = height / 2f

        if (scaledWidth <= width) {
            translationX = 0f
        } else {
            val maxOffsetX = (scaledWidth - width) / 2f
            translationX = translationX.coerceIn(-maxOffsetX, maxOffsetX)
        }

        if (scaledHeight <= height) {
            translationY = 0f
        } else {
            val maxOffsetY = (scaledHeight - height) / 2f
            translationY = translationY.coerceIn(-maxOffsetY, maxOffsetY)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeBaseScale()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawColor(Color.BLACK)
        val bm = bitmap ?: return

        matrix.reset()

        // Fit-center base scale
        val totalScale = baseScale * userScale
        val dx = (width - bm.width * totalScale) / 2f + translationX
        val dy = (height - bm.height * totalScale) / 2f + translationY
        matrix.postScale(totalScale, totalScale)
        matrix.postTranslate(dx, dy)

        canvas.drawBitmap(bm, matrix, null)

        // 绘制裁剪覆盖层
        cropRectImage?.let { imgRect ->
            val leftTop = floatArrayOf(imgRect.left, imgRect.top)
            val rightBottom = floatArrayOf(imgRect.right, imgRect.bottom)
            matrix.mapPoints(leftTop)
            matrix.mapPoints(rightBottom)
            val vx1 = leftTop[0]
            val vy1 = leftTop[1]
            val vx2 = rightBottom[0]
            val vy2 = rightBottom[1]

            canvas.drawRect(0f, 0f, width.toFloat(), vy1, cropShadePaint)
            canvas.drawRect(0f, vy2, width.toFloat(), height.toFloat(), cropShadePaint)
            canvas.drawRect(0f, vy1, vx1, vy2, cropShadePaint)
            canvas.drawRect(vx2, vy1, width.toFloat(), vy2, cropShadePaint)

            canvas.drawRect(vx1, vy1, vx2, vy2, cropBorderPaint)
            
            // 绘制 3x3 网格线（重用预分配 paint）
            val cropWidth = vx2 - vx1
            val cropHeight = vy2 - vy1
            
            // 绘制两条垂直线（三等分）
            val verticalStep = cropWidth / 3f
            canvas.drawLine(vx1 + verticalStep, vy1, vx1 + verticalStep, vy2, cropGridPaint)
            canvas.drawLine(vx1 + verticalStep * 2, vy1, vx1 + verticalStep * 2, vy2, cropGridPaint)
            
            // 绘制两条水平线（三等分）
            val horizontalStep = cropHeight / 3f
            canvas.drawLine(vx1, vy1 + horizontalStep, vx2, vy1 + horizontalStep, cropGridPaint)
            canvas.drawLine(vx1, vy1 + horizontalStep * 2, vx2, vy1 + horizontalStep * 2, cropGridPaint)
        }
    }

    /**
     * 当前图片在 View 中的显示区域（考虑 fitCenter、缩放、平移），用于同步叠加层。
     */
    fun getImageDisplayRect(): RectF? {
        val bm = bitmap ?: return null
        val totalScale = baseScale * userScale
        val dx = (width - bm.width * totalScale) / 2f + translationX
        val dy = (height - bm.height * totalScale) / 2f + translationY
        return RectF(dx, dy, dx + bm.width * totalScale, dy + bm.height * totalScale)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rect = cropRectImage
        if (rect != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activeCropHandle = hitCropHandle(event.x, event.y)
                    if (activeCropHandle != CropHandle.NONE) {
                        lastTouchX = event.x
                        lastTouchY = event.y
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (activeCropHandle != CropHandle.NONE) {
                        val dxTouch = event.x - lastTouchX
                        val dyTouch = event.y - lastTouchY
                        lastTouchX = event.x
                        lastTouchY = event.y
                        resizeCropInImage(activeCropHandle, dxTouch, dyTouch)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (activeCropHandle != CropHandle.NONE) {
                        activeCropHandle = CropHandle.NONE
                        return true
                    }
                }
            }
        }

        // 如果交互被禁用，不处理缩放和平移手势
        if (!isInteractionEnabled) {
            return super.onTouchEvent(event)
        }
        
        var handled = scaleGestureDetector.onTouchEvent(event)
        if (!scaleGestureDetector.isInProgress) {
            handled = gestureDetector.onTouchEvent(event) || handled
        }
        if (handled && event.action == MotionEvent.ACTION_UP) {
            performClick()
        }
        return handled || super.onTouchEvent(event)
    }

    private fun hitCropHandle(x: Float, y: Float): CropHandle {
        val imgRect = cropRectImage ?: return CropHandle.NONE
        val leftTop = floatArrayOf(imgRect.left, imgRect.top)
        val rightBottom = floatArrayOf(imgRect.right, imgRect.bottom)
        val rightTop = floatArrayOf(imgRect.right, imgRect.top)
        val leftBottom = floatArrayOf(imgRect.left, imgRect.bottom)
        matrix.mapPoints(leftTop)
        matrix.mapPoints(rightBottom)
        matrix.mapPoints(rightTop)
        matrix.mapPoints(leftBottom)

        // 检测四个角（扩大点击区域到 40dp）
        val cornerSlop = handleHitSlopPx * 2  // 增大角的点击区域
        
        // 左上角
        if (Math.abs(x - leftTop[0]) <= cornerSlop && Math.abs(y - leftTop[1]) <= cornerSlop) {
            return CropHandle.TOP_LEFT
        }
        // 右上角
        if (Math.abs(x - rightTop[0]) <= cornerSlop && Math.abs(y - rightTop[1]) <= cornerSlop) {
            return CropHandle.TOP_RIGHT
        }
        // 左下角
        if (Math.abs(x - leftBottom[0]) <= cornerSlop && Math.abs(y - leftBottom[1]) <= cornerSlop) {
            return CropHandle.BOTTOM_LEFT
        }
        // 右下角
        if (Math.abs(x - rightBottom[0]) <= cornerSlop && Math.abs(y - rightBottom[1]) <= cornerSlop) {
            return CropHandle.BOTTOM_RIGHT
        }

        // 检测是否在裁剪框内部（用于移动整个裁剪框）
        val vx1 = leftTop[0]
        val vy1 = leftTop[1]
        val vx2 = rightBottom[0]
        val vy2 = rightBottom[1]
        
        if (x >= vx1 && x <= vx2 && y >= vy1 && y <= vy2) {
            return CropHandle.MOVE
        }

        return CropHandle.NONE
    }

    private fun resizeCropInImage(handle: CropHandle, dxTouch: Float, dyTouch: Float) {
        val bm = bitmap ?: return
        val rect = cropRectImage ?: return
        val totalScale = baseScale * userScale
        val dxImage = dxTouch / totalScale
        val dyImage = dyTouch / totalScale
        val minSize = 40f
        val ratio = cropAspectRatio

        if (handle == CropHandle.MOVE) {
            val newRect = RectF(rect)
            newRect.left += dxImage
            newRect.right += dxImage
            newRect.top += dyImage
            newRect.bottom += dyImage

            val cropWidth = rect.width()
            val cropHeight = rect.height()

            if (newRect.left < 0f) {
                newRect.left = 0f
                newRect.right = cropWidth
            }
            if (newRect.right > bm.width) {
                newRect.right = bm.width.toFloat()
                newRect.left = newRect.right - cropWidth
            }
            if (newRect.top < 0f) {
                newRect.top = 0f
                newRect.bottom = cropHeight
            }
            if (newRect.bottom > bm.height) {
                newRect.bottom = bm.height.toFloat()
                newRect.top = newRect.bottom - cropHeight
            }

            cropRectImage = newRect
            invalidate()
            return
        }

        if (ratio != null && ratio > 0f) {
            val newRect = RectF(rect)
            when (handle) {
                CropHandle.TOP_LEFT -> {
                    val anchorX = rect.right
                    val anchorY = rect.bottom
                    val widthCandidate = if (abs(dxImage) >= abs(dyImage)) {
                        anchorX - (rect.left + dxImage)
                    } else {
                        (anchorY - (rect.top + dyImage)) * ratio
                    }
                    val maxWidth = max(minSize, min(anchorX, anchorY * ratio))
                    val newWidth = widthCandidate.coerceIn(minSize, maxWidth)
                    val newHeight = newWidth / ratio
                    newRect.left = anchorX - newWidth
                    newRect.top = anchorY - newHeight
                    newRect.right = anchorX
                    newRect.bottom = anchorY
                }
                CropHandle.TOP_RIGHT -> {
                    val anchorX = rect.left
                    val anchorY = rect.bottom
                    val widthCandidate = if (abs(dxImage) >= abs(dyImage)) {
                        (rect.right + dxImage) - anchorX
                    } else {
                        (anchorY - (rect.top + dyImage)) * ratio
                    }
                    val maxWidth = max(minSize, min(bm.width.toFloat() - anchorX, anchorY * ratio))
                    val newWidth = widthCandidate.coerceIn(minSize, maxWidth)
                    val newHeight = newWidth / ratio
                    newRect.left = anchorX
                    newRect.top = anchorY - newHeight
                    newRect.right = anchorX + newWidth
                    newRect.bottom = anchorY
                }
                CropHandle.BOTTOM_LEFT -> {
                    val anchorX = rect.right
                    val anchorY = rect.top
                    val widthCandidate = if (abs(dxImage) >= abs(dyImage)) {
                        anchorX - (rect.left + dxImage)
                    } else {
                        ((rect.bottom + dyImage) - anchorY) * ratio
                    }
                    val maxWidth = max(minSize, min(anchorX, (bm.height.toFloat() - anchorY) * ratio))
                    val newWidth = widthCandidate.coerceIn(minSize, maxWidth)
                    val newHeight = newWidth / ratio
                    newRect.left = anchorX - newWidth
                    newRect.top = anchorY
                    newRect.right = anchorX
                    newRect.bottom = anchorY + newHeight
                }
                CropHandle.BOTTOM_RIGHT -> {
                    val anchorX = rect.left
                    val anchorY = rect.top
                    val widthCandidate = if (abs(dxImage) >= abs(dyImage)) {
                        (rect.right + dxImage) - anchorX
                    } else {
                        ((rect.bottom + dyImage) - anchorY) * ratio
                    }
                    val maxWidth = max(minSize, min(bm.width.toFloat() - anchorX, (bm.height.toFloat() - anchorY) * ratio))
                    val newWidth = widthCandidate.coerceIn(minSize, maxWidth)
                    val newHeight = newWidth / ratio
                    newRect.left = anchorX
                    newRect.top = anchorY
                    newRect.right = anchorX + newWidth
                    newRect.bottom = anchorY + newHeight
                }
                else -> return
            }

            cropRectImage = newRect
            invalidate()
            return
        }

        val newRect = RectF(rect)
        when (handle) {
            CropHandle.TOP_LEFT -> {
                val newLeft = (rect.left + dxImage).coerceIn(0f, rect.right - minSize)
                val newTop = (rect.top + dyImage).coerceIn(0f, rect.bottom - minSize)
                newRect.left = newLeft
                newRect.top = newTop
                newRect.right = rect.right
                newRect.bottom = rect.bottom
            }
            CropHandle.TOP_RIGHT -> {
                val newRight = (rect.right + dxImage).coerceIn(rect.left + minSize, bm.width.toFloat())
                val newTop = (rect.top + dyImage).coerceIn(0f, rect.bottom - minSize)
                newRect.left = rect.left
                newRect.top = newTop
                newRect.right = newRight
                newRect.bottom = rect.bottom
            }
            CropHandle.BOTTOM_LEFT -> {
                val newLeft = (rect.left + dxImage).coerceIn(0f, rect.right - minSize)
                val newBottom = (rect.bottom + dyImage).coerceIn(rect.top + minSize, bm.height.toFloat())
                newRect.left = newLeft
                newRect.top = rect.top
                newRect.right = rect.right
                newRect.bottom = newBottom
            }
            CropHandle.BOTTOM_RIGHT -> {
                val newRight = (rect.right + dxImage).coerceIn(rect.left + minSize, bm.width.toFloat())
                val newBottom = (rect.bottom + dyImage).coerceIn(rect.top + minSize, bm.height.toFloat())
                newRect.left = rect.left
                newRect.top = rect.top
                newRect.right = newRight
                newRect.bottom = newBottom
            }
            else -> return
        }

        cropRectImage = newRect
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
