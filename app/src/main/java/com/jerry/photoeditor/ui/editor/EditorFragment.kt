package com.jerry.photoeditor.ui.editor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.snackbar.Snackbar
import com.jerry.photoeditor.R

class EditorFragment : Fragment() {

    private lateinit var viewModel: EditorViewModel

    private lateinit var canvasView: EditorCanvasView
    private lateinit var btnPickGallery: Button
    private lateinit var btnTakePhoto: Button
    private lateinit var btnSave: Button

    private var cameraImageUri: Uri? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
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

        canvasView = view.findViewById(R.id.editor_canvas_view)
        btnPickGallery = view.findViewById(R.id.btn_pick_gallery)
        btnTakePhoto = view.findViewById(R.id.btn_take_photo)
        btnSave = view.findViewById(R.id.btn_save)

        btnPickGallery.setOnClickListener { launchGalleryPicker() }
        btnTakePhoto.setOnClickListener { launchCamera() }
        btnSave.setOnClickListener { onSaveClicked() }

        // Observe bitmap from ViewModel and render it on the canvas
        viewModel.bitmap.observe(viewLifecycleOwner) { bitmap ->
            if (bitmap == null) {
                Toast.makeText(requireContext(), "加载图片失败", Toast.LENGTH_SHORT).show()
            }
            canvasView.setBitmap(bitmap)
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
        val success = viewModel.exportToGallery()
        if (success) {
            Toast.makeText(requireContext(), "保存成功，已存入相册", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "保存失败，可能是存储空间不足或权限受限", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchGalleryPicker() {
        // Check and request permission if necessary, then launch picker
        val ctx = context ?: return
        val permissions = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            permissions.add(android.Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestGalleryPermissionsLauncher.launch(permissions.toTypedArray())
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
}
