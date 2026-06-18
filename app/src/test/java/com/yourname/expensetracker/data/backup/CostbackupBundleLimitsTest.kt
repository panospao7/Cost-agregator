package com.yourname.expensetracker.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
            limits = CostbackupBundle.ExtractionLimits(
                maxTotalDecompressedBytes = 10L * 1024 * 1024,
                maxEntryBytes = 10L * 1024 * 1024,
                maxEntryCount = 1000
            )
        )
        assertTrue("extract should succeed within generous limits", result.isSuccess)
    }

    @Test
    fun `extract rejects entry exceeding per-entry byte limit`() {
        // database.sqlite is 64 KB; cap a single entry at 1 KB.
        val bundle = buildBundle(dbBytes = 64 * 1024)
        val outDir = File(tmp.root, "extract_entry")

        val result = CostbackupBundle.extract(
            bundle, outDir, password,
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
            limits = CostbackupBundle.ExtractionLimits(maxEntryCount = 1)
        )
        assertTrue("extract should fail", result.isFailure)
        assertTrue(
            "should be a BackupTooLargeException",
            result.exceptionOrNull() is CostbackupBundle.BackupTooLargeException
        )
    }

    @Test
    fun `default limits are sane`() {
        assertEquals(2L * 1024 * 1024 * 1024, CostbackupBundle.DEFAULT_MAX_TOTAL_DECOMPRESSED_BYTES)
        assertEquals(1L * 1024 * 1024 * 1024, CostbackupBundle.DEFAULT_MAX_ENTRY_BYTES)
        assertEquals(100_000, CostbackupBundle.DEFAULT_MAX_ENTRY_COUNT)
    }
}
