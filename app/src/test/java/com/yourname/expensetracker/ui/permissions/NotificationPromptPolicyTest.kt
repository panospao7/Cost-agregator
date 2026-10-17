package com.yourname.expensetracker.ui.permissions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPromptPolicyTest {

    @Test
    fun `auto prompts once on API 33 when not granted and never asked`() {
        assertTrue(NotificationPromptPolicy.shouldAutoPrompt(granted = false, sdkInt = 33, alreadyAsked = false))
        assertTrue(NotificationPromptPolicy.shouldAutoPrompt(granted = false, sdkInt = 35, alreadyAsked = false))
    }

    @Test
    fun `never auto prompts below API 33`() {
        assertFalse(NotificationPromptPolicy.shouldAutoPrompt(granted = false, sdkInt = 32, alreadyAsked = false))
        assertFalse(NotificationPromptPolicy.shouldAutoPrompt(granted = false, sdkInt = 26, alreadyAsked = false))
    }

    @Test
    fun `never auto prompts when already granted`() {
        assertFalse(NotificationPromptPolicy.shouldAutoPrompt(granted = true, sdkInt = 33, alreadyAsked = false))
    }

    @Test
    fun `never auto prompts again after being asked`() {
        assertFalse(NotificationPromptPolicy.shouldAutoPrompt(granted = false, sdkInt = 33, alreadyAsked = true))
        assertFalse(NotificationPromptPolicy.shouldAutoPrompt(granted = false, sdkInt = 34, alreadyAsked = true))
    }

    @Test
    fun `offers settings only when denied and system showed no rationale before or after`() {
        assertTrue(offer(rationaleBefore = false, rationaleAfter = false, granted = false))
        // First-time denial: system dialog was shown, rationale becomes available afterwards.
        assertFalse(offer(rationaleBefore = false, rationaleAfter = true, granted = false))
        // Rationale was available before the request (system dialog shown).
        assertFalse(offer(rationaleBefore = true, rationaleAfter = false, granted = false))
        assertFalse(offer(rationaleBefore = true, rationaleAfter = true, granted = false))
    }

    @Test
    fun `never offers settings when permission was granted`() {
        assertFalse(offer(rationaleBefore = false, rationaleAfter = false, granted = true))
        assertFalse(offer(rationaleBefore = true, rationaleAfter = true, granted = true))
    }

    private fun offer(rationaleBefore: Boolean, rationaleAfter: Boolean, granted: Boolean) =
        NotificationPromptPolicy.shouldOfferSettingsAfterDenial(rationaleBefore, rationaleAfter, granted)
}
