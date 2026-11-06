package com.yourname.expensetracker.data.backup

import com.yourname.expensetracker.testfixtures.backup.lowIterationBackupEncryptionService
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * FL-07: the optional `homeCurrency` manifest key. [CostbackupBundle.BackupManifest.fromJson] must
 * never throw on it, must drop anything that is not a 3-letter code (after trim + uppercase), and
 * the value must survive a real create/extract round trip. Synthetic data only.
 */
class CostbackupBundleHomeCurrencyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val encryption = lowIterationBackupEncryptionService()
    private val nowEpochMs = 1716163200000L
    private val password = "fl07-home-currency-password"

    private fun manifestJson(configure: JSONObject.() -> Unit = {}): JSONObject =
        JSONObject().apply {
            put("databaseVersion", 1)
            put("createdAt", nowEpochMs)
            configure()
        }

    private fun parse(json: JSONObject): CostbackupBundle.BackupManifest =
        CostbackupBundle.BackupManifest.fromJson(json, nowEpochMs)

    @Test
    fun `FL07 homeCurrency USD round trips through toJson and fromJson`() {
        val manifest = CostbackupBundle.BackupManifest(
            databaseVersion = 1,
            createdAt = nowEpochMs,
            homeCurrency = "USD"
        )

        val json = manifest.toJson()
        assertEquals("USD", json.getString("homeCurrency"))
        assertEquals("USD", parse(json).homeCurrency)
    }

    @Test
    fun `FL07 null homeCurrency writes no key and reads back as null`() {
        val manifest = CostbackupBundle.BackupManifest(databaseVersion = 1, createdAt = nowEpochMs)

        val json = manifest.toJson()
        assertFalse("a null home currency must not write the key", json.has("homeCurrency"))
        assertNull(parse(json).homeCurrency)
    }

    @Test
    fun `FL07 lowercase usd normalizes to USD`() {
        assertEquals("USD", parse(manifestJson { put("homeCurrency", "usd") }).homeCurrency)
    }

    @Test
    fun `FL07 padded code is trimmed and uppercased`() {
        assertEquals("EUR", parse(manifestJson { put("homeCurrency", " eur ") }).homeCurrency)
    }

    @Test
    fun `FL07 missing key gives null without throwing`() {
        assertNull(parse(manifestJson()).homeCurrency)
    }

    @Test
    fun `FL07 JSON null value gives null without throwing`() {
        assertNull(parse(manifestJson { put("homeCurrency", JSONObject.NULL) }).homeCurrency)
    }

    @Test
    fun `FL07 numeric value gives null without throwing`() {
        assertNull(parse(manifestJson { put("homeCurrency", 123) }).homeCurrency)
    }

    @Test
    fun `FL07 malformed code with a digit gives null without throwing`() {
        assertNull(parse(manifestJson { put("homeCurrency", "eur1") }).homeCurrency)
    }

    @Test
    fun `FL07 empty string gives null without throwing`() {
        assertNull(parse(manifestJson { put("homeCurrency", "") }).homeCurrency)
    }

    @Test
    fun `FL07 two letter and four letter codes give null`() {
        assertNull(parse(manifestJson { put("homeCurrency", "EU") }).homeCurrency)
        assertNull(parse(manifestJson { put("homeCurrency", "EURO") }).homeCurrency)
    }

    @Test
    fun `FL07 homeCurrency written by create is read back by extract`() {
        val dbFile = tmp.newFile("fl07_db.bin").apply { writeBytes(ByteArray(2048) { (it % 251).toByte() }) }
        val out = File(tmp.root, "fl07_with.costbackup")

        val created = CostbackupBundle.create(
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
            homeCurrency = "GBP"
        )
        assertTrue("bundle creation should succeed", created.isSuccess)

        val extracted = CostbackupBundle.extract(
            out, File(tmp.root, "fl07_extract_with"), password,
            nowEpochMs = 0L,
            encryptionService = encryption
        )
        assertTrue("extract should succeed", extracted.isSuccess)
        assertEquals("GBP", extracted.getOrThrow().manifest.homeCurrency)
    }

    @Test
    fun `FL07 bundle created without homeCurrency extracts as a legacy manifest with null`() {
        val dbFile = tmp.newFile("fl07_legacy_db.bin").apply { writeBytes(ByteArray(2048) { (it % 251).toByte() }) }
        val out = File(tmp.root, "fl07_without.costbackup")

        val created = CostbackupBundle.create(
            outputFile = out,
            databaseFile = dbFile,
            receiptFiles = emptyMap(),
            password = password,
            nowEpochMs = nowEpochMs,
            tableCounts = mapOf("expenses" to 1),
            databaseVersion = 1,
            redacted = true,
            includeReceiptImages = false,
            encryptionService = encryption
        )
        assertTrue("bundle creation should succeed", created.isSuccess)

        val extracted = CostbackupBundle.extract(
            out, File(tmp.root, "fl07_extract_without"), password,
            nowEpochMs = 0L,
            encryptionService = encryption
        )
        assertTrue("extract should succeed", extracted.isSuccess)
        assertNull(extracted.getOrThrow().manifest.homeCurrency)
    }
}
