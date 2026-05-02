package com.yourname.expensetracker.data.backup

import com.yourname.expensetracker.data.privacy.BackupEncryptionService
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
 *   salt (16B)
 *   iv (12B)
 *   [ciphertext...]
 *
 * Inside the ciphertext is a standard ZIP archive.
 */
object CostbackupBundle {

    private const val MAGIC = "COSTBACKUP1"
    private const val FORMAT_VERSION: UShort = 1u
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12

    private const val HEADER_SIZE = 10 + 2 + SALT_LENGTH + IV_LENGTH

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
     * Writes the .costbackup header (magic, version, salt, IV) to [stream],
     * then returns the salt and IV so the caller can re-use them for encryption.
     */
    private fun writeHeader(stream: FileOutputStream): Pair<ByteArray, ByteArray> {
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }

        stream.write(MAGIC.toByteArray(Charsets.US_ASCII))
        stream.write(byteArrayOf(
            (FORMAT_VERSION.toInt() shr 8).toByte(),
            FORMAT_VERSION.toInt().toByte()
        ))
        stream.write(salt)
        stream.write(iv)

        return Pair(salt, iv)
    }

    /**
     * Reads and validates the .costbackup header from [bytes].
     * Returns (salt, iv, remainingCiphertext).
     */
    fun readHeader(bytes: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
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

        // Salt
        val salt = bytes.copyOfRange(offset, offset + SALT_LENGTH)
        offset += SALT_LENGTH

        // IV
        val iv = bytes.copyOfRange(offset, offset + IV_LENGTH)
        offset += IV_LENGTH

        // Remaining ciphertext
        val ciphertext = bytes.copyOfRange(offset, bytes.size)

        return Triple(salt, iv, ciphertext)
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
        // 1. Build ZIP in memory
        val zipBytes = buildZip(databaseFile, receiptFiles, tableCounts, databaseVersion, redacted, includeReceiptImages)

        // 2. Encrypt with user password
        val encrypted = encryptionService.encrypt(zipBytes, password)

        // 3. Write header + ciphertext to output file
        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { fos ->
            writeHeader(fos)
            fos.write(encrypted)
        }

        Timber.d("Created .costbackup bundle: %s (%d bytes)", outputFile.absolutePath, outputFile.length())
        outputFile
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
        val bundleBytes = bundleFile.readBytes()

        // 1. Read and validate header
        val (salt, iv, ciphertext) = readHeader(bundleBytes)

        // 2. Decrypt
        val zipBytes = try {
            encryptionService.decrypt(ciphertext, password)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw WrongBackupPasswordException("Incorrect password or corrupted data")
        }

        // 3. Verify ZIP magic
        if (zipBytes.size < 4 ||
            zipBytes[0] != 0x50.toByte() ||
            zipBytes[1] != 0x4B.toByte() ||
            zipBytes[2] != 0x03.toByte() ||
            zipBytes[3] != 0x04.toByte()
        ) {
            throw InvalidBackupFormatException("Decrypted data is not a valid ZIP archive")
        }

        // 4. Extract ZIP
        outputDir.mkdirs()
        val extractedFiles = mutableMapOf<String, File>()
        val zis = ZipInputStream(zipBytes.inputStream())
        try {
            var entry = zis.nextEntry
            while (entry != null) {
                val targetFile = File(outputDir, entry.name)
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
                warnings += "Missing file in extraction: $relPath"
                checksumsVerified = false
                continue
            }
            val actualHash = sha256Hex(file)
            if (actualHash != expectedHash) {
                warnings += "Checksum mismatch for $relPath: expected $expectedHash, got $actualHash"
                checksumsVerified = false
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
        databaseFile: File,
        receiptFiles: Map<String, File>,
        tableCounts: Map<String, Int>,
        databaseVersion: Int,
        redacted: Boolean,
        includeReceiptImages: Boolean
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->

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

            // -- database.sqlite --
            zos.putNextEntry(ZipEntry("database.sqlite"))
            databaseFile.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()

            // -- files/receipts/ --
            if (includeReceiptImages) {
                for ((relPath, file) in receiptFiles) {
                    if (!file.exists() || !file.isFile) {
                        Timber.w("Receipt file missing during bundle creation: %s", file.absolutePath)
                        continue
                    }
                    zos.putNextEntry(ZipEntry(relPath))
                    file.inputStream().use { it.copyTo(zos) }
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
        return baos.toByteArray()
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
