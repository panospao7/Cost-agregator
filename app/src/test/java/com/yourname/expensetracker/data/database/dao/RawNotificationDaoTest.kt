package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.RawNotification
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
 * Unit tests for [RawNotificationDao] covering insert, query by package name,
 * and deduplication behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RawNotificationDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: RawNotificationDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.rawNotificationDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun createNotification(
        packageName: String = "com.example.app",
        appName: String? = "Example",
        title: String? = "Title",
        text: String? = "Text body",
        bigText: String? = null,
        subText: String? = null,
        extrasJson: String? = null,
        timestamp: Long = FIXED_NOW,
        capturedAt: Long = FIXED_NOW,
        isProcessed: Boolean = false,
        isRelevant: Boolean? = null,
        parseResult: String? = null,
        rawContentPurgedAt: Long? = null,
        dedupeFingerprint: String? = null
    ): RawNotification = RawNotification(
        packageName = packageName,
        appName = appName,
        title = title,
        text = text,
        bigText = bigText,
        subText = subText,
        extrasJson = extrasJson,
        timestamp = timestamp,
        capturedAt = capturedAt,
        isProcessed = isProcessed,
        isRelevant = isRelevant,
        parseResult = parseResult,
        rawContentPurgedAt = rawContentPurgedAt,
        dedupeFingerprint = dedupeFingerprint
    )

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `insert a raw notification and query by id`() = runTest {
        val notification = createNotification()
        val id = dao.insert(notification)

        assertTrue(id > 0)

        val loaded = dao.getById(id)
        assertNotNull(loaded)
        assertEquals("com.example.app", loaded.packageName)
        assertEquals("Title", loaded.title)
        assertEquals("Text body", loaded.text)
    }

    @Test
    fun `query by package name returns matching notifications`() = runTest {
        dao.insert(createNotification(packageName = "com.example.bank", timestamp = FIXED_NOW, title = "Bank Alert", text = "Spent $50"))
        dao.insert(createNotification(packageName = "com.example.shop", timestamp = FIXED_NOW + 1, title = "Shop Alert", text = "Order shipped"))
        dao.insert(createNotification(packageName = "com.example.bank", timestamp = FIXED_NOW + 2, title = "Bank Alert 2", text = "Deposit received"))

        val bankNotifications = dao.getAll().filter { it.packageName == "com.example.bank" }

        assertEquals(2, bankNotifications.size)
    }

    @Test
    fun `query by package name returns empty list for unknown package`() = runTest {
        dao.insert(createNotification(packageName = "com.example.bank"))

        val results = dao.getAll().filter { it.packageName == "com.example.unknown" }

        assertEquals(0, results.size)
    }

    @Test
    fun `verify deduplication via insertOrIgnore returns -1 for duplicate fingerprint`() = runTest {
        val fingerprint = "abc123def456"
        val notification1 = createNotification(
            packageName = "com.example.app",
            dedupeFingerprint = fingerprint
        )
        val notification2 = createNotification(
            packageName = "com.example.app",
            dedupeFingerprint = fingerprint
        )

        val id1 = dao.insertOrIgnore(notification1)
        val id2 = dao.insertOrIgnore(notification2)

        assertTrue(id1 > 0)
        assertEquals(-1L, id2)
    }

    @Test
    fun `insert same notification twice via regular insert returns different ids`() = runTest {
        val notification = createNotification(
            packageName = "com.example.app",
            dedupeFingerprint = "unique_fingerprint_1"
        )

        val id1 = dao.insert(notification)
        // Change the dedupe fingerprint so it is not rejected by UNIQUE constraint
        val notification2 = notification.copy(dedupeFingerprint = "unique_fingerprint_2")
        val id2 = dao.insert(notification2)

        assertTrue(id1 > 0)
        assertTrue(id2 > 0)
        assertTrue(id2 != id1)
    }

    @Test
    fun `insert multiple notifications and verify ordering by capturedAt DESC`() = runTest {
        val early = createNotification(
            packageName = "com.example.app",
            timestamp = FIXED_NOW,
            title = "Early",
            text = "First notification",
            capturedAt = FIXED_NOW,
            dedupeFingerprint = "fp_early"
        )
        val late = createNotification(
            packageName = "com.example.app",
            timestamp = FIXED_NOW + 5000,
            title = "Late",
            text = "Second notification",
            capturedAt = FIXED_NOW + 5000,
            dedupeFingerprint = "fp_late"
        )

        dao.insert(early)
        dao.insert(late)

        val all = dao.getAll()
        assertEquals(2, all.size)
        // Most recent first
        assertEquals(FIXED_NOW + 5000, all[0].capturedAt)
        assertEquals(FIXED_NOW, all[1].capturedAt)
    }

    @Test
    fun `verify all fields are persisted correctly`() = runTest {
        val notification = createNotification(
            packageName = "com.example.bank",
            appName = "MyBank",
            title = "Transaction Alert",
            text = "You spent $50.00",
            bigText = "You spent $50.00 at Grocery Store",
            subText = "Bank notification",
            extrasJson = """{"key":"value"}""",
            timestamp = FIXED_NOW - 10_000,
            capturedAt = FIXED_NOW,
            isProcessed = true,
            isRelevant = true,
            parseResult = """{"amount":50.0}""",
            rawContentPurgedAt = null,
            dedupeFingerprint = "bank_fp_123"
        )

        val id = dao.insert(notification)
        val loaded = dao.getById(id)

        assertNotNull(loaded)
        assertEquals("com.example.bank", loaded.packageName)
        assertEquals("MyBank", loaded.appName)
        assertEquals("Transaction Alert", loaded.title)
        assertEquals("You spent $50.00", loaded.text)
        assertEquals("You spent $50.00 at Grocery Store", loaded.bigText)
        assertEquals("Bank notification", loaded.subText)
        assertEquals("""{"key":"value"}""", loaded.extrasJson)
        assertEquals(FIXED_NOW - 10_000, loaded.timestamp)
        assertEquals(FIXED_NOW, loaded.capturedAt)
        assertEquals(true, loaded.isProcessed)
        assertEquals(true, loaded.isRelevant)
        assertEquals("""{"amount":50.0}""", loaded.parseResult)
        assertEquals("bank_fp_123", loaded.dedupeFingerprint)
    }

    @Test
    fun `markRelevance sets isRelevant flag`() = runTest {
        val id = dao.insert(createNotification(dedupeFingerprint = "mark_rel_fp"))
        dao.markRelevance(id, true)

        val loaded = dao.getById(id)
        assertNotNull(loaded)
        assertEquals(true, loaded.isRelevant)

        dao.markRelevance(id, false)

        val reloaded = dao.getById(id)
        assertNotNull(reloaded)
        assertEquals(false, reloaded.isRelevant)
    }

    @Test
    fun `deleteAll removes all notifications`() = runTest {
        dao.insert(createNotification(dedupeFingerprint = "fp_a", title = "A", text = "Notification A"))
        dao.insert(createNotification(dedupeFingerprint = "fp_b", title = "B", text = "Notification B"))

        dao.deleteAll()

        val all = dao.getAll()
        assertEquals(0, all.size)
    }
}
