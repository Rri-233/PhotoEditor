package com.jerry.photoeditor

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.jerry.photoeditor.ui.editor.EditorFragment

class MainActivity : AppCompatActivity() {

    // 拼图多选启动器（最多4张）
    private val pickMultipleImagesForCollage = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(4)
    ) { uris ->
        if (uris.isNotEmpty()) {
            if (uris.size < 2) {
                Toast.makeText(this, "拼图至少需要2张图片", Toast.LENGTH_SHORT).show()
            } else {
                showCollageModeDialog(uris)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val btnPickGallery: Button = findViewById(R.id.btn_home_pick_gallery)
        val btnTakePhoto: Button = findViewById(R.id.btn_home_take_photo)
        val btnCollage: Button = findViewById(R.id.btn_home_collage)

        btnPickGallery.setOnClickListener {
            openEditor(startMode = EditorFragment.START_MODE_GALLERY)
        }

        btnTakePhoto.setOnClickListener {
            openEditor(startMode = EditorFragment.START_MODE_CAMERA)
        }

        btnCollage.setOnClickListener {
            // 启动图片多选
            pickMultipleImagesForCollage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
    }

    private fun showCollageModeDialog(uris: List<Uri>) {
        val modes = arrayOf("横向拼接", "纵向拼接", "网格拼接")
        AlertDialog.Builder(this)
            .setTitle("选择拼接模式")
            .setItems(modes) { _, which ->
                val mode = when (which) {
                    0 -> "HORIZONTAL"
                    1 -> "VERTICAL"
                    else -> "GRID"
                }
                openEditorWithCollage(uris, mode)
            }
            .show()
    }

    private fun openEditorWithCollage(uris: List<Uri>, collageMode: String) {
        val fragment = EditorFragment.newInstanceWithCollage(uris, collageMode)
        supportFragmentManager.beginTransaction()
            .replace(R.id.main, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun openEditor(startMode: String) {
        val fragment = EditorFragment.newInstance(startMode)
        supportFragmentManager.beginTransaction()
            .replace(R.id.main, fragment)
            .addToBackStack(null)
            .commit()
    }
}