package com.yourname.expensetracker.domain.receipt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrResult(
    val fullText: String,
    val blocks: List<TextBlock>,
    val savedImagePath: String
)

data class TextBlock(
    val text: String,
    val confidence: Float?
)

@Singleton
class ReceiptOcrService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Process an image URI and return OCR results.
     * Also saves a compressed copy of the image for future reference.
     */
    suspend fun processImage(imageUri: Uri): OcrResult {
        // 1. Load and prepare the image (throws if fail)
        val bitmap = loadAndCorrectBitmap(imageUri)!!

        try {
            // 2. Save compressed copy
            val savedPath = saveReceiptImage(bitmap)

            // 3. Run ML Kit OCR
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizeText(inputImage)

            // 4. Extract blocks
            val blocks = visionText.textBlocks.map { block ->
                TextBlock(
                    text = block.text,
                    confidence = block.lines.firstOrNull()?.confidence
                )
            }

            return OcrResult(
                fullText = visionText.text,
                blocks = blocks,
                savedImagePath = savedPath
            )
        } finally {
            // CRITICAL: Prevent memory leaks during batch processing
            bitmap.recycle()
        }
    }

    private suspend fun recognizeText(
        image: InputImage
    ): com.google.mlkit.vision.text.Text {
        return suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    continuation.resume(text)
                }
                .addOnFailureListener { e ->
                    continuation.resumeWithException(e)
                }
        }
    }

    /**
     * Load bitmap from URI with EXIF rotation correction.
     * Copies to a temp file first to ensure reliable multi-read access.
     */
    private fun loadAndCorrectBitmap(uri: Uri): Bitmap? {
        val tempFile = File(context.cacheDir, "temp_ocr_${System.nanoTime()}.jpg")
        return try {
            // Copy URI to temp file
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Could not open input stream for $uri")
            
            inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (!tempFile.exists() || tempFile.length() == 0L) {
                throw IllegalStateException("Temp file creation failed or empty for $uri")
            }

            // 1. Get dimensions
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(tempFile.absolutePath, options)

            // Calculate sample size
            val maxDimension = 2048
            var sampleSize = 1
            if (options.outWidth > 0 && options.outHeight > 0) {
                while (options.outWidth / sampleSize > maxDimension ||
                    options.outHeight / sampleSize > maxDimension
                ) {
                    sampleSize *= 2
                }
            }

            // 2. Decode actual bitmap
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath, decodeOptions)
                ?: throw IllegalStateException("Bitmap decode failed for $uri (Sample: $sampleSize)")

            // 3. Apply EXIF rotation
            val exif = ExifInterface(tempFile.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val matrix = Matrix()
            var needsRotate = true
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                else -> needsRotate = false
            }

            if (needsRotate) {
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) {
                    bitmap.recycle() // Clean up original if rotated
                }
                rotated
            } else {
                bitmap
            }
        } catch (e: Exception) {
            android.util.Log.e("ReceiptOcrService", "Error loading bitmap from $uri", e)
            throw IllegalStateException("Failed to load image: ${e.message}", e)
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /**
     * Save a compressed copy of the receipt image
     */
    private fun saveReceiptImage(bitmap: Bitmap): String {
        val receiptsDir = File(context.filesDir, "receipts")
        if (!receiptsDir.exists()) receiptsDir.mkdirs()

        val fileName = "receipt_${System.currentTimeMillis()}.jpg"
        val file = File(receiptsDir, fileName)

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }

        return file.absolutePath
    }

    /**
     * Create a temporary URI for the camera to write to
     */
    fun createTempImageUri(): Uri {
        val cacheDir = File(context.cacheDir, "receipt_images")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val file = File(cacheDir, "camera_${System.currentTimeMillis()}.jpg")

        return androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    /**
     * Delete a saved receipt image
     */
    fun deleteImage(path: String) {
        try {
            File(path).delete()
        } catch (_: Exception) {
        }
    }
}
