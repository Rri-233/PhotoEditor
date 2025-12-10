package com.jerry.photoeditor.ui.editor

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel holding editor state for bitmap transformations and exports.
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    enum class FilterType { ORIGINAL, BLACK_WHITE, RETRO, FRESH, WARM, COOL }
    enum class CollageMode { HORIZONTAL, VERTICAL, GRID }

    private val _bitmap = MutableLiveData<Bitmap?>()
    val bitmap: LiveData<Bitmap?> = _bitmap

    // 调节亮度/对比度时的基准图
    private var adjustBaseBitmap: Bitmap? = null
    
    // 滤镜基准图（原图），用于避免滤镜叠加
    private var filterBaseBitmap: Bitmap? = null
    private var currentFilterType: FilterType = FilterType.ORIGINAL
    
    // 旋转/翻转时的基准图（用于取消操作）
    private var transformBaseBitmap: Bitmap? = null

    // 防止 Fragment 重建时重复触发初始操作（相册/相机/拼图）
    var initialActionHandled: Boolean = false

    // 通用的图像更新方法，确保辅助缓存同步
    private fun commitBitmapChange(newBitmap: Bitmap, preserveTransformBaseline: Boolean) {
        _bitmap.value = newBitmap
        adjustBaseBitmap = null
        filterBaseBitmap = null
        lastBrightness = null
        lastContrast = null
        if (!preserveTransformBaseline) {
            transformBaseBitmap = null
        }
    }
    
    fun loadImageFromUri(uri: Uri) {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                decodeUri(uri)
            }
            loaded?.let { commitBitmapChange(it, preserveTransformBaseline = false) }
        }
    }

    private fun decodeUri(uri: Uri): Bitmap? {
        val appContext = getApplication<Application>().applicationContext
        return try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                // 首先获取图片尺寸
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(input, null, options)
                input.close()
                
                // 计算采样率以优化内存使用
                val maxSize = 2048 // 最大尺寸限制
                var inSampleSize = 1
                if (options.outHeight > maxSize || options.outWidth > maxSize) {
                    val halfHeight = options.outHeight / 2
                    val halfWidth = options.outWidth / 2
                    while (halfHeight / inSampleSize >= maxSize && halfWidth / inSampleSize >= maxSize) {
                        inSampleSize *= 2
                    }
                }
                
                // 使用采样率重新解码
                appContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BitmapFactory.Options().apply {
                        this.inSampleSize = inSampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }.let { newOptions ->
                        BitmapFactory.decodeStream(inputStream, null, newOptions)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 开始旋转/翻转操作，保存当前图片作为基准
     */
    fun beginTransform() {
        transformBaseBitmap = _bitmap.value
    }
    
    /**
     * 取消旋转/翻转，恢复到基准图
     */
    fun cancelTransform() {
        transformBaseBitmap?.let {
            _bitmap.value = it
        }
        transformBaseBitmap = null
    }
    
    /**
     * 确认旋转/翻转，清除基准图
     */
    fun confirmTransform() {
        transformBaseBitmap = null
    }
    
    /**
     * 直接更新bitmap（用于文本编辑等操作）
     */
    fun updateBitmapDirectly(newBitmap: Bitmap) {
        commitBitmapChange(newBitmap, preserveTransformBaseline = false)
    }
    
    /**
     * Rotate current bitmap by [degrees] (90, -90, 180).
     */
    fun rotate(degrees: Float) {
        val src = _bitmap.value ?: return
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = try {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } ?: return
        commitBitmapChange(rotated, preserveTransformBaseline = true)
    }

    fun flipHorizontal() {
        val src = _bitmap.value ?: return
        val matrix = Matrix().apply {
            postScale(-1f, 1f, src.width / 2f, src.height / 2f)
        }
        val flipped = try {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } ?: return
        commitBitmapChange(flipped, preserveTransformBaseline = true)
    }

    fun flipVertical() {
        val src = _bitmap.value ?: return
        val matrix = Matrix().apply {
            postScale(1f, -1f, src.width / 2f, src.height / 2f)
        }
        val flipped = try {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } ?: return
        commitBitmapChange(flipped, preserveTransformBaseline = true)
    }

    /**
     * 裁剪当前 bitmap 到给定的图片坐标矩形 [cropRect]。
     */
    fun crop(cropRect: Rect) {
        val src = _bitmap.value ?: return
        val safeRect = Rect(
            cropRect.left.coerceIn(0, src.width),
            cropRect.top.coerceIn(0, src.height),
            cropRect.right.coerceIn(0, src.width),
            cropRect.bottom.coerceIn(0, src.height)
        )
        if (safeRect.width() <= 0 || safeRect.height() <= 0) return
        val cropped = try {
            Bitmap.createBitmap(src, safeRect.left, safeRect.top, safeRect.width(), safeRect.height())
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } ?: return
        commitBitmapChange(cropped, preserveTransformBaseline = false)
    }

    fun beginAdjust() {
        adjustBaseBitmap = _bitmap.value?.copy(Bitmap.Config.ARGB_8888, true)
    }

    // 防抖机制：缓存上次的参数
    private var lastBrightness: Float? = null
    private var lastContrast: Float? = null
    
    fun applyAdjust(brightnessValue: Float, contrastValue: Float) {
        // 优化：避免相同参数重复计算
        if (brightnessValue == lastBrightness && contrastValue == lastContrast) {
            return
        }
        lastBrightness = brightnessValue
        lastContrast = contrastValue
        
        val src = adjustBaseBitmap ?: _bitmap.value ?: return
        
        // 使用协程异步处理，避免阻塞 UI
        viewModelScope.launch(Dispatchers.Default) {
            // brightness: -100..100, contrastValue: -50..150 (map to factor 0.5..2.5)
            val br = brightnessValue.coerceIn(-100f, 100f)
            val ctValue = contrastValue.coerceIn(-50f, 150f)
            val contrastFactor = 1f + ctValue / 100f

            val cm = ColorMatrix()
            // Contrast
            val translate = 128f * (1 - contrastFactor)
            val contrastMatrix = ColorMatrix(
                floatArrayOf(
                    contrastFactor, 0f, 0f, 0f, translate,
                    0f, contrastFactor, 0f, 0f, translate,
                    0f, 0f, contrastFactor, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(contrastMatrix)
            // Brightness
            val b = br * 255f / 100f
            val brightMatrix = ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, b,
                    0f, 1f, 0f, 0f, b,
                    0f, 0f, 1f, 0f, b,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(brightMatrix)

            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint().apply {
                colorFilter = ColorMatrixColorFilter(cm)
            }
            canvas.drawBitmap(src, 0f, 0f, paint)
            
            // 切换回主线程更新 UI
            withContext(Dispatchers.Main) {
                _bitmap.value = out
            }
        }
    }

    fun cancelAdjust() {
        adjustBaseBitmap?.let { _bitmap.value = it }
        adjustBaseBitmap = null
        lastBrightness = null
        lastContrast = null
    }

    // 开始滤镜模式，保存原图
    fun beginFilter() {
        if (filterBaseBitmap == null) {
            filterBaseBitmap = _bitmap.value?.copy(Bitmap.Config.ARGB_8888, true)
        }
    }

    // 应用滤镜（基于原图，避免叠加）
    fun applyFilter(type: FilterType, intensity: Float = 1.0f) {
        currentFilterType = type
        val src = filterBaseBitmap ?: _bitmap.value ?: return
        val cm = when (type) {
            FilterType.ORIGINAL -> null
            FilterType.BLACK_WHITE -> ColorMatrix().apply { setSaturation(0f) }
            FilterType.RETRO -> ColorMatrix(
                floatArrayOf(
                    1.2f, -0.1f, 0f, 0f, 0f,
                    -0.05f, 1.1f, 0f, 0f, 10f,
                    0f, 0f, 0.9f, 0f, -10f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            FilterType.FRESH -> ColorMatrix().apply {
                setSaturation(1.2f)
                postConcat(ColorMatrix(floatArrayOf(
                    1.05f, 0f, 0f, 0f, 5f,
                    0f, 1.05f, 0f, 0f, 5f,
                    0f, 0f, 1.05f, 0f, 5f,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            FilterType.WARM -> ColorMatrix(
                floatArrayOf(
                    1.1f, 0f, 0f, 0f, 10f,
                    0f, 1.0f, 0f, 0f, 0f,
                    0f, 0f, 0.9f, 0f, -10f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            FilterType.COOL -> ColorMatrix(
                floatArrayOf(
                    0.9f, 0f, 0f, 0f, -10f,
                    0f, 1.0f, 0f, 0f, 0f,
                    0f, 0f, 1.1f, 0f, 10f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        }
        
        // 使用协程异步处理
        viewModelScope.launch(Dispatchers.Default) {
            val out = if (cm == null) {
                // 原图：直接复制
                src.copy(Bitmap.Config.ARGB_8888, true)
            } else {
                // 应用滤镜，支持强度调节
                val adjustedCm = if (intensity < 1.0f) {
                    // 混合原图和滤镜效果
                    val identityMatrix = ColorMatrix()
                    val blendMatrix = ColorMatrix()
                    blendMatrix.set(cm)
                    // 简单线性插值
                    val array = blendMatrix.array
                    val identityArray = identityMatrix.array
                    for (i in array.indices) {
                        array[i] = identityArray[i] * (1 - intensity) + array[i] * intensity
                    }
                    blendMatrix
                } else {
                    cm
                }
                
                val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(result)
                val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(adjustedCm) }
                canvas.drawBitmap(src, 0f, 0f, paint)
                result
            }
            
            withContext(Dispatchers.Main) {
                _bitmap.value = out
            }
        }
    }
    
    // 确认滤镜，清除基准图
    fun confirmFilter() {
        filterBaseBitmap = null
    }
    
    // 取消滤镜，恢复原图
    fun cancelFilter() {
        filterBaseBitmap?.let { _bitmap.value = it }
        filterBaseBitmap = null
        currentFilterType = FilterType.ORIGINAL
    }

    /**
     * Save the current bitmap (包含叠加元素) into the device gallery using MediaStore.
     */
    fun exportToGallery(
        textOverlays: List<MovableTextView> = emptyList(), 
        containerWidth: Int = 0, 
        containerHeight: Int = 0
    ): Boolean {
        val bmp = renderBitmapWithOverlays(textOverlays, containerWidth, containerHeight) ?: return false
        return try {
            val appContext = getApplication<Application>().applicationContext
            val resolver = appContext.contentResolver
            val fileName = "PhotoEditor_${System.currentTimeMillis()}.jpg"
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PhotoEditor")
            }

            val uri: Uri? = resolver.insert(collection, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { outStream ->
                    val success = bmp.compress(Bitmap.CompressFormat.JPEG, 95, outStream)
                    if (!success) return false
                } ?: return false
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun renderBitmapWithOverlays(
        textOverlays: List<MovableTextView> = emptyList(), 
        containerWidth: Int = 0, 
        containerHeight: Int = 0
    ): Bitmap? {
        val base = _bitmap.value ?: return null
        
        // 如果没有文字图层，直接返回原图
        if (textOverlays.isEmpty()) {
            return base
        }
        
        // 创建输出bitmap
        val output = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        
        // 1. 绘制底图
        canvas.drawBitmap(base, 0f, 0f, null)
        
        // 2. 渲染所有文字图层（使用新的renderToCanvas方法，直接使用归一化坐标）
        for (textView in textOverlays) {
            textView.renderToCanvas(canvas, base.width, base.height)
        }
        
        return output
    }

    fun composeCollage(
        uris: List<Uri>,
        mode: CollageMode,
        onResult: (Boolean, String?) -> Unit
    ) {
        if (uris.isEmpty()) {
            onResult(false, "未选择图片")
            return
        }

        viewModelScope.launch {
            val (collage, error) = withContext(Dispatchers.IO) {
                try {
                    val bitmaps = uris.mapNotNull { decodeUri(it) }
                    if (bitmaps.size != uris.size) {
                        bitmaps.forEach { if (!it.isRecycled) it.recycle() }
                        return@withContext Pair(null, "部分图片加载失败")
                    }
                    val generated = when (mode) {
                        CollageMode.HORIZONTAL -> buildHorizontalCollage(bitmaps)
                        CollageMode.VERTICAL -> buildVerticalCollage(bitmaps)
                        CollageMode.GRID -> buildGridCollage(bitmaps)
                    }
                    bitmaps.forEach { if (!it.isRecycled) it.recycle() }
                    if (generated != null) {
                        Pair(generated, null)
                    } else {
                        Pair(null, "拼接失败")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Pair(null, "拼接过程出错: ${e.message}")
                }
            }

            if (collage != null) {
                commitBitmapChange(collage, preserveTransformBaseline = false)
                currentFilterType = FilterType.ORIGINAL
                onResult(true, null)
            } else {
                onResult(false, error)
            }
        }
    }

    private fun buildHorizontalCollage(bitmaps: List<Bitmap>): Bitmap? {
        val targetHeight = bitmaps.minOfOrNull { it.height } ?: return null
        var totalWidth = 0
        val scaled = bitmaps.map { bm ->
            val scaledBm = Bitmap.createScaledBitmap(
                bm,
                (bm.width * (targetHeight / bm.height.toFloat())).toInt(),
                targetHeight,
                true
            )
            totalWidth += scaledBm.width
            scaledBm
        }
        val out = Bitmap.createBitmap(totalWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        var offsetX = 0
        scaled.forEach { bm ->
            canvas.drawBitmap(bm, offsetX.toFloat(), 0f, null)
            offsetX += bm.width
        }
        return out
    }

    private fun buildVerticalCollage(bitmaps: List<Bitmap>): Bitmap? {
        val targetWidth = bitmaps.minOfOrNull { it.width } ?: return null
        var totalHeight = 0
        val scaled = bitmaps.map { bm ->
            val scaledBm = Bitmap.createScaledBitmap(
                bm,
                targetWidth,
                (bm.height * (targetWidth / bm.width.toFloat())).toInt(),
                true
            )
            totalHeight += scaledBm.height
            scaledBm
        }
        val out = Bitmap.createBitmap(targetWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        var offsetY = 0
        scaled.forEach { bm ->
            canvas.drawBitmap(bm, 0f, offsetY.toFloat(), null)
            offsetY += bm.height
        }
        return out
    }

    private fun buildGridCollage(bitmaps: List<Bitmap>): Bitmap? {
        val cellSize = bitmaps.minOfOrNull { minOf(it.width, it.height) } ?: return null
        val out = Bitmap.createBitmap(cellSize * 2, cellSize * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        bitmaps.take(4).forEachIndexed { index, bm ->
            val scaled = Bitmap.createScaledBitmap(bm, cellSize, cellSize, true)
            val col = index % 2
            val row = index / 2
            canvas.drawBitmap(scaled, (col * cellSize).toFloat(), (row * cellSize).toFloat(), null)
        }
        return out
    }

    fun renderTempUriForShare(
        textOverlays: List<MovableTextView> = emptyList(), 
        containerWidth: Int = 0, 
        containerHeight: Int = 0
    ): Uri? {
        val context = getApplication<Application>().applicationContext
        val bmp = renderBitmapWithOverlays(textOverlays, containerWidth, containerHeight) ?: return null
        return try {
            val cacheDir = context.cacheDir
            val outFile = java.io.File(cacheDir, "share_${System.currentTimeMillis()}.jpg")
            java.io.FileOutputStream(outFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            androidx.core.content.FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                outFile
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
