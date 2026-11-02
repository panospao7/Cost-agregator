package com.yourname.expensetracker.data.backup

import com.yourname.expensetracker.testfixtures.backup.lowIterationBackupEncryptionService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.util.concurrent.CancellationException
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

    /**
     * One low-iteration PBKDF2 instance shared by EVERY create/extract/encrypt call in a
     * test (the iteration count is not stored in the envelope, so mixing counts would fail
     * authentication). No checked-in production-count fixture is read by this class.
     */
    private val encryption = lowIterationBackupEncryptionService()

    /** Deterministic epoch-millis stamped into the bundle manifest `createdAt`. */
    private val nowEpochMs = 1716163200000L // 2024-05-20 00:00 UTC

    /**
     * Builds a real .costbackup whose database.sqlite entry is [dbBytes] bytes.
     * [label] keeps file names unique so table rows can build several bundles.
     */
    private fun buildBundle(
        dbBytes: Int,
        label: String = "default",
        semanticAggregates: SemanticAggregateSet? = null
    ): File {
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
            includeReceiptImages = false,
            encryptionService = encryption,
            semanticAggregates = semanticAggregates
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
            encryptionService = encryption,
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
            encryptionService = encryption,
            privacyModeName = "REDACT_RAW_TEXT"
        )
        assertTrue("bundle creation should succeed", created.isSuccess)

        val extracted = CostbackupBundle.extract(
            bundleFile = bundle,
            outputDir = File(tmp.root, "extract_redacted_with_images"),
            password = password,
            nowEpochMs = nowEpochMs,
            encryptionService = encryption
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
            includeReceiptImages = true,
            encryptionService = encryption
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
                encryptionService = encryption,
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
                bundle, outDir, password, nowEpochMs = row.extractNowEpochMs,
                encryptionService = encryption
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
            encryption.encrypt(innerZip, fos, password)
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
            encryptionService = encryption,
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
            bundle, File(tmp.root, "extract_streamed"), password, nowEpochMs = 0L,
            encryptionService = encryption
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
            encryptionService = encryption,
            tempDir = tempDir
        )

        assertTrue("destination write failure must fail the result", result.isFailure)
        assertEquals(
            "temp ZIP must be cleaned up after a destination failure",
            0,
            tempDir.listFiles()?.size ?: 0
        )
    }

    // ── UF-46 / S2: semantic section in the encrypted manifest (INV-5, INV-6, INV-12; AE-6, AE-13) ──

    private fun bucket(n: Int, u: Int, s: BigDecimal, vararg key: String) =
        AggregateBucket(key.toList(), n, u, s)

    /** The entry carries the current definition hash of [id], as a real capture does (INV-10). */
    private fun entry(id: SemanticAggregateId, vararg buckets: AggregateBucket) =
        AggregateEntry(SemanticAggregates.definitionHash(id), buckets.toList())

    /** Hand-built CAPTURED set: includes a u > 0 bucket, a zero-sum bucket and a full binary expansion sum. */
    private fun sampleCapturedSet(): SemanticAggregateSet = SemanticAggregateSet.ofCaptured(
        mapOf(
            SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY to entry(
                SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY,
                bucket(1, 0, BigDecimal("1000"), "DEPOSIT", "EUR"),
                bucket(2, 0, BigDecimal("52.5"), "PURCHASE", "EUR"),
                bucket(1, 0, BigDecimal(9.99), "PURCHASE", "USD"),
                bucket(1, 1, BigDecimal.ZERO, "UNKNOWN", "GBP")
            ),
            SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY to entry(
                SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY,
                bucket(2, 0, BigDecimal("32.5"), "EUR"),
                bucket(1, 0, BigDecimal.ZERO, "USD")
            ),
            SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY to entry(
                SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY,
                bucket(1, 0, BigDecimal("1000"), "EUR")
            ),
            SemanticAggregateId.RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY to entry(
                SemanticAggregateId.RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY,
                bucket(1, 0, BigDecimal("15"), "0", "USD"),
                bucket(1, 0, BigDecimal("9"), "1", "EUR")
            )
        )
    )

    private fun extractManifest(bundle: File, label: String): CostbackupBundle.BackupManifest {
        val result = CostbackupBundle.extract(
            bundle, File(tmp.root, "extract_semantic_$label"), password,
            nowEpochMs = nowEpochMs,
            encryptionService = encryption
        )
        assertTrue("[$label] extract should succeed", result.isSuccess)
        return result.getOrThrow().manifest
    }

    private fun baseManifestJson(): JSONObject = JSONObject().apply {
        put("backupFormatVersion", 1)
        put("databaseVersion", 152)
        put("createdAt", nowEpochMs)
        put("tableCounts", JSONObject(mapOf("expenses" to 6)))
    }

    @Test
    fun `INV5 bundle with a captured section round trips the exact set`() {
        val set = sampleCapturedSet()
        val bundle = buildBundle(dbBytes = 2048, label = "semantic_roundtrip", semanticAggregates = set)

        val manifest = extractManifest(bundle, "roundtrip")

        assertFalse(manifest.semanticSectionMalformed)
        assertNotNull(manifest.semanticAggregates)
        assertEquals(set, manifest.semanticAggregates)
        // The 9.99 bucket must keep the exact binary expansion, not a rounded value.
        val usd = manifest.semanticAggregates!!
            .aggregates.getValue(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY)
            .buckets.single { it.key == listOf("PURCHASE", "USD") }
        assertEquals(0, BigDecimal(9.99).compareTo(usd.s))
        assertEquals(1, manifest.backupFormatVersion)
    }

    @Test
    fun `INV5 bundle without a section yields a null section and no malformed marker`() {
        val bundle = buildBundle(dbBytes = 2048, label = "semantic_absent")

        val manifest = extractManifest(bundle, "absent")

        assertNull(manifest.semanticAggregates)
        assertFalse(manifest.semanticSectionMalformed)
    }

    @Test
    fun `INV5 bundle with a NOT_CAPTURED section round trips its reason and stays empty`() {
        val set = SemanticAggregateSet.ofNotCaptured(SemanticNotCapturedReason.SEMANTIC_BUCKET_LIMIT)
        val bundle = buildBundle(dbBytes = 1024, label = "semantic_not_captured", semanticAggregates = set)

        val manifest = extractManifest(bundle, "not_captured")

        val read = manifest.semanticAggregates
        assertNotNull(read)
        assertEquals(SemanticCaptureStatus.NOT_CAPTURED, read!!.status)
        assertEquals(SemanticNotCapturedReason.SEMANTIC_BUCKET_LIMIT, read.notCapturedReason)
        assertTrue(read.aggregates.isEmpty())
        assertFalse(manifest.semanticSectionMalformed)
    }

    @Test
    fun `INV5 OutputStream create carries the section into an extractable bundle`() {
        val set = sampleCapturedSet()
        val dbFile = tmp.newFile("database_stream_semantic.bin").apply { writeBytes(ByteArray(512) { 7 }) }
        val buffer = java.io.ByteArrayOutputStream()

        val result = CostbackupBundle.create(
            outputStream = buffer,
            databaseFile = dbFile,
            receiptFiles = emptyMap(),
            password = password,
            nowEpochMs = nowEpochMs,
            tableCounts = mapOf("expenses" to 6),
            databaseVersion = 152,
            redacted = true,
            includeReceiptImages = false,
            encryptionService = encryption,
            tempDir = tmp.newFolder(),
            semanticAggregates = set
        )
        assertTrue(result.isSuccess)
        val bundle = File(tmp.root, "streamed_semantic.costbackup").apply { writeBytes(buffer.toByteArray()) }

        assertEquals(set, extractManifest(bundle, "streamed").semanticAggregates)
    }

    @Test
    fun `INV5 plaintext header and format version are unchanged and carry no section text`() {
        val bundle = buildBundle(
            dbBytes = 4096, label = "semantic_header", semanticAggregates = sampleCapturedSet()
        )
        val bytes = bundle.readBytes()

        assertEquals("COSTBACKUP1", String(bytes, 0, 11, Charsets.US_ASCII))
        assertEquals("format version high byte", 0.toByte(), bytes[11])
        assertEquals("format version low byte (FORMAT_VERSION must stay 1)", 1.toByte(), bytes[12])
        assertEquals(13, CostbackupBundle.HEADER_SIZE)
        val everything = String(bytes, Charsets.ISO_8859_1)
        assertFalse(everything.contains("semanticAggregates"))
        assertFalse(everything.contains("EXPENSE_RAW_BY_TYPE_CURRENCY"))
        assertEquals(
            "backupFormatVersion must stay 1 with a section present",
            1,
            extractManifest(bundle, "header").backupFormatVersion
        )
    }

    @Test
    fun `INV5 toJson writes the section only when present and sums stay JSON strings`() {
        val without = CostbackupBundle.BackupManifest(databaseVersion = 152, createdAt = nowEpochMs)
        assertFalse(without.toJson().has("semanticAggregates"))

        val with = CostbackupBundle.BackupManifest(
            databaseVersion = 152, createdAt = nowEpochMs, semanticAggregates = sampleCapturedSet()
        ).toJson()
        assertTrue(with.has("semanticAggregates"))
        val bucket = with.getJSONObject("semanticAggregates")
            .getJSONObject("aggregates")
            .getJSONObject("INCOME_EFFECTIVE_BY_CURRENCY")
            .getJSONArray("buckets").getJSONObject(0)
        assertTrue("s must be serialised as a JSON string", bucket.opt("s") is String)
        assertEquals("1000", bucket.getString("s"))
    }

    @Test
    fun `INV12 malformed marker is never serialised`() {
        val manifest = CostbackupBundle.BackupManifest(
            databaseVersion = 152, createdAt = nowEpochMs, semanticSectionMalformed = true
        )

        assertFalse(manifest.toJson().has("semanticAggregates"))
    }

    @Test
    fun `INV12 fromJson of a text manifest round trips the section`() {
        val set = sampleCapturedSet()
        val text = CostbackupBundle.BackupManifest(
            databaseVersion = 152, createdAt = nowEpochMs, semanticAggregates = set
        ).toJson().toString()

        val manifest = CostbackupBundle.BackupManifest.fromJson(JSONObject(text), nowEpochMs)

        assertEquals(set, manifest.semanticAggregates)
        assertFalse(manifest.semanticSectionMalformed)
    }

    @Test
    fun `INV12 fromJson ignores unknown keys at manifest and section level`() {
        val json = CostbackupBundle.BackupManifest(
            databaseVersion = 152, createdAt = nowEpochMs, semanticAggregates = sampleCapturedSet()
        ).toJson()
        json.put("futureTopLevelKey", JSONObject().put("anything", 1))
        json.getJSONObject("semanticAggregates").put("futureSectionKey", "ignored")

        val manifest = CostbackupBundle.BackupManifest.fromJson(json, nowEpochMs)

        assertEquals(sampleCapturedSet(), manifest.semanticAggregates)
        assertFalse(manifest.semanticSectionMalformed)
        assertEquals(152, manifest.databaseVersion)
    }

    @Test
    fun `INV12 legacy manifest without the key has a null section and no marker`() {
        val manifest = CostbackupBundle.BackupManifest.fromJson(baseManifestJson(), nowEpochMs)

        assertNull(manifest.semanticAggregates)
        assertFalse(manifest.semanticSectionMalformed)
    }

    @Test
    fun `AE13 unknown section version parses as a non malformed set that is never interpreted`() {
        val json = baseManifestJson().put(
            "semanticAggregates",
            JSONObject().put("version", 99).put("status", "CAPTURED")
                .put("aggregates", JSONObject().put("NOT_A_REGISTERED_ID", 5))
        )

        val manifest = CostbackupBundle.BackupManifest.fromJson(json, nowEpochMs)

        assertFalse(manifest.semanticSectionMalformed)
        assertEquals(99, manifest.semanticAggregates?.version)
        assertTrue(manifest.semanticAggregates!!.aggregates.isEmpty())
    }

    private class MalformedSectionRow(val name: String, val manifest: () -> JSONObject)

    @Test
    fun `AE13 malformed section sets the marker and fromJson never throws`() {
        fun mutated(change: (JSONObject) -> Unit): JSONObject {
            val section = sampleCapturedSet().toJson()
            change(section)
            return baseManifestJson().put("semanticAggregates", section)
        }
        fun firstBucket(section: JSONObject): JSONObject = section.getJSONObject("aggregates")
            .getJSONObject("SPENDING_EFFECTIVE_BY_CURRENCY").getJSONArray("buckets").getJSONObject(0)

        val rows = listOf(
            MalformedSectionRow("section is a string") { baseManifestJson().put("semanticAggregates", "oops") },
            MalformedSectionRow("section is a number") { baseManifestJson().put("semanticAggregates", 5) },
            MalformedSectionRow("section is a boolean") { baseManifestJson().put("semanticAggregates", true) },
            MalformedSectionRow("section is an array") { baseManifestJson().put("semanticAggregates", JSONArray()) },
            MalformedSectionRow("section is JSON null") {
                baseManifestJson().put("semanticAggregates", JSONObject.NULL)
            },
            MalformedSectionRow("section is JSON null parsed from text") {
                JSONObject("{\"databaseVersion\":152,\"semanticAggregates\":null}")
            },
            MalformedSectionRow("section is an empty object") {
                baseManifestJson().put("semanticAggregates", JSONObject())
            },
            MalformedSectionRow("negative n") { mutated { firstBucket(it).put("n", -1) } },
            MalformedSectionRow("sum written as a JSON number") { mutated { firstBucket(it).put("s", 32.5) } },
            MalformedSectionRow("captured with three ids") {
                mutated { it.getJSONObject("aggregates").remove("INCOME_EFFECTIVE_BY_CURRENCY") }
            },
            MalformedSectionRow("not captured with aggregates present") {
                mutated { it.put("status", "NOT_CAPTURED") }
            }
        )
        assertEquals("row names must be unique", rows.size, rows.map { it.name }.toSet().size)

        // Control: the same builder with an untouched section is accepted.
        val control = CostbackupBundle.BackupManifest.fromJson(mutated { }, nowEpochMs)
        assertFalse(control.semanticSectionMalformed)
        assertNotNull(control.semanticAggregates)

        for (row in rows) {
            val manifest = CostbackupBundle.BackupManifest.fromJson(row.manifest(), nowEpochMs)

            assertTrue("[${row.name}] marker must be set", manifest.semanticSectionMalformed)
            assertNull("[${row.name}] no set may be returned", manifest.semanticAggregates)
            assertEquals("[${row.name}] other fields still parse", 152, manifest.databaseVersion)
        }
    }

    @Test
    fun `AE13 malformed section inside an encrypted bundle still extracts with the marker`() {
        val dbFile = tmp.newFile("database_malformed_section.bin").apply { writeBytes(ByteArray(64) { 3 }) }
        val innerZip = File(tmp.root, "malformed_section_inner.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(innerZip))).use { zos ->
            val manifest = baseManifestJson().put("semanticAggregates", "not an object")
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("database.sqlite"))
            dbFile.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
            val checksums = JSONObject().put(
                "entries", JSONObject(mapOf("database.sqlite" to CostbackupBundle.sha256Hex(dbFile)))
            )
            zos.putNextEntry(ZipEntry("checksums.json"))
            zos.write(checksums.toString(2).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val bundle = File(tmp.root, "malformed_section.costbackup")
        FileOutputStream(bundle).use { fos ->
            fos.write("COSTBACKUP1".toByteArray(Charsets.US_ASCII))
            fos.write(byteArrayOf(0x00, 0x01))
            encryption.encrypt(innerZip, fos, password)
        }

        val manifest = extractManifest(bundle, "malformed")

        assertTrue(manifest.semanticSectionMalformed)
        assertNull(manifest.semanticAggregates)
    }

    // ── C0 self-check (INV-6, AE-6) ──────────────────────────────

    @Test
    fun `INV6 self check of a well formed set is Verified at checkpoint C0`() {
        val result = CostbackupBundle.selfCheckSemanticSection(sampleCapturedSet())

        assertTrue("expected Verified but was ${result.javaClass.simpleName}", result is SemanticCheckResult.Verified)
        assertEquals(SemanticCheckpoint.C0_BACKUP_SELF_CHECK, result.checkpoint)
    }

    private fun assertSelfCheckFailed(label: String, result: SemanticCheckResult) {
        assertTrue("[$label] expected Failed but was ${result.javaClass.simpleName}", result is SemanticCheckResult.Failed)
        assertEquals(label, SemanticCheckpoint.C0_BACKUP_SELF_CHECK, result.checkpoint)
    }

    @Test
    fun `AE6 serializer writing sums as JSON numbers fails the self check with the self check code`() {
        val numericSums: (SemanticAggregateSet) -> String = { set ->
            val json = set.toJson()
            val aggregates = json.getJSONObject("aggregates")
            for (name in aggregates.keys()) {
                val buckets = aggregates.getJSONObject(name).getJSONArray("buckets")
                for (i in 0 until buckets.length()) buckets.getJSONObject(i).put("s", 12.5)
            }
            json.toString()
        }

        val result = CostbackupBundle.selfCheckSemanticSection(sampleCapturedSet(), numericSums)

        assertSelfCheckFailed("numeric s", result)
        assertEquals(
            SemanticCode.SEMANTIC_SELF_CHECK_FAILED,
            (result as SemanticCheckResult.Failed).code
        )
    }

    @Test
    fun `AE6 serializer that loses data or breaks fails the self check without throwing`() {
        val set = sampleCapturedSet()
        val droppedBucket: (SemanticAggregateSet) -> String = { s ->
            val json = s.toJson()
            json.getJSONObject("aggregates").getJSONObject("EXPENSE_RAW_BY_TYPE_CURRENCY")
                .put("buckets", JSONArray())
            json.toString()
        }
        val alteredSum: (SemanticAggregateSet) -> String = { s ->
            val json = s.toJson()
            json.getJSONObject("aggregates").getJSONObject("INCOME_EFFECTIVE_BY_CURRENCY")
                .getJSONArray("buckets").getJSONObject(0).put("s", "1000.01")
            json.toString()
        }
        val rows: List<Pair<String, (SemanticAggregateSet) -> String>> = listOf(
            "bucket dropped" to droppedBucket,
            "sum altered by one cent" to alteredSum,
            "text is not JSON" to { _: SemanticAggregateSet -> "this is not json" },
            "serializer throws" to { _: SemanticAggregateSet -> throw IllegalStateException("12.5 EUR /secret/path") }
        )

        for ((label, serializer) in rows) {
            val result = CostbackupBundle.selfCheckSemanticSection(set, serializer)
            assertSelfCheckFailed(label, result)
        }
    }

    @Test
    fun `INV6 m3 self check passes for an identical NOT_CAPTURED set at C0`() {
        val set = SemanticAggregateSet.ofNotCaptured(SemanticNotCapturedReason.SEMANTIC_CAPTURE_FAILED)

        val result = CostbackupBundle.selfCheckSemanticSection(set)

        assertTrue("expected Verified but was ${result.javaClass.simpleName}", result is SemanticCheckResult.Verified)
        assertEquals(SemanticCheckpoint.C0_BACKUP_SELF_CHECK, result.checkpoint)
    }

    @Test
    fun `INV6 m3 serialized section differing only in definitionHash fails the self check`() {
        val set = sampleCapturedSet()
        val hashAltered: (SemanticAggregateSet) -> String = { s ->
            val json = s.toJson()
            val entry = json.getJSONObject("aggregates").getJSONObject("INCOME_EFFECTIVE_BY_CURRENCY")
            val original = entry.getString("definitionHash")
            entry.put("definitionHash", original + "x")
            json.toString()
        }

        val result = CostbackupBundle.selfCheckSemanticSection(set, hashAltered)

        assertSelfCheckFailed("hash only", result)
        assertEquals(SemanticCode.SEMANTIC_SELF_CHECK_FAILED, (result as SemanticCheckResult.Failed).code)
    }

    @Test
    fun `INV6 m3 serialized section differing only in version fails the self check`() {
        val set = sampleCapturedSet()
        val versionAltered: (SemanticAggregateSet) -> String = { s ->
            val json = s.toJson()
            json.put("version", s.version + 1)
            json.toString()
        }

        val result = CostbackupBundle.selfCheckSemanticSection(set, versionAltered)

        assertSelfCheckFailed("version only", result)
        assertEquals(SemanticCode.SEMANTIC_SELF_CHECK_FAILED, (result as SemanticCheckResult.Failed).code)
    }

    @Test
    fun `INV6 m3 serialized section differing only in notCapturedReason fails the self check`() {
        val set = SemanticAggregateSet.ofNotCaptured(SemanticNotCapturedReason.SEMANTIC_CAPTURE_FAILED)
        val reasonAltered: (SemanticAggregateSet) -> String = { s ->
            val json = s.toJson()
            json.put("notCapturedReason", SemanticNotCapturedReason.SEMANTIC_BUCKET_LIMIT.name)
            json.toString()
        }

        val result = CostbackupBundle.selfCheckSemanticSection(set, reasonAltered)

        assertSelfCheckFailed("reason only", result)
        assertEquals(SemanticCode.SEMANTIC_SELF_CHECK_FAILED, (result as SemanticCheckResult.Failed).code)
    }

    @Test
    fun `AE6 self check rethrows cancellation from the serializer with identity`() {
        val cancellation = CancellationException("cancelled during self check")

        val thrown = assertThrows(CancellationException::class.java) {
            CostbackupBundle.selfCheckSemanticSection(sampleCapturedSet()) { throw cancellation }
        }

        assertSame(cancellation, thrown)
    }
}
