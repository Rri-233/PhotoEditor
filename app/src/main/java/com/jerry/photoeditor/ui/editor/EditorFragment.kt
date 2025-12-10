package com.jerry.photoeditor.ui.editor

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.snackbar.Snackbar
import com.jerry.photoeditor.R
import android.graphics.Rect
import android.graphics.RectF
import android.widget.TextView
import android.content.Context
import android.view.MotionEvent
import androidx.activity.addCallback

class EditorFragment : Fragment() {

    companion object {
        private const val ARG_START_MODE = "start_mode"
        private const val ARG_COLLAGE_URIS = "collage_uris"
        private const val ARG_COLLAGE_MODE = "collage_mode"
        const val START_MODE_GALLERY = "gallery"
        const val START_MODE_CAMERA = "camera"

        fun newInstance(startMode: String): EditorFragment {
            val f = EditorFragment()
            f.arguments = Bundle().apply {
                putString(ARG_START_MODE, startMode)
            }
            return f
        }

        fun newInstanceWithCollage(uris: List<Uri>, collageMode: String): EditorFragment {
            val f = EditorFragment()
            f.arguments = Bundle().apply {
                putParcelableArrayList(ARG_COLLAGE_URIS, ArrayList(uris))
                putString(ARG_COLLAGE_MODE, collageMode)
            }
            return f
        }
    }

    // 编辑模式枚举
    private enum class EditMode {
        NONE,       // 无编辑模式
        CROP,       // 裁剪模式
        ROTATE,     // 旋转模式
        FLIP,       // 翻转模式
        ADJUST,     // 亮度/对比度调节模式
        FILTER,     // 滤镜模式
        TEXT        // 文本编辑模式
    }

    private var currentEditMode: EditMode = EditMode.NONE

    private lateinit var viewModel: EditorViewModel

    // 视图组件
    private lateinit var canvasView: EditorCanvasView
    
    // 主模式顶部栏
    private lateinit var topBarMain: LinearLayout
    private lateinit var btnClose: ImageButton
    private lateinit var btnExport: ImageButton
    private lateinit var btnShare: ImageButton
    private lateinit var btnToggleTheme: ImageButton
    
    // 编辑模式顶部栏
    private lateinit var topBarEditMode: LinearLayout
    private lateinit var btnEditCancel: ImageButton
    private lateinit var btnEditConfirm: ImageButton
    private lateinit var tvEditModeTitle: TextView
    
    // 底部工具栏
    private lateinit var bottomTools: android.widget.HorizontalScrollView
    private lateinit var btnToolCrop: Button
    private lateinit var btnToolRotate: Button
    private lateinit var btnToolFlip: Button
    private lateinit var btnToolAdjust: Button
    private lateinit var btnToolFilter: Button
    private lateinit var btnToolText: Button
    
    // 文字图层
    private lateinit var textOverlayContainer: android.widget.FrameLayout
    private val textOverlays = mutableListOf<MovableTextView>()
    private var selectedTextOverlay: MovableTextView? = null
    
    // 各功能面板
    private lateinit var panelAdjust: LinearLayout
    private lateinit var panelCrop: LinearLayout
    private lateinit var panelRotate: LinearLayout
    private lateinit var panelFlip: LinearLayout
    private lateinit var panelFilter: LinearLayout
    
    // 调节控件
    private lateinit var seekBrightness: SeekBar
    private lateinit var seekContrast: SeekBar
    private lateinit var tvBrightnessValue: TextView
    private lateinit var tvContrastValue: TextView
    private lateinit var btnAdjustReset: Button

    private var cameraImageUri: Uri? = null

    private var currentBrightnessValue: Float = 0f // -100..100
    private var currentContrastValue: Float = 0f   // -50..150


    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.loadImageFromUri(uri)
        } else {
            Toast.makeText(requireContext(), "未选择图片", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickVisualMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.loadImageFromUri(uri)
        } else {
            Toast.makeText(requireContext(), "未选择图片", Toast.LENGTH_SHORT).show()
        }
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) {
            val uri = cameraImageUri
            if (uri != null) {
                viewModel.loadImageFromUri(uri)
            } else {
                Toast.makeText(requireContext(), "无法获取拍照结果", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(requireContext(), "拍照取消或失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPermissionDeniedSnackbar(message: String) {
        val root = view ?: return
        Snackbar.make(root, message, Snackbar.LENGTH_LONG)
            .setAction("开启") {
                openAppSettings()
            }
            .show()
    }

    private fun openAppSettings() {
        val context = context ?: return
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        startActivity(intent)
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCameraInternal()
        } else {
            showPermissionDeniedSnackbar("未授予相机权限")
        }
    }

    private val requestGalleryPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.any { it }
        if (granted) {
            launchGalleryPickerInternal()
        } else {
            showPermissionDeniedSnackbar("未授予访问相册权限")
        }
    }

    // ==================== 编辑模式管理 ====================
    
    private fun enterEditMode(mode: EditMode, title: String) {
        if (viewModel.bitmap.value == null) {
            Toast.makeText(requireContext(), "请先导入一张图片", Toast.LENGTH_SHORT).show()
            return
        }
        
        currentEditMode = mode
        
        // 切换顶部栏
        topBarMain.visibility = View.GONE
        topBarEditMode.visibility = View.VISIBLE
        tvEditModeTitle.text = title
        
        // 隐藏底部工具栏
        bottomTools?.visibility = View.GONE
        
        // 隐藏所有面板
        hideAllPanels()
        
        // 显示对应面板并初始化状态
        when (mode) {
            EditMode.CROP -> {
                panelCrop?.visibility = View.VISIBLE
                // 默认自由裁剪
                startCropMode(null)
            }
            EditMode.ROTATE -> {
                panelRotate?.visibility = View.VISIBLE
                viewModel.beginTransform()
            }
            EditMode.FLIP -> {
                panelFlip?.visibility = View.VISIBLE
                viewModel.beginTransform()
            }
            EditMode.ADJUST -> {
                panelAdjust?.visibility = View.VISIBLE
                viewModel.beginAdjust()
            }
            EditMode.FILTER -> {
                panelFilter?.visibility = View.VISIBLE
                // 保存原图用于滤镜预览
                viewModel.beginFilter()
                // 重置滤镜强度为100%
                view?.findViewById<SeekBar>(R.id.seek_filter_intensity)?.progress = 100
            }
            else -> {}
        }
    }
    
    private fun exitEditMode(apply: Boolean) {
        when (currentEditMode) {
            EditMode.CROP -> {
                if (apply) {
                    applyCrop()
                } else {
                    cancelCrop()
                }
            }
            EditMode.ADJUST -> {
                if (apply) {
                    // 确认调节，不需要额外操作
                } else {
                    // 取消调节，恢复原图
                    viewModel.cancelAdjust()
                }
            }
            EditMode.FILTER -> {
                if (apply) {
                    // 确认滤镜
                    viewModel.confirmFilter()
                } else {
                    // 取消滤镜，恢复原图
                    viewModel.cancelFilter()
                }
            }
            EditMode.ROTATE, EditMode.FLIP -> {
                if (apply) {
                    // 确认旋转/翻转操作
                    viewModel.confirmTransform()
                } else {
                    // 取消旋转/翻转，恢复原图
                    viewModel.cancelTransform()
                }
            }
            EditMode.TEXT -> {
                // 文字编辑通过 TextEditorFragment 完成，这里的 TEXT 模式不再使用
            }
            else -> {}
        }
        
        currentEditMode = EditMode.NONE
        
        // 切换回主模式
        topBarMain?.visibility = View.VISIBLE
        topBarEditMode?.visibility = View.GONE
        bottomTools?.visibility = View.VISIBLE
        
        // 隐藏所有面板
        hideAllPanels()
    }
    
    private fun hideAllPanels() {
        panelAdjust?.visibility = View.GONE
        panelCrop?.visibility = View.GONE
        panelRotate?.visibility = View.GONE
        panelFlip?.visibility = View.GONE
        panelFilter?.visibility = View.GONE
    }
    
    // 旧的文字交互开关（基于内联文字面板），当前已不再使用
    private fun enableTextInteraction(enable: Boolean) { }
    
    // ==================== 视图初始化 ====================
    
    private fun setupMainToolbar() {
        btnClose.setOnClickListener {
            handleBackWithConfirm()
        }
        
        btnExport.setOnClickListener {
            onSaveClicked()
        }
        
        btnShare.setOnClickListener {
            shareToDouyin()
        }
        
        // 初始化主题图标
        updateThemeIcon()
        
        btnToggleTheme.setOnClickListener {
            val current = AppCompatDelegate.getDefaultNightMode()
            val next = if (current == AppCompatDelegate.MODE_NIGHT_YES) {
                AppCompatDelegate.MODE_NIGHT_NO
            } else {
                AppCompatDelegate.MODE_NIGHT_YES
            }
            AppCompatDelegate.setDefaultNightMode(next)
            // 切换后更新图标
            updateThemeIcon()
        }
    }

    /**
     * 统一处理“返回/退出编辑”的逻辑：
     * - 如果没有图片，直接返回上一页面（popBackStack）
     * - 如果有图片，先弹出确认对话框，确认后再返回上一页面
     */
    private fun handleBackWithConfirm() {
        val hasImage = viewModel.bitmap.value != null
        if (!hasImage) {
            parentFragmentManager.popBackStack()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("退出编辑")
            .setMessage("当前编辑内容尚未保存，确定要退出吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("退出") { _, _ ->
                parentFragmentManager.popBackStack()
            }
            .show()
    }
    
    private fun setupEditModeToolbar() {
        btnEditCancel.setOnClickListener {
            if (currentEditMode == EditMode.NONE) {
                return@setOnClickListener
            }

            AlertDialog.Builder(requireContext())
                .setTitle("取消编辑")
                .setMessage("当前编辑内容尚未应用，确定要放弃本次编辑吗？")
                .setNegativeButton("继续编辑", null)
                .setPositiveButton("放弃") { _, _ ->
                    exitEditMode(apply = false)
                }
                .show()
        }
        
        btnEditConfirm.setOnClickListener {
            exitEditMode(apply = true)
        }
    }
    
    private fun setupBottomTools() {
        btnToolCrop.setOnClickListener {
            enterEditMode(EditMode.CROP, "裁剪")
        }
        
        btnToolRotate.setOnClickListener {
            enterEditMode(EditMode.ROTATE, "旋转")
        }
        
        btnToolFlip.setOnClickListener {
            enterEditMode(EditMode.FLIP, "翻转")
        }
        
        btnToolAdjust.setOnClickListener {
            enterEditMode(EditMode.ADJUST, "调节")
        }
        
        btnToolFilter.setOnClickListener {
            enterEditMode(EditMode.FILTER, "滤镜")
        }
        
        btnToolText.setOnClickListener {
            // 统一使用 TextEditorFragment 作为文字编辑入口
            openTextEditor()
        }
    }
    
    private fun setupAdjustPanel() {
        seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val brightness = (progress - 100).toFloat() // -100..100
                val contrast = (seekContrast.progress - 50).toFloat() // -50..150
                tvBrightnessValue.text = brightness.toInt().toString()
                viewModel.applyAdjust(brightness, contrast)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        seekContrast.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val brightness = (seekBrightness.progress - 100).toFloat()
                val contrast = (progress - 50).toFloat()
                tvContrastValue.text = contrast.toInt().toString()
                viewModel.applyAdjust(brightness, contrast)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        btnAdjustReset.setOnClickListener {
            seekBrightness.progress = 100
            seekContrast.progress = 50
            tvBrightnessValue.text = "0"
            tvContrastValue.text = "0"
            viewModel.applyAdjust(0f, 0f)
        }
    }
    
    private fun setupCropPanel() {
        val cropButtons = mapOf(
            R.id.btn_crop_free to null,
            R.id.btn_crop_1_1 to 1f,
            R.id.btn_crop_4_3 to (4f / 3f),
            R.id.btn_crop_16_9 to (16f / 9f),
            R.id.btn_crop_3_4 to (3f / 4f),
            R.id.btn_crop_9_16 to (9f / 16f)
        )
        
        for ((btnId, ratio) in cropButtons) {
            view?.findViewById<Button>(btnId)?.setOnClickListener {
                startCropMode(ratio)
            }
        }
    }
    
    private fun startCropMode(ratio: Float?) {
        val bmp = viewModel.bitmap.value ?: return
        canvasView.cropAspectRatio = ratio
        
        // 计算符合比例的最大裁剪框
        val rect = if (ratio == null) {
            // 自由裁剪：留10%边距
            val marginX = bmp.width * 0.1f
            val marginY = bmp.height * 0.1f
            RectF(marginX, marginY, bmp.width - marginX, bmp.height - marginY)
        } else {
            // 固定比例：计算最大裁剪框
            val imgWidth = bmp.width.toFloat()
            val imgHeight = bmp.height.toFloat()
            val imgRatio = imgWidth / imgHeight
            
            val (cropWidth, cropHeight) = if (imgRatio > ratio) {
                // 图片更宽，以高度为基准
                Pair(imgHeight * ratio, imgHeight)
            } else {
                // 图片更高或相等，以宽度为基准
                Pair(imgWidth, imgWidth / ratio)
            }
            
            // 居中裁剪框
            val left = (imgWidth - cropWidth) / 2f
            val top = (imgHeight - cropHeight) / 2f
            RectF(left, top, left + cropWidth, top + cropHeight)
        }
        
        canvasView.setCropRect(rect)
    }
    
    private fun applyCrop() {
        val imgRect = canvasView.getCropRectInImageCoords()
        if (imgRect != null) {
            val applyRect = Rect(
                imgRect.left.toInt(),
                imgRect.top.toInt(),
                imgRect.right.toInt(),
                imgRect.bottom.toInt()
            )
            viewModel.crop(applyRect)
        }
        canvasView.setCropRect(null)
    }
    
    private fun cancelCrop() {
        canvasView.setCropRect(null)
    }
    
    private fun setupRotatePanel() {
        view?.findViewById<Button>(R.id.btn_rotate_left)?.setOnClickListener {
            viewModel.rotate(-90f)
        }
        
        view?.findViewById<Button>(R.id.btn_rotate_right)?.setOnClickListener {
            viewModel.rotate(90f)
        }
        
        view?.findViewById<Button>(R.id.btn_rotate_180)?.setOnClickListener {
            viewModel.rotate(180f)
        }
    }
    
    private fun setupFlipPanel() {
        view?.findViewById<Button>(R.id.btn_flip_horizontal)?.setOnClickListener {
            viewModel.flipHorizontal()
        }
        
        view?.findViewById<Button>(R.id.btn_flip_vertical)?.setOnClickListener {
            viewModel.flipVertical()
        }
    }
    
    private fun setupFilterPanel() {
        val seekBarFilterIntensity = view?.findViewById<SeekBar>(R.id.seek_filter_intensity)
        val tvFilterIntensityValue = view?.findViewById<TextView>(R.id.tv_filter_intensity_value)
        
        var currentFilterType = EditorViewModel.FilterType.ORIGINAL
        
        val filters = mapOf(
            R.id.btn_filter_original to EditorViewModel.FilterType.ORIGINAL,
            R.id.btn_filter_bw to EditorViewModel.FilterType.BLACK_WHITE,
            R.id.btn_filter_retro to EditorViewModel.FilterType.RETRO,
            R.id.btn_filter_fresh to EditorViewModel.FilterType.FRESH,
            R.id.btn_filter_warm to EditorViewModel.FilterType.WARM,
            R.id.btn_filter_cool to EditorViewModel.FilterType.COOL
        )
        
        // 滤镜强度滑动条
        seekBarFilterIntensity?.max = 100
        seekBarFilterIntensity?.progress = 100
        tvFilterIntensityValue?.text = "100%"
        
        seekBarFilterIntensity?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val intensity = progress / 100f
                tvFilterIntensityValue?.text = "$progress%"
                viewModel.applyFilter(currentFilterType, intensity)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        
        for ((btnId, filterType) in filters) {
            view?.findViewById<Button>(btnId)?.setOnClickListener {
                currentFilterType = filterType
                val intensity = (seekBarFilterIntensity?.progress ?: 100) / 100f
                viewModel.applyFilter(filterType, intensity)
            }
        }
    }
    
    // 旧的内联文字样式状态（currentTextColor 等）和 setupTextPanel()/updateCurrentTextStyle()
    // 已废弃，文字样式统一在 TextEditorFragment 中处理。
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 系统返回键 / 手势返回：
        // - 如果当前在某个编辑模式（裁剪/旋转/翻转/调节/滤镜），等价于点击编辑模式顶部栏的“取消”按钮（带确认弹窗）
        // - 否则，行为与左上角关闭按钮一致（弹出“退出编辑”确认框）
        requireActivity().onBackPressedDispatcher.addCallback(this) {
            if (currentEditMode != EditMode.NONE) {
                // 等价于点击编辑模式顶部栏的“取消”按钮：弹出“取消编辑”对话框
                if (currentEditMode == EditMode.NONE) return@addCallback

                AlertDialog.Builder(requireContext())
                    .setTitle("取消编辑")
                    .setMessage("当前编辑内容尚未应用，确定要放弃本次编辑吗？")
                    .setNegativeButton("继续编辑", null)
                    .setPositiveButton("放弃") { _, _ ->
                        exitEditMode(apply = false)
                    }
                    .show()
            } else {
                handleBackWithConfirm()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_editor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this)[EditorViewModel::class.java]

        // 监听文本编辑结果
        setupTextEditorResultListener()

        // 初始化视图
        canvasView = view.findViewById(R.id.editor_canvas_view)
        
        // 主模式顶部栏
        topBarMain = view.findViewById(R.id.editor_top_bar)
        btnClose = view.findViewById(R.id.btn_close)
        btnExport = view.findViewById(R.id.btn_export)
        btnShare = view.findViewById(R.id.btn_share)
        btnToggleTheme = view.findViewById(R.id.btn_toggle_theme)
        
        // 编辑模式顶部栏
        topBarEditMode = view.findViewById(R.id.editor_top_bar_edit_mode)
        btnEditCancel = view.findViewById(R.id.btn_edit_cancel)
        btnEditConfirm = view.findViewById(R.id.btn_edit_confirm)
        tvEditModeTitle = view.findViewById(R.id.tv_edit_mode_title)
        
        // 底部工具栏
        bottomTools = view.findViewById(R.id.editor_bottom_tools)
        btnToolCrop = view.findViewById(R.id.btn_tool_crop)
        btnToolRotate = view.findViewById(R.id.btn_tool_rotate)
        btnToolFlip = view.findViewById(R.id.btn_tool_flip)
        btnToolAdjust = view.findViewById(R.id.btn_tool_adjust)
    btnToolFilter = view.findViewById(R.id.btn_tool_filter)
    btnToolText = view.findViewById(R.id.btn_tool_text)
        
        // 文字图层容器
        textOverlayContainer = view.findViewById(R.id.text_overlay_container)
        
        // 各功能面板
        panelAdjust = view.findViewById(R.id.panel_adjust)
        panelCrop = view.findViewById(R.id.panel_crop)
        panelRotate = view.findViewById(R.id.panel_rotate)
        panelFlip = view.findViewById(R.id.panel_flip)
        panelFilter = view.findViewById(R.id.panel_filter)
    // 旧的内联文字面板已废弃，仅保留变量以兼容 hideAllPanels
    // panelText = view.findViewById(R.id.panel_text)
        
        // 调节控件
        seekBrightness = view.findViewById(R.id.seek_brightness)
        seekContrast = view.findViewById(R.id.seek_contrast)
        tvBrightnessValue = view.findViewById(R.id.tv_brightness_value)
        tvContrastValue = view.findViewById(R.id.tv_contrast_value)
        btnAdjustReset = view.findViewById(R.id.btn_adjust_reset)

        setupMainToolbar()
        setupEditModeToolbar()
        setupBottomTools()
        setupAdjustPanel()
        setupCropPanel()
        setupRotatePanel()
        setupFlipPanel()
        setupFilterPanel()
    // 旧的内联文字 Panel 已废弃，保留 TextEditorFragment 作为唯一文字编辑界面
    // setupTextPanel()

        // 观察 bitmap 并刷新画布
        viewModel.bitmap.observe(viewLifecycleOwner) { bitmap ->
            if (bitmap == null) {
                Toast.makeText(requireContext(), "加载图片失败", Toast.LENGTH_SHORT).show()
            }
            canvasView.setBitmap(bitmap)
        }

        // 根据首页传入的模式，自动触发一次相册或相机
        val startMode = arguments?.getString(ARG_START_MODE)
        val collageUris = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arguments?.getParcelableArrayList(ARG_COLLAGE_URIS, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments?.getParcelableArrayList<Uri>(ARG_COLLAGE_URIS)
        }
        val collageMode = arguments?.getString(ARG_COLLAGE_MODE)
        
        if (!viewModel.initialActionHandled) {
            when {
                collageUris != null && collageMode != null -> {
                    // 拼图模式
                    val mode = when (collageMode) {
                        "HORIZONTAL" -> EditorViewModel.CollageMode.HORIZONTAL
                        "VERTICAL" -> EditorViewModel.CollageMode.VERTICAL
                        "GRID" -> EditorViewModel.CollageMode.GRID
                        else -> EditorViewModel.CollageMode.HORIZONTAL
                    }
                    viewModel.composeCollage(collageUris, mode) { success, error ->
                        requireActivity().runOnUiThread {
                            if (success) {
                                Toast.makeText(requireContext(), "拼图已生成", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(requireContext(), error ?: "拼图失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    viewModel.initialActionHandled = true
                }
                startMode == START_MODE_GALLERY -> {
                    viewModel.initialActionHandled = true
                    launchGalleryPicker()
                }
                startMode == START_MODE_CAMERA -> {
                    viewModel.initialActionHandled = true
                    launchCamera()
                }
                else -> {
                    viewModel.initialActionHandled = true
                }
            }
            
            // 处理完参数后立即清除，避免重复触发
            arguments?.remove(ARG_START_MODE)
            arguments?.remove(ARG_COLLAGE_URIS)
            arguments?.remove(ARG_COLLAGE_MODE)
        }
    }

    private fun onSaveClicked() {
        val hasImage = viewModel.bitmap.value != null
        if (!hasImage) {
            Toast.makeText(requireContext(), "当前没有可保存的图片", Toast.LENGTH_SHORT).show()
            return
        }
        // Using MediaStore on Android 10+ usually does not require WRITE permission,
        // so we directly call export and then inform user.
        val success = viewModel.exportToGallery(
            textOverlays = getTextOverlays(),
            containerWidth = textOverlayContainer.width,
            containerHeight = textOverlayContainer.height
        )
        if (success) {
            Toast.makeText(requireContext(), "保存成功，已存入相册", Toast.LENGTH_SHORT).show()
            // 保存成功后返回首页
            requireActivity().supportFragmentManager.popBackStack()
        } else {
            Toast.makeText(requireContext(), "保存失败，可能是存储空间不足或权限受限", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchGalleryPicker() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 使用系统 Photo Picker，不再需要存储权限
            pickVisualMediaLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } else {
            // Android 12 及以下继续走旧流程
            requestGalleryPermissionsLauncher.launch(
                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            )
        }
    }

    private fun launchGalleryPickerInternal() {
        pickImageLauncher.launch("image/*")
    }

    private fun launchCamera() {
        val ctx = context ?: return
        val permission = android.Manifest.permission.CAMERA
        requestCameraPermissionLauncher.launch(permission)
    }

    private fun launchCameraInternal() {
        val context = context ?: return
        val packageManager = context.packageManager
        val hasCamera = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
        if (!hasCamera) {
            Toast.makeText(context, "设备不支持相机", Toast.LENGTH_SHORT).show()
            return
        }

        val imagesDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
        if (imagesDir == null) {
            Toast.makeText(context, "无法访问存储目录", Toast.LENGTH_SHORT).show()
            return
        }
        val file = java.io.File(imagesDir, "captured_${System.currentTimeMillis()}.jpg")
        cameraImageUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        takePictureLauncher.launch(cameraImageUri)
    }

    private fun shareToDouyin() {
        val uri = viewModel.renderTempUriForShare(
            textOverlays = getTextOverlays(),
            containerWidth = textOverlayContainer.width,
            containerHeight = textOverlayContainer.height
        )
        if (uri == null) {
            Toast.makeText(requireContext(), "请先准备好可分享的图片", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            setPackage("com.ss.android.ugc.aweme")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // 抖音未安装时，尝试系统分享
            val chooser = Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "分享图片"
            )
            startActivity(chooser)
        }
    }
    
    // 更新主题图标
    private fun updateThemeIcon() {
        val currentNightMode = resources.configuration.uiMode and 
                              android.content.res.Configuration.UI_MODE_NIGHT_MASK
        
        when (currentNightMode) {
            android.content.res.Configuration.UI_MODE_NIGHT_YES -> {
                // 当前是夜间模式，显示太阳图标（表示点击后切换到日间）
                btnToggleTheme.setImageResource(R.drawable.ic_sun)
                btnToggleTheme.contentDescription = "切换到日间模式"
                // 设置图标颜色为白色（夜间模式下）
                btnToggleTheme.setColorFilter(android.graphics.Color.WHITE)
            }
            else -> {
                // 当前是日间模式，显示月亮图标（表示点击后切换到夜间）
                btnToggleTheme.setImageResource(R.drawable.ic_moon)
                btnToggleTheme.contentDescription = "切换到夜间模式"
                // 设置图标颜色为黑色（日间模式下）
                btnToggleTheme.setColorFilter(android.graphics.Color.BLACK)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 恢复时更新图标状态
        updateThemeIcon()
    }
    
    // ==================== 文字功能 ====================
    
    private fun showAddTextDialog(existingTextView: MovableTextView? = null) {
        val etTextInput = android.widget.EditText(requireContext()).apply {
            hint = "输入文字"
            setText(existingTextView?.text ?: "")
            setSingleLine(false)
            maxLines = 5
        }
        
        AlertDialog.Builder(requireContext())
            .setTitle(if (existingTextView == null) "添加文字" else "编辑文字")
            .setView(etTextInput)
            .setPositiveButton("确定") { _, _ ->
                val text = etTextInput.text.toString().trim()
                if (text.isNotEmpty()) {
                    if (existingTextView != null) {
                        // 更新现有文本 - 保持现有的TextOptions
                        val currentTypeface = existingTextView.typeface ?: android.graphics.Typeface.DEFAULT
                        val options = MovableTextView.TextOptions(
                            color = existingTextView.currentTextColor,
                            sizeSp = existingTextView.textSize / resources.displayMetrics.scaledDensity,
                            typeface = currentTypeface,
                            alphaPercent = existingTextView.alpha * 100
                        )
                        updateTextOverlay(existingTextView, text, options)
                    } else {
                        // 添加新文本 - 使用一个简单的默认样式
                        val defaultTypeface = android.graphics.Typeface.DEFAULT
                        val options = MovableTextView.TextOptions(
                            color = android.graphics.Color.WHITE,
                            sizeSp = 24f,
                            typeface = defaultTypeface,
                            alphaPercent = 100f
                        )
                        addTextOverlay(text, options)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
        
        // 旧的对话框代码注释掉
        /*
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_text, null)
        
        val etTextInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_text_input)
        val spinnerFont = dialogView.findViewById<android.widget.Spinner>(R.id.spinner_font)
        val seekFontSize = dialogView.findViewById<SeekBar>(R.id.seek_font_size)
        val tvFontSizeValue = dialogView.findViewById<TextView>(R.id.tv_font_size_value)
        val seekTextAlpha = dialogView.findViewById<SeekBar>(R.id.seek_text_alpha)
        val tvTextAlphaValue = dialogView.findViewById<TextView>(R.id.tv_text_alpha_value)
        val colorGrid = dialogView.findViewById<android.widget.GridLayout>(R.id.color_grid)
        
        // 字体选择
        val fontNames = arrayOf("无衬线", "衬线", "等宽")
        val fontAdapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, fontNames)
        fontAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFont.adapter = fontAdapter
        
        // 字号调节（12-36）
        seekFontSize.max = 24
        seekFontSize.progress = 12  // 默认24sp
        tvFontSizeValue.text = "24sp"
        
        seekFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val fontSize = progress + 12
                tvFontSizeValue.text = "${fontSize}sp"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // 透明度调节（50%-100%）
        seekTextAlpha.max = 50
        seekTextAlpha.progress = 50  // 默认100%
        tvTextAlphaValue.text = "100%"
        
        seekTextAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val alpha = progress + 50
                tvTextAlphaValue.text = "${alpha}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // 颜色选择（10种常用颜色）
        val colors = intArrayOf(
            android.graphics.Color.WHITE,
            android.graphics.Color.BLACK,
            android.graphics.Color.RED,
            android.graphics.Color.GREEN,
            android.graphics.Color.BLUE,
            android.graphics.Color.YELLOW,
            android.graphics.Color.CYAN,
            android.graphics.Color.MAGENTA,
            0xFFFF9800.toInt(), // 橙色
            0xFF9C27B0.toInt()  // 紫色
        )
        
        var selectedColor = android.graphics.Color.WHITE
        
        for (color in colors) {
            val colorButton = View(requireContext()).apply {
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = 80
                    height = 80
                    setMargins(8, 8, 8, 8)
                }
                setBackgroundColor(color)
                
                // 添加边框
                val drawable = android.graphics.drawable.GradientDrawable()
                drawable.setColor(color)
                drawable.setStroke(4, android.graphics.Color.GRAY)
                drawable.cornerRadius = 8f
                background = drawable
                
                setOnClickListener {
                    selectedColor = color
                    // 更新所有按钮的边框（选中的显示白色边框）
                    for (i in 0 until colorGrid.childCount) {
                        val child = colorGrid.getChildAt(i)
                        val childDrawable = child.background as? android.graphics.drawable.GradientDrawable
                        val childColor = colors.getOrNull(i) ?: android.graphics.Color.WHITE
                        childDrawable?.setStroke(4, if (childColor == selectedColor) android.graphics.Color.WHITE else android.graphics.Color.GRAY)
                    }
                }
            }
            colorGrid.addView(colorButton)
        }
        */
    }
    
    private fun addTextOverlay(text: String, options: MovableTextView.TextOptions) {
        val textView = MovableTextView(requireContext()).apply {
            setTextOptions(text, options)
            
            // 设置回调
            onOverlaySelected = { selected ->
                selectTextOverlay(selected)
            }
            
            onOverlayDoubleTapped = { selected ->
                showAddTextDialog(selected)
            }
            
            onOverlayDeleted = { deleted ->
                showDeleteConfirmDialog(deleted)
            }
        }
        
        textOverlayContainer.addView(textView)
        textOverlays.add(textView)
        
        // 先测量以获取实际大小
        textView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        
        // 居中显示，并初始化归一化坐标为中心点(0.5, 0.5)
        textView.post {
            val containerWidth = textOverlayContainer.width
            val containerHeight = textOverlayContainer.height
            val textWidth = textView.measuredWidth
            val textHeight = textView.measuredHeight
            
            // 设置图片显示区域（用于所见即所得）
            canvasView.getImageDisplayRect()?.let { rect ->
                textView.setImageDisplayFrame(rect.left, rect.top, rect.width(), rect.height())
            }
            
            textView.x = (containerWidth - textWidth) / 2f
            textView.y = (containerHeight - textHeight) / 2f
            
            // 初始化归一化坐标为中心点
            textView.normalizedX = 0.5f
            textView.normalizedY = 0.5f
            
            // 添加触摸监听，在用户移动文本后更新归一化坐标
            textView.setOnTouchListener { v, event ->
                val handled = v.onTouchEvent(event)
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    // 用户操作结束，更新归一化坐标
                    textView.updateNormalizedPosition(containerWidth, containerHeight)
                }
                handled
            }
        }
        
        selectTextOverlay(textView)
    }
    
    private fun updateTextOverlay(textView: MovableTextView, text: String, options: MovableTextView.TextOptions) {
        textView.setTextOptions(text, options)
        textView.requestLayout()
        textView.invalidate()
    }
    
    private fun selectTextOverlay(textView: MovableTextView) {
        // 取消其他文字的选中状态
        textOverlays.forEach { it.isTextSelected = false }
        
        // 选中当前文字
        textView.isTextSelected = true
        selectedTextOverlay = textView
    }
    
    private fun showDeleteConfirmDialog(textView: MovableTextView) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除文字")
            .setMessage("确定要删除这个文字吗？")
            .setPositiveButton("删除") { _, _ ->
                removeTextOverlay(textView)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun removeTextOverlay(textView: MovableTextView) {
        textOverlayContainer.removeView(textView)
        textOverlays.remove(textView)
        if (selectedTextOverlay == textView) {
            selectedTextOverlay = null
        }
    }
    
    fun getTextOverlays(): List<MovableTextView> {
        return textOverlays
    }

    // ==================== 文本编辑二级页面 ====================

    private fun setupTextEditorResultListener() {
        parentFragmentManager.setFragmentResultListener(
            TextEditorFragment.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            @Suppress("DEPRECATION")
            val resultBitmap = bundle.getParcelable<Bitmap>(TextEditorFragment.RESULT_BITMAP)
            resultBitmap?.let {
                // 更新图片 - 直接更新bitmap
                viewModel.updateBitmapDirectly(it)
                // 更新画布显示
                canvasView.invalidate()
            }
        }
    }

    private fun openTextEditor() {
        val currentBitmap = viewModel.bitmap.value ?: return

        // 创建TextEditorFragment实例并传递图片
        val fragment = TextEditorFragment.newInstance(currentBitmap)
        
        // 使用FragmentManager事务显示
        parentFragmentManager.beginTransaction()
            .replace(R.id.main, fragment)
            .addToBackStack(null)
            .commit()
    }
}
