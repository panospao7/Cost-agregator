package com.yourname.expensetracker.domain.receipt.lifecycle

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Validates receipt input URIs before they enter the processing pipeline.
 *
 * Checks performed:
 * 1. URI is readable via [ContentResolver.openInputStream].
 * 2. MIME type is one of the supported types.
 * 3. File size is within the specified limit.
 * 4. For image MIME types, the bitmap can be decoded successfully.
 */
/**
 * Maps file extensions to MIME types for fallback detection when
 * ContentResolver.getType() returns null.
 */
private val EXTENSION_MIME_MAP = mapOf(
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "png" to "image/png",
    "webp" to "image/webp",
    "heic" to "image/heic",
    "pdf" to "application/pdf",
    "tiff" to "image/tiff",
    "tif" to "image/tiff",
    "bmp" to "image/bmp"
)

@Singleton
class ReceiptInputValidator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Outcome of a validation attempt.
     *
     * @property isValid       Whether the input passed all validation checks.
     * @property errors        Human-readable error messages describing each failure.
     * @property mimeType      The MIME type resolved by the content provider, if available.
     * @property fileSizeBytes The file size in bytes, if determinable.
     */
    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>,
        val mimeType: String?,
        val fileSizeBytes: Long?
    )

    /**
     * Validates the content at [uri] against the supported receipt input rules.
     *
     * @param uri          The content URI to validate.
     * @param maxSizeBytes Maximum allowed file size in bytes (default 50 MB).
     * @return A [ValidationResult] summarising all checks.
     */
    suspend fun validate(
        uri: Uri,
        maxSizeBytes: Long = DEFAULT_MAX_SIZE_BYTES
    ): ValidationResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val errors = mutableListOf<String>()

        // 1. Resolve MIME type
        val contentResolverMimeType = try {
            context.contentResolver.getType(uri)
        } catch (e: Exception) {
            Timber.e(e, "Failed to resolve MIME type") // P3-BLOCKER-E: no raw URI in logs
            null
        }

        // P2-12: MIME fallback — if ContentResolver returns null, try extension-based detection
        val mimeType = contentResolverMimeType ?: detectMimeTypeFromExtension(uri)

        // 2. Check URI readability
        val readable = try {
            context.contentResolver.openInputStream(uri)?.use { /* just open & close */ }
            true
        } catch (e: Exception) {
            Timber.e(e, "Receipt input URI not readable") // P3-BLOCKER-E: no raw URI
            false
        }
        if (!readable) {
            errors.add("Receipt input is not readable")
        }

        // 3. Validate MIME type
        if (mimeType == null) {
            errors.add("Could not determine MIME type for receipt input")
        } else if (mimeType !in SUPPORTED_MIME_TYPES) {
            errors.add("Unsupported MIME type: $mimeType. Supported: ${SUPPORTED_MIME_TYPES.joinToString()}")
        }

        // 4. Check file size
        val fileSizeBytes = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                pfd.statSize.takeIf { it >= 0 }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to query file size") // P3-BLOCKER-E: no raw URI
            null
        }

        if (fileSizeBytes != null && fileSizeBytes > maxSizeBytes) {
            errors.add(
                "File too large: ${fileSizeBytes / 1024 / 1024}MB exceeds limit of ${maxSizeBytes / 1024 / 1024}MB"
            )
        } else if (fileSizeBytes == null) {
            // Content length unknown (-1 from statSize): fall back to streaming
            // copy with hard byte limit to prevent oversized uploads.
            val exceeded = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(DEFAULT_CHUNK_SIZE)
                    var totalBytes = 0L
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        totalBytes += bytesRead
                        if (totalBytes > maxSizeBytes) {
                            return@use true // exceeded
                        }
                    }
                    false
                } ?: false
            } catch (e: Exception) {
                Timber.e(e, "Failed to stream-check file size") // P3-BLOCKER-E
                false
            }
            if (exceeded) {
                errors.add("File exceeds size limit of ${maxSizeBytes / 1024 / 1024}MB")
            }
        }

        // 5. For image MIME types: try decoding to confirm valid image
        if (mimeType in IMAGE_MIME_TYPES && readable) {
            val decodeResult = try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val opts = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeStream(stream, null, opts)
                    opts.outWidth > 0 && opts.outHeight > 0
                } ?: false
            } catch (e: Exception) {
                Timber.e(e, "Bitmap decode failed") // P3-BLOCKER-E: no raw URI
                false
            }
            if (!decodeResult) {
                errors.add("Failed to decode image (invalid or corrupted file)")
            }
        }

        ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            mimeType = mimeType,
            fileSizeBytes = fileSizeBytes
        )
    }

    private fun detectMimeTypeFromExtension(uri: Uri): String? {
        val path = uri.lastPathSegment ?: uri.path ?: return null
        val extension = path.substringAfterLast('.').lowercase().takeIf { it.isNotBlank() && it != path }
            ?: return null
        val detected = EXTENSION_MIME_MAP[extension]
        if (detected != null) {
            Timber.d("MIME fallback: extension .%s → %s for %s", extension, detected, uri)
        }
        return detected
    }

    companion object {
        /**
         * P2-13: Exported constant for use in ReceiptOcrService and other consumers.
         * Represents the default max receipt file size (50 MB).
         */
        const val DEFAULT_MAX_SIZE_BYTES = 50 * 1024 * 1024L // 50 MB

        private const val DEFAULT_CHUNK_SIZE = 8192

        private val SUPPORTED_MIME_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "application/pdf",
            "image/heic"
        )

        private val IMAGE_MIME_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/heic"
        )
    }
}
