package com.yourname.expensetracker.domain.util

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Table-driven tests for [CancellationSafe]. Failure messages name the row.
 */
class CancellationSafeTest {

    /** Real subclass so the "including subclasses" rows exercise a distinct type. */
    private class SubCancellation(message: String) : CancellationException(message)

    private data class NonCancellationRow(val label: String, val verify: () -> Unit)

    @Test
    fun `non-cancellation outcomes are returned or handled normally`() {
        val rows = listOf(
            NonCancellationRow("runCatchingCancellable returns success for normal value") {
                val result = CancellationSafe.runCatchingCancellable { 42 }
                assertTrue("success flag", result.isSuccess)
                assertEquals(42, result.getOrNull())
            },
            NonCancellationRow("runCatchingCancellable returns failure for non-CE exception") {
                val ex = IllegalStateException("test")
                val result = CancellationSafe.runCatchingCancellable<Int> { throw ex }
                assertTrue("failure flag", result.isFailure)
                assertSame(ex, result.exceptionOrNull())
            },
            NonCancellationRow("rethrowIfCancellation does nothing for non-CE exception") {
                // Must return normally; any thrown exception fails the row.
                CancellationSafe.rethrowIfCancellation(IllegalStateException("test"))
            },
            NonCancellationRow("typical catch block pattern handles non-CE") {
                var handled = false
                try {
                    throw IllegalStateException("test")
                } catch (e: Exception) {
                    CancellationSafe.rethrowIfCancellation(e)
                    handled = true
                }
                assertTrue("Non-CE exception should be handled normally", handled)
            }
        )
        assertEquals(4, rows.size)
        rows.forEach { row ->
            try {
                row.verify()
            } catch (e: Throwable) {
                throw AssertionError("row '${row.label}' failed: $e", e)
            }
        }
    }

    private data class CancellationRow(
        val label: String,
        val thrown: CancellationException,
        val invoke: (CancellationException) -> Unit
    )

    @Test
    fun `cancellation exceptions are always rethrown unchanged`() {
        val plain = CancellationException("cancel")
        val sub = SubCancellation("timeout")

        val viaRunCatching: (CancellationException) -> Unit = { ce ->
            CancellationSafe.runCatchingCancellable<Int> { throw ce }
        }
        val viaRethrow: (CancellationException) -> Unit = { ce ->
            CancellationSafe.rethrowIfCancellation(ce)
        }
        val viaCatchBlock: (CancellationException) -> Unit = { ce ->
            try {
                throw ce
            } catch (e: Exception) {
                CancellationSafe.rethrowIfCancellation(e)
                fail("Should have rethrown")
            }
        }

        val rows = listOf(
            CancellationRow("runCatchingCancellable rethrows CancellationException", plain, viaRunCatching),
            CancellationRow("runCatchingCancellable rethrows CancellationException subclass", sub, viaRunCatching),
            CancellationRow("rethrowIfCancellation rethrows CancellationException", plain, viaRethrow),
            CancellationRow("rethrowIfCancellation rethrows CancellationException subclass", sub, viaRethrow),
            CancellationRow("typical catch block pattern preserves CancellationException", plain, viaCatchBlock),
            CancellationRow("typical catch block pattern preserves CancellationException subclass", sub, viaCatchBlock)
        )
        assertEquals(6, rows.size)
        rows.forEach { row ->
            var caught: CancellationException? = null
            try {
                row.invoke(row.thrown)
            } catch (e: CancellationException) {
                caught = e
            }
            assertNotNull("row '${row.label}': expected CancellationException to be rethrown", caught)
            assertSame("row '${row.label}': must be the same instance", row.thrown, caught)
        }
    }
}
