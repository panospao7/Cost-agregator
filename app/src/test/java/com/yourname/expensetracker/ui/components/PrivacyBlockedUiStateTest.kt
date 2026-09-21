package com.yourname.expensetracker.ui.components

import com.yourname.expensetracker.domain.privacy.PrivacyBlocked
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGateReasonCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RP-15 (15-D) — typed denied UI.
 *
 * Covers the [PrivacyBlockedUiState] adapter (every domain blocked subclass →
 * resource-backed state with a controlled reason code), decision mapping
 * (denied / fail-closed / allowed), and the static source checks that keep
 * raw reason/exception text out of the rendered UI.
 */
class PrivacyBlockedUiStateTest {

    // ── Adapter: every PrivacyBlocked subclass maps to a dedicated resource ──

    @Test
    fun `every typed blocked subclass maps to a resource-backed state with a controlled code`() {
        val samples: List<PrivacyBlocked> = listOf(
            PrivacyBlocked.CloudAiDisabled(),
            PrivacyBlocked.ReceiptImageUploadDisabled(),
            PrivacyBlocked.ExternalGeocodingDisabled(),
            PrivacyBlocked.NotificationCaptureDisabled(),
            PrivacyBlocked.RawExportDisabled(),
            PrivacyBlocked.DeviceGpsDisabled(),
            PrivacyBlocked.BackgroundLocationDisabled(),
            PrivacyBlocked.BankStatementAiDisabled(),
            PrivacyBlocked.EncryptedBackupDisabled(),
            PrivacyBlocked.OverpassDisabled(),
            PrivacyBlocked.DebugDataPersistenceDisabled()
        )
        for (blocked in samples) {
            val state = PrivacyBlockedUiState.fromBlocked(blocked)
            assertEquals("capability($blocked)", blocked.capability, state.capability)
            assertTrue(
                "message for $blocked must be a string resource id",
                state.messageResId != 0
            )
            assertTrue(
                "reasonCode for $blocked must be a bounded code",
                state.reasonCode in PrivacyGateReasonCodes.ALL
            )
        }
    }

    @Test
    fun `custom blocked keeps capability and bounded code but never raw text`() {
        val withCode = PrivacyBlockedUiState.fromBlocked(
            PrivacyBlocked.Custom(PrivacyCapability.CLOUD_AI_GENERAL, PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE)
        )
        assertEquals(R_generic_marker, withCode.messageResId) // generic resource, not the code text
        assertEquals(PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE, withCode.reasonCode)

        // A Custom carrying unrecognized text must not pass that text through.
        val withText = PrivacyBlockedUiState.fromBlocked(
            PrivacyBlocked.Custom(PrivacyCapability.CLOUD_AI_GENERAL, "some leaked detail")
        )
        assertEquals(R_generic_marker, withText.messageResId)
        assertEquals(PrivacyGateReasonCodes.PRIVACY_BLOCKED, withText.reasonCode)
    }

    /** The resource id of privacy_blocked_generic, resolved from the adapter source. */
    private val R_generic_marker: Int
        get() = PrivacyBlockedUiState.fromBlocked(
            PrivacyBlocked.Custom(PrivacyCapability.CLOUD_AI_GENERAL, PrivacyGateReasonCodes.PRIVACY_BLOCKED)
        ).messageResId

    // ── Decision mapping ──────────────────────────────────────────────────────

    @Test
    fun `denied decision maps to typed state`() {
        val state = PrivacyBlockedUiState.fromDecision(
            PrivacyDecision.Denied("any gate text"),
            PrivacyCapability.CLOUD_AI_BANK_STATEMENT
        )
        assertNotNull(state)
        assertEquals(PrivacyCapability.CLOUD_AI_BANK_STATEMENT, state!!.capability)
        assertEquals(PrivacyGateReasonCodes.BANK_STATEMENT_AI_DISABLED, state.reasonCode)
    }

    @Test
    fun `fail_closed decision maps to generic state with gate failure code`() {
        val state = PrivacyBlockedUiState.fromDecision(
            PrivacyDecision.FailClosed(PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE),
            PrivacyCapability.DEVICE_GPS_LOCATION
        )
        assertNotNull(state)
        assertEquals(PrivacyCapability.DEVICE_GPS_LOCATION, state!!.capability)
        assertEquals(PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE, state.reasonCode)
    }

    @Test
    fun `allowed and not_applicable map to null and fallback never fails`() {
        assertNull(PrivacyBlockedUiState.fromDecision(PrivacyDecision.Allowed, PrivacyCapability.DEVICE_GPS_LOCATION))
        assertNull(PrivacyBlockedUiState.fromDecision(PrivacyDecision.NotApplicable, PrivacyCapability.DEVICE_GPS_LOCATION))

        val fallback = PrivacyBlockedUiState.fromDecisionOrFallback(
            PrivacyDecision.Allowed,
            PrivacyCapability.DEVICE_GPS_LOCATION
        )
        assertEquals(PrivacyGateReasonCodes.PRIVACY_BLOCKED, fallback.reasonCode)
    }

    // ── Static source checks: no raw reason/exception rendering in the UI ────

    private val sourceRoot: File by lazy { resolveSourceRoot() }

    private fun resolveSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate production source root. user.dir=${System.getProperty("user.dir")}")
    }

    private fun readProductionSource(relativePath: String): String {
        val file = File(sourceRoot, relativePath)
        assertTrue("Expected production source at ${file.absolutePath}", file.exists())
        return file.readText()
    }

    @Test
    fun `static_card_renders_resource_backed_text_only`() {
        val cardSource = readProductionSource(
            "com/yourname/expensetracker/ui/components/PrivacyBlockedCard.kt"
        )
        // The card must render the adapter's resource, never the domain reason string.
        assertTrue(
            "card must render stringResource(state.messageResId)",
            cardSource.contains("stringResource(state.messageResId)")
        )
        assertTrue(
            "card must bridge the domain state through the adapter",
            cardSource.contains("PrivacyBlockedUiState.fromBlocked(blocked)")
        )
        // `.reason` must not appear anywhere in the card (covers contentDescription too).
        val withoutComments = cardSource
            .replace(Regex("/\\*\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\n]*"), "")
        assertTrue(
            "card must not render the domain reason string",
            !Regex("\\.reason\\b").containsMatchIn(withoutComments)
        )
    }

    @Test
    fun `static_no_denied_message_matching_remains_in_converged_viewmodels`() {
        val converged = listOf(
            "com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModel.kt",
            "com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModel.kt",
            "com/yourname/expensetracker/ui/screens/map/SpendingMapViewModel.kt",
            "com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt"
        )
        for (path in converged) {
            val text = readProductionSource(path)
            assertTrue(
                "$path must not message-match 'denied'",
                !text.contains("contains(\"denied\"")
            )
            assertTrue(
                "$path must not render e.message into user-visible state",
                !Regex("""\$\{e\.message""").containsMatchIn(text)
            )
        }
    }

    @Test
    fun `static_viewmodels_consume_the_typed_blocked_state`() {
        val expected = listOf(
            "com/yourname/expensetracker/ui/screens/backup/BackupRestoreViewModel.kt" to "privacyBlocked",
            "com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModel.kt" to "privacyBlocked",
            "com/yourname/expensetracker/ui/screens/map/SpendingMapViewModel.kt" to "PrivacyBlockedUiState",
            "com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt" to "debugExportBlocked"
        )
        for ((path, marker) in expected) {
            val text = readProductionSource(path)
            assertTrue("$path must reference $marker", text.contains(marker))
        }
    }

    @Test
    fun `negative_fixture_reason_rendering_is_detected_by_scan`() {
        val badCard = """
            @Composable
            fun BadCard(blocked: PrivacyBlocked) {
                Text(blocked.reason)
            }
        """.trimIndent()
        assertTrue(Regex("\\.reason\\b").containsMatchIn(badCard))
    }
}
