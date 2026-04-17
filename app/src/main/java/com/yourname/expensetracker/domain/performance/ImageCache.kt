package com.yourname.expensetracker.domain.performance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private data class CacheEntry(
        val file: File,
        var sizeBytes: Long
    )

    private companion object {
        private const val MAX_CACHE_SIZE_BYTES = 50L * 1024L * 1024L
    }

    private val cacheDir = context.cacheDir.resolve("image_cache")
    private val cacheLock = Any()
    private val cacheEntries = LinkedHashMap<String, CacheEntry>(16, 0.75f, true)
    private var currentCacheSizeBytes = 0L
    
    init {
        if (!cacheDir.exists()) cacheDir.mkdirs()
        rebuildIndex()
    }
    
    suspend fun getOrLoadBitmap(
        uri: Uri,
        maxWidth: Int = 256,
        maxHeight: Int = 256
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = buildCacheKey(uri, maxWidth, maxHeight)
        val cachedFile = cacheDir.resolve(cacheKey)
        
        if (cachedFile.exists()) {
            try {
                BitmapFactory.decodeFile(cachedFile.absolutePath)?.let {
                    touchCacheEntry(cacheKey, cachedFile)
                    return@withContext it
                }
            } catch (e: Exception) {
                cachedFile.delete()
                removeCacheEntry(cacheKey)
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

    private fun buildCacheKey(uri: Uri, maxWidth: Int, maxHeight: Int): String {
        return "${uri}_${maxWidth}x${maxHeight}".hashCode().toString()
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
            updateCacheEntry(key, file)
        } catch (e: Exception) {
            // Ignore cache write failures
        }
    }
    
    fun clearCache() {
        synchronized(cacheLock) {
            cacheDir.listFiles()?.forEach { it.delete() }
            cacheEntries.clear()
            currentCacheSizeBytes = 0L
        }
    }
    
    fun getCacheSize(): Long {
        return synchronized(cacheLock) { currentCacheSizeBytes }
    }

    private fun rebuildIndex() {
        synchronized(cacheLock) {
            cacheEntries.clear()
            currentCacheSizeBytes = 0L
            cacheDir.listFiles()?.sortedBy { it.lastModified() }?.forEach { file ->
                val size = file.length()
                cacheEntries[file.name] = CacheEntry(file, size)
                currentCacheSizeBytes += size
            }
            evictIfNeededLocked()
        }
    }

    private fun touchCacheEntry(key: String, file: File) {
        synchronized(cacheLock) {
            val existing = cacheEntries[key]
            if (existing != null) {
                cacheEntries[key] = existing
            } else if (file.exists()) {
                cacheEntries[key] = CacheEntry(file, file.length())
                currentCacheSizeBytes += file.length()
                evictIfNeededLocked()
            }
        }
    }

    private fun updateCacheEntry(key: String, file: File) {
        synchronized(cacheLock) {
            val newSize = file.length()
            val previousSize = cacheEntries.remove(key)?.sizeBytes ?: 0L
            cacheEntries[key] = CacheEntry(file, newSize)
            currentCacheSizeBytes = (currentCacheSizeBytes - previousSize + newSize).coerceAtLeast(0L)
            evictIfNeededLocked()
        }
    }

    private fun removeCacheEntry(key: String) {
        synchronized(cacheLock) {
            val removed = cacheEntries.remove(key) ?: return
            currentCacheSizeBytes = (currentCacheSizeBytes - removed.sizeBytes).coerceAtLeast(0L)
        }
    }

    private fun evictIfNeededLocked() {
        val iterator = cacheEntries.entries.iterator()
        while (currentCacheSizeBytes > MAX_CACHE_SIZE_BYTES && iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.file.exists()) {
                entry.file.delete()
            }
            currentCacheSizeBytes = (currentCacheSizeBytes - entry.sizeBytes).coerceAtLeast(0L)
            iterator.remove()
        }
    }
}
