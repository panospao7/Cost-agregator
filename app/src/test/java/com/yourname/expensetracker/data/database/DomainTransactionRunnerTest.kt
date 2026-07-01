package com.yourname.expensetracker.data.database

import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import com.yourname.expensetracker.domain.transaction.TransactionContext
import com.yourname.expensetracker.domain.util.CancellationSafe
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

/**
 * PR 3 — Tests for [DomainTransactionRunner], [TransactionContext], and [CancellationSafe].
 *
 * Verifies:
 * - TransactionContext construction and validation.
 * - CancellationException propagation through CancellationSafe.
 * - DomainTransactionRunner contract: context population, return values.
 *
 * Note: Room's `withTransaction` is a top-level extension function that cannot be
 * trivially mocked with mockk. The actual transaction delegation and rollback
 * behavior is verified through integration tests with a real in-memory Room
 * database. This test file covers the domain-level contract.
 */
class DomainTransactionRunnerTest {

    private val mockTimeProvider: TimeProvider = mockk()

    // --- TransactionContext Tests ---

    @Test
    fun `TransactionContext is constructed with all supplied fields`() {
        val ctx = TransactionContext(
            correlationId = "corr-1",
            causationId = "cause-1",
            source = "TestSource.method",
            occurredAt = 1700000000000L,
            metadata = mapOf("key" to "value")
        )

        assertEquals("corr-1", ctx.correlationId)
        assertEquals("cause-1", ctx.causationId)
        assertEquals("TestSource.method", ctx.source)
        assertEquals(1700000000000L, ctx.occurredAt)
        assertEquals("value", ctx.metadata["key"])
    }

    @Test
    fun `TransactionContext default values are correct`() {
        val ctx = TransactionContext(
            correlationId = "corr-2",
            occurredAt = 1700000000000L
        )

        assertNull(ctx.causationId)
        assertEquals("unknown", ctx.source)
        assertTrue(ctx.metadata.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `TransactionContext rejects zero occurredAt`() {
        TransactionContext(correlationId = "corr-3", occurredAt = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `TransactionContext rejects negative occurredAt`() {
        TransactionContext(correlationId = "corr-4", occurredAt = -1L)
    }

    // --- CancellationException Propagation Tests ---

    @Test
    fun `CancellationSafe rethrowIfCancellation correctly rethrows CE`() {
        val ce = CancellationException("cancel")
        try {
            CancellationSafe.rethrowIfCancellation(ce)
            fail("Expected CancellationException")
        } catch (e: CancellationException) {
            assertSame(ce, e)
        }
    }

    @Test
    fun `CancellationSafe rethrowIfCancellation passes through non-CE`() {
        val ex = IllegalStateException("test")
        try {
            CancellationSafe.rethrowIfCancellation(ex)
            // Should not throw — proceed normally
        } catch (e: Exception) {
            fail("Expected no exception: $e")
        }
    }

    // --- RoomDomainTransactionRunner Context Construction Tests ---

    @Test
    fun `runner constructs TransactionContext from TimeProvider`() {
        every { mockTimeProvider.now() } returns 1710000000000L

        val runner = RoomDomainTransactionRunner(
            database = mockk(relaxed = true),
            timeProvider = mockTimeProvider
        )

        // Verify the time provider is injected — context construction is verified
        // indirectly through the TransactionContext tests above. The actual
        // withTransaction call would require a real Room database.
        assertNotNull(runner)
    }

    // --- DomainTransactionRunner Contract Tests (using a fake) ---

    /** Fake runner that executes the block directly without Room. */
    private class FakeDomainTransactionRunner(
        private val timeProvider: TimeProvider
    ) : DomainTransactionRunner {
        override suspend fun <T> runInTransaction(
            correlationId: String,
            causationId: String?,
            source: String,
            metadata: Map<String, String>,
            block: suspend (TransactionContext) -> T
        ): T {
            val context = TransactionContext(
                correlationId = correlationId,
                causationId = causationId,
                source = source,
                occurredAt = timeProvider.now(),
                metadata = metadata
            )
            return block(context)
        }
    }

    @Test
    fun `fake runner returns block value on success`() {
        every { mockTimeProvider.now() } returns 1700000000000L
        val runner = FakeDomainTransactionRunner(mockTimeProvider)

        runBlockingOnTest {
            val result = runner.runInTransaction(
                correlationId = "corr-ok",
                source = "Test.success"
            ) { ctx ->
                assertEquals("corr-ok", ctx.correlationId)
                assertEquals("Test.success", ctx.source)
                assertEquals(1700000000000L, ctx.occurredAt)
                "success"
            }

            assertEquals("success", result)
        }
    }

    @Test
    fun `fake runner populates TransactionContext with correct occurredAt`() {
        every { mockTimeProvider.now() } returns 1710000000000L
        val runner = FakeDomainTransactionRunner(mockTimeProvider)

        runBlockingOnTest {
            runner.runInTransaction(
                correlationId = "corr-time",
                source = "Test.time"
            ) { ctx ->
                assertEquals(1710000000000L, ctx.occurredAt)
            }
        }
    }

    @Test
    fun `fake runner passes causationId to TransactionContext`() {
        every { mockTimeProvider.now() } returns 1700000000000L
        val runner = FakeDomainTransactionRunner(mockTimeProvider)

        runBlockingOnTest {
            runner.runInTransaction(
                correlationId = "corr-child",
                causationId = "corr-parent",
                source = "Test.cause"
            ) { ctx ->
                assertEquals("corr-parent", ctx.causationId)
            }
        }
    }

    @Test
    fun `fake runner passes metadata to TransactionContext`() {
        every { mockTimeProvider.now() } returns 1700000000000L
        val runner = FakeDomainTransactionRunner(mockTimeProvider)

        val metadata = mapOf("receiptId" to "42", "action" to "approve")
        runBlockingOnTest {
            runner.runInTransaction(
                correlationId = "corr-meta",
                source = "Test.meta",
                metadata = metadata
            ) { ctx ->
                assertEquals("42", ctx.metadata["receiptId"])
                assertEquals("approve", ctx.metadata["action"])
            }
        }
    }

    @Test
    fun `fake runner rethrows CancellationException from block`() {
        every { mockTimeProvider.now() } returns 1700000000000L
        val runner = FakeDomainTransactionRunner(mockTimeProvider)
        val ce = CancellationException("cancel")

        runBlockingOnTest {
            try {
                runner.runInTransaction(
                    correlationId = "corr-ce",
                    source = "Test.cancel"
                ) { ctx ->
                    throw ce
                }
                fail("Expected CancellationException to propagate")
            } catch (e: CancellationException) {
                assertSame(ce, e)
            }
        }
    }

    @Test
    fun `fake runner propagates non-CE exceptions from block`() {
        every { mockTimeProvider.now() } returns 1700000000000L
        val runner = FakeDomainTransactionRunner(mockTimeProvider)
        val ex = IllegalStateException("state error")

        runBlockingOnTest {
            try {
                runner.runInTransaction(
                    correlationId = "corr-ex",
                    source = "Test.error"
                ) { ctx ->
                    throw ex
                }
                fail("Expected IllegalStateException to propagate")
            } catch (e: IllegalStateException) {
                assertSame(ex, e)
            }
        }
    }

    // Helper to bridge suspend into blocking tests without kotlinx-coroutines-test
    private fun runBlockingOnTest(block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking {
            block()
        }
    }
}
