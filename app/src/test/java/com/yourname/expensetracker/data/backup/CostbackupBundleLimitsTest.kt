package com.yourname.expensetracker.data.backup

import com.yourname.expensetracker.data.privacy.BackupEncryptionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
 * The create/extract path is pure JVM (java.io + javax.crypto), so no Robolectric
 * is needed. We build a real bundle then extract it with tight limits.
 */
class CostbackupBundleLimitsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val password = "zip-bomb-test-password"

    /** Deterministic epoch-millis stamped into the bundle manifest `createdAt`. */
    private val nowEpochMs = 1716163200000L // 2024-05-20 00:00 UTC

    /** Builds a real .costbackup whose database.sqlite entry is [dbBytes] bytes. */
    private fun buildBundle(dbBytes: Int): File {
        val dbFile = tmp.newFile("database_source.bin").apply {
            writeBytes(ByteArray(dbBytes) { (it % 251).toByte() })
        }
        val out = File(tmp.root, "bundle.costbackup")
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
    fun `extract rejects entry exceeding per-entry byte limit`() {
        // database.sqlite is 64 KB; cap a single entry at 1 KB.
        val bundle = buildBundle(dbBytes = 64 * 1024)
        val outDir = File(tmp.root, "extract_entry")

        val result = CostbackupBundle.extract(
            bundle, outDir, password,
            nowEpochMs = nowEpochMs,
            limits = CostbackupBundle.ExtractionLimits(maxEntryBytes = 1024)
        )
        assertTrue("extract should fail", result.isFailure)
        assertTrue(
            "should be a BackupTooLargeException",
            result.exceptionOrNull() is CostbackupBundle.BackupTooLargeException
        )
    }

    @Test
    fun `extract rejects total decompressed size exceeding limit`() {
        val bundle = buildBundle(dbBytes = 64 * 1024)
        val outDir = File(tmp.root, "extract_total")

        // Manifest + db + checksums together exceed a 2 KB total cap.
        val result = CostbackupBundle.extract(
            bundle, outDir, password,
            nowEpochMs = nowEpochMs,
            limits = CostbackupBundle.ExtractionLimits(maxTotalDecompressedBytes = 2048)
        )
        assertTrue("extract should fail", result.isFailure)
        assertTrue(
            "should be a BackupTooLargeException",
            result.exceptionOrNull() is CostbackupBundle.BackupTooLargeException
        )
    }

    @Test
    fun `extract rejects too many entries`() {
        val bundle = buildBundle(dbBytes = 1024)
        val outDir = File(tmp.root, "extract_count")

        // The bundle always has at least manifest.json + database.sqlite + checksums.json.
        // A cap of 1 entry must trip the entry-count guard.
        val result = CostbackupBundle.extract(
            bundle, outDir, password,
            nowEpochMs = nowEpochMs,
            limits = CostbackupBundle.ExtractionLimits(maxEntryCount = 1)
        )
        assertTrue("extract should fail", result.isFailure)
        assertTrue(
            "should be a BackupTooLargeException",
            result.exceptionOrNull() is CostbackupBundle.BackupTooLargeException
        )
    }

    @Test
    fun `manifest without createdAt falls back to supplied nowEpochMs`() {
        // Legacy bundles predate the createdAt field; the fallback must be the
        // explicit caller-supplied timestamp — never a hidden wall-clock read.
        val legacyJson = org.json.JSONObject().apply {
            put("backupFormatVersion", 1)
            put("databaseVersion", 1)
            put("tableCounts", org.json.JSONObject(mapOf("expenses" to 1)))
            // No createdAt field
        }

        val manifest = CostbackupBundle.BackupManifest.fromJson(legacyJson, nowEpochMs = nowEpochMs)
        assertEquals(
            "legacy manifest createdAt must fall back to the supplied nowEpochMs",
            nowEpochMs,
            manifest.createdAt
        )
    }

    @Test
    fun `fromJson keeps stored createdAt when present instead of the fallback`() {
        // T2B: the nowEpochMs fallback must only apply when createdAt is missing —
        // a stored value must never be overwritten by an unrelated caller-supplied time.
        val json = org.json.JSONObject().apply {
            put("backupFormatVersion", 1)
            put("databaseVersion", 1)
            put("createdAt", nowEpochMs)
        }

        val manifest = CostbackupBundle.BackupManifest.fromJson(json, nowEpochMs = nowEpochMs + 5L)
        assertEquals(
            "stored createdAt must win over the supplied fallback",
            nowEpochMs,
            manifest.createdAt
        )
    }

    @Test
    fun `extract of legacy bundle without createdAt falls back to the exact supplied nowEpochMs`() {
        // T2B: the extract boundary must thread the caller's explicit nowEpochMs into
        // BackupManifest.fromJson for legacy bundles that predate createdAt. The fallback
        // value must be reproduced verbatim — proving no wall clock is consulted.
        val bundle = buildLegacyBundleWithoutCreatedAt()
        val outDir = File(tmp.root, "extract_legacy")
        val fallback = nowEpochMs + 1001L // distinct from any creation-time value

        val result = CostbackupBundle.extract(bundle, outDir, password, nowEpochMs = fallback)

        assertTrue("legacy bundle extract should succeed", result.isSuccess)
        assertEquals(
            "legacy createdAt fallback must be the exact supplied nowEpochMs",
            fallback,
            result.getOrNull()?.manifest?.createdAt
        )
    }

    @Test
    fun `extract keeps the stored manifest createdAt instead of the extract nowEpochMs fallback`() {
        // T2B: a bundle that already stores createdAt (normal case) must preserve that
        // value even when extract is called with a different nowEpochMs.
        val bundle = buildBundle(dbBytes = 1024) // stored createdAt == nowEpochMs
        val outDir = File(tmp.root, "extract_present")
        val unrelatedNow = nowEpochMs + 999L // different from the stored value

        val result = CostbackupBundle.extract(bundle, outDir, password, nowEpochMs = unrelatedNow)

        assertTrue("extract should succeed", result.isSuccess)
        assertEquals(
            "stored createdAt must win over the extract fallback",
            nowEpochMs,
            result.getOrNull()?.manifest?.createdAt
        )
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
}
