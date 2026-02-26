package com.yourname.expensetracker.domain.performance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cacheDir = context.cacheDir.resolve("image_cache")
    
    init {
        if (!cacheDir.exists()) cacheDir.mkdirs()
    }
    
    suspend fun getOrLoadBitmap(
        uri: Uri,
        maxWidth: Int = 256,
        maxHeight: Int = 256
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = uri.toString().hashCode().toString()
        val cachedFile = cacheDir.resolve(cacheKey)
        
        if (cachedFile.exists()) {
            try {
                BitmapFactory.decodeFile(cachedFile.absolutePath)?.let { return@withContext it }
            } catch (e: Exception) {
                cachedFile.delete()
            }
        }
        
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(input, null, options)
                
                options.inSampleSize = calculateInSampleSize(options, maxWidth, maxHeight)
                options.inJustDecodeBounds = false
                
                context.contentResolver.openInputStream(uri)?.use { input2 ->
                    val bitmap = BitmapFactory.decodeStream(input2, null, options)
                    bitmap?.let {
                        saveToCache(it, cacheKey)
                    }
                    bitmap
                }
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            
            while ((halfHeight / inSampleSize) >= reqHeight &&
                   (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
    
    private fun saveToCache(bitmap: Bitmap, key: String) {
        try {
            val file = cacheDir.resolve(key)
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out)
            }
        } catch (e: Exception) {
            // Ignore cache write failures
        }
    }
    
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }
    
    fun getCacheSize(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }
}
