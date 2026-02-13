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
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
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
    val confidence: Float?,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
)

@Singleton
class ReceiptOcrService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Reverting to DEFAULT_OPTIONS as Builder might not be available in current dependency version
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Dispatcher that automatically routes URIs to the correct processor based on MIME type.
     */
    suspend fun processUri(uri: Uri): OcrResult {
        val mimeType = context.contentResolver.getType(uri) ?: ""
        return if (mimeType == "application/pdf") {
            processPdf(uri)
        } else {
            processImage(uri)
        }
    }

    /**
     * Process an image URI and return OCR results.
     * Also saves a compressed copy of the image for future reference.
     */
    suspend fun processImage(imageUri: Uri): OcrResult {
        // 1. Load and prepare the image (throws if fail)
        val bitmap = loadAndCorrectBitmap(imageUri) ?: throw IllegalStateException("Failed to load and correct image: $imageUri")

        try {
            // 2. Save compressed copy
            val savedPath = saveReceiptImage(bitmap)

            // 3. Run ML Kit OCR
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizeText(inputImage)

            // 4. Extract blocks with confidence filtering
            val blocks = visionText.textBlocks.mapNotNull { block ->
                val avgConfidence = block.lines.mapNotNull { it.confidence }.average().toFloat()
                // If confidence is available and very low (< 0.2), skip it.
                // Note: ML Kit often returns null confidence for Latin/Default models, so we default to 1.0 if null
                val safeConfidence = if (block.lines.firstOrNull()?.confidence != null) avgConfidence else 1.0f
                
                if (safeConfidence < 0.2f && block.text.length < 3) {
                    // Skip very low confidence noise (usually single characters)
                    null
                } else {
                    TextBlock(
                        text = block.text,
                        confidence = safeConfidence,
                        // lines argument removed as it's not in TextBlock definition
                        left = block.boundingBox?.left ?: 0,
                        top = block.boundingBox?.top ?: 0,
                        right = block.boundingBox?.right ?: 0,
                        bottom = block.boundingBox?.bottom ?: 0
                    )
                }
            }

            return OcrResult(
                fullText = blocks.joinToString("\n\n") { it.text },
                blocks = blocks,
                savedImagePath = savedPath
            )
        } finally {
            // CRITICAL: Prevent memory leaks during batch processing
            bitmap.recycle()
        }
    }

    /**
     * Process a PDF URI by rendering pages to bitmaps and running OCR on each.
     */
    suspend fun processPdf(pdfUri: Uri): OcrResult {
        val tempFile = File(context.cacheDir, "temp_pdf_${System.nanoTime()}.pdf")
        var renderer: PdfRenderer? = null
        var pfd: ParcelFileDescriptor? = null
        
        try {
            // 1. Copy PDF to local file (PdfRenderer needs a ParcelFileDescriptor from a file or pipe)
            context.contentResolver.openInputStream(pdfUri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Failed to open PDF stream: $pdfUri")

            pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            
            val allFullText = StringBuilder()
            val allBlocks = mutableListOf<TextBlock>()
            var savedThumbnailPath = ""
            
            // Limit to first 3-5 pages for performance (Rich functionality requirement)
            val pageLimit = 5 
            val pagesToProcess = minOf(renderer.pageCount, pageLimit)
            
            var verticalOffset = 0
            
            for (i in 0 until pagesToProcess) {
                val page = renderer.openPage(i)
                
                // Render page to high-quality Bitmap (OCR prefers ~200-300 DPI equivalent)
                // 1024 width is our standard for OCR in loadAndCorrectBitmap
                val scale = 1024f / page.width
                val bitmapWidth = 1024
                val bitmapHeight = (page.height * scale).toInt()
                
                val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                
                try {
                    // Save first page as JPG for UI preview/record
                    if (i == 0) {
                        savedThumbnailPath = saveReceiptImage(bitmap)
                    }
                    
                    // Run OCR on this page
                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    val visionText = recognizeText(inputImage)
                    
                    // Add full text
                    allFullText.append(visionText.text).append("\n\n")
                    
                    // Add blocks with offset (Virtual Long Page strategy)
                    visionText.textBlocks.forEach { block ->
                        allBlocks.add(
                            TextBlock(
                                text = block.text,
                                confidence = block.lines.firstOrNull()?.confidence,
                                left = block.boundingBox?.left ?: 0,
                                top = (block.boundingBox?.top ?: 0) + verticalOffset,
                                right = block.boundingBox?.right ?: 0,
                                bottom = (block.boundingBox?.bottom ?: 0) + verticalOffset
                            )
                        )
                    }
                    
                    verticalOffset += bitmapHeight
                    
                } finally {
                    bitmap.recycle() // CRITICAL: Release memory immediately
                    page.close()
                }
            }
            
            return OcrResult(
                fullText = allFullText.toString().trim(),
                blocks = allBlocks,
                savedImagePath = savedThumbnailPath
            )
            
        } catch (e: Exception) {
            android.util.Log.e("ReceiptOcrService", "PDF processing failed for $pdfUri", e)
            throw IllegalStateException("Failed to scan PDF: ${e.message}", e)
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private suspend fun recognizeText(
        image: InputImage
    ): com.google.mlkit.vision.text.Text {
        return kotlinx.coroutines.withTimeout(15000) { // Fix 4.17: 15s timeout
            suspendCancellableCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { text ->
                        continuation.resume(text)
                    }
                    .addOnFailureListener { e ->
                        continuation.resumeWithException(e)
                    }
            }
        }
    }

    /**
     * Load bitmap from URI with EXIF rotation correction.
     * Copies to a temp file first to ensure reliable multi-read access.
     */
    private fun loadAndCorrectBitmap(uri: Uri): Bitmap? {
        val tempFile = File(context.cacheDir, "temp_ocr_${System.nanoTime()}.jpg")
        var decodedBitmap: Bitmap? = null
        try {
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

            // Calculate sample size - Optimized: 1024 is plenty for OCR and saves memory/time
            val maxDimension = 1024
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
            decodedBitmap = bitmap

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
                try {
                    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    if (rotated != bitmap) {
                        bitmap.recycle() // Clean up original if rotated
                    }
                    return rotated
                } catch (e: Exception) {
                    bitmap.recycle() // CRITICAL: Recycle original if rotation fails (OOM similar)
                    throw e
                }
            } else {
                return bitmap
            }
        } catch (e: Exception) {
            android.util.Log.e("ReceiptOcrService", "Error loading bitmap from $uri", e)
            if (decodedBitmap?.isRecycled == false) {
                decodedBitmap?.recycle()
            }
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
