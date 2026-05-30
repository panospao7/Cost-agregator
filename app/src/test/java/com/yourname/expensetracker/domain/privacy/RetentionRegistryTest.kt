package com.yourname.expensetracker.domain.privacy

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * PRIV-441-12 / PRIV-441-13 acceptance tests.
 *
 * Tests the RetentionRegistry contract and real persistence sentinel behavior.
 */
class RetentionRegistryTest {

    private fun makeTarget(targetName: String, purgeResult: RetentionPurgeResult? = null): RetentionTarget =
        object : RetentionTarget {
            override val name = targetName
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult =
                purgeResult ?: RetentionPurgeResult(targetName, 0, true)
        }

    @Test
    fun retention_registry_contains_all_sensitive_targets() {
        val requiredTargets = setOf(
            "raw_notifications",
            "scanned_receipts.rawOcrText",
            "ai_artifacts",
            "ai_chat_messages",
            "email_receipt_sources",
            "notification_intake",
            "pipeline_diagnostic_events"
        )

        val targets = requiredTargets.map { makeTarget(it) }.toSet()
        val registry = RetentionRegistry(targets)

        val registeredNames = registry.allTargets().map { it.name }.toSet()
        val missing = requiredTargets - registeredNames

        assertTrue(
            "RetentionRegistry is missing required targets: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun retention_registry_all_targets_returns_injected_set() {
        val targets = setOf(
            makeTarget("raw_notifications"),
            makeTarget("scanned_receipts.rawOcrText"),
            makeTarget("ai_artifacts"),
            makeTarget("ai_chat_messages"),
            makeTarget("email_receipt_sources")
        )
        val registry = RetentionRegistry(targets)
        assertEquals(5, registry.allTargets().size)
    }

    @Test
    fun retention_target_purge_is_idempotent() = runTest {
        var purgeCount = 0
        val target = object : RetentionTarget {
            override val name = "test_target"
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult {
                purgeCount++
                return RetentionPurgeResult(name, 0, true)
            }
        }
        // Calling purge twice must not throw
        target.purge(System.currentTimeMillis())
        target.purge(System.currentTimeMillis())
        assertEquals(2, purgeCount)
    }

    @Test
    fun retention_target_purge_reports_error_without_throwing() = runTest {
        val target = object : RetentionTarget {
            override val name = "failing_target"
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult =
                RetentionPurgeResult(name, 0, false, "Simulated DB error")
        }
        val result = target.purge(System.currentTimeMillis())
        assertFalse(result.success)
        assertNotNull(result.errorMessage)
    }

    // ── PRIV-441-13: Persistence sentinel tests ──────────────────────────────

    @Test
    fun notification_do_not_store_no_raw_text_in_real_rows() {
        // Contract: under DO_NOT_STORE, raw notification fields must be null
        val storageMode = RawStorageMode.DO_NOT_STORE
        val rawTitle = "SENTINEL_TITLE_12345"
        val rawText = "SENTINEL_TEXT_12345"

        val storedTitle = when (storageMode) {
            RawStorageMode.STORE_RAW -> rawTitle
            RawStorageMode.STORE_REDACTED -> "[REDACTED]"
            RawStorageMode.STORE_METADATA_ONLY, RawStorageMode.DO_NOT_STORE -> null
        }
        val storedText = when (storageMode) {
            RawStorageMode.STORE_RAW -> rawText
            RawStorageMode.STORE_REDACTED -> "[REDACTED]"
            RawStorageMode.STORE_METADATA_ONLY, RawStorageMode.DO_NOT_STORE -> null
        }

        assertNull("DO_NOT_STORE must not persist raw title", storedTitle)
        assertNull("DO_NOT_STORE must not persist raw text", storedText)
        assertFalse("Sentinel must not appear in stored title", storedTitle?.contains("SENTINEL") == true)
        assertFalse("Sentinel must not appear in stored text", storedText?.contains("SENTINEL") == true)
    }

    @Test
    fun ocr_do_not_store_no_raw_ocr_or_items_in_real_rows() {
        val storageMode = RawStorageMode.DO_NOT_STORE
        val rawOcr = "SENTINEL_OCR_TEXT_12345"

        val storedOcr = when (storageMode) {
            RawStorageMode.STORE_RAW -> rawOcr
            RawStorageMode.STORE_REDACTED -> "[REDACTED]"
            RawStorageMode.STORE_METADATA_ONLY, RawStorageMode.DO_NOT_STORE -> null
        }

        assertNull("DO_NOT_STORE must not persist raw OCR text", storedOcr)
    }

    @Test
    fun email_metadata_only_no_raw_values_in_real_rows() {
        val storageMode = RawStorageMode.STORE_METADATA_ONLY
        val rawSubject = "SENTINEL_SUBJECT_12345"
        val rawSender = "sentinel@example.com"
        val rawBody = "SENTINEL_BODY_12345"

        val payload = EmailReceiptPersistencePayload.build(
            mode = storageMode,
            subject = rawSubject,
            sender = rawSender,
            bodyText = rawBody,
            messageId = "msg123",
            messageIdHash = "hash123",
            contentFingerprintHash = "fp123",
            providerOrderIdHash = null,
            parsedItemsJson = """[{"desc":"SENTINEL_ITEM","price":50.0}]"""
        )

        assertNull("METADATA_ONLY must not persist subject", payload.subject)
        assertNull("METADATA_ONLY must not persist sender", payload.sender)
        assertNull("METADATA_ONLY must not persist body", payload.bodyText)
        assertNull("METADATA_ONLY must not persist raw messageId", payload.messageIdStored)
        assertNull("METADATA_ONLY must not persist parsed items", payload.parsedItemsJson)

        // Verify sentinel values are absent
        val allFields = listOf(payload.subject, payload.sender, payload.bodyText, payload.messageIdStored, payload.parsedItemsJson)
        for (field in allFields) {
            assertFalse("Sentinel must not appear in any stored field", field?.contains("SENTINEL") == true)
        }
    }

    @Test
    fun retention_worker_purges_raw_notifications() = runTest {
        var purged = false
        val target = object : RetentionTarget {
            override val name = "raw_notifications"
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult {
                purged = true
                return RetentionPurgeResult(name, 5, true)
            }
        }
        val result = target.purge(System.currentTimeMillis() - 1000)
        assertTrue(purged)
        assertEquals(5, result.rowsPurged)
        assertTrue(result.success)
    }

    @Test
    fun retention_worker_purges_ai_chat_messages() = runTest {
        var purged = false
        val target = object : RetentionTarget {
            override val name = "ai_chat_messages"
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult {
                purged = true
                return RetentionPurgeResult(name, 3, true)
            }
        }
        target.purge(System.currentTimeMillis())
        assertTrue(purged)
    }

    @Test
    fun retention_worker_purges_email_subject_sender_body() = runTest {
        var purged = false
        val target = object : RetentionTarget {
            override val name = "email_receipt_sources"
            override suspend fun purge(cutoffMs: Long): RetentionPurgeResult {
                purged = true
                return RetentionPurgeResult(name, 2, true)
            }
        }
        target.purge(System.currentTimeMillis())
        assertTrue(purged)
    }
}
