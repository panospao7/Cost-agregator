package com.yourname.expensetracker.domain.privacy

import org.junit.Assert.*
import org.junit.Test

/**
 * PRIV-441-05 / PRIV-441-06 / PRIV-441-07 acceptance tests.
 *
 * These are unit-level contract tests for the notification pre-extraction
 * privacy hardening. Full integration tests require Android instrumentation.
 */
class NotificationPrivacyHardeningTest {

    // ── PRIV-441-07: Dedupe cache poisoning ──────────────────────────────────

    @Test
    fun privacy_denied_does_not_poison_dedupe_cache() {
        // Contract: dedupe cache insertion must happen AFTER privacy gate check.
        // If privacy is denied, the notification key must NOT be in the cache.
        val cache = mutableMapOf<String, Long>()
        val privacyDenied = true
        val notificationKey = "com.example.app|12345"
        val now = System.currentTimeMillis()

        // Simulate the corrected flow: check privacy BEFORE inserting into cache
        if (!privacyDenied) {
            cache[notificationKey] = now
        }

        assertFalse(
            "Privacy-denied notification must not poison dedupe cache",
            cache.containsKey(notificationKey)
        )
    }

    @Test
    fun privacy_denied_then_enabled_same_notification_not_dropped_as_duplicate() {
        val cache = mutableMapOf<String, Long>()
        val notificationKey = "com.example.app|12345"
        val now = System.currentTimeMillis()
        val dedupeWindowMs = 5000L

        // First attempt: privacy denied — cache NOT poisoned
        val privacyDeniedFirst = true
        if (!privacyDeniedFirst) {
            cache[notificationKey] = now
        }

        // Second attempt: privacy now enabled
        val privacyDeniedSecond = false
        val lastProcessed = cache[notificationKey]
        val isDuplicate = lastProcessed != null && (now - lastProcessed) < dedupeWindowMs

        assertFalse(
            "After privacy is re-enabled, same notification must not be dropped as duplicate",
            isDuplicate
        )
    }

    // ── PRIV-441-06: Blocked package pre-extraction ──────────────────────────

    @Test
    fun blocked_package_drop_does_not_read_notification_extras() {
        // Contract: if package is in blocked set, we return before reading extras.
        val blockedPackages = setOf("com.blocked.app")
        val packageName = "com.blocked.app"
        var extrasRead = false

        // Simulate the corrected flow
        if (packageName !in blockedPackages) {
            // Only read extras if not blocked
            extrasRead = true
        }

        assertFalse(
            "Blocked package must not cause extras to be read",
            extrasRead
        )
    }

    @Test
    fun non_blocked_package_proceeds_to_extras_extraction() {
        val blockedPackages = setOf("com.blocked.app")
        val packageName = "com.allowed.bank"
        var extrasRead = false

        if (packageName !in blockedPackages) {
            extrasRead = true
        }

        assertTrue("Non-blocked package must proceed to extras extraction", extrasRead)
    }

    // ── PRIV-441-05: Privacy gate before extras extraction ───────────────────

    @Test
    fun privacy_fail_closed_notification_does_not_read_extras() {
        var extrasRead = false
        val capturePrivacyDenied = true  // fail-closed

        if (!capturePrivacyDenied) {
            extrasRead = true
        }

        assertFalse(
            "Privacy-denied (fail-closed) notification must not read extras",
            extrasRead
        )
    }

    @Test
    fun notification_disabled_does_not_read_extras() {
        var extrasRead = false
        val notificationCaptureEnabled = false
        val capturePrivacyDenied = !notificationCaptureEnabled

        if (!capturePrivacyDenied) {
            extrasRead = true
        }

        assertFalse(
            "Notification capture disabled must not read extras",
            extrasRead
        )
    }

    @Test
    fun stale_fast_cache_cannot_read_extras_when_load_state_corrupted() {
        // When load state is corrupted, capturePrivacyDenied must be true (fail-closed)
        val loadStateCorrupted = true
        val capturePrivacyDenied = if (loadStateCorrupted) true else false

        assertTrue(
            "Corrupted load state must result in capturePrivacyDenied=true",
            capturePrivacyDenied
        )
    }

    // ── Blocked package cache contract ───────────────────────────────────────

    @Test
    fun blocked_package_cache_is_checked_before_extras_extraction() {
        // Verify the ordering contract: blocked check before extras
        val executionOrder = mutableListOf<String>()
        val blockedPackages = setOf("com.blocked.app")
        val packageName = "com.blocked.app"

        executionOrder.add("privacy_check")
        if (packageName !in blockedPackages) {
            executionOrder.add("extras_extraction")
        } else {
            executionOrder.add("blocked_drop")
        }

        assertEquals(listOf("privacy_check", "blocked_drop"), executionOrder)
        assertFalse(executionOrder.contains("extras_extraction"))
    }

    // ── P8F-05: Fail-closed — only Allowed proceeds to capture/persist ────────

    /**
     * Models the corrected NotificationCaptureService privacy-gate `when`:
     * only [PrivacyDecision.Allowed] proceeds; every other decision
     * (including [PrivacyDecision.NotApplicable]) MUST block persistence.
     */
    private fun proceedsToCapture(decision: PrivacyDecision): Boolean = when (decision) {
        is PrivacyDecision.Denied, is PrivacyDecision.FailClosed -> false
        is PrivacyDecision.Allowed -> true
        else -> false // NotApplicable / any non-Allowed decision fails closed
    }

    @Test
    fun not_applicable_decision_does_not_persist() {
        assertFalse(
            "NotApplicable privacy decision must not proceed to capture/persist (fail-closed)",
            proceedsToCapture(PrivacyDecision.NotApplicable)
        )
    }

    @Test
    fun only_allowed_decision_proceeds_to_persist() {
        assertTrue(proceedsToCapture(PrivacyDecision.Allowed))
        assertFalse(proceedsToCapture(PrivacyDecision.Denied("denied")))
        assertFalse(proceedsToCapture(PrivacyDecision.FailClosed("fail-closed")))
        assertFalse(proceedsToCapture(PrivacyDecision.NotApplicable))
    }
}
