package com.yourname.expensetracker.domain.util

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class CancellationSafeTest {

    // region runCatchingCancellable

    @Test
    fun `runCatchingCancellable returns success for normal value`() {
        val result = CancellationSafe.runCatchingCancellable { 42 }
        assertTrue(result.isSuccess)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `runCatchingCancellable returns failure for non-CE exception`() {
        val ex = IllegalStateException("test")
        val result = CancellationSafe.runCatchingCancellable<Int> { throw ex }
        assertTrue(result.isFailure)
        assertSame(ex, result.exceptionOrNull())
    }

    @Test
    fun `runCatchingCancellable rethrows CancellationException`() {
        val ce = CancellationException("cancel")
        try {
            CancellationSafe.runCatchingCancellable<Int> { throw ce }
            fail("Expected CancellationException to be rethrown")
        } catch (e: CancellationException) {
            assertSame(ce, e)
        }
    }

    @Test
    fun `runCatchingCancellable rethrows CancellationException (including subclasses)`() {
        val tce = CancellationException("timeout")
        try {
            CancellationSafe.runCatchingCancellable<Int> { throw tce }
            fail("Expected CancellationException to be rethrown")
        } catch (e: CancellationException) {
            assertSame(tce, e)
        }
    }

    // endregion

    // region rethrowIfCancellation

    @Test
    fun `rethrowIfCancellation rethrows CancellationException`() {
        val ce = CancellationException("cancel")
        try {
            CancellationSafe.rethrowIfCancellation(ce)
            fail("Expected CancellationException to be rethrown")
        } catch (e: CancellationException) {
            assertSame(ce, e)
        }
    }

    @Test
    fun `rethrowIfCancellation rethrows CancellationException including subclasses`() {
        val tce = CancellationException("timeout")
        try {
            CancellationSafe.rethrowIfCancellation(tce)
            fail("Expected CancellationException to be rethrown")
        } catch (e: CancellationException) {
            assertSame(tce, e)
        }
    }

    @Test
    fun `rethrowIfCancellation does nothing for non-CE exception`() {
        val ex = IllegalStateException("test")
        try {
            CancellationSafe.rethrowIfCancellation(ex)
            // No exception expected — should return normally
        } catch (e: Exception) {
            fail("Expected no exception for non-CancellationException: $e")
        }
    }

    // endregion

    // region Integration: typical catch-block pattern

    @Test
    fun `typical catch block pattern with rethrowIfCancellation preserves CE`() {
        val ce = CancellationException("cancel")
        try {
            try {
                throw ce
            } catch (e: Exception) {
                CancellationSafe.rethrowIfCancellation(e)
                // Should never reach here for CE
                fail("Should have rethrown")
            }
            fail("CancellationException should have propagated")
        } catch (e: CancellationException) {
            assertSame(ce, e)
        }
    }

    @Test
    fun `typical catch block pattern with rethrowIfCancellation handles non-CE`() {
        var handled = false
        try {
            throw IllegalStateException("test")
        } catch (e: Exception) {
            CancellationSafe.rethrowIfCancellation(e)
            handled = true
        }
        assertTrue("Non-CE exception should be handled normally", handled)
    }

    // endregion
}
