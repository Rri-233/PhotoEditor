package com.jerry.photoeditor.ui.editor

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * ViewModel holding editor state. For now it only manages a single image bitmap
 * loaded from gallery or camera.
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val _bitmap = MutableLiveData<Bitmap?>()
    val bitmap: LiveData<Bitmap?> = _bitmap

    /**
     * Load an image bitmap from the given content [uri]. Decoding is done off the main thread
     * and the result is posted to [bitmap].
     */
    fun loadImageFromUri(uri: Uri) {
        Thread {
            val appContext = getApplication<Application>().applicationContext
            val loaded: Bitmap? = try {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    // For now decode directly; later we can add downscaling for performance/memory.
                    android.graphics.BitmapFactory.decodeStream(input)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
            _bitmap.postValue(loaded)
        }.start()
    }

    /**
     * Save the current bitmap into the device gallery using MediaStore.
     * Returns true on success, false on failure.
     */
    fun exportToGallery(): Boolean {
        val bmp = _bitmap.value ?: return false
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
}
