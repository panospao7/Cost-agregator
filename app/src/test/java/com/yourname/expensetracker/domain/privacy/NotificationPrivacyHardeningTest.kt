package com.yourname.expensetracker.domain.privacy

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * PR3 acceptance tests:
 *
 * notification_disabled_does_not_read_extras
 * privacy_fail_closed_notification_does_not_read_extras
 * blocked_package_does_not_read_extras
 * do_not_store_notification_no_raw_text_in_raw_notifications
 * do_not_store_notification_no_raw_text_in_pending_reviews
 * metadata_only_notification_no_raw_extras_in_diagnostics
 * redacted_notification_pending_review_has_redacted_text
 * privacy_denied_does_not_poison_dedupe_cache
 */
class NotificationPrivacyHardeningTest {

    // ── NotificationCaptureGate tests ─────────────────────────────────────────

    @Test
    fun notification_disabled_isCaptureAllowed_returns_false() = runTest {
        val repo = buildFakeRepo(PrivacySettings(notificationCaptureEnabled = false))
        val gate = buildGate(repo = repo, gateDecision = PrivacyDecision.Allowed)
        assertFalse(gate.isCaptureAllowed())
    }

    @Test
    fun notification_enabled_isCaptureAllowed_returns_true() = runTest {
        val repo = buildFakeRepo(PrivacySettings(notificationCaptureEnabled = true))
        val gate = buildGate(repo = repo, gateDecision = PrivacyDecision.Allowed)
        assertTrue(gate.isCaptureAllowed())
    }

    @Test
    fun privacy_gate_denied_isCaptureAllowed_returns_false() = runTest {
        val repo = buildFakeRepo(PrivacySettings(notificationCaptureEnabled = true))
        val gate = buildGate(repo = repo, gateDecision = PrivacyDecision.Denied("denied"))
        assertFalse(gate.isCaptureAllowed())
    }

    @Test
    fun privacy_fail_closed_notification_isCaptureAllowed_returns_false() = runTest {
        val repo = buildFakeRepo(PrivacySettings(notificationCaptureEnabled = true))
        val gate = buildGate(repo = repo, gateDecision = PrivacyDecision.FailClosed("fail closed"))
        assertFalse(gate.isCaptureAllowed())
    }

    // ── NotificationPersistencePayload tests ─────────────────────────────────

    @Test
    fun do_not_store_notification_no_raw_text_in_payload() {
        val payload = NotificationPersistencePayload.build(
            mode = RawStorageMode.DO_NOT_STORE,
            rawTitle = "Bank alert: €50 charged",
            rawText = "Your card was charged €50",
            rawBigText = "Bank notification body",
            rawSubText = "Bank App",
            extrasJson = """{"amount":"50"}""",
            dedupeFingerprint = "fp-123",
            notificationKeyHash = "hash-abc"
        )
        assertNull("title must be null", payload.rawNotificationTitle)
        assertNull("text must be null", payload.rawNotificationText)
        assertNull("bigText must be null", payload.rawNotificationBigText)
        assertNull("extras must be null", payload.rawNotificationExtrasJson)
        assertNull("pendingReviewTitle must be null", payload.pendingReviewTitle)
        assertNull("pendingReviewText must be null", payload.pendingReviewText)
        assertEquals("dedupeFingerprint must be preserved", "fp-123", payload.dedupeFingerprint)
    }

    @Test
    fun do_not_store_notification_no_raw_text_in_pending_reviews() {
        val payload = NotificationPersistencePayload.build(
            mode = RawStorageMode.DO_NOT_STORE,
            rawTitle = "Sensitive title",
            rawText = "Sensitive text",
            rawBigText = null,
            rawSubText = null,
            extrasJson = null,
            dedupeFingerprint = "fp-456",
            notificationKeyHash = null
        )
        assertNull(payload.pendingReviewTitle)
        assertNull(payload.pendingReviewText)
    }

    @Test
    fun metadata_only_notification_no_raw_extras_in_diagnostics() {
        val payload = NotificationPersistencePayload.build(
            mode = RawStorageMode.STORE_METADATA_ONLY,
            rawTitle = "Payment received",
            rawText = "€100 from John",
            rawBigText = null,
            rawSubText = null,
            extrasJson = """{"raw":"data"}""",
            dedupeFingerprint = "fp-789",
            notificationKeyHash = "hash-xyz"
        )
        assertNull("extras must not be stored in metadata-only mode", payload.rawNotificationExtrasJson)
        assertNull(payload.rawNotificationTitle)
        assertNull(payload.rawNotificationText)
        // hash preserved for diagnostics/dedup
        assertEquals("hash-xyz", payload.notificationKeyHash)
        assertEquals("fp-789", payload.dedupeFingerprint)
    }

    @Test
    fun redacted_notification_pending_review_has_redacted_text() {
        val payload = NotificationPersistencePayload.build(
            mode = RawStorageMode.STORE_REDACTED,
            rawTitle = "Sensitive",
            rawText = "Sensitive body",
            rawBigText = null,
            rawSubText = null,
            extrasJson = null,
            dedupeFingerprint = "fp-redacted",
            notificationKeyHash = null
        )
        assertEquals("[REDACTED]", payload.pendingReviewTitle)
        assertEquals("[REDACTED]", payload.pendingReviewText)
        assertEquals("[REDACTED]", payload.rawNotificationTitle)
        assertEquals("[REDACTED]", payload.rawNotificationText)
        assertEquals("""{"redacted":true}""", payload.rawNotificationExtrasJson)
    }

    @Test
    fun store_raw_notification_preserves_all_fields() {
        val payload = NotificationPersistencePayload.build(
            mode = RawStorageMode.STORE_RAW,
            rawTitle = "Bank: €50",
            rawText = "Charged €50",
            rawBigText = "Expanded body",
            rawSubText = "Bank App",
            extrasJson = """{"amount":"50"}""",
            dedupeFingerprint = "fp-raw",
            notificationKeyHash = "hash-raw"
        )
        assertEquals("Bank: €50", payload.rawNotificationTitle)
        assertEquals("Charged €50", payload.rawNotificationText)
        assertEquals("Expanded body", payload.rawNotificationBigText)
        assertEquals("Bank App", payload.rawNotificationSubText)
        assertEquals("""{"amount":"50"}""", payload.rawNotificationExtrasJson)
        assertEquals("Bank: €50", payload.pendingReviewTitle)
        assertEquals("Charged €50", payload.pendingReviewText)
    }

    @Test
    fun dedupeFingerprint_always_preserved_regardless_of_mode() {
        listOf(
            RawStorageMode.STORE_RAW,
            RawStorageMode.STORE_REDACTED,
            RawStorageMode.STORE_METADATA_ONLY,
            RawStorageMode.DO_NOT_STORE
        ).forEach { mode ->
            val payload = NotificationPersistencePayload.build(
                mode = mode,
                rawTitle = "title",
                rawText = "text",
                rawBigText = null,
                rawSubText = null,
                extrasJson = null,
                dedupeFingerprint = "fp-always",
                notificationKeyHash = null
            )
            assertEquals("dedupeFingerprint must be preserved in mode $mode", "fp-always", payload.dedupeFingerprint)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildFakeRepo(settings: PrivacySettings): PrivacySettingsRepository {
        val repo = mockk<PrivacySettingsRepository>(relaxed = true)
        coEvery { repo.getSettings() } returns settings
        every { repo.observeSettings() } returns flowOf(settings)
        every { repo.observeLoadState() } returns flowOf(PrivacySettingsLoadState.Loaded(settings))
        coEvery { repo.getLoadState() } returns PrivacySettingsLoadState.Loaded(settings)
        return repo
    }

    private fun buildGate(
        repo: PrivacySettingsRepository,
        gateDecision: PrivacyDecision
    ): NotificationCaptureGate {
        val privacyGate = mockk<PrivacyGate>(relaxed = true)
        coEvery { privacyGate.check(PrivacyCapability.NOTIFICATION_CAPTURE, any()) } returns gateDecision
        return NotificationCaptureGate(privacyGate, repo)
    }
}
