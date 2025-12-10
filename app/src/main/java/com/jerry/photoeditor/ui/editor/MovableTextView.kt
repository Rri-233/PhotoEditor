package com.jerry.photoeditor.ui.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import androidx.appcompat.content.res.AppCompatResources
import android.graphics.Typeface
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewParent
import androidx.appcompat.widget.AppCompatTextView
import com.jerry.photoeditor.R
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 可移动、缩放、旋转的文字视图
 */
class MovableTextView(context: Context) : AppCompatTextView(context) {

    data class TextOptions(
        val color: Int,
        val sizeSp: Float,
        val typeface: Typeface?,
        val alphaPercent: Float
    )

    // 回调
    var onOverlaySelected: ((MovableTextView) -> Unit)? = null
    var onOverlayDoubleTapped: ((MovableTextView) -> Unit)? = null
    var onOverlayDeleted: ((MovableTextView) -> Unit)? = null

    // 选中状态（使用不同的名字避免与View.isSelected冲突）
    var isTextSelected: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // 相对于图片的归一化坐标（0-1范围）
    // 这样无论视图大小如何变化，文本在图片上的位置都是固定的
    var normalizedX: Float = 0.5f  // 图片中心
    var normalizedY: Float = 0.5f
    var relativeScale: Float = 1.0f  // 相对于基准大小的缩放
    var textRotation: Float = 0f  // 旋转角度
    
    // 图片尺寸信息（用于所见即所得）
    private var imageDisplayWidth: Float = 0f
    private var imageDisplayHeight: Float = 0f
    private var imageOffsetX: Float = 0f
    private var imageOffsetY: Float = 0f

    // 手势相关
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false
    private var isScaleRotating = false  // 是否正在通过控制点缩放旋转
    
    // 双指旋转相关
    private var initialRotation = 0f
    private var initialAngle = 0f
    private var primaryPointerId = -1
    private var secondaryPointerId = -1
    private var suppressTapUntilUp = false

    private fun hasTwoPointers(): Boolean = primaryPointerId != -1 && secondaryPointerId != -1
    private fun requestParentDisallowIntercept(disallow: Boolean) {
        var currentParent: ViewParent? = parent
        while (currentParent != null) {
            currentParent.requestDisallowInterceptTouchEvent(disallow)
            currentParent = currentParent.parent
        }
    }
    
    // 缩放旋转控制点相关
    private var initialScale = 1f
    private var initialDistance = 0f

    // 缩放手势检测器
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            relativeScale *= scaleFactor
            // 限制缩放范围 0.5x - 3.0x
            relativeScale = relativeScale.coerceIn(0.5f, 3.0f)
            
            // 同步更新View的缩放
            scaleX = relativeScale
            scaleY = relativeScale
            return true
        }
    })

    // 点击手势检测器
    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // 确保可访问性事件触发
            this@MovableTextView.performClick()
            onOverlaySelected?.invoke(this@MovableTextView)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            onOverlayDoubleTapped?.invoke(this@MovableTextView)
            return true
        }
    })

    init {
        // 设置默认样式 - 减小padding让文本框更紧凑
        setPadding(8, 8, 8, 8)
        textSize = 24f
        setTextColor(android.graphics.Color.WHITE)
        
        // 设置为wrap_content，自动调整大小
        layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        
        // 设置背景（选中时显示虚线边框）
        updateBackground()
    }

    // 预分配绘制用 Paint，避免在 onDraw 中频繁分配
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 3f
        setShadowLayer(6f, 0f, 2f, 0x88000000.toInt())
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFF44336.toInt()
    }

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x88FFFFFF.toInt()
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
    }

    private val iconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }

    fun setTextOptions(content: String, options: TextOptions) {
        text = content
        setTextColor(options.color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, options.sizeSp)
        typeface = options.typeface ?: Typeface.DEFAULT
        alpha = options.alphaPercent / 100f
        
        // 设置最大宽度，避免文本过长
        maxWidth = resources.displayMetrics.widthPixels - 100
        
        // 自动换行
        maxLines = 10
        
        // 重新测量以调整大小
        requestLayout()
    }
    
    /**
     * 设置图片和容器尺寸信息
     * 用于实现所见即所得的文本大小
     */
    /**
        * 传入图片实际显示区域（相对于父容器的坐标）。推荐使用 ImageView 的 matrix 计算得到。
        */
    fun setImageDisplayFrame(left: Float, top: Float, width: Float, height: Float) {
        imageDisplayWidth = width
        imageDisplayHeight = height
        imageOffsetX = left
        imageOffsetY = top
    }

    private fun updateBackground() {
        if (isTextSelected) {
            setBackgroundResource(R.drawable.text_overlay_selection)
        } else {
            background = null
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                primaryPointerId = event.getPointerId(0)
                secondaryPointerId = -1
                suppressTapUntilUp = false
                
                val touchX = event.x
                val touchY = event.y
                
                // 检测是否点击了左上角的删除按钮
                val deleteHandleRadius = 28f
                val deleteDistance = sqrt(touchX * touchX + touchY * touchY)
                if (isTextSelected && deleteDistance <= deleteHandleRadius) {
                    // 点击了删除按钮
                    onOverlayDeleted?.invoke(this)
                    return true
                }
                
                // 检测是否点击了右下角的缩放旋转控制点
                val scaleHandleRadius = 24f
                val handleX = width.toFloat()
                val handleY = height.toFloat()
                val scaleDx = touchX - handleX
                val scaleDy = touchY - handleY
                val scaleDistance = sqrt(scaleDx * scaleDx + scaleDy * scaleDy)

                if (isTextSelected && scaleDistance <= scaleHandleRadius) {
                    // 点击了控制点，开始缩放旋转
                    isScaleRotating = true
                    isDragging = false
                    requestParentDisallowIntercept(true)
                    
                    initialScale = relativeScale
                    initialRotation = textRotation

                    val dx = event.rawX - (x + width / 2f)
                    val dy = event.rawY - (y + height / 2f)
                    initialDistance = sqrt(dx * dx + dy * dy)
                    initialAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                } else {
                    // 普通拖动
                    isDragging = true
                    isScaleRotating = false
                    requestParentDisallowIntercept(true)
                }
                
                onOverlaySelected?.invoke(this)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (secondaryPointerId == -1) {
                    val actionIndex = event.actionIndex
                    secondaryPointerId = event.getPointerId(actionIndex)
                    initialRotation = textRotation
                    getAngleBetweenPointers(event)?.let {
                        initialAngle = it
                    }
                    isDragging = false
                    isScaleRotating = false
                    suppressTapUntilUp = true
                    requestParentDisallowIntercept(true)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (hasTwoPointers()) {
                    getAngleBetweenPointers(event)?.let { currentAngle ->
                        val deltaAngle = currentAngle - initialAngle
                        textRotation = initialRotation + deltaAngle
                        rotation = textRotation
                    }
                } else if (isScaleRotating && !hasTwoPointers()) {
                    // 通过控制点缩放和旋转
                    val dx = event.rawX - (x + width / 2f)
                    val dy = event.rawY - (y + height / 2f)
                    val currentDistance = sqrt(dx * dx + dy * dy)
                    val currentAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    
                    // 更新缩放
                    if (initialDistance > 0) {
                        relativeScale = initialScale * (currentDistance / initialDistance)
                        relativeScale = relativeScale.coerceIn(0.5f, 3.0f)
                        scaleX = relativeScale
                        scaleY = relativeScale
                    }
                    
                    // 更新旋转
                    textRotation = initialRotation + (currentAngle - initialAngle)
                    rotation = textRotation
                    
                    // 触发重绘以显示辅助线
                    invalidate()
                    
                } else if (isDragging && !hasTwoPointers()) {
                    // 单指拖动
                    val deltaX = event.rawX - lastTouchX
                    val deltaY = event.rawY - lastTouchY
                    translationX += deltaX
                    translationY += deltaY
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == secondaryPointerId) {
                    secondaryPointerId = -1
                } else if (pointerId == primaryPointerId) {
                    primaryPointerId = secondaryPointerId
                    secondaryPointerId = -1
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                }
                if (!hasTwoPointers()) {
                    suppressTapUntilUp = false
                    if (!isDragging && !isScaleRotating) {
                        requestParentDisallowIntercept(false)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                primaryPointerId = -1
                secondaryPointerId = -1
                isDragging = false
                isScaleRotating = false
                suppressTapUntilUp = false
                requestParentDisallowIntercept(false)
            }
        }
        
        // 不在控制点旋转时，允许捏合缩放（双指）
        if (!isScaleRotating) {
            scaleDetector.onTouchEvent(event)
        }
        
        // 处理点击和长按（多指或控制点操作时跳过，避免误触）
        if (!suppressTapUntilUp && !isScaleRotating && event.pointerCount == 1) {
            tapDetector.onTouchEvent(event)
        }

        return true
    }

    /**
     * 计算两个触摸点之间的角度
     */
    private fun getAngleBetweenPointers(event: MotionEvent): Float? {
        if (!hasTwoPointers()) return null
        val firstIndex = event.findPointerIndex(primaryPointerId)
        val secondIndex = event.findPointerIndex(secondaryPointerId)
        if (firstIndex == -1 || secondIndex == -1) return null
        val deltaX = event.getX(secondIndex) - event.getX(firstIndex)
        val deltaY = event.getY(secondIndex) - event.getY(firstIndex)
        return Math.toDegrees(atan2(deltaY.toDouble(), deltaX.toDouble())).toFloat()
    }

    /**
     * 渲染到最终图片（用于保存）
     * 确保所见即所得：屏幕上看到的大小和位置 = 保存后的效果
     */
    fun renderToCanvas(canvas: Canvas, bitmapWidth: Int, bitmapHeight: Int) {
        canvas.save()

        // 计算文本在图片上的中心位置（使用归一化坐标）
        val centerXOnBitmap = normalizedX * bitmapWidth
        val centerYOnBitmap = normalizedY * bitmapHeight
        val displayScale = if (imageDisplayWidth > 0f) {
            bitmapWidth.toFloat() / imageDisplayWidth
        } else {
            1f
        }
        val scale = displayScale * relativeScale

        canvas.translate(centerXOnBitmap, centerYOnBitmap)
        canvas.rotate(textRotation)
        canvas.scale(scale, scale)
        canvas.translate(-width / 2f, -height / 2f)

        // 临时标记为非选中状态，避免绘制UI控制元素
        val originalBackground = background
        val wasSelected = isTextSelected
        val wasScaleRotating = isScaleRotating
        
        background = null
        isTextSelected = false
        isScaleRotating = false
        
        // 11. 绘制TextView（只绘制文本内容）
        draw(canvas)
        
        // 12. 恢复原始状态
        background = originalBackground
        isTextSelected = wasSelected
        isScaleRotating = wasScaleRotating

        canvas.restore()
    }
    
    /**
     * 更新归一化坐标
     * 在用户拖动文本后调用，将视图坐标转换为归一化坐标
     */
    fun updateNormalizedPosition(imageViewWidth: Int, imageViewHeight: Int) {
        if (imageDisplayWidth <= 0f || imageDisplayHeight <= 0f) return
        val centerX = x + width / 2f
        val centerY = y + height / 2f

        // 相对于图片实际显示区域（去除信箱黑边）求归一化坐标
        normalizedX = ((centerX - imageOffsetX) / imageDisplayWidth).coerceIn(0f, 1f)
        normalizedY = ((centerY - imageOffsetY) / imageDisplayHeight).coerceIn(0f, 1f)
    }
    
    /**
     * 从归一化坐标设置视图位置
     * 在文本添加时调用，将归一化坐标转换为视图坐标
     */
    fun setPositionFromNormalized(imageViewWidth: Int, imageViewHeight: Int) {
        if (imageDisplayWidth <= 0f || imageDisplayHeight <= 0f) return
        // 从归一化坐标计算相对于图片显示区域的中心点
        val centerX = imageOffsetX + normalizedX * imageDisplayWidth
        val centerY = imageOffsetY + normalizedY * imageDisplayHeight

        x = centerX - width / 2f
        y = centerY - height / 2f
        
        // 同步缩放和旋转
        scaleX = relativeScale
        scaleY = relativeScale
        rotation = textRotation
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateBackground()
        
        // 如果选中，绘制控制点
        if (isTextSelected) {
            drawDeleteHandle(canvas)  // 左上角删除按钮
            drawScaleRotateHandle(canvas)  // 右下角缩放旋转按钮
        }
        
        // 如果正在缩放旋转，绘制辅助线
        if (isScaleRotating) {
            drawScaleRotateGuide(canvas)
        }
    }
    
    private fun drawScaleRotateGuide(canvas: Canvas) {
        // 绘制从中心到控制点的辅助线
        val handleSize = 48f
        val handleRadius = handleSize / 2f
        val handleX = width.toFloat()
        val handleY = height.toFloat()

        canvas.drawLine(
            width / 2f,
            height / 2f,
            handleX,
            handleY,
            guidePaint
        )
    }
    
    private fun drawDeleteHandle(canvas: Canvas) {
        val handleSize = 48f // 控制点大小
        val handleRadius = handleSize / 2f

        // 控制点中心位于左上角顶点
        val handleX = 0f
        val handleY = 0f
        
        // 绘制外圈白色边框（重用预分配 paint）
        canvas.drawCircle(handleX, handleY, handleRadius, borderPaint)
        
        // 绘制控制点背景（红色圆圈）
        canvas.drawCircle(handleX, handleY, handleRadius - 2f, handlePaint)

        // 绘制删除图标（使用提供的资源），使用 AppCompat 兼容加载
        AppCompatResources.getDrawable(context, R.drawable.sticker_view_close_icon_12)?.let { drawable ->
            val density = resources.displayMetrics.density
            val intrinsic = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else (12 * density).toInt()
            val iconSizePx = intrinsic.coerceAtLeast(8)
            val halfSize = iconSizePx / 2f
            val left = (handleX - halfSize).toInt()
            val top = (handleY - halfSize).toInt()
            drawable.setBounds(left, top, left + iconSizePx, top + iconSizePx)
            drawable.setTint(0xFFFFFFFF.toInt())
            drawable.draw(canvas)
        }
    }
    
    private fun drawScaleRotateHandle(canvas: Canvas) {
        val handleSize = 48f
        val handleRadius = handleSize / 2f

        // 控制点中心位于右下角顶点
        val handleX = width.toFloat()
        val handleY = height.toFloat()
        
        // 绘制外圈白色边框（重用预分配 paint）
        canvas.drawCircle(handleX, handleY, handleRadius, borderPaint)
        
        // 绘制控制点背景（红色，与删除按钮保持一致）
        canvas.drawCircle(handleX, handleY, handleRadius - 2f, handlePaint)
        
        // 绘制缩放/旋转图标（使用drawable）
        try {
            val drawable = AppCompatResources.getDrawable(context, R.drawable.sticker_view_resize_12)
            drawable?.let {
                val density = resources.displayMetrics.density
                val intrinsic = if (it.intrinsicWidth > 0) it.intrinsicWidth else (12 * density).toInt()
                val iconSizePx = intrinsic.coerceAtMost((handleRadius * 1.2f).toInt())
                val halfSize = iconSizePx / 2f
                val left = (handleX - halfSize).toInt()
                val top = (handleY - halfSize).toInt()
                it.setBounds(left, top, left + iconSizePx, top + iconSizePx)
                it.setTint(0xFFFFFFFF.toInt()) // 白色图标
                it.draw(canvas)
            }
        } catch (e: Exception) {
            // 如果加载图标失败，绘制默认的箭头图标
            val iconRadius = handleSize * 0.3f
            val rect = android.graphics.RectF(
                handleX - iconRadius,
                handleY - iconRadius,
                handleX + iconRadius,
                handleY + iconRadius
            )
            canvas.drawArc(rect, -45f, 270f, false, iconStrokePaint)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
