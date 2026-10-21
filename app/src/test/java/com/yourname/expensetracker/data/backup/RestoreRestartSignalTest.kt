package com.yourname.expensetracker.data.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreRestartSignalTest {

    @Test
    fun `default is not requested`() {
        assertFalse(RestoreRestartSignal().isRequested())
    }

    @Test
    fun `markRequested sets the flag and is idempotent`() {
        val signal = RestoreRestartSignal()
        signal.markRequested()
        signal.markRequested()
        assertTrue(signal.isRequested())
    }

    @Test
    fun `a new instance models a fresh process and starts not requested`() {
        RestoreRestartSignal().markRequested()
        assertFalse(RestoreRestartSignal().isRequested())
    }
}
