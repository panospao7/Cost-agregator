package com.yourname.expensetracker.domain.receipt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Preprocessing pipeline for receipt images before OCR.
 * Improves OCR accuracy through image enhancement.
 */
@Singleton
class OcrPreprocessingPipeline @Inject constructor() {
    companion object {
        const val TARGET_DPI = 300
        const val MIN_TEXT_HEIGHT_DP = 8 // Minimum text height in density-independent pixels
    }

    /**
     * Process receipt image for optimal OCR results.
     * Returns enhanced bitmap ready for OCR.
     */
    fun preprocessForOcr(originalBitmap: Bitmap): Bitmap {
        try {
            var processedBitmap = originalBitmap
            
            // Step 1: Ensure minimum resolution
            processedBitmap = ensureMinimumResolution(processedBitmap)
            
            // Step 2: Convert to grayscale
            processedBitmap = convertToGrayscale(processedBitmap)
            
            // Step 3: Apply adaptive contrast enhancement
            processedBitmap = enhanceContrast(processedBitmap)
            
            // Step 4: Denoise
            processedBitmap = denoise(processedBitmap)
            
            // Step 5: Binarize (black and white)
            processedBitmap = binarize(processedBitmap)
            
            Timber.d("OCR preprocessing complete. Original: ${originalBitmap.width}x${originalBitmap.height}, " +
                    "Processed: ${processedBitmap.width}x${processedBitmap.height}")
            
            return processedBitmap
        } catch (e: Exception) {
            Timber.e(e, "OCR preprocessing failed, returning original")
            return originalBitmap
        }
    }

    /**
     * Ensure minimum resolution for good OCR results.
     */
    private fun ensureMinimumResolution(bitmap: Bitmap): Bitmap {
        val minWidth = 1024
        val minHeight = 768
        
        if (bitmap.width >= minWidth && bitmap.height >= minHeight) {
            return bitmap
        }
        
        val scaleFactor = max(
            minWidth.toFloat() / bitmap.width,
            minHeight.toFloat() / bitmap.height
        )
        
        val newWidth = (bitmap.width * scaleFactor).toInt()
        val newHeight = (bitmap.height * scaleFactor).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Convert bitmap to grayscale.
     */
    private fun convertToGrayscale(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        val grayscaleBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grayscaleBitmap)
        
        val paint = Paint()
        val colorMatrix = ColorMatrix()
        colorMatrix.setSaturation(0f) // Convert to grayscale
        
        val filter = ColorMatrixColorFilter(colorMatrix)
        paint.colorFilter = filter
        
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        
        return grayscaleBitmap
    }

    /**
     * Enhance contrast using histogram equalization.
     */
    private fun enhanceContrast(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // Calculate histogram
        val histogram = IntArray(256) { 0 }
        for (pixel in pixels) {
            val gray = (pixel shr 16) and 0xFF // Extract red channel (same for all in grayscale)
            histogram[gray]++
        }
        
        // Calculate cumulative distribution
        val cumulative = IntArray(256)
        cumulative[0] = histogram[0]
        for (i in 1..255) {
            cumulative[i] = cumulative[i - 1] + histogram[i]
        }
        
        // Calculate lookup table
        val totalPixels = width * height
        val lookupTable = IntArray(256)
        for (i in 0..255) {
            lookupTable[i] = ((cumulative[i] * 255.0) / totalPixels).toInt()
        }
        
        // Apply lookup table
        val enhancedPixels = IntArray(width * height)
        for (i in pixels.indices) {
            val gray = (pixels[i] shr 16) and 0xFF
            val newGray = lookupTable[gray]
            enhancedPixels[i] = (0xFF shl 24) or (newGray shl 16) or (newGray shl 8) or newGray
        }
        
        val enhancedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        enhancedBitmap.setPixels(enhancedPixels, 0, width, 0, 0, width, height)
        
        return enhancedBitmap
    }

    /**
     * Simple denoising using median filter approximation.
     */
    private fun denoise(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val denoisedPixels = IntArray(width * height)
        
        // Simple 3x3 median filter
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val neighbors = mutableListOf<Int>()
                
                // Collect 3x3 neighborhood
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val idx = (y + dy) * width + (x + dx)
                        neighbors.add((pixels[idx] shr 16) and 0xFF)
                    }
                }
                
                // Use median (middle value after sorting)
                neighbors.sort()
                val median = neighbors[4] // Middle of 9 values
                
                val idx = y * width + x
                denoisedPixels[idx] = (0xFF shl 24) or (median shl 16) or (median shl 8) or median
            }
        }
        
        // Copy edge pixels unchanged
        for (x in 0 until width) {
            denoisedPixels[x] = pixels[x] // Top edge
            denoisedPixels[(height - 1) * width + x] = pixels[(height - 1) * width + x] // Bottom edge
        }
        for (y in 0 until height) {
            denoisedPixels[y * width] = pixels[y * width] // Left edge
            denoisedPixels[y * width + width - 1] = pixels[y * width + width - 1] // Right edge
        }
        
        val denoisedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        denoisedBitmap.setPixels(denoisedPixels, 0, width, 0, 0, width, height)
        
        return denoisedBitmap
    }

    /**
     * Convert to black and white using Otsu's method approximation.
     */
    private fun binarize(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // Calculate average intensity for threshold
        var sum = 0L
        for (pixel in pixels) {
            sum += (pixel shr 16) and 0xFF
        }
        val threshold = (sum / pixels.size).toInt()
        
        // Apply threshold
        val binaryPixels = IntArray(width * height)
        for (i in pixels.indices) {
            val gray = (pixels[i] shr 16) and 0xFF
            val binary = if (gray > threshold) 255 else 0
            binaryPixels[i] = (0xFF shl 24) or (binary shl 16) or (binary shl 8) or binary
        }
        
        val binaryBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        binaryBitmap.setPixels(binaryPixels, 0, width, 0, 0, width, height)
        
        return binaryBitmap
    }

    /**
     * Calculate image quality score (0-100).
     */
    fun calculateQualityScore(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        
        // Check resolution
        val resolutionScore = min((width * height) / (1024 * 768), 1) * 30
        
        // Check sharpness (variance of Laplacian approximation)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        var variance = 0.0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val center = (pixels[y * width + x] shr 16) and 0xFF
                val neighbors = listOf(
                    (pixels[(y - 1) * width + x] shr 16) and 0xFF,
                    (pixels[(y + 1) * width + x] shr 16) and 0xFF,
                    (pixels[y * width + (x - 1)] shr 16) and 0xFF,
                    (pixels[y * width + (x + 1)] shr 16) and 0xFF
                )
                
                val diff = center - neighbors.average()
                variance += diff * diff
            }
        }
        
        variance /= ((width - 2) * (height - 2))
        val sharpnessScore = min((variance / 500).toInt(), 40)
        
        // Check contrast
        var minVal = 255
        var maxVal = 0
        for (pixel in pixels) {
            val gray = (pixel shr 16) and 0xFF
            minVal = min(minVal, gray)
            maxVal = max(maxVal, gray)
        }
        val contrast = maxVal - minVal
        val contrastScore = min(contrast / 2, 30)
        
        return resolutionScore + sharpnessScore + contrastScore
    }
}
