package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.EmailReceiptSource
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val FIXED_NOW = 1_710_000_000_000L

/**
 * Unit tests for [EmailReceiptDao] covering insert, query by provider/sender,
 * and deduplication via message ID.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EmailReceiptDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: EmailReceiptDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.emailReceiptDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Inserts a bare-minimum [ScannedReceipt] and returns its generated ID for
     * use as the FK in [EmailReceiptSource].
     */
    private suspend fun insertScannedReceipt(): Long {
        return database.scannedReceiptDao().insert(
            ScannedReceipt(
                imagePath = null,
                rawOcrText = "test receipt text",
                parsedTotal = null,
                parsedMerchant = null,
                parsedDate = null,
                parsedItems = null,
                parsedTaxAmount = null,
                currency = "EUR",
                confidence = 1.0f
            )
        )
    }

    private suspend fun makeEmailReceipt(
        receiptId: Long? = null,
        emailSender: String = "orders@amazon.com",
        emailSubject: String = "Your Amazon order",
        emailMessageId: String? = "msg_123",
        parsedAt: Long = FIXED_NOW,
        provider: String = "amazon",
        confidence: Double = 0.95,
        fingerprint: String = "amazon_50.00_20240101"
    ): EmailReceiptSource {
        val rid = receiptId ?: insertScannedReceipt()
        return EmailReceiptSource(
            receiptId = rid,
            emailSender = emailSender,
            emailSubject = emailSubject,
            emailMessageId = emailMessageId,
            parsedAt = parsedAt,
            provider = provider,
            confidence = confidence,
            fingerprint = fingerprint
        )
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `insert email receipt source`() = runTest {
        val email = makeEmailReceipt()
        val id = dao.insert(email)

        assertTrue(id > 0)
    }

    @Test
    fun `query email receipt by id`() = runTest {
        val email = makeEmailReceipt()
        val id = dao.insert(email)

        val loaded = dao.getById(id)
        assertNotNull(loaded)
        assertEquals("orders@amazon.com", loaded.emailSender)
        assertEquals("Your Amazon order", loaded.emailSubject)
        assertEquals("amazon", loaded.provider)
    }

    @Test
    fun `query by provider returns matching receipts`() = runTest {
        dao.insert(makeEmailReceipt(provider = "amazon", emailMessageId = "msg_1"))
        dao.insert(makeEmailReceipt(provider = "uber", emailMessageId = "msg_2"))
        dao.insert(makeEmailReceipt(provider = "amazon", emailMessageId = "msg_3"))

        val amazonReceipts = dao.getByProvider("amazon")
        val uberReceipts = dao.getByProvider("uber")

        assertEquals(2, amazonReceipts.size)
        assertEquals(1, uberReceipts.size)
    }

    @Test
    fun `query by provider returns empty list for unknown provider`() = runTest {
        dao.insert(makeEmailReceipt(provider = "amazon"))

        val results = dao.getByProvider("unknown_provider")

        assertEquals(0, results.size)
    }

    @Test
    fun `query by email sender returns matching receipts`() = runTest {
        dao.insert(makeEmailReceipt(
            emailSender = "orders@amazon.com",
            emailMessageId = "msg_1"
        ))
        dao.insert(makeEmailReceipt(
            emailSender = "receipts@uber.com",
            emailMessageId = "msg_2"
        ))

        val all = dao.getAll()
        val amazonEmails = all.filter { it.emailSender == "orders@amazon.com" }
        val uberEmails = all.filter { it.emailSender == "receipts@uber.com" }

        assertEquals(1, amazonEmails.size)
        assertEquals(1, uberEmails.size)
    }

    @Test
    fun `insertOrIgnore returns -1 for duplicate emailMessageId`() = runTest {
        val email1 = makeEmailReceipt(emailMessageId = "dup_msg_001")
        val email2 = makeEmailReceipt(emailMessageId = "dup_msg_001")

        val id1 = dao.insertOrIgnore(email1)
        val id2 = dao.insertOrIgnore(email2)

        assertTrue(id1 > 0)
        assertEquals(-1L, id2)
    }

    @Test
    fun `getByMessageId retrieves receipt by unique message id`() = runTest {
        dao.insert(makeEmailReceipt(emailMessageId = "unique_msg_001"))

        val loaded = dao.getByMessageId("unique_msg_001")
        assertNotNull(loaded)
        assertEquals("unique_msg_001", loaded.emailMessageId)
    }

    @Test
    fun `getByFingerprint retrieves receipt by fingerprint`() = runTest {
        dao.insert(makeEmailReceipt(fingerprint = "amazon_25.00_20240115"))

        val loaded = dao.getByFingerprint("amazon_25.00_20240115")
        assertNotNull(loaded)
        assertEquals("amazon_25.00_20240115", loaded.fingerprint)
    }

    @Test
    fun `getByReceiptId retrieves email sources for a given receipt`() = runTest {
        val receiptId = insertScannedReceipt()
        dao.insert(makeEmailReceipt(receiptId = receiptId, emailMessageId = "msg_a"))
        dao.insert(makeEmailReceipt(receiptId = receiptId, emailMessageId = "msg_b"))

        val results = dao.getByReceiptId(receiptId)
        assertEquals(2, results.size)
    }

    @Test
    fun `verify ordering by parsedAt DESC`() = runTest {
        val early = makeEmailReceipt(parsedAt = FIXED_NOW, emailMessageId = "msg_early")
        val late = makeEmailReceipt(parsedAt = FIXED_NOW + 5000, emailMessageId = "msg_late")

        dao.insert(early)
        dao.insert(late)

        val all = dao.getAll()
        assertEquals(2, all.size)
        // Most recent first
        assertEquals(FIXED_NOW + 5000, all[0].parsedAt)
        assertEquals(FIXED_NOW, all[1].parsedAt)
    }

    @Test
    fun `getCount returns correct count`() = runTest {
        assertEquals(0, dao.getCount())

        dao.insert(makeEmailReceipt(emailMessageId = "msg_1"))
        assertEquals(1, dao.getCount())

        dao.insert(makeEmailReceipt(emailMessageId = "msg_2"))
        assertEquals(2, dao.getCount())
    }

    @Test
    fun `getCountByProvider returns correct counts`() = runTest {
        dao.insert(makeEmailReceipt(provider = "amazon", emailMessageId = "msg_1"))
        dao.insert(makeEmailReceipt(provider = "amazon", emailMessageId = "msg_2"))
        dao.insert(makeEmailReceipt(provider = "uber", emailMessageId = "msg_3"))

        assertEquals(2, dao.getCountByProvider("amazon"))
        assertEquals(1, dao.getCountByProvider("uber"))
        assertEquals(0, dao.getCountByProvider("unknown"))
    }

    @Test
    fun `getRecent returns receipts parsed after given time`() = runTest {
        dao.insert(makeEmailReceipt(parsedAt = FIXED_NOW - 10_000, emailMessageId = "msg_old"))
        dao.insert(makeEmailReceipt(parsedAt = FIXED_NOW, emailMessageId = "msg_new"))
        dao.insert(makeEmailReceipt(parsedAt = FIXED_NOW + 5000, emailMessageId = "msg_newer"))

        val recent = dao.getRecent(FIXED_NOW)
        assertEquals(2, recent.size)
    }

    @Test
    fun `deleteAll removes all email receipt sources`() = runTest {
        dao.insert(makeEmailReceipt(emailMessageId = "msg_1"))
        dao.insert(makeEmailReceipt(emailMessageId = "msg_2"))

        dao.deleteAll()

        assertEquals(0, dao.getCount())
    }

    @Test
    fun `deleteOlderThan removes old receipts`() = runTest {
        val threshold = FIXED_NOW

        dao.insert(makeEmailReceipt(parsedAt = threshold - 10_000, emailMessageId = "msg_old"))
        dao.insert(makeEmailReceipt(parsedAt = threshold + 10_000, emailMessageId = "msg_new"))

        dao.deleteOlderThan(threshold)

        assertEquals(1, dao.getCount())
        val loaded = dao.getByMessageId("msg_new")
        assertNotNull(loaded)
    }
}
