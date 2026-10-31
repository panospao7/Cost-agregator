package com.yourname.expensetracker.ui.applock.pin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.privacy.applock.EffectiveLockMode
import com.yourname.expensetracker.domain.privacy.applock.PinPresentation
import com.yourname.expensetracker.domain.privacy.applock.PinRecovery
import com.yourname.expensetracker.domain.privacy.applock.PinRejection
import com.yourname.expensetracker.domain.privacy.applock.PinStep
import com.yourname.expensetracker.domain.privacy.applock.PinUnlockResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDelayTier
import com.yourname.expensetracker.ui.applock.AppLockComposeTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** S27 a2: the PIN lock surface (INV-38 zeroing, INV-40 no fallback, INV-41 recovery, INV-54[2] fresh dots, AE-119). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PinLockSurfaceTest : AppLockComposeTestBase() {

    private class FakeActions : PinLockActions {
        var result: PinUnlockResult = PinUnlockResult.Rejected(PinRejection.Wrong(PinDelayTier.NONE))
        var submitCalls = 0
        var arrayRef: CharArray? = null
        var digitsSeen: String? = null
        var retryCalls = 0
        var fingerprintCalls = 0

        override suspend fun submitUnlockPin(pin: CharArray): PinUnlockResult {
            submitCalls++
            arrayRef = pin
            digitsSeen = String(pin)
            return result
        }

        override suspend fun retryPinRecovery() { retryCalls++ }

        override suspend fun requestFingerprintUnlock() { fingerprintCalls++ }
    }

    private val actions = FakeActions()
    private var settingsOpened = 0
    private val presentation = mutableStateOf(pin())
    private val promptInFlight = mutableStateOf(false)

    private fun pin(
        recovery: PinRecovery? = null,
        fingerprint: Boolean = false,
        delayMillis: Long? = null
    ) = PinPresentation(EffectiveLockMode.APP_PIN, PinStep.NONE, recovery, fingerprint, delayMillis)

    private fun show() {
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            PinLockSurface(
                pin = presentation.value,
                promptInFlight = promptInFlight.value,
                actions = actions,
                scope = scope,
                onOpenAppSettings = { settingsOpened++ }
            )
        }
    }

    private fun press(vararg digits: Char) =
        digits.forEach { composeRule.onNodeWithTag(PinPadTags.KEY_PREFIX + it).performClick() }

    private fun dots(): String? = composeRule.onNodeWithTag(PinPadTags.DOT_ROW)
        .fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun count(tag: String, expected: Int) = composeRule.onAllNodesWithTag(tag).assertCountEquals(expected)

    private fun typeAndConfirm(digits: String) {
        press(*digits.toCharArray())
        composeRule.onNodeWithTag(PinPadTags.CONFIRM).performClick()
        composeRule.waitForIdle()
    }

    // ---- ready state ----

    @Test
    fun inv40_ready_showsPadAndEmptyDots_noRetry_noUnreadable() {
        show()
        count(PinLockTags.SURFACE, 1)
        assertEquals("0 digits entered", dots())
        composeRule.onNodeWithTag(PinPadTags.KEY_PREFIX + "5").assertIsEnabled()
        count(PinPadTags.CONFIRM, 1)
        count(PinLockTags.RETRY, 0)
        count(PinLockTags.UNREADABLE, 0)
        composeRule.onNodeWithTag(PinLockTags.MESSAGE).assertTextEquals("")
        count(PinLockTags.FORGOT, 1)
    }

    @Test
    fun inv38_confirm_submitsTypedDigits_andZeroesTheArrayAfterTheCallReturns() {
        show()
        typeAndConfirm("135790")
        assertEquals(1, actions.submitCalls)
        assertEquals("135790", actions.digitsSeen)
        val ref = actions.arrayRef
        assertNotNull(ref)
        assertTrue("array must be zeroed after the call returned", ref!!.all { it == '\u0000' })
        assertEquals("0 digits entered", dots())
    }

    @Test
    fun inv38_submitWithUnlockedResult_alsoZeroesTheArray() {
        actions.result = PinUnlockResult.Unlocked
        show()
        typeAndConfirm("246801")
        assertEquals("246801", actions.digitsSeen)
        assertTrue(actions.arrayRef!!.all { it == '\u0000' })
    }

    @Test
    fun inv38_confirmBelowMinimumLength_neverSubmits() {
        show()
        press('1', '2')
        composeRule.onNodeWithTag(PinPadTags.CONFIRM).assertIsNotEnabled()
        assertEquals(0, actions.submitCalls)
    }

    // ---- rejections ----

    @Test
    fun wrongPin_showsWrongMessage_andClearsDots_padStaysEnabled() {
        actions.result = PinUnlockResult.Rejected(PinRejection.Wrong(PinDelayTier.NONE))
        show()
        typeAndConfirm("135790")
        composeRule.onNodeWithTag(PinLockTags.MESSAGE).assertTextEquals(text(R.string.applock_pin_wrong))
        assertEquals("0 digits entered", dots())
        composeRule.onNodeWithTag(PinPadTags.KEY_PREFIX + "1").assertIsEnabled()
    }

    @Test
    fun wrongPin_nextEntryClearsTheWrongMessage_whileSubmitting() {
        show()
        typeAndConfirm("135790")
        composeRule.onNodeWithTag(PinLockTags.MESSAGE).assertTextEquals(text(R.string.applock_pin_wrong))
        actions.result = PinUnlockResult.Unlocked
        typeAndConfirm("111111")
        composeRule.onNodeWithTag(PinLockTags.MESSAGE).assertTextEquals("")
    }

    @Test
    fun inv38_activeDelay_disablesPad_showsCountdown_andClearsDots() {
        composeRule.mainClock.autoAdvance = false
        actions.result = PinUnlockResult.Rejected(PinRejection.Wrong(PinDelayTier.S30))
        show()
        typeAndConfirm("135790")
        // autoAdvance is off, so compose one frame for the submit result before asserting (as in the published-delay test).
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        val waitPrefix = text(R.string.applock_pin_wait, "\u0001").substringBefore("\u0001")
        composeRule.onNodeWithTag(PinLockTags.MESSAGE).assertTextContains(waitPrefix, substring = true)
        composeRule.onNodeWithTag(PinLockTags.MESSAGE).assertTextContains("seconds", substring = true)
        composeRule.onNodeWithTag(PinPadTags.KEY_PREFIX + "5").assertIsNotEnabled()
        composeRule.onNodeWithTag(PinPadTags.CONFIRM).assertIsNotEnabled()
        composeRule.onNodeWithTag(PinPadTags.KEY_DELETE).assertIsNotEnabled()
        assertEquals("0 digits entered", dots())
    }

    @Test
    fun inv38_publishedDelay_disablesPad_andShowsCountdown() {
        composeRule.mainClock.autoAdvance = false
        presentation.value = pin(delayMillis = 120_000L)
        show()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        val waitPrefix = text(R.string.applock_pin_wait, "\u0001").substringBefore("\u0001")
        composeRule.onNodeWithTag(PinLockTags.MESSAGE).assertTextContains(waitPrefix, substring = true)
        composeRule.onNodeWithTag(PinPadTags.KEY_PREFIX + "3").assertIsNotEnabled()
    }

    @Test
    fun s27b_publishedNeutralDelay_showsNeutralCopy_notTooManyAttempts_andDisablesPad() {
        composeRule.mainClock.autoAdvance = false
        presentation.value = PinPresentation(EffectiveLockMode.APP_PIN, PinStep.NONE, null, false, 120_000L, true)
        show()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        val neutralPrefix = text(R.string.applock_pin_wait_neutral, "\u0001").substringBefore("\u0001")
        val tooManyPrefix = text(R.string.applock_pin_wait, "\u0001").substringBefore("\u0001")
        composeRule.onNodeWithTag(PinLockTags.MESSAGE).assertTextContains(neutralPrefix, substring = true)
        assertTrue(neutralPrefix != tooManyPrefix)
        composeRule.onAllNodesWithText(tooManyPrefix, substring = true).assertCountEquals(0)
        composeRule.onNodeWithTag(PinPadTags.KEY_PREFIX + "3").assertIsNotEnabled()
    }

    @Test
    fun promptInFlight_disablesPad() {
        promptInFlight.value = true
        show()
        composeRule.onNodeWithTag(PinPadTags.KEY_PREFIX + "3").assertIsNotEnabled()
        promptInFlight.value = false
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PinPadTags.KEY_PREFIX + "3").assertIsEnabled()
    }

    // ---- unreadable verifier (INV-41, INV-40: no fallback) ----

    private fun assertPadHidden() {
        count(PinPadTags.DOT_ROW, 0)
        count(PinPadTags.CONFIRM, 0)
        count(PinPadTags.KEY_PREFIX + "1", 0)
        count(PinPadTags.KEY_FINGERPRINT, 0)
    }

    @Test
    fun inv41_retryableRecovery_hidesPad_showsMessageAndRetry_retryCallsTheAction() {
        presentation.value = pin(recovery = PinRecovery.KEYSTORE_ERROR)
        show()
        assertPadHidden()
        composeRule.onNodeWithTag(PinLockTags.UNREADABLE).assertTextEquals(text(R.string.applock_pin_unreadable))
        count(PinLockTags.MESSAGE, 0)
        composeRule.onNodeWithTag(PinLockTags.RETRY).performClick()
        composeRule.waitForIdle()
        assertEquals(1, actions.retryCalls)
        assertEquals(0, actions.submitCalls)
        count(PinLockTags.FORGOT, 1)
    }

    @Test
    fun inv41_recordUnreadable_isAlsoRetryable() {
        presentation.value = pin(recovery = PinRecovery.RECORD_UNREADABLE)
        show()
        count(PinLockTags.RETRY, 1)
    }

    @Test
    fun inv41_nonRetryableRecovery_hidesPadAndRetry_stillShowsForgottenPinHelp() {
        presentation.value = pin(recovery = PinRecovery.KEY_MISSING, fingerprint = true)
        show()
        assertPadHidden()
        count(PinLockTags.RETRY, 0)
        count(PinLockTags.UNREADABLE, 1)
        count(PinLockTags.FORGOT, 1)
        assertEquals(0, actions.retryCalls)
    }

    @Test
    fun inv40_forgottenPinHelp_opensDialog_settingsAndCloseActionsWork() {
        presentation.value = pin(recovery = PinRecovery.KEY_MISSING)
        show()
        count(PinLockTags.FORGOT_DIALOG, 0)
        composeRule.onNodeWithTag(PinLockTags.FORGOT).performClick()
        count(PinLockTags.FORGOT_DIALOG, 1)
        composeRule.onNodeWithTag(PinLockTags.FORGOT_OPEN_SETTINGS).performClick()
        assertEquals(1, settingsOpened)
        composeRule.onNodeWithTag(PinLockTags.FORGOT_CLOSE).performClick()
        composeRule.waitForIdle()
        count(PinLockTags.FORGOT_DIALOG, 0)
    }

    // ---- fingerprint key (INV-34: a tap only) ----

    @Test
    fun fingerprintKey_hiddenUnlessCanOfferFingerprint() {
        presentation.value = pin(fingerprint = false)
        show()
        count(PinPadTags.KEY_FINGERPRINT, 0)
        assertEquals(0, actions.fingerprintCalls)
    }

    @Test
    fun fingerprintKey_shownWhenOffered_tapCallsRequestFingerprintUnlockOnly() {
        presentation.value = pin(fingerprint = true)
        show()
        assertEquals("composition alone must not request a prompt", 0, actions.fingerprintCalls)
        composeRule.onNodeWithTag(PinPadTags.KEY_FINGERPRINT).performClick()
        composeRule.waitForIdle()
        assertEquals(1, actions.fingerprintCalls)
        assertEquals(0, actions.submitCalls)
    }

    // ---- AE-119 / INV-54[2]: a re-lock starts with empty dots ----

    @Test
    fun ae119_inv54_relock_surfaceLeavesAndReturns_dotsStartEmpty() {
        val locked = mutableStateOf(true)
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            if (locked.value) {
                PinLockSurface(presentation.value, false, actions, scope, { })
            }
        }
        press('1', '2', '3')
        assertEquals("3 digits entered", dots())
        composeRule.runOnIdle { locked.value = false }
        composeRule.waitForIdle()
        count(PinLockTags.SURFACE, 0)
        composeRule.runOnIdle { locked.value = true }
        composeRule.waitForIdle()
        assertEquals("0 digits entered", dots())
    }

    @Test
    fun ae119_inv54_relock_freshKey_dotsStartEmpty() {
        var epoch by mutableStateOf(0)
        composeRule.setContent {
            val scope = rememberCoroutineScope()
            androidx.compose.runtime.key(epoch) {
                PinLockSurface(presentation.value, false, actions, scope, { })
            }
        }
        press('4', '5')
        assertEquals("2 digits entered", dots())
        composeRule.runOnIdle { epoch++ }
        composeRule.waitForIdle()
        assertEquals("0 digits entered", dots())
        assertEquals(0, actions.submitCalls)
    }
}
