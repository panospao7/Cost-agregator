package com.yourname.expensetracker.domain.receipt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrResult(
    val fullText: String,
    val blocks: List<TextBlock>,
    val savedImagePath: String,
    // F1: Warranty extraction result
    val warrantyExtractionResult: com.yourname.expensetracker.domain.usecase.warranty.WarrantyCreationResult? = null,
    // P2-15: PDF truncation metadata — populated when processing multi-page PDFs
    val pagesProcessed: Int? = null,
    val totalPages: Int? = null
)

data class TextBlock(
    val text: String,
    val confidence: Float?,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
)

/**
 * OCR service for images and PDFs.
 *
 * Throughput optimization: only serializes access to the shared ML recognizer.
 * Decode/rotate/save steps are allowed to run in parallel for batch imports.
 */
@Singleton
class ReceiptOcrService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    init {
        // Initialize PDFBox for Android
        PDFBoxResourceLoader.init(context)
    }
    
    /**
     * Serialize recognizer usage only (shared native ML resources).
     */
    private val recognizerMutex = Mutex()
    
    // Lazily recreated so resources can be released safely via close().
    @Volatile
    private var recognizer: TextRecognizer? = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private fun getRecognizer(): TextRecognizer {
        val existing = recognizer
        if (existing != null) return existing

        return synchronized(this) {
            recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also {
                recognizer = it
            }
        }
    }

    companion object {
        private val ALLOWED_IMAGE_TYPES = setOf(
            "image/jpeg",
            "image/png", 
            "image/webp",
            "image/heic"
        )
        // P2-13: Use exported constant from ReceiptInputValidator for consistency
        private val MAX_FILE_SIZE = com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptInputValidator.DEFAULT_MAX_SIZE_BYTES
        private const val DEFAULT_BUFFER_SIZE = 8192
    }

    /**
     * Dispatcher that automatically routes URIs to the correct processor based on MIME type.
     */
    suspend fun processUri(uri: Uri): OcrResult {
        val mimeType = context.contentResolver.getType(uri) ?: ""
        
        // Validate file type
        if (mimeType == "application/pdf") {
            // Apply the same file-size guard used for images.
            validateFileSize(uri)
            return processPdf(uri)
        } else if (mimeType in ALLOWED_IMAGE_TYPES) {
            validateFileSize(uri)
            return processImage(uri)
        } else {
            throw IllegalArgumentException(
                "Unsupported file type: $mimeType. " +
                "Supported types: ${ALLOWED_IMAGE_TYPES.joinToString()}, application/pdf"
            )
        }
    }

    /**
     * Platform-agnostic URI reference overload used by domain abstractions.
     */
    suspend fun processUri(uriRef: String): OcrResult = processUri(Uri.parse(uriRef))

    private fun validateFileSize(uri: Uri) {
        // --- RCP-2: Two-phase size validation ---
        // Phase 1: Try the cheap content-provider statSize (works for most file:// URIs).
        val fileSize = context.contentResolver.openFileDescriptor(uri, "r")?.use {
            it.statSize
        }

        if (fileSize != null && fileSize >= 0) {
            // Provider reported a definite size — simple bounds check.
            if (fileSize > MAX_FILE_SIZE) {
                throw IllegalArgumentException(
                    "File too large: ${fileSize / 1024 / 1024}MB. Maximum: ${MAX_FILE_SIZE / 1024 / 1024}MB"
                )
            }
            return
        }

        // Phase 2: Content provider did NOT report size (statSize == -1 or null).
        // Stream-copy up to MAX_FILE_SIZE + 1 bytes; if the stream has MORE data
        // than the limit, reject immediately. This prevents OOM from huge files
        // whose size cannot be determined upfront.
        Timber.w("Content provider does not report file size for URI: $uri. Performing streaming size check (max ${MAX_FILE_SIZE / 1024 / 1024}MB).")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var totalRead = 0L
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    totalRead += bytesRead
                    if (totalRead > MAX_FILE_SIZE) {
                        throw IllegalArgumentException(
                            "File too large (streaming check): exceeds ${MAX_FILE_SIZE / 1024 / 1024}MB limit. URI: $uri"
                        )
                    }
                }
            } ?: throw IllegalArgumentException("Cannot open InputStream for URI: $uri")
        } catch (e: IllegalArgumentException) {
            throw e // Rethrow our own size-limit exception
        } catch (e: Exception) {
            // If streaming size check itself fails for any other reason,
            // fall through with a warning rather than blocking the user.
            Timber.w(e, "Streaming size check failed for URI: $uri — proceeding without size guarantee.")
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

            // 3. Run ML Kit OCR with retry
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = runWithRetry(maxAttempts = 3) {
                recognizeText(inputImage)
            }

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

            // RCP-7: Compute overall OCR confidence (average of block confidences)
            // and log a warning when it falls below 0.5 threshold.
            val overallConfidence = if (blocks.isNotEmpty()) {
                blocks.mapNotNull { it.confidence }.average().toFloat()
            } else 0f
            if (overallConfidence < 0.5f && blocks.isNotEmpty()) {
                Timber.w(
                    "Low OCR confidence: overall=%.2f, blocks=%d, uri=%s",
                    overallConfidence, blocks.size, imageUri
                )
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
     * Persist a normalized/compressed image copy without running OCR.
     *
     * Use for manual fallback flows where recognition already failed
     * and only a durable preview path is needed.
     */
    fun persistImageCopy(imageUri: Uri): String {
        val bitmap = loadAndCorrectBitmap(imageUri)
            ?: throw IllegalStateException("Failed to load and correct image: $imageUri")

        return try {
            saveReceiptImage(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Process a PDF URI with intelligent routing:
     * 1. Try direct text extraction (fast for digital PDFs)
     * 2. Fall back to bitmap rendering + OCR (for scanned PDFs)
     */
    suspend fun processPdf(pdfUri: Uri): OcrResult {
        // First, try direct text extraction
        val (extractedText, totalPages) = extractPdfText(pdfUri)
        
        // If we got substantial text (>100 chars), use it
        if (extractedText.length > 100) {
            Timber.d("Using direct PDF text extraction (${extractedText.length} chars)")
            return processPdfWithTextExtraction(pdfUri, extractedText, totalPages)
        }
        
        // Otherwise, fall back to OCR
        Timber.d("PDF has minimal text, falling back to OCR")
        return processPdfWithOcr(pdfUri)
    }
    
    /**
     * Extract text directly from PDF using PDFBox (fast for digital PDFs).
     * @return Pair of (extracted text, total page count).
     */
    private suspend fun extractPdfText(pdfUri: Uri): Pair<String, Int> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "temp_pdf_extract_${System.nanoTime()}.pdf")
        var document: PDDocument? = null
        var totalPages = 0
        
        try {
            // Copy PDF to temp file
            context.contentResolver.openInputStream(pdfUri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Pair("", 0)
            
            // Load PDF and extract text
            document = PDDocument.load(tempFile)
            totalPages = document.numberOfPages
            val stripper = PDFTextStripper()
            
            // Limit to first 5 pages for performance
            val pageLimit = minOf(document.numberOfPages, 5)

            // RCP-27: Warn user when PDF has more pages than we're processing
            if (document.numberOfPages > 5) {
                Timber.w(
                    "PDF has %d pages — only processing the first %d. " +
                    "Remaining pages will be skipped.",
                    document.numberOfPages, 5
                )
            }

            stripper.startPage = 1
            stripper.endPage = pageLimit
            
            val text = stripper.getText(document)
            Timber.d("Extracted ${text.length} chars from $pageLimit pages")
            
            return@withContext Pair(text, totalPages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // MED-01 FIX: Add logging instead of silent catch
            Timber.e(e, "PDF text extraction failed for $pdfUri")
            return@withContext Pair("", 0)
        } finally {
            // MED-01 FIX: Add logging to catch blocks
            try { document?.close() } catch (e: Exception) { 
                Timber.e(e, "Failed to close PDF document")
            }
            if (tempFile.exists()) tempFile.delete()
        }
    }
    
    /**
     * Process PDF using direct text extraction (for digital PDFs).
     */
    private suspend fun processPdfWithTextExtraction(pdfUri: Uri, extractedText: String, totalPages: Int): OcrResult {
        // Save first page as thumbnail for UI
        val thumbnailPath = renderPdfFirstPageThumbnail(pdfUri)
        
        // Limit to first 5 pages for performance (matching extractPdfText)
        val pagesProcessed = minOf(totalPages, 5)
        
        // Create text blocks from extracted text (simple line-based approach)
        val blocks = extractedText.lines()
            .filter { it.isNotBlank() }
            .mapIndexed { index, line ->
                TextBlock(
                    text = line.trim(),
                    confidence = 1.0f, // Direct extraction has perfect confidence
                    left = 0,
                    top = index * 20, // Approximate line height
                    right = 1000,
                    bottom = (index + 1) * 20
                )
            }
        
        return OcrResult(
            fullText = extractedText,
            blocks = blocks,
            savedImagePath = thumbnailPath,
            pagesProcessed = pagesProcessed,
            totalPages = totalPages
        )
    }
    
    /**
     * Render first page of PDF as thumbnail for UI preview.
     */
    private suspend fun renderPdfFirstPageThumbnail(pdfUri: Uri): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "temp_pdf_thumb_${System.nanoTime()}.pdf")
        var renderer: PdfRenderer? = null
        var pfd: ParcelFileDescriptor? = null
        
        try {
            // Copy PDF to temp file
            context.contentResolver.openInputStream(pdfUri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext ""
            
            pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            
            // HIGH-04 FIX: Use try-finally for page resource safety
            val page = renderer.openPage(0)
            var bitmap: Bitmap? = null
            try {
                val scale = 1024f / page.width
                bitmap = Bitmap.createBitmap(
                    1024,
                    (page.height * scale).toInt(),
                    Bitmap.Config.ARGB_8888
                )
                
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                
                val savedPath = saveReceiptImage(bitmap)
                return@withContext savedPath
            } finally {
                page.close()
                bitmap?.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // MED-01 FIX: Add logging to catch blocks  
            Timber.e(e, "Thumbnail rendering failed for PDF: $pdfUri")
            return@withContext ""
        } finally {
            // MED-01 FIX: Add logging to catch blocks
            try { renderer?.close() } catch (e: Exception) { 
                Timber.e(e, "Failed to close PDF renderer during thumbnail")
            }
            try { pfd?.close() } catch (e: Exception) { 
                Timber.e(e, "Failed to close ParcelFileDescriptor during thumbnail")
            }
            if (tempFile.exists()) tempFile.delete()
        }
    }
    
    /**
     * Process PDF by rendering pages to bitmaps and running OCR (for scanned PDFs).
     */
    private suspend fun processPdfWithOcr(pdfUri: Uri): OcrResult {
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

            // RCP-27: Warn user when PDF has more pages than we're processing
            if (renderer.pageCount > pageLimit) {
                Timber.w(
                    "OCR PDF has %d pages — only processing the first %d. " +
                    "Remaining pages will be skipped.",
                    renderer.pageCount, pageLimit
                )
            }

            val totalPages = renderer.pageCount
            val pagesToProcess = minOf(renderer.pageCount, pageLimit)
            
            var verticalOffset = 0
            
            for (i in 0 until pagesToProcess) {
                // HIGH-04 FIX: Wrap openPage in try-finally for resource safety
                val page = renderer.openPage(i)
                try {
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
                        
                        // Run OCR on this page with retry (matching processImage path)
                        val inputImage = InputImage.fromBitmap(bitmap, 0)
                        val visionText = runWithRetry(maxAttempts = 3) {
                            recognizeText(inputImage)
                        }
                        
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
                    }
                } finally {
                    page.close() // HIGH-04 FIX: Always close page even if bitmap/render fails
                }
            }
            
            return OcrResult(
                fullText = allFullText.toString().trim(),
                blocks = allBlocks,
                savedImagePath = savedThumbnailPath,
                pagesProcessed = pagesToProcess,
                totalPages = totalPages
            )
            
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "PDF processing failed for $pdfUri")
            throw IllegalStateException("Failed to scan PDF: ${e.message}", e)
        } finally {
            // MED-01 FIX: Add logging to catch blocks
            try { renderer?.close() } catch (e: Exception) { 
                Timber.e(e, "Failed to close PDF renderer in processPdfWithOcr")
            }
            try { pfd?.close() } catch (e: Exception) { 
                Timber.e(e, "Failed to close ParcelFileDescriptor in processPdfWithOcr")
            }
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private suspend fun recognizeText(
        image: InputImage
    ): com.google.mlkit.vision.text.Text {
        return recognizerMutex.withLock {
            kotlinx.coroutines.withTimeout(15000) { // Fix 4.17: 15s timeout
                suspendCancellableCoroutine { continuation ->
                    getRecognizer().process(image)
                        .addOnSuccessListener { text ->
                            continuation.resume(text)
                        }
                        .addOnFailureListener { e ->
                            continuation.resumeWithException(e)
                        }
                }
            }
        }
    }

    /**
     * Load bitmap from URI with EXIF rotation correction.
     * Copies to a temp file first to ensure reliable multi-read access.
     */
    private fun calculateOptimalDimension(): Int {
        val runtime = Runtime.getRuntime()
        val freeMemory = runtime.freeMemory()
        val totalMemory = runtime.totalMemory()
        val maxMemory = runtime.maxMemory()
        
        val availableMemory = maxMemory - (totalMemory - freeMemory)
        
        return when {
            availableMemory > 200 * 1024 * 1024 -> 1024  // >200MB available: use 1024
            availableMemory > 100 * 1024 * 1024 -> 768   // >100MB: use 768
            availableMemory > 50 * 1024 * 1024 -> 512    // >50MB: use 512
            else -> 384                                    // Low memory: use 384
        }
    }

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

            // Dynamic dimension based on available memory (Issue 2.10)
            val maxDimension = calculateOptimalDimension()
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
                        bitmap.recycle()
                    }
                    return rotated
                } catch (e: Exception) {
                    Timber.e(e, "Rotation failed, using original")
                    return bitmap
                }
            } else {
                return bitmap
            }
        } catch (e: Exception) {
            Timber.e(e, "Error loading bitmap from $uri")
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

        // P2-14: Use UUID-based naming instead of currentTimeMillis for uniqueness
        val fileName = "receipt_${UUID.randomUUID()}.jpg"
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
        } catch (e: Exception) {
            // MED-01 FIX: Add logging to catch blocks
            Timber.w(e, "Failed to delete receipt image at path: $path")
        }
    }

    /**
     * Release ML Kit recognizer resources.
     *
     * Safe to call multiple times; a new recognizer will be created lazily on next OCR request.
     */
    suspend fun close() {
        recognizerMutex.withLock {
            recognizer?.close()
            recognizer = null
        }
    }

    private suspend fun <T> runWithRetry(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 500,
        maxDelayMs: Long = 2000,
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelayMs
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == maxAttempts - 1) {
                    Timber.e(e, "OCR failed after $maxAttempts attempts")
                    throw e
                }
                Timber.w(e, "OCR attempt ${attempt + 1} failed, retrying in ${currentDelay}ms...")
                delay(currentDelay)
                currentDelay = (currentDelay * 2).coerceAtMost(maxDelayMs)
            }
        }
        throw IllegalStateException("Should not reach here")
    }
}
