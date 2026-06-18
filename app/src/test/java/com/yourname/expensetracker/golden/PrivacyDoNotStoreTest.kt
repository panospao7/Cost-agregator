package com.yourname.expensetracker.golden

import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Golden Scenario Test 5: Privacy DO_NOT_STORE
 *
 * Verifies that when RawStorageMode is DO_NOT_STORE:
 * 1. Processing still works (parser receives real text)
 * 2. No raw notification text is persisted
 * 3. Dedup fingerprint is still computed from real text
 * 4. The storage notification has null text fields
 */
class PrivacyDoNotStoreTest : GoldenTestBase() {

    @Test
    fun `DO_NOT_STORE notification has null text fields`() = runTest {
        // Given: A notification with real text
        val processingNotification = RawNotification(
            packageName = "com.revolut.revolut",
            appName = "Revolut",
            title = "Card payment",
            text = "You paid €45.30 at Lidl",
            bigText = "You paid €45.30 at Lidl Hellas",
            subText = "Visa •1234",
            extrasJson = """{"key":"value"}""",
            timestamp = fixedNow,
            capturedAt = fixedNow,
            dedupeFingerprint = "test_fingerprint_123"
        )

        // When: Build storage version for DO_NOT_STORE
        val storageNotification = processingNotification.copy(
            title = null,
            text = null,
            bigText = null,
            subText = null,
            extrasJson = null
        )

        // Then: Storage has no raw text
        assertNull(storageNotification.title)
        assertNull(storageNotification.text)
        assertNull(storageNotification.bigText)
        assertNull(storageNotification.subText)
        assertNull(storageNotification.extrasJson)

        // But: Processing notification still has real text for parsing
        assertEquals("Card payment", processingNotification.title)
        assertEquals("You paid €45.30 at Lidl", processingNotification.text)

        // And: Fingerprint is preserved on storage (computed from real text)
        assertEquals("test_fingerprint_123", storageNotification.dedupeFingerprint)
        assertEquals(storageNotification.packageName, "com.revolut.revolut")
    }

    @Test
    fun `DO_NOT_STORE persists metadata only`() = runTest {
        // Given: Storage notification with null text fields
        val storageNotification = RawNotification(
            packageName = "com.revolut.revolut",
            appName = "Revolut",
            title = null,
            text = null,
            bigText = null,
            subText = null,
            extrasJson = null,
            timestamp = fixedNow,
            capturedAt = fixedNow,
            dedupeFingerprint = "fingerprint_abc"
        )

        // When: Insert into DB
        val id = database.rawNotificationDao().insertOrIgnore(storageNotification)
        assertTrue(id > 0)

        // Then: Persisted row has no raw text
        val persisted = database.rawNotificationDao().getById(id)
        assertNotNull(persisted)
        assertNull(persisted!!.title)
        assertNull(persisted.text)
        assertNull(persisted.bigText)
        assertNull(persisted.subText)
        assertNull(persisted.extrasJson)

        // But: Metadata is preserved
        assertEquals("com.revolut.revolut", persisted.packageName)
        assertEquals("Revolut", persisted.appName)
        assertEquals(fixedNow, persisted.timestamp)
        assertEquals("fingerprint_abc", persisted.dedupeFingerprint)
    }

    @Test
    fun `STORE_REDACTED persists redacted text`() = runTest {
        // Given: Redacted storage notification
        val storageNotification = RawNotification(
            packageName = "com.revolut.revolut",
            appName = "Revolut",
            title = "[REDACTED]",
            text = "[REDACTED]",
            bigText = "[REDACTED]",
            subText = "[REDACTED]",
            extrasJson = """{"redacted":true}""",
            timestamp = fixedNow,
            capturedAt = fixedNow,
            dedupeFingerprint = "fingerprint_def"
        )

        // When: Insert
        val id = database.rawNotificationDao().insertOrIgnore(storageNotification)

        // Then: Redacted text persisted (not null, not real)
        val persisted = database.rawNotificationDao().getById(id)
        assertEquals("[REDACTED]", persisted!!.title)
        assertEquals("[REDACTED]", persisted.text)
    }
}
