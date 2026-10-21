package com.yourname.expensetracker.data.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.transaction.TransactionContext
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PR 3 — Tests for [TransactionContext] and the production [RoomDomainTransactionRunner].
 *
 * The runner tests use the REAL [RoomDomainTransactionRunner] over a real in-memory
 * [AppDatabase] and a real DAO write (synthetic categories), so rollback, commit,
 * nesting and exception propagation are proven against Room itself rather than a
 * test-local fake. Only the clock is substituted.
 *
 * [com.yourname.expensetracker.domain.util.CancellationSafe] semantics are covered by
 * CancellationSafeTest; they are intentionally not duplicated here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DomainTransactionRunnerTest {

    private lateinit var database: AppDatabase
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var runner: RoomDomainTransactionRunner

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = AppDatabaseTestFactory.create(context)
        timeProvider = FakeTimeProvider(1_700_000_000_000L)
        runner = RoomDomainTransactionRunner(database, timeProvider)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    private suspend fun insertSynthetic(name: String): Long =
        database.categoryDao().insert(Category(name = name, icon = "T", color = "#123456"))

    private suspend fun exists(name: String): Boolean = database.categoryDao().getByName(name) != null

    /**
     * kotlinx.coroutines stack-trace recovery may rethrow a COPY of an exception (same
     * type and message, with the original attached as its cause) when it crosses a
     * suspension point such as Room's withTransaction. Object identity is therefore not
     * a stable contract; this asserts the original exception is the thrown one or its
     * direct cause, with the same type and message.
     */
    private fun assertRethrownOriginal(original: Throwable, thrown: Throwable) {
        assertTrue(
            "thrown exception must be the original or a stack-trace-recovery copy whose cause is the original",
            thrown === original || thrown.cause === original
        )
        assertEquals(original.javaClass, thrown.javaClass)
        assertEquals(original.message, thrown.message)
    }

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

    // --- RoomDomainTransactionRunner (real Room) ---

    @Test
    fun `runner returns block value and populates context from arguments and TimeProvider`() = runTest {
        timeProvider.setTime(1_710_000_000_000L)
        var captured: TransactionContext? = null

        val result = runner.runInTransaction(
            correlationId = "corr-ok",
            causationId = "corr-parent",
            operationId = "test.op",
            source = "Test.success",
            metadata = mapOf("receiptId" to "42", "action" to "approve")
        ) { ctx ->
            captured = ctx
            "success"
        }

        assertEquals("success", result)
        val ctx = captured
        assertNotNull(ctx)
        assertEquals("corr-ok", ctx!!.correlationId)
        assertEquals("corr-parent", ctx.causationId)
        assertEquals("test.op", ctx.operationId)
        assertEquals("Test.success", ctx.source)
        assertEquals("system", ctx.actor)
        assertEquals(1_710_000_000_000L, ctx.occurredAt)
        assertEquals(1_710_000_000_000L, ctx.startedAtMs)
        assertEquals("42", ctx.metadata["receiptId"])
        assertEquals("approve", ctx.metadata["action"])
    }

    @Test
    fun `runner reads the clock per call so successive transactions get distinct occurredAt`() = runTest {
        timeProvider.setTime(1_700_000_000_000L)
        val first = runner.runInTransaction(correlationId = "c1") { it.occurredAt }
        timeProvider.advanceTime(5_000L)
        val second = runner.runInTransaction(correlationId = "c2") { it.occurredAt }

        assertEquals(1_700_000_000_000L, first)
        assertEquals(1_700_000_005_000L, second)
    }

    @Test
    fun `runner commits block writes on success`() = runTest {
        runner.runInTransaction(correlationId = "corr-commit") {
            insertSynthetic("Tx Commit A")
            insertSynthetic("Tx Commit B")
        }

        assertTrue(exists("Tx Commit A"))
        assertTrue(exists("Tx Commit B"))
    }

    @Test
    fun `runner rolls back earlier writes and rethrows the original non-cancellation exception`() = runTest {
        val failure = IllegalStateException("synthetic state error")

        try {
            runner.runInTransaction(correlationId = "corr-ex") {
                assertTrue("insert must succeed before the throw", insertSynthetic("Tx Rollback Ex") > 0)
                throw failure
            }
            fail("Expected IllegalStateException to propagate")
        } catch (e: IllegalStateException) {
            assertRethrownOriginal(failure, e)
        }

        assertTrue("write before the throw must be rolled back", !exists("Tx Rollback Ex"))
    }

    @Test
    fun `runner rolls back earlier writes and rethrows the original CancellationException`() = runTest {
        val cancellation = CancellationException("synthetic cancel")

        try {
            runner.runInTransaction(correlationId = "corr-ce") {
                assertTrue("insert must succeed before the throw", insertSynthetic("Tx Rollback Ce") > 0)
                throw cancellation
            }
            fail("Expected CancellationException to propagate")
        } catch (e: CancellationException) {
            assertRethrownOriginal(cancellation, e)
        }

        assertTrue("write before cancellation must be rolled back", !exists("Tx Rollback Ce"))
    }

    @Test
    fun `nested runner call joins the outer transaction and commits with it`() = runTest {
        runner.runInTransaction(correlationId = "outer") {
            insertSynthetic("Tx Nested Outer")
            runner.runInTransaction(correlationId = "inner") {
                insertSynthetic("Tx Nested Inner")
            }
        }

        assertTrue(exists("Tx Nested Outer"))
        assertTrue(exists("Tx Nested Inner"))
    }

    @Test
    fun `nested runner call is rolled back when the outer block fails after the inner succeeded`() = runTest {
        val failure = IllegalStateException("synthetic outer failure")

        try {
            runner.runInTransaction(correlationId = "outer") {
                runner.runInTransaction(correlationId = "inner") {
                    assertTrue("inner insert must succeed", insertSynthetic("Tx Nested Inner Done") > 0)
                }
                assertTrue("outer insert must succeed", insertSynthetic("Tx Nested Outer Done") > 0)
                throw failure
            }
            fail("Expected IllegalStateException to propagate")
        } catch (e: IllegalStateException) {
            assertRethrownOriginal(failure, e)
        }

        assertTrue("inner write must not survive the outer rollback", !exists("Tx Nested Inner Done"))
        assertTrue("outer write must be rolled back", !exists("Tx Nested Outer Done"))
    }
}
