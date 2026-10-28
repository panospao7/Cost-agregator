package com.yourname.expensetracker.domain.privacy.applock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S18 ii-d: INV-45 classification as a pure function of the record (AE-85, AE-86). */
class ReviewSaveAuthorisationTest {

    private fun record(
        lock: Boolean,
        timeout: AutoLockTimeout,
        discreet: Boolean = false,
        secure: Boolean = false
    ) = AppLockPrivacySettings(
        discreetNotifications = discreet,
        appLockEnabled = lock,
        autoLockTimeout = timeout,
        secureScreen = secure
    )

    @Test
    fun `AE-85 lock off is weakening for every timeout and every plain value`() {
        for (timeout in AutoLockTimeout.entries) {
            for (discreet in listOf(false, true)) {
                for (secure in listOf(false, true)) {
                    assertEquals(
                        ReviewSaveClass.WEAKENING,
                        ReviewSaveAuthorisation.classify(record(false, timeout, discreet, secure))
                    )
                }
            }
        }
    }

    @Test
    fun `AE-86 lock on with IMMEDIATE is not weakening, and forced discreet and secure values do not count`() {
        for (discreet in listOf(false, true)) {
            for (secure in listOf(false, true)) {
                val saved = record(true, AutoLockTimeout.IMMEDIATE, discreet, secure)
                assertEquals(ReviewSaveClass.NOT_WEAKENING, ReviewSaveAuthorisation.classify(saved))
                assertFalse(ReviewSaveAuthorisation.isWeakening(saved))
            }
        }
    }

    @Test
    fun `lock on with any timeout longer than IMMEDIATE is weakening`() {
        for (timeout in AutoLockTimeout.entries.filter { it != AutoLockTimeout.IMMEDIATE }) {
            val saved = record(true, timeout)
            assertEquals(ReviewSaveClass.WEAKENING, ReviewSaveAuthorisation.classify(saved))
            assertTrue(ReviewSaveAuthorisation.isWeakening(saved))
        }
    }
}
