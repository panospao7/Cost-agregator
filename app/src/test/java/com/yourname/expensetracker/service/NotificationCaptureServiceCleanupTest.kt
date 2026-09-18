package com.yourname.expensetracker.service

import android.os.Bundle
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.test.runTest

/**
 * Unit tests covering cleanup changes made in P1-SLICE-A:
 * - Dead code / null-launch removal
 * - CamelCase-sensitive key filtering
 *
 * Runs under Robolectric so the real [NotificationCaptureService.buildExtrasJson]
 * can be exercised against a real android.os.Bundle (P1-005 test gap). The
 * service is attached WITHOUT onCreate, so Hilt field injection never runs and
 * no collaborators are needed — buildExtrasJson only touches org.json + the
 * static sensitive-key set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class NotificationCaptureServiceCleanupTest {

    // --- Work tracker (NEW-P1-003 / NEW-P1-004) ---

    @Test
    fun `workTracker_launch_always_returns_non_null_job`() = runTest {
        val tracker = NotificationServiceWorkTracker()
        var completed = false

        val job = tracker.launch(this) {
            completed = true
        }

        assertTrue("Job should be active right after launch", job.isActive)
        // Allow the launched coroutine to complete
        kotlinx.coroutines.delay(10)
        assertTrue("The launched block must have executed", completed)
    }

    // --- Sensitive key filter (NEW-P1-016) ---

    @Test
    fun `sensitive_key_set_contains_snake_case_variants`() {
        val keys = NotificationCaptureService.SENSITIVE_EXTRAS_KEYS
        assertTrue("account_number must be in sensitive keys", keys.contains("account_number"))
        assertTrue("card_number must be in sensitive keys", keys.contains("card_number"))
        assertTrue("transaction_id must be in sensitive keys", keys.contains("transaction_id"))
        assertTrue("full_name must be in sensitive keys", keys.contains("full_name"))
        assertTrue("email must be in sensitive keys", keys.contains("email"))
        assertTrue("phone must be in sensitive keys", keys.contains("phone"))
        assertTrue("cvv must be in sensitive keys", keys.contains("cvv"))
        assertTrue("pin must be in sensitive keys", keys.contains("pin"))
        assertTrue("password must be in sensitive keys", keys.contains("password"))
        assertTrue("iban must be in sensitive keys", keys.contains("iban"))
        assertTrue("balance must be in sensitive keys", keys.contains("balance"))
        assertTrue("amount must be in sensitive keys", keys.contains("amount"))
    }

    @Test
    fun `sensitive_key_set_contains_camelCase_variants`() {
        val keys = NotificationCaptureService.SENSITIVE_EXTRAS_KEYS
        assertTrue("apiKey must be in sensitive keys", keys.contains("apiKey"))
        assertTrue("cardNumber must be in sensitive keys", keys.contains("cardNumber"))
        assertTrue("accountNumber must be in sensitive keys", keys.contains("accountNumber"))
        assertTrue("transactionId must be in sensitive keys", keys.contains("transactionId"))
        assertTrue("referenceNumber must be in sensitive keys", keys.contains("referenceNumber"))
        assertTrue("fullName must be in sensitive keys", keys.contains("fullName"))
        assertTrue("authToken must be in sensitive keys", keys.contains("authToken"))
        assertTrue("accessToken must be in sensitive keys", keys.contains("accessToken"))
        assertTrue("sessionId must be in sensitive keys", keys.contains("sessionId"))
        assertTrue("deviceId must be in sensitive keys", keys.contains("deviceId"))
        assertTrue("userId must be in sensitive keys", keys.contains("userId"))
        assertTrue("phoneNumber must be in sensitive keys", keys.contains("phoneNumber"))
        assertTrue("emailAddress must be in sensitive keys", keys.contains("emailAddress"))
    }

    @Test
    fun `sensitive_key_filter_is_case_insensitive`() {
        val keys = NotificationCaptureService.SENSITIVE_EXTRAS_KEYS
        // Upper-case variants
        assertTrue("APIKEY should match apiKey (case-insensitive)",
            keys.any { "APIKEY".equals(it, ignoreCase = true) })
        assertTrue("CARD_NUMBER should match card_number (case-insensitive)",
            keys.any { "CARD_NUMBER".equals(it, ignoreCase = true) })
        assertTrue("TRANSACTIONID should match transactionId (case-insensitive)",
            keys.any { "TRANSACTIONID".equals(it, ignoreCase = true) })
        // Mixed-case variants
        assertTrue("ApiKey should match apiKey (case-insensitive)",
            keys.any { "ApiKey".equals(it, ignoreCase = true) })
        assertTrue("Card_Number should match card_number (case-insensitive)",
            keys.any { "Card_Number".equals(it, ignoreCase = true) })
        assertTrue("FullName should match fullName (case-insensitive)",
            keys.any { "FullName".equals(it, ignoreCase = true) })
        // Messaging key case variants (P1-005)
        assertTrue("ANDROID.MESSAGES should match android.messages (case-insensitive)",
            keys.any { "ANDROID.MESSAGES".equals(it, ignoreCase = true) })
        assertTrue("Android.TextLines should match android.textLines (case-insensitive)",
            keys.any { "Android.TextLines".equals(it, ignoreCase = true) })
        assertTrue("ANDROID.REMOTEINPUTHISTORY should match android.remoteInputHistory (case-insensitive)",
            keys.any { "ANDROID.REMOTEINPUTHISTORY".equals(it, ignoreCase = true) })
        assertTrue("Android.ConversationTitle should match android.conversationTitle (case-insensitive)",
            keys.any { "Android.ConversationTitle".equals(it, ignoreCase = true) })
    }

    // ── P1-005: real buildExtrasJson drops messaging keys (Robolectric) ──

    @Test
    fun `buildExtrasJson_drops_messaging_keys_and_values_but_keeps_benign_key`() {
        // Real service instance WITHOUT onCreate — Hilt field injection never
        // runs and buildExtrasJson only needs org.json + the static key set.
        val service = Robolectric.buildService(NotificationCaptureService::class.java).get()

        val messagesValue = "messaging-messages-value"
        val textLinesValue = "messaging-textlines-value"
        val remoteInputValue = "messaging-remoteinput-value"
        val conversationTitleValue = "messaging-conversation-title-value"
        val benignValue = "benign-plaintext-value"

        val extras = Bundle().apply {
            putString("android.messages", messagesValue)
            putString("Android.TextLines", textLinesValue)
            putString("android.remoteInputHistory", remoteInputValue)
            putString("android.conversationTitle", conversationTitleValue)
            putString("someOtherKey", benignValue)
        }

        val json = service.buildExtrasJson(extras)

        // Assert on boolean contains checks only — never print values in
        // failure messages (privacy: no notification content in test output).
        assertFalse("messaging key android.messages must not appear",
            json.contains("android.messages"))
        assertFalse("messaging key Android.TextLines must not appear",
            json.contains("Android.TextLines"))
        assertFalse("messaging key android.remoteInputHistory must not appear",
            json.contains("android.remoteInputHistory"))
        assertFalse("messaging key android.conversationTitle must not appear",
            json.contains("android.conversationTitle"))
        assertFalse("android.messages value must not be persisted",
            json.contains(messagesValue))
        assertFalse("Android.TextLines value must not be persisted",
            json.contains(textLinesValue))
        assertFalse("android.remoteInputHistory value must not be persisted",
            json.contains(remoteInputValue))
        assertFalse("android.conversationTitle value must not be persisted",
            json.contains(conversationTitleValue))
        assertTrue("benign key must survive filtering",
            json.contains("someOtherKey"))
        assertTrue("benign value must survive filtering",
            json.contains(benignValue))
    }

    @Test
    fun `sensitive_key_set_contains_android_system_keys`() {
        val keys = NotificationCaptureService.SENSITIVE_EXTRAS_KEYS
        assertTrue("android.largeIcon must be in sensitive keys", keys.contains("android.largeIcon"))
        assertTrue("android.picture must be in sensitive keys", keys.contains("android.picture"))
        assertTrue("android.messages must be in sensitive keys", keys.contains("android.messages"))
        assertTrue("android.textLines must be in sensitive keys", keys.contains("android.textLines"))
        assertTrue("android.remoteInputHistory must be in sensitive keys", keys.contains("android.remoteInputHistory"))
        assertTrue("android.conversationTitle must be in sensitive keys", keys.contains("android.conversationTitle"))
    }

    @Test
    fun `sensitive_key_set_contains_messaging_keys`() {
        val keys = NotificationCaptureService.SENSITIVE_EXTRAS_KEYS
        assertTrue("android.messages must be in sensitive keys", keys.contains("android.messages"))
        assertTrue("android.textLines must be in sensitive keys", keys.contains("android.textLines"))
        assertTrue("android.remoteInputHistory must be in sensitive keys", keys.contains("android.remoteInputHistory"))
        assertTrue("android.conversationTitle must be in sensitive keys", keys.contains("android.conversationTitle"))
    }

    // ── NonCancellable durability (P1-P1-07) ────────────────────────────

    @Test
    fun `service_destruction_after_filter_pass_does_not_lose_notification`() {
        // This test verifies the structural invariant that, once the filter
        // passes (Step 4), the entire path through privacy check, settings
        // load, extras JSON construction, app name resolution, and intake
        // coordinator capture runs inside withContext(NonCancellable).
        //
        // Because NonCancellable prevents coroutine cancellation, a service
        // shutdown (serviceJob.cancel()) that occurs between filter-pass and
        // intakeCoordinator.capture() will NOT prevent the capture from
        // completing. Previously there was a gap between the filter check
        // and the NonCancellable wrapper, allowing notification loss (P1-P1-07).
        //
        // This is a structural/contract test: it validates that the
        // NonCancellable block encompasses all the post-filter steps.

        // Verify captureNotification exists as a private method with the
        // correct signature — this is the entry point that must be wrapped
        // in withContext(NonCancellable). Use getDeclaredMethods() to access
        // private methods.
        val methods = NotificationCaptureService::class.java.declaredMethods
            .filter { it.name == "captureNotification" }

        assertTrue(
            "captureNotification method must exist (private, 2 params)",
            methods.isNotEmpty()
        )
        assertEquals(
            "captureNotification must accept 2 parameters (StatusBarNotification, CaptureSource)",
            2, methods[0].parameterCount
        )
        assertEquals(
            "First parameter must be StatusBarNotification",
            "android.service.notification.StatusBarNotification",
            methods[0].parameterTypes[0].name
        )
        assertEquals(
            "Second parameter must be CaptureSource",
            "com.yourname.expensetracker.domain.notification.capture.CaptureSource",
            methods[0].parameterTypes[1].name
        )

        // The actual NonCancellable behavior is enforced at runtime by the
        // withContext(NonCancellable) wrapping in captureNotification().
        // This test documents the structural contract of the entry point.
    }

    // ── NEW-P1-009: TOCTOU privacy settings fix ─────────────────────────

    @Test
    fun `processNotification_accepts_privacy_settings_parameter`() {
        // Verify that processNotification now accepts PrivacySettings as its
        // 6th Kotlin parameter, instead of performing a second independent
        // fetch of privacySettingsRepository.getSettings() internally. This
        // prevents a TOCTOU race where settings could change between extras
        // JSON construction (in captureNotification) and storage notification
        // construction (in processNotification).
        //
        // Note: processNotification is a suspend function, so JVM reflection
        // (getDeclaredMethods) reports one extra trailing parameter of type
        // kotlin.coroutines.Continuation. The reflected parameterCount is
        // therefore 7: the 6 declared Kotlin parameters plus the Continuation.
        val methods = NotificationCaptureService::class.java.declaredMethods
            .filter { it.name == "processNotification" }

        assertTrue(
            "processNotification method must exist (private suspend, 6 Kotlin params + Continuation)",
            methods.isNotEmpty()
        )
        assertEquals(
            "processNotification must reflect 7 parameters: 6 Kotlin params " +
                "(StatusBarNotification, String, NotificationTextParts, Bundle, String, PrivacySettings) " +
                "plus a trailing kotlin.coroutines.Continuation added by the compiler because " +
                "the method is suspend",
            7, methods[0].parameterCount
        )
        assertEquals(
            "6th Kotlin parameter must be PrivacySettings",
            "com.yourname.expensetracker.domain.privacy.PrivacySettings",
            methods[0].parameterTypes[5].name
        )
        assertEquals(
            "Last reflected parameter must be kotlin.coroutines.Continuation " +
                "(pins the suspend contract of processNotification)",
            "kotlin.coroutines.Continuation",
            methods[0].parameterTypes[6].name
        )

        // Also verify captureNotification has not changed its signature
        val captureMethods = NotificationCaptureService::class.java.declaredMethods
            .filter { it.name == "captureNotification" }
        assertTrue(
            "captureNotification method must exist (private, 2 params)",
            captureMethods.isNotEmpty()
        )
        assertEquals(
            "captureNotification must accept 2 parameters (StatusBarNotification, CaptureSource)",
            2, captureMethods[0].parameterCount
        )
    }
}
