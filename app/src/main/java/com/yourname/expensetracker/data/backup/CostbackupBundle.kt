package com.yourname.expensetracker.data.backup

import com.yourname.expensetracker.data.privacy.BackupEncryptionService
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Represents a .costbackup bundle — an encrypted ZIP archive containing:
 * - manifest.json (BackupManifest)
 * - checksums.json (SHA-256 of each entry)
 * - database.sqlite (decrypted: the Room DB snapshot)
 * - files/receipts/ (receipt image assets)
 *
 * The outer archive is a header + AES-256-GCM ciphertext:
 *   COSTBACKUP1 (10B magic)
 *   format_version (2B, big-endian uint16)
 *   [ciphertext...]
 *
 * Encryption is handled entirely by [BackupEncryptionService], which embeds
 * its own salt (16B) and IV (12B) as a prefix to the ciphertext payload.
 *
 * Inside the ciphertext is a standard ZIP archive.
 */
object CostbackupBundle {

    private const val MAGIC = "COSTBACKUP1"
    private const val FORMAT_VERSION: UShort = 1u

    /** Header size: magic (10) + format version (2) = 12 bytes. */
    private const val HEADER_SIZE = 10 + 2

    // ── Manifest / Checksums data classes (manual JSON) ───────────

    data class BackupManifest(
        val backupFormatVersion: Int = 1,
        val databaseVersion: Int,
        val createdAt: Long = System.currentTimeMillis(),
        val includes: BackupIncludes = BackupIncludes(),
        val tableCounts: Map<String, Int> = emptyMap(),
        val receiptAssetCount: Int = 0,
        val options: BackupOptionsManifest = BackupOptionsManifest()
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("backupFormatVersion", backupFormatVersion)
            put("databaseVersion", databaseVersion)
            put("createdAt", createdAt)
            put("includes", includes.toJson())
            put("tableCounts", JSONObject(tableCounts))
            put("receiptAssetCount", receiptAssetCount)
            put("options", options.toJson())
        }

        companion object {
            fun fromJson(json: JSONObject): BackupManifest = BackupManifest(
                backupFormatVersion = json.optInt("backupFormatVersion", 1),
                databaseVersion = json.getInt("databaseVersion"),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                includes = json.optJSONObject("includes")?.let { BackupIncludes.fromJson(it) }
                    ?: BackupIncludes(),
                tableCounts = json.optJSONObject("tableCounts")?.let { obj ->
                    val map = mutableMapOf<String, Int>()
                    for (key in obj.keys()) {
                        map[key] = obj.getInt(key)
                    }
                    map
                } ?: emptyMap(),
                receiptAssetCount = json.optInt("receiptAssetCount", 0),
                options = json.optJSONObject("options")?.let { BackupOptionsManifest.fromJson(it) }
                    ?: BackupOptionsManifest()
            )
        }
    }

    data class BackupIncludes(
        val database: Boolean = true,
        val receiptImages: Boolean = false,
        val rawNotifications: Boolean = false,
        val rawOcr: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("database", database)
            put("receiptImages", receiptImages)
            put("rawNotifications", rawNotifications)
            put("rawOcr", rawOcr)
        }

        companion object {
            fun fromJson(json: JSONObject): BackupIncludes = BackupIncludes(
                database = json.optBoolean("database", true),
                receiptImages = json.optBoolean("receiptImages", false),
                rawNotifications = json.optBoolean("rawNotifications", false),
                rawOcr = json.optBoolean("rawOcr", false)
            )
        }
    }

    data class BackupOptionsManifest(
        val redacted: Boolean = true,
        val includeReceiptImages: Boolean = true
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("redacted", redacted)
            put("includeReceiptImages", includeReceiptImages)
        }

        companion object {
            fun fromJson(json: JSONObject): BackupOptionsManifest = BackupOptionsManifest(
                redacted = json.optBoolean("redacted", true),
                includeReceiptImages = json.optBoolean("includeReceiptImages", true)
            )
        }
    }

    data class ChecksumsManifest(
        val entries: Map<String, String> = emptyMap() // relative path → SHA-256 hex
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("entries", JSONObject(entries))
        }

        companion object {
            fun fromJson(json: JSONObject): ChecksumsManifest {
                val entries = mutableMapOf<String, String>()
                val entriesObj = json.optJSONObject("entries")
                if (entriesObj != null) {
                    for (key in entriesObj.keys()) {
                        entries[key] = entriesObj.getString(key)
                    }
                }
                return ChecksumsManifest(entries)
            }
        }
    }

    // ── Exceptions ────────────────────────────────────────────────

    class WrongBackupPasswordException(message: String) : Exception(message)
    class UnsupportedBackupVersionException(message: String) : Exception(message)
    class InvalidBackupFormatException(message: String) : Exception(message)
    class ChecksumMismatchException(message: String) : Exception(message)

    // ── Header helpers ─────────────────────────────────────────────

    /**
     * Writes the .costbackup header (magic, version) to [stream].
     * Encryption metadata (salt, IV) is handled by [BackupEncryptionService]
     * and embedded in the ciphertext payload.
     */
    private fun writeHeader(stream: FileOutputStream) {
        stream.write(MAGIC.toByteArray(Charsets.US_ASCII))
        stream.write(byteArrayOf(
            (FORMAT_VERSION.toInt() shr 8).toByte(),
            FORMAT_VERSION.toInt().toByte()
        ))
    }

    /**
     * Reads and validates the .costbackup header from [inputStream], consuming
     * exactly [HEADER_SIZE] bytes. Throws on invalid magic or version.
     *
     * After this call returns, [inputStream] is positioned at the start of the
     * ciphertext (salt + IV + encrypted ZIP).
     */
    fun readHeaderFromStream(inputStream: InputStream) {
        val headerBytes = ByteArray(HEADER_SIZE)
        var offset = 0
        while (offset < HEADER_SIZE) {
            val n = inputStream.read(headerBytes, offset, HEADER_SIZE - offset)
            if (n == -1) throw InvalidBackupFormatException("File too short: missing header")
            offset += n
        }
        readHeader(headerBytes)
    }

    /**
     * Reads and validates the .costbackup header from [bytes].
     * Returns the remaining ciphertext (which includes salt + IV embedded by
     * [BackupEncryptionService]).
     */
    fun readHeader(bytes: ByteArray): ByteArray {
        require(bytes.size >= HEADER_SIZE) {
            "File too short: ${bytes.size} bytes, expected at least $HEADER_SIZE"
        }

        var offset = 0

        // Magic
        val magic = String(bytes, offset, 10, Charsets.US_ASCII)
        offset += 10
        if (magic != MAGIC) {
            throw InvalidBackupFormatException(
                "Invalid magic: expected '$MAGIC', got '$magic'"
            )
        }

        // Format version
        val versionHigh = bytes[offset++].toInt() and 0xFF
        val versionLow = bytes[offset++].toInt() and 0xFF
        val version = (versionHigh shl 8) or versionLow
        if (version != FORMAT_VERSION.toInt()) {
            throw UnsupportedBackupVersionException(
                "Unsupported format version: $version (expected ${FORMAT_VERSION})"
            )
        }

        // Remaining ciphertext (BackupEncryptionService embeds its own salt + IV)
        val ciphertext = bytes.copyOfRange(offset, bytes.size)

        return ciphertext
    }

    // ── Create ────────────────────────────────────────────────────

    /**
     * Creates a .costbackup bundle at [outputFile].
     *
     * @param databaseFile the Room DB file to include
     * @param receiptFiles map of relative path (e.g. "files/receipts/r1.jpg") → original file
     * @param password user-provided encryption password
     * @param tableCounts map of table name → row count for manifest
     * @param databaseVersion Room schema version
     * @param redacted whether the backup was sanitised (privacy-first)
     */
    fun create(
        outputFile: File,
        databaseFile: File,
        receiptFiles: Map<String, File>,
        password: String,
        tableCounts: Map<String, Int>,
        databaseVersion: Int,
        redacted: Boolean = true,
        includeReceiptImages: Boolean = true,
        encryptionService: BackupEncryptionService = BackupEncryptionService()
    ): Result<File> = runCatching {
        // 1. Create a temp file alongside the output for streaming ZIP construction
        val parentDir = outputFile.parentFile ?: File(".")
        parentDir.mkdirs()
        val tempZip = File(parentDir, "backup_${UUID.randomUUID()}.tmp")

        try {
            // 2. Build ZIP streaming to temp file (avoids OOM from ByteArrayOutputStream)
            buildZip(tempZip, databaseFile, receiptFiles, tableCounts, databaseVersion, redacted, includeReceiptImages)

            // 3. Encrypt from temp file + write header + ciphertext to output file
            FileOutputStream(outputFile).use { fos ->
                writeHeader(fos)
                encryptionService.encrypt(tempZip, fos, password)
            }

            Timber.d("Created .costbackup bundle: %s (%d bytes)", outputFile.absolutePath, outputFile.length())
            outputFile
        } finally {
            // 4. Always clean up the temp ZIP file
            if (tempZip.exists() && !tempZip.delete()) {
                Timber.w("Failed to delete temp ZIP file: %s", tempZip.absolutePath)
            }
        }
    }

    // ── Extract ───────────────────────────────────────────────────

    /**
     * Extracts a .costbackup bundle to [outputDir].
     *
     * @return the extracted directory containing manifest.json, database.sqlite, files/, checksums.json
     */
    fun extract(
        bundleFile: File,
        outputDir: File,
        password: String,
        encryptionService: BackupEncryptionService = BackupEncryptionService()
    ): Result<ExtractionResult> = runCatching {
        // 1. Open bundle and read/validate header via streaming
        val fis = FileInputStream(bundleFile)
        readHeaderFromStream(fis)
        // fis is now positioned at start of ciphertext (salt + IV + encrypted ZIP)

        // 2. Streaming decrypt (CipherInputStream wraps the remaining stream)
        // The GCM tag is only verified when the stream is fully consumed, so
        // AEADBadTagException may be thrown from any read() call or close().
        val cipherStream = try {
            encryptionService.decryptStream(fis, password)
        } catch (e: javax.crypto.AEADBadTagException) {
            fis.close()
            throw WrongBackupPasswordException("Incorrect password or corrupted data")
        }

        // 3. Verify ZIP magic + extract ZIP (wrapped for late GCM tag failures)
        val extractedFiles = mutableMapOf<String, File>()
        try {
            // Verify ZIP magic by peeking at first 4 bytes, then re-assemble
            val magicBytes = ByteArray(4)
            var magicOffset = 0
            while (magicOffset < 4) {
                val n = cipherStream.read(magicBytes, magicOffset, 4 - magicOffset)
                if (n == -1) {
                    cipherStream.close()
                    throw InvalidBackupFormatException("Decrypted data too short for ZIP magic")
                }
                magicOffset += n
            }
            if (magicBytes[0] != 0x50.toByte() || magicBytes[1] != 0x4B.toByte() ||
                magicBytes[2] != 0x03.toByte() || magicBytes[3] != 0x04.toByte()
            ) {
                cipherStream.close()
                throw InvalidBackupFormatException("Decrypted data is not a valid ZIP archive")
            }

            // 4. Extract ZIP (with ZIP Slip protection)
            // Reassemble: magic bytes (already read) + remaining cipher stream
            outputDir.mkdirs()
            val magicInput = ByteArrayInputStream(magicBytes)
            val fullStream = SequenceInputStream(magicInput, cipherStream)
            val zis = ZipInputStream(fullStream)
            try {
                var entry = zis.nextEntry
                while (entry != null) {
                    // ZIP Slip prevention: resolve against outputDir and verify canonical path
                    val entryName = entry.name.replace('\\', '/')
                    // Reject entries with parent directory traversal
                    if (entryName.contains("..") &&
                        entryName.split("/").any { it == ".." }
                    ) {
                        throw InvalidBackupFormatException(
                            "ZIP entry with path traversal rejected: ${entry.name}"
                        )
                    }
                    val targetFile = File(outputDir, entryName)
                    val canonicalTarget = targetFile.canonicalPath
                    val canonicalOutput = outputDir.canonicalPath
                    if (!canonicalTarget.startsWith(canonicalOutput + File.separator) &&
                        canonicalTarget != canonicalOutput
                    ) {
                        throw InvalidBackupFormatException(
                            "ZIP entry escapes output directory: ${entry.name}"
                        )
                    }
                    if (entry.isDirectory) {
                        targetFile.mkdirs()
                    } else {
                        targetFile.parentFile?.mkdirs()
                        FileOutputStream(targetFile).use { fos ->
                            zis.copyTo(fos)
                        }
                        extractedFiles[entry.name] = targetFile
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            } finally {
                zis.close()
            }
        } catch (e: javax.crypto.AEADBadTagException) {
            throw WrongBackupPasswordException("Incorrect password or corrupted data")
        }

        // 5. Read manifest
        val manifestFile = extractedFiles["manifest.json"]
            ?: throw InvalidBackupFormatException("Missing manifest.json in bundle")
        val manifestJson = JSONObject(manifestFile.readText())
        val manifest = BackupManifest.fromJson(manifestJson)

        // 6. Verify checksums
        val checksumsFile = extractedFiles["checksums.json"]
            ?: throw InvalidBackupFormatException("Missing checksums.json in bundle")
        val checksumsJson = JSONObject(checksumsFile.readText())
        val checksums = ChecksumsManifest.fromJson(checksumsJson)

        val warnings = mutableListOf<String>()
        var checksumsVerified = true
        for ((relPath, expectedHash) in checksums.entries) {
            val file = extractedFiles[relPath]
            if (file == null) {
                val msg = "Missing file in extraction: $relPath"
                warnings += msg
                checksumsVerified = false
                throw ChecksumMismatchException(msg)
            }
            val actualHash = sha256Hex(file)
            if (actualHash != expectedHash) {
                val msg = "Checksum mismatch for $relPath: expected $expectedHash, got $actualHash"
                warnings += msg
                checksumsVerified = false
                throw ChecksumMismatchException(msg)
            }
        }

        val dbFile = extractedFiles["database.sqlite"]
        if (dbFile == null || !dbFile.exists()) {
            throw InvalidBackupFormatException("Missing database.sqlite in extracted bundle")
        }

        Timber.d("Extracted .costbackup bundle to: %s", outputDir.absolutePath)

        ExtractionResult(
            manifest = manifest,
            dbFile = dbFile,
            assetsDir = File(outputDir, "files").takeIf { it.exists() },
            checksumsVerified = checksumsVerified,
            warnings = warnings,
            extractedFiles = extractedFiles
        )
    }

    // ── Internal helpers ──────────────────────────────────────────

    private fun buildZip(
        tempZip: File,
        databaseFile: File,
        receiptFiles: Map<String, File>,
        tableCounts: Map<String, Int>,
        databaseVersion: Int,
        redacted: Boolean,
        includeReceiptImages: Boolean
    ) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(tempZip))).use { zos ->

            // -- manifest.json --
            val manifest = BackupManifest(
                databaseVersion = databaseVersion,
                tableCounts = tableCounts,
                receiptAssetCount = receiptFiles.size,
                options = BackupOptionsManifest(
                    redacted = redacted,
                    includeReceiptImages = includeReceiptImages
                ),
                includes = BackupIncludes(
                    receiptImages = includeReceiptImages && receiptFiles.isNotEmpty()
                )
            )
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toJson().toString(2).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // -- database.sqlite (stream from file) --
            zos.putNextEntry(ZipEntry("database.sqlite"))
            FileInputStream(databaseFile).use { it.copyTo(zos) }
            zos.closeEntry()

            // -- files/receipts/ --
            if (includeReceiptImages) {
                for ((relPath, file) in receiptFiles) {
                    if (!file.exists() || !file.isFile) {
                        Timber.w("Receipt file missing during bundle creation: %s", file.absolutePath)
                        continue
                    }
                    zos.putNextEntry(ZipEntry(relPath))
                    FileInputStream(file).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }

            // -- checksums.json --
            val checksumEntries = mutableMapOf<String, String>().apply {
                put("database.sqlite", sha256Hex(databaseFile))
                if (includeReceiptImages) {
                    for ((relPath, file) in receiptFiles) {
                        if (file.exists() && file.isFile) {
                            put(relPath, sha256Hex(file))
                        }
                    }
                }
            }
            val checksumsManifest = ChecksumsManifest(entries = checksumEntries)
            zos.putNextEntry(ZipEntry("checksums.json"))
            zos.write(checksumsManifest.toJson().toString(2).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
    }

    /**
     * Computes the SHA-256 hex digest of a file.
     */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        FileInputStream(file).use { fis ->
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ── Result type for extraction ─────────────────────────────────

    data class ExtractionResult(
        val manifest: BackupManifest,
        val dbFile: File,
        val assetsDir: File?,
        val checksumsVerified: Boolean,
        val warnings: List<String>,
        val extractedFiles: Map<String, File>
    )
}
