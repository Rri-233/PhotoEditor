package com.jerry.photoeditor.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
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
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            translationX -= distanceX
            translationY -= distanceY
            invalidate()
            return true
        }
    })

    fun setBitmap(bm: Bitmap?) {
        bitmap = bm
        computeBaseScale()
        invalidate()
    }

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
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        var handled = scaleGestureDetector.onTouchEvent(event)
        if (!scaleGestureDetector.isInProgress) {
            handled = gestureDetector.onTouchEvent(event) || handled
        }
        if (handled && event.action == MotionEvent.ACTION_UP) {
            performClick()
        }
        return handled || super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
