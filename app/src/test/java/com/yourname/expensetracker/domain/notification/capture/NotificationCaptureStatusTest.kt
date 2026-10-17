package com.yourname.expensetracker.domain.notification.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationCaptureStatusTest {

    @Test
    fun `consent on and access granted is active`() {
        assertEquals(
            NotificationCaptureStatus.ACTIVE,
            NotificationCaptureStatus.resolve(consentOn = true, accessGranted = true)
        )
    }

    @Test
    fun `consent on and access missing is access off`() {
        assertEquals(
            NotificationCaptureStatus.ACCESS_OFF,
            NotificationCaptureStatus.resolve(consentOn = true, accessGranted = false)
        )
    }

    @Test
    fun `consent off wins even when access is granted`() {
        assertEquals(
            NotificationCaptureStatus.CONSENT_OFF,
            NotificationCaptureStatus.resolve(consentOn = false, accessGranted = true)
        )
    }

    @Test
    fun `consent off and access missing is consent off`() {
        assertEquals(
            NotificationCaptureStatus.CONSENT_OFF,
            NotificationCaptureStatus.resolve(consentOn = false, accessGranted = false)
        )
    }
}
