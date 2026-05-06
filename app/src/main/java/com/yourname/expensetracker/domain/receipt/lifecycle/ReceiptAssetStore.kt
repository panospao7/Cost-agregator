package com.yourname.expensetracker.domain.receipt.lifecycle

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLConnection
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralised file operations for receipt assets.
 *
 * Owns the "receipts" subdirectory under [Context.filesDir] and mediates all
 * reads/writes/deletes of receipt image files.  Provides helpers for creating
 * camera temp URIs, persisting incoming assets, computing file hashes, and
 * listing stored files.
 */
@Singleton
class ReceiptAssetStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val receiptsDir: File
        get() = File(context.filesDir, RECEIPTS_DIR).also {
            if (!it.exists()) it.mkdirs()
        }

    /**
     * Creates a temporary content URI suitable for the camera app to write into.
     *
     * The file is placed under the app's cache directory so it can be garbage
     * collected automatically, but a content URI is returned so the camera can
     * access it via a [FileProvider] authority.
     */
    fun createTempImageUri(): Uri {
        val cacheDir = File(context.cacheDir, CACHE_SUBDIR)
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val file = File(cacheDir, "camera_${System.currentTimeMillis()}.jpg")

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    /**
     * Copies the content at [sourceUri] into the app's persistent receipt
     * storage directory and returns the absolute path of the saved file.
     *
     * The destination filename is `{timestamp}_{uuid}.jpg` to guarantee
     * uniqueness even when multiple receipts are persisted concurrently.
     *
     * @return [Result.success] with the absolute file path on success,
     *         [Result.failure] if the source cannot be read or the copy fails.
     */
    suspend fun persistReceiptAsset(sourceUri: Uri): Result<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val dir = receiptsDir
            val fileName = "${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg"
            val destFile = File(dir, fileName)

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Cannot open input stream for URI: $sourceUri")

            if (!destFile.exists() || destFile.length() == 0L) {
                throw IllegalStateException("Persisted file is missing or empty: ${destFile.absolutePath}")
            }

            Timber.d("Persisted receipt asset: %s -> %s (%d bytes)", sourceUri, destFile.absolutePath, destFile.length())
            destFile.absolutePath
        }.onFailure { error ->
            Timber.e(error, "Failed to persist receipt asset from %s", sourceUri)
        }
    }

    /**
     * Computes the SHA-256 hash of the file at [filePath].
     *
     * Reads the file in 8 KB chunks to keep memory usage low regardless of
     * file size.
     *
     * @return [Result.success] with the hex-encoded hash string on success,
     *         [Result.failure] if the file does not exist or cannot be read.
     */
    suspend fun computeFileHash(filePath: String): Result<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)

            DigestInputStream(FileInputStream(filePath), digest).use { dis ->
                @Suppress("UnusedEquals") // read until EOF
                while (dis.read(buffer) != -1) { /* digest is updated automatically */ }
            }

            val hashBytes = digest.digest()
            hashBytes.joinToString("") { "%02x".format(it) }
        }.onFailure { error ->
            Timber.e(error, "Failed to compute SHA-256 hash for %s", filePath)
        }
    }

    /**
     * Computes the SHA-256 hash of the content at [uri] without persisting it
     * to disk. Reads the input stream in 8 KB chunks into the digest.
     *
     * This is used for pre-OCR duplicate detection: if the hash matches an
     * already-processed receipt we can skip the expensive OCR step entirely.
     *
     * @return [Result.success] with the hex-encoded hash string on success,
     *         [Result.failure] if the URI cannot be opened or read.
     */
    suspend fun computeUriHash(uri: Uri): Result<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)

            context.contentResolver.openInputStream(uri)?.use { input ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            } ?: throw IllegalStateException("Cannot open input stream for URI: $uri")

            val hashBytes = digest.digest()
            hashBytes.joinToString("") { "%02x".format(it) }
        }.onFailure { error ->
            Timber.e(error, "Failed to compute SHA-256 hash for URI: %s", uri)
        }
    }

    /**
     * Deletes the receipt asset file at [filePath].
     *
     * @return `true` if the file was successfully deleted, `false` otherwise
     *         (including when the file does not exist).
     */
    fun deleteAsset(filePath: String): Boolean {
        return try {
            val result = File(filePath).delete()
            if (result) {
                Timber.d("Deleted receipt asset: %s", filePath)
            } else {
                Timber.w("Failed to delete receipt asset (may not exist): %s", filePath)
            }
            result
        } catch (e: Exception) {
            Timber.e(e, "Error deleting receipt asset: %s", filePath)
            false
        }
    }

    /**
     * Returns all receipt asset files currently stored in the receipts directory.
     *
     * Only returns regular files (directories are ignored).  The list is sorted
     * by last-modified descending (newest first).
     */
    fun listReceiptFiles(): List<File> {
        return receiptsDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * Entry in a backup manifest describing a single receipt asset file.
     *
     * @property receiptId The database ID of the receipt this file belongs to.
     * @property imagePath Absolute path to the asset file in app-local storage.
     * @property fileHash SHA-256 hex digest of the file content (null if hash computation failed).
     * @property fileSizeBytes Size of the file in bytes.
     * @property mimeType MIME type inferred from the file extension (e.g. "image/jpeg", "application/pdf").
     */
    data class ReceiptAssetManifestEntry(
        val receiptId: Long,
        val imagePath: String,
        val fileHash: String?,
        val fileSizeBytes: Long,
        val mimeType: String
    )

    /**
     * Generates a backup manifest of all receipt asset files referenced by the
     * given [receipts].
     *
     * Only processes receipts that have a non-null [ScannedReceipt.imagePath].
     * For each such receipt, the method:
     * - Verifies that the file exists on disk.
     * - Looks up the file size from [File.length].
     * - Computes the SHA-256 hash of the file content (best-effort; non-fatal).
     * - Infers the MIME type from the file extension.
     *
     * Use this manifest to include receipt images in an archive-based backup.
     *
     * @param receipts List of all [ScannedReceipt] rows (typically from
     *            [ScannedReceiptDao.getAllWithImagePath]).
     * @return A list of [ReceiptAssetManifestEntry] entries, one per receipt
     *         that has an [imagePath] and whose file exists on disk.
     */
    suspend fun generateBackupManifest(receipts: List<ScannedReceipt>): List<ReceiptAssetManifestEntry> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            receipts
                .filter { it.imagePath != null }
                .mapNotNull { receipt ->
                    val path = receipt.imagePath!!
                    val file = File(path)
                    if (!file.exists() || !file.isFile) {
                        Timber.w("Backup manifest: receipt asset file missing: %s (receiptId=%d)", path, receipt.id)
                        return@mapNotNull null
                    }
                    val fileHash = runCatching {
                        computeFileHashSync(path)
                    }.getOrElse { error ->
                        Timber.w(error, "Backup manifest: hash computation failed for %s", path)
                        null
                    }
                    val mimeType = URLConnection.guessContentTypeFromName(file.name)
                        ?: "application/octet-stream"

                    ReceiptAssetManifestEntry(
                        receiptId = receipt.id,
                        imagePath = path,
                        fileHash = fileHash,
                        fileSizeBytes = file.length(),
                        mimeType = mimeType
                    )
                }
        }

    /**
     * Synchronous SHA-256 hash of the file at [filePath] used by
     * [generateBackupManifest] (which already runs on [Dispatchers.IO]).
     */
    private fun computeFileHashSync(filePath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        DigestInputStream(FileInputStream(filePath), digest).use { dis ->
            @Suppress("UnusedEquals")
            while (dis.read(buffer) != -1) { /* digest is updated automatically */ }
        }
        val hashBytes = digest.digest()
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        private const val RECEIPTS_DIR = "receipts"
        private const val CACHE_SUBDIR = "receipt_images"
    }
}
