package com.yourname.expensetracker.domain.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyDeniedExceptionTest {
    @Test
    fun recognizedCodesSurviveTypedTransportAndCompatibilityMessage() {
        for (code in PrivacyGateReasonCodes.ALL) {
            val failure = PrivacyDeniedException(PrivacyCapability.ENCRYPTED_BACKUP, code)
            assertEquals(PrivacyCapability.ENCRYPTED_BACKUP, failure.capability)
            assertEquals(code, failure.reasonCode)
            assertEquals(code, failure.message)
            assertTrue(SecurityException::class.java.isInstance(failure))
        }
    }

    @Test
    fun unspecifiedDenialRemainsAnOperationalGateFailure() {
        val failure = PrivacyDeniedException(PrivacyCapability.ENCRYPTED_BACKUP)
        assertEquals(PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE, failure.reasonCode)
        assertEquals(PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE, failure.message)
    }

    @Test
    fun unrecognizedReasonTextIsNeverCarriedAcrossTheBoundary() {
        for (raw in listOf("", "/private/receipt financial_data", "Encrypted backup disabled", "encrypted_backup_disabled")) {
            val failure = PrivacyDeniedException(PrivacyCapability.ENCRYPTED_BACKUP, raw)
            assertEquals(PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE, failure.reasonCode)
            assertEquals(PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE, failure.message)
            if (raw.isNotEmpty()) assertFalse(failure.toString().contains(raw))
        }
    }
}
