package com.jerry.photoeditor.ui.editor

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.addCallback
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import com.jerry.photoeditor.R

/**
 * 文本编辑Fragment - 二级页面
 * 功能：添加和编辑文本，完成后返回合成的图片
 */
class TextEditorFragment : Fragment() {

    private lateinit var toolbar: LinearLayout
    private lateinit var btnCancel: ImageButton
    private lateinit var btnConfirm: ImageButton
    private lateinit var imagePreview: ImageView
    private lateinit var textStickerContainer: FrameLayout
    private lateinit var btnAddText: Button
    private lateinit var textStylePanel: LinearLayout
    private lateinit var spinnerFont: Spinner
    private lateinit var colorContainer: LinearLayout
    private lateinit var seekTextSize: SeekBar
    private lateinit var tvTextSize: TextView

    private var currentBitmap: Bitmap? = null
    private val textViews = mutableListOf<MovableTextView>()
    private var selectedTextView: MovableTextView? = null

    // 颜色列表
    private val colors = listOf(
        Color.BLACK, Color.WHITE, Color.RED, Color.GREEN, 
        Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA,
        Color.parseColor("#FF5722"), Color.parseColor("#9C27B0")
    )

    // 字体列表
    private val fontNames = arrayOf("默认", "黑体加粗", "斜体", "粗斜体", "衬线体", "等宽体")
    private val fontTypefaces = arrayOf(
        Typeface.DEFAULT,
        Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD),
        Typeface.create(Typeface.DEFAULT, Typeface.ITALIC),
        Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC),
        Typeface.SERIF,
        Typeface.MONOSPACE
    )

    companion object {
        const val ARG_BITMAP = "bitmap"
        const val RESULT_KEY = "text_editor_result"
        const val RESULT_BITMAP = "result_bitmap"
        
        fun newInstance(bitmap: Bitmap): TextEditorFragment {
            return TextEditorFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_BITMAP, bitmap)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 系统返回键 / 手势返回：行为与顶部“取消”按钮完全一致
        requireActivity().onBackPressedDispatcher.addCallback(this) {
            handleCancelWithConfirm()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_text_editor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupToolbar()
        setupBottomPanel()

        // 从参数获取图片
        @Suppress("DEPRECATION")
        val bitmap = arguments?.getParcelable<Bitmap>(ARG_BITMAP)
        bitmap?.let {
            currentBitmap = it
            imagePreview.setImageBitmap(it)
        }

        // 监听尺寸变化，实时刷新文本归一化坐标的映射
        textStickerContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateTextDisplayFrames()
            restorePositionsFromNormalized()
        }
    }

    private fun initViews(view: View) {
    toolbar = view.findViewById(R.id.toolbar)
    btnCancel = view.findViewById(R.id.btn_text_edit_cancel)
    btnConfirm = view.findViewById(R.id.btn_text_edit_confirm)
        imagePreview = view.findViewById(R.id.image_preview)
        textStickerContainer = view.findViewById(R.id.text_sticker_container)
        btnAddText = view.findViewById(R.id.btn_add_text)
        textStylePanel = view.findViewById(R.id.text_style_panel)
        spinnerFont = view.findViewById(R.id.spinner_font)
        colorContainer = view.findViewById(R.id.color_container)
        seekTextSize = view.findViewById(R.id.seek_text_size)
        tvTextSize = view.findViewById(R.id.tv_text_size)
    }

    private fun setupToolbar() {
        // 左侧取消按钮 - 弹出确认对话框
        btnCancel.setOnClickListener {
            handleCancelWithConfirm()
        }

        // 右侧完成按钮 - 保存并返回
        btnConfirm.setOnClickListener {
            finishEditing()
        }
    }

    /**
     * 统一处理“取消文字编辑”的逻辑：
     * 顶部左侧取消按钮 + 系统返回键，都走这里。
     */
    private fun handleCancelWithConfirm() {
        AlertDialog.Builder(requireContext())
            .setTitle("取消文字编辑")
            .setMessage("当前添加或修改的文字尚未应用，确定要放弃本次编辑吗？")
            .setNegativeButton("继续编辑", null)
            .setPositiveButton("放弃") { _, _ ->
                parentFragmentManager.popBackStack()
            }
            .show()
    }

    private fun setupBottomPanel() {
        // 添加文本按钮
        btnAddText.setOnClickListener {
            showAddTextDialog()
        }

        // 字体选择器
        val fontAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, fontNames)
        fontAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFont.adapter = fontAdapter
        spinnerFont.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedTextView?.let { textView ->
                    val options = MovableTextView.TextOptions(
                        color = textView.currentTextColor,
                        sizeSp = seekTextSize.progress + 12f,
                        typeface = fontTypefaces[position],
                        alphaPercent = 100f
                    )
                    textView.setTextOptions(textView.text.toString(), options)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 颜色选择器
        setupColorPicker()

        // 文本大小滑动条
        seekTextSize.max = 24
        seekTextSize.progress = 12
        tvTextSize.text = "${seekTextSize.progress + 12}sp"
        
        seekTextSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val size = progress + 12f
                tvTextSize.text = "${size.toInt()}sp"
                if (fromUser) {
                    selectedTextView?.let { textView ->
                        val options = MovableTextView.TextOptions(
                            color = textView.currentTextColor,
                            sizeSp = size,
                            typeface = textView.typeface,
                            alphaPercent = 100f
                        )
                        textView.setTextOptions(textView.text.toString(), options)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun setupColorPicker() {
        colors.forEach { color ->
            val colorView = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(40.dpToPx(), 40.dpToPx()).apply {
                    marginEnd = 8.dpToPx()
                }
                setBackgroundColor(color)
                setPadding(2.dpToPx(), 2.dpToPx(), 2.dpToPx(), 2.dpToPx())
                setOnClickListener {
                    selectedTextView?.let { textView ->
                        val options = MovableTextView.TextOptions(
                            color = color,
                            sizeSp = seekTextSize.progress + 12f,
                            typeface = textView.typeface,
                            alphaPercent = 100f
                        )
                        textView.setTextOptions(textView.text.toString(), options)
                    }
                }
            }
            colorContainer.addView(colorView)
        }
    }

    private fun showAddTextDialog() {
        val editText = EditText(requireContext()).apply {
            hint = "输入文本"
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
        }

        AlertDialog.Builder(requireContext())
            .setTitle("添加文本")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val text = editText.text.toString()
                if (text.isNotBlank()) {
                    addTextView(text)
                }
            }
            .setNegativeButton("取消", null)
            .show()

        // 自动弹出键盘
        editText.requestFocus()
        editText.post {
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun addTextView(text: String) {
        val textView = MovableTextView(requireContext()).apply {
            val options = MovableTextView.TextOptions(
                color = colors[0],
                sizeSp = 24f,
                typeface = Typeface.DEFAULT,
                alphaPercent = 100f
            )
            setTextOptions(text, options)

            // 设置回调
            onOverlaySelected = { selected ->
                selectTextView(selected)
            }

            onOverlayDoubleTapped = { selected ->
                showEditTextDialog(selected)
            }

            onOverlayDeleted = { deleted ->
                removeTextView(deleted)
            }

            // 初始化归一化坐标为中心
            normalizedX = 0.5f
            normalizedY = 0.5f
        }

        textStickerContainer.addView(textView)
        textViews.add(textView)

        // 测量并居中显示
        textView.post {
            textView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )

            updateDisplayFrameFor(textView)
            textView.setPositionFromNormalized(textStickerContainer.width, textStickerContainer.height)

            // 添加触摸监听
            textView.setOnTouchListener { v, event ->
                val handled = v.onTouchEvent(event)
                if (event.action == android.view.MotionEvent.ACTION_UP || 
                    event.action == android.view.MotionEvent.ACTION_CANCEL) {
                    textView.updateNormalizedPosition(textStickerContainer.width, textStickerContainer.height)
                }
                handled
            }

            selectTextView(textView)
        }
    }

    private fun selectTextView(textView: MovableTextView) {
        textViews.forEach { it.isTextSelected = false }
        textView.isTextSelected = true
        selectedTextView = textView

        // 显示样式面板
        textStylePanel.visibility = View.VISIBLE
    }

    private fun showEditTextDialog(textView: MovableTextView) {
        val editText = EditText(requireContext()).apply {
            setText(textView.text)
            hint = "编辑文本"
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
            selectAll()
        }

        AlertDialog.Builder(requireContext())
            .setTitle("编辑文本")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val text = editText.text.toString()
                if (text.isNotBlank()) {
                    val options = MovableTextView.TextOptions(
                        color = textView.currentTextColor,
                        sizeSp = seekTextSize.progress + 12f,
                        typeface = textView.typeface,
                        alphaPercent = 100f
                    )
                    textView.setTextOptions(text, options)
                }
            }
            .setNegativeButton("取消", null)
            .show()

        editText.requestFocus()
        editText.post {
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun removeTextView(textView: MovableTextView) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除文本")
            .setMessage("确定要删除这个文本吗？")
            .setPositiveButton("删除") { _, _ ->
                textStickerContainer.removeView(textView)
                textViews.remove(textView)
                if (selectedTextView == textView) {
                    selectedTextView = null
                    textStylePanel.visibility = View.GONE
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun finishEditing() {
        val bitmap = currentBitmap ?: return

        // 渲染所有文本到图片
        val resultBitmap = renderTextToBitmap(bitmap)

        // 返回结果
        setFragmentResult(RESULT_KEY, bundleOf(RESULT_BITMAP to resultBitmap))
        parentFragmentManager.popBackStack()
    }

    private fun renderTextToBitmap(baseBitmap: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(baseBitmap.width, baseBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)

        // 1. 绘制底图
        canvas.drawBitmap(baseBitmap, 0f, 0f, null)

        // 2. 绘制所有文本
        updateTextDisplayFrames()
        textViews.forEach { textView ->
            textView.renderToCanvas(canvas, baseBitmap.width, baseBitmap.height)
        }

        return result
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    /**
     * 根据 ImageView 的 matrix 计算图片实际显示区域（fitCenter/centerCrop 等都能适配）
     */
    private fun calculateImageDisplayRect(): android.graphics.RectF? {
        val drawable = imagePreview.drawable ?: return null
        val matrix = imagePreview.imageMatrix
        val bounds = android.graphics.RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        matrix.mapRect(bounds)
        // matrix.mapRect 以 view 左上为原点，需要加上 imageView 的内边距
        bounds.offset(imagePreview.paddingLeft.toFloat(), imagePreview.paddingTop.toFloat())
        return bounds
    }

    private fun updateDisplayFrameFor(textView: MovableTextView) {
        calculateImageDisplayRect()?.let { rect ->
            textView.setImageDisplayFrame(rect.left, rect.top, rect.width(), rect.height())
        }
    }

    private fun updateTextDisplayFrames() {
        textViews.forEach { updateDisplayFrameFor(it) }
    }

    private fun restorePositionsFromNormalized() {
        textViews.forEach {
            it.setPositionFromNormalized(textStickerContainer.width, textStickerContainer.height)
        }
    }
}
