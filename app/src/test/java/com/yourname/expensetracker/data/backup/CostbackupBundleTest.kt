package com.yourname.expensetracker.data.backup

import com.yourname.expensetracker.data.privacy.BackupEncryptionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * P7-CURRENT-023 — [CostbackupBundle.extract] must enforce decompressed-size and
 * entry-count limits so a malicious (but correctly encrypted + password-valid)
 * bundle cannot fill storage after passing header/password validation.
 *
 * Also covers bundle create/extract round trips, manifest `createdAt` provenance
 * (stored value vs caller-supplied fallback) and the OutputStream create variant.
 * (Formerly CostbackupBundleLimitsTest; consolidated in B6-21.)
 *
 * The create/extract path is pure JVM (java.io + javax.crypto), so no Robolectric
 * is needed. We build a real bundle then extract it with tight limits.
 */
class CostbackupBundleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val password = "zip-bomb-test-password"

    /** Deterministic epoch-millis stamped into the bundle manifest `createdAt`. */
    private val nowEpochMs = 1716163200000L // 2024-05-20 00:00 UTC

    /**
     * Builds a real .costbackup whose database.sqlite entry is [dbBytes] bytes.
     * [label] keeps file names unique so table rows can build several bundles.
     */
    private fun buildBundle(dbBytes: Int, label: String = "default"): File {
        val dbFile = tmp.newFile("database_source_$label.bin").apply {
            writeBytes(ByteArray(dbBytes) { (it % 251).toByte() })
        }
        val out = File(tmp.root, "bundle_$label.costbackup")
        val result = CostbackupBundle.create(
            outputFile = out,
            databaseFile = dbFile,
            receiptFiles = emptyMap(),
            password = password,
            nowEpochMs = nowEpochMs,
            tableCounts = mapOf("expenses" to 1),
            databaseVersion = 1,
            redacted = true,
            includeReceiptImages = false
        )
        assertTrue("bundle creation should succeed", result.isSuccess)
        return out
    }

    @Test
    fun `extract within limits succeeds`() {
        val bundle = buildBundle(dbBytes = 4096)
        val outDir = File(tmp.root, "extract_ok")

        val result = CostbackupBundle.extract(
            bundle, outDir, password,
            nowEpochMs = nowEpochMs,
            limits = CostbackupBundle.ExtractionLimits(
                maxTotalDecompressedBytes = 10L * 1024 * 1024,
                maxEntryBytes = 10L * 1024 * 1024,
                maxEntryCount = 1000
            )
        )
        assertTrue("extract should succeed within generous limits", result.isSuccess)
        assertEquals(
            "manifest createdAt must equal the supplied deterministic nowEpochMs",
            nowEpochMs,
            result.getOrNull()?.manifest?.createdAt
        )
    }

    @Test
    fun `redacted image-inclusive bundle keeps receipt assets and manifest flags aligned`() {
        val dbFile = tmp.newFile("database_with_receipt.bin").apply {
            writeBytes(ByteArray(128) { (it % 251).toByte() })
        }
        val receiptFile = tmp.newFile("receipt.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val bundle = File(tmp.root, "redacted_with_images.costbackup")

        val created = CostbackupBundle.create(
            outputFile = bundle,
            databaseFile = dbFile,
            receiptFiles = mapOf("files/receipts/receipt.jpg" to receiptFile),
            password = password,
            nowEpochMs = nowEpochMs,
            tableCounts = mapOf("expenses" to 1),
            databaseVersion = 1,
            redacted = true,
            includeReceiptImages = true,
            privacyModeName = "REDACT_RAW_TEXT"
        )
        assertTrue("bundle creation should succeed", created.isSuccess)

        val extracted = CostbackupBundle.extract(
            bundleFile = bundle,
            outputDir = File(tmp.root, "extract_redacted_with_images"),
            password = password,
            nowEpochMs = nowEpochMs
        )

        assertTrue("bundle extraction should succeed", extracted.isSuccess)
        val result = extracted.getOrNull()!!
        assertTrue(result.manifest.options.redacted)
        assertTrue(result.manifest.options.includeReceiptImages)
        assertTrue(result.manifest.includes.receiptImages)
        assertEquals(1, result.manifest.receiptAssetCount)
        assertTrue(result.extractedFiles.containsKey("files/receipts/receipt.jpg"))
    }

    @Test
    fun `bundle creation fails when a required receipt asset is missing`() {
        val dbFile = tmp.newFile("database_missing_receipt.bin").apply {
            writeBytes(ByteArray(128) { (it % 251).toByte() })
        }
        val missingReceipt = File(tmp.root, "receipt_missing.jpg")

        val result = CostbackupBundle.create(
            outputFile = File(tmp.root, "missing_receipt.costbackup"),
            databaseFile = dbFile,
            receiptFiles = mapOf("files/receipts/receipt_missing.jpg" to missingReceipt),
            password = password,
            nowEpochMs = nowEpochMs,
            tableCounts = mapOf("expenses" to 1),
            databaseVersion = 1,
            redacted = false,
            includeReceiptImages = true
        )

        assertTrue("required receipt asset failure must fail the bundle", result.isFailure)
        assertEquals("RECEIPT_ASSET_MISSING", result.exceptionOrNull()?.message)
    }

    private class LimitRejectionRow(
        val name: String,
        val dbBytes: Int,
        val limits: CostbackupBundle.ExtractionLimits
    )

    @Test
    fun `extract rejects bundles exceeding per-entry, total-size and entry-count limits`() {
        val rows = listOf(
            // database.sqlite is 64 KB; cap a single entry at 1 KB.
            LimitRejectionRow(
                name = "entry exceeding per-entry byte limit",
                dbBytes = 64 * 1024,
                limits = CostbackupBundle.ExtractionLimits(maxEntryBytes = 1024)
            ),
            // Manifest + db + checksums together exceed a 2 KB total cap.
            LimitRejectionRow(
                name = "total decompressed size exceeding limit",
                dbBytes = 64 * 1024,
                limits = CostbackupBundle.ExtractionLimits(maxTotalDecompressedBytes = 2048)
            ),
            // The bundle always has at least manifest.json + database.sqlite + checksums.json.
            // A cap of 1 entry must trip the entry-count guard.
            LimitRejectionRow(
                name = "too many entries",
                dbBytes = 1024,
                limits = CostbackupBundle.ExtractionLimits(maxEntryCount = 1)
            )
        )
        assertEquals("table must keep all three limit boundaries", 3, rows.size)
        assertEquals("row names must be unique", rows.size, rows.map { it.name }.toSet().size)

        rows.forEachIndexed { index, row ->
            val bundle = buildBundle(dbBytes = row.dbBytes, label = "limit_$index")
            val outDir = File(tmp.root, "extract_limit_$index")

            val result = CostbackupBundle.extract(
                bundle, outDir, password,
                nowEpochMs = nowEpochMs,
                limits = row.limits
            )
            assertTrue("[${row.name}] extract should fail", result.isFailure)
            assertTrue(
                "[${row.name}] should be a BackupTooLargeException",
                result.exceptionOrNull() is CostbackupBundle.BackupTooLargeException
            )
        }
    }

    private class FromJsonCreatedAtRow(
        val name: String,
        val json: () -> org.json.JSONObject,
        val suppliedNowEpochMs: Long,
        val expectedCreatedAt: Long,
        val message: String
    )

    @Test
    fun `fromJson uses stored createdAt when present and the supplied nowEpochMs only when missing`() {
        val rows = listOf(
            // Legacy bundles predate the createdAt field; the fallback must be the
            // explicit caller-supplied timestamp — never a hidden wall-clock read.
            FromJsonCreatedAtRow(
                name = "manifest without createdAt",
                json = {
                    org.json.JSONObject().apply {
                        put("backupFormatVersion", 1)
                        put("databaseVersion", 1)
                        put("tableCounts", org.json.JSONObject(mapOf("expenses" to 1)))
                        // No createdAt field
                    }
                },
                suppliedNowEpochMs = nowEpochMs,
                expectedCreatedAt = nowEpochMs,
                message = "legacy manifest createdAt must fall back to the supplied nowEpochMs"
            ),
            // T2B: the nowEpochMs fallback must only apply when createdAt is missing —
            // a stored value must never be overwritten by an unrelated caller-supplied time.
            FromJsonCreatedAtRow(
                name = "manifest with stored createdAt",
                json = {
                    org.json.JSONObject().apply {
                        put("backupFormatVersion", 1)
                        put("databaseVersion", 1)
                        put("createdAt", nowEpochMs)
                    }
                },
                suppliedNowEpochMs = nowEpochMs + 5L,
                expectedCreatedAt = nowEpochMs,
                message = "stored createdAt must win over the supplied fallback"
            )
        )
        assertEquals("table must keep both createdAt rows", 2, rows.size)
        assertEquals("row names must be unique", rows.size, rows.map { it.name }.toSet().size)

        for (row in rows) {
            val manifest = CostbackupBundle.BackupManifest.fromJson(
                row.json(),
                nowEpochMs = row.suppliedNowEpochMs
            )
            assertEquals("[${row.name}] ${row.message}", row.expectedCreatedAt, manifest.createdAt)
        }
    }

    private class ExtractCreatedAtRow(
        val name: String,
        val buildBundle: () -> File,
        val extractNowEpochMs: Long,
        val expectedCreatedAt: Long,
        val successMessage: String,
        val createdAtMessage: String
    )

    @Test
    fun `extract threads the supplied nowEpochMs only for legacy bundles and keeps stored createdAt otherwise`() {
        val legacyFallback = nowEpochMs + 1001L // distinct from any creation-time value
        val rows = listOf(
            // T2B: the extract boundary must thread the caller's explicit nowEpochMs into
            // BackupManifest.fromJson for legacy bundles that predate createdAt. The fallback
            // value must be reproduced verbatim — proving no wall clock is consulted.
            ExtractCreatedAtRow(
                name = "legacy bundle without createdAt",
                buildBundle = { buildLegacyBundleWithoutCreatedAt() },
                extractNowEpochMs = legacyFallback,
                expectedCreatedAt = legacyFallback,
                successMessage = "legacy bundle extract should succeed",
                createdAtMessage = "legacy createdAt fallback must be the exact supplied nowEpochMs"
            ),
            // T2B: a bundle that already stores createdAt (normal case) must preserve that
            // value even when extract is called with a different nowEpochMs.
            ExtractCreatedAtRow(
                name = "bundle with stored createdAt",
                buildBundle = { buildBundle(dbBytes = 1024, label = "stored_created_at") },
                extractNowEpochMs = nowEpochMs + 999L, // different from the stored value
                expectedCreatedAt = nowEpochMs,
                successMessage = "extract should succeed",
                createdAtMessage = "stored createdAt must win over the extract fallback"
            )
        )
        assertEquals("table must keep both createdAt rows", 2, rows.size)
        assertEquals("row names must be unique", rows.size, rows.map { it.name }.toSet().size)

        rows.forEachIndexed { index, row ->
            val bundle = row.buildBundle()
            val outDir = File(tmp.root, "extract_created_at_$index")

            val result = CostbackupBundle.extract(
                bundle, outDir, password, nowEpochMs = row.extractNowEpochMs
            )

            assertTrue("[${row.name}] ${row.successMessage}", result.isSuccess)
            assertEquals(
                "[${row.name}] ${row.createdAtMessage}",
                row.expectedCreatedAt,
                result.getOrNull()?.manifest?.createdAt
            )
        }
    }

    /**
     * Builds a legacy-format .costbackup whose manifest.json intentionally omits the
     * `createdAt` field (predates the field). Header + AES-GCM encryption mirror
     * [CostbackupBundle.create] so [CostbackupBundle.extract] can read it.
     */
    private fun buildLegacyBundleWithoutCreatedAt(): File {
        val dbFile = tmp.newFile("legacy_database.bin").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        }
        val innerZip = File(tmp.root, "legacy_bundle_inner.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(innerZip))).use { zos ->
            val manifest = org.json.JSONObject().apply {
                put("backupFormatVersion", 1)
                put("databaseVersion", 1)
                put("tableCounts", org.json.JSONObject(mapOf("expenses" to 1)))
                // Intentionally no createdAt — this is the legacy format
            }
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("database.sqlite"))
            dbFile.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()

            val checksums = org.json.JSONObject().apply {
                put("entries", org.json.JSONObject(mapOf("database.sqlite" to CostbackupBundle.sha256Hex(dbFile))))
            }
            zos.putNextEntry(ZipEntry("checksums.json"))
            zos.write(checksums.toString(2).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val out = File(tmp.root, "legacy_bundle.costbackup")
        FileOutputStream(out).use { fos ->
            // Header: magic (11B) + format version (2B big-endian uint16 = 1)
            fos.write("COSTBACKUP1".toByteArray(Charsets.US_ASCII))
            fos.write(byteArrayOf(0x00, 0x01))
            BackupEncryptionService().encrypt(innerZip, fos, password)
        }
        return out
    }

    @Test
    fun `default limits are sane`() {
        assertEquals(2L * 1024 * 1024 * 1024, CostbackupBundle.DEFAULT_MAX_TOTAL_DECOMPRESSED_BYTES)
        assertEquals(1L * 1024 * 1024 * 1024, CostbackupBundle.DEFAULT_MAX_ENTRY_BYTES)
        assertEquals(100_000, CostbackupBundle.DEFAULT_MAX_ENTRY_COUNT)
    }

    // ── RP-03A (P7-003): OutputStream (SAF destination) create variant ──

    @Test
    fun `create with OutputStream produces an extractable bundle with identical envelope`() {
        val dbFile = tmp.newFile("database_stream_src.bin").apply {
            writeBytes(ByteArray(8192) { (it % 251).toByte() })
        }
        val buffer = java.io.ByteArrayOutputStream()

        val result = CostbackupBundle.create(
            outputStream = buffer,
            databaseFile = dbFile,
            receiptFiles = emptyMap(),
            password = password,
            nowEpochMs = nowEpochMs,
            tableCounts = mapOf("expenses" to 2),
            databaseVersion = 7,
            redacted = true,
            includeReceiptImages = false,
            tempDir = tmp.newFolder()
        )
        assertTrue("streaming bundle creation should succeed", result.isSuccess)

        val bytes = buffer.toByteArray()
        assertTrue("streamed bundle must carry the COSTBACKUP1 header", bytes.size > 0)
        assertEquals(
            "COSTBACKUP1",
            String(bytes, 0, 11, Charsets.US_ASCII)
        )

        // The streamed bytes must extract like a File-produced bundle.
        val bundle = File(tmp.root, "streamed_bundle.costbackup").apply { writeBytes(bytes) }
        val extraction = CostbackupBundle.extract(
            bundle, File(tmp.root, "extract_streamed"), password, nowEpochMs = 0L
        )
        assertTrue("streamed bundle should extract", extraction.isSuccess)
        assertEquals("stored createdAt must survive the streaming path", nowEpochMs, extraction.getOrNull()?.manifest?.createdAt)
        assertEquals(7, extraction.getOrNull()?.manifest?.databaseVersion)
    }

    @Test
    fun `create with OutputStream fails and cleans the temp zip when the destination throws`() {
        val dbFile = tmp.newFile("database_stream_fail.bin").apply {
            writeBytes(ByteArray(1024) { (it % 127).toByte() })
        }
        val tempDir = tmp.newFolder()
        val throwingStream = object : java.io.OutputStream() {
            override fun write(b: Int) {
                throw java.io.IOException("destination write failed")
            }
        }

        val result = CostbackupBundle.create(
            outputStream = throwingStream,
            databaseFile = dbFile,
            receiptFiles = emptyMap(),
            password = password,
            nowEpochMs = nowEpochMs,
            tableCounts = emptyMap(),
            databaseVersion = 1,
            redacted = true,
            includeReceiptImages = false,
            tempDir = tempDir
        )

        assertTrue("destination write failure must fail the result", result.isFailure)
        assertEquals(
            "temp ZIP must be cleaned up after a destination failure",
            0,
            tempDir.listFiles()?.size ?: 0
        )
    }
}
