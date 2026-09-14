package com.yourname.expensetracker.util

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused tests for [JsonExpenseImporter] date/timestamp resolution (T3A).
 *
 * Verifies the fallback contract:
 * - a valid `date` is always preferred;
 * - otherwise a valid `timestamp` is used;
 * - only when both are absent/invalid is the time provider consulted, exactly once.
 *
 * A counting wrapper around [FakeTimeProvider] proves that no eager `now()`
 * call happens when a valid `date` or `timestamp` is present.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION_ERROR")
class JsonExpenseImporterTest {

    private val fixedNow = 1_712_000_000_000L
    private val providedDate = 1_711_000_000_000L

    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val coordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)
    private val maintenanceMode = mockk<RestoreMaintenanceMode>()

    private fun writeBarrier(mode: RestoreMaintenanceMode.Mode): DatabaseWriteBarrier {
        val barrier = DatabaseWriteBarrier(maintenanceMode)
        io.mockk.every { maintenanceMode.currentMode() } returns mode
        io.mockk.every { maintenanceMode.isWritesAllowed() } returns
            (mode == RestoreMaintenanceMode.Mode.NORMAL)
        return barrier
    }

    private fun newImporter(timeProvider: TimeProvider): JsonExpenseImporter =
        JsonExpenseImporter(coordinator, categoryDao, timeProvider, writeBarrier(RestoreMaintenanceMode.Mode.NORMAL))

    private fun newCountingProvider(): CountingTimeProvider =
        CountingTimeProvider(FakeTimeProvider(fixedNow))

    private fun captureRequest(): CapturingSlot<CreateExpenseRequest> {
        val requestSlot = slot<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requestSlot)) } returns CreateExpenseResult.Created(1L)
        return requestSlot
    }

    // ── Valid date ────────────────────────────────────────────────────────────

    @Test
    fun `valid date is used and provider is not consulted`() = runTest {
        val provider = newCountingProvider()
        val importer = newImporter(provider)
        val requestSlot = captureRequest()

        val content = """
            {
              "schemaVersion": 2,
              "rows": [
                {"merchant": "Starbucks", "amount": 25.5, "date": $providedDate}
              ]
            }
        """.trimIndent()

        val result = importer.importFromContent(content)

        assertTrue(result.success)
        assertEquals(providedDate, requestSlot.captured.date)
        assertEquals("Provider must not be called when a valid date exists", 0, provider.nowCalls)
    }

    @Test
    fun `valid date takes precedence over valid timestamp`() = runTest {
        val provider = newCountingProvider()
        val importer = newImporter(provider)
        val requestSlot = captureRequest()

        val content = """
            {
              "schemaVersion": 2,
              "rows": [
                {"merchant": "Starbucks", "amount": 25.5, "date": $providedDate, "timestamp": ${providedDate - 1000}}
              ]
            }
        """.trimIndent()

        val result = importer.importFromContent(content)

        assertTrue(result.success)
        assertEquals(providedDate, requestSlot.captured.date)
        assertEquals("Valid date must win over timestamp", 0, provider.nowCalls)
    }

    @Test
    fun `Long MIN_VALUE date is a valid provided value and provider is not consulted`() = runTest {
        val provider = newCountingProvider()
        val importer = newImporter(provider)
        val requestSlot = captureRequest()

        val content = """
            {
              "schemaVersion": 2,
              "rows": [
                {"merchant": "Starbucks", "amount": 25.5, "date": ${Long.MIN_VALUE}}
              ]
            }
        """.trimIndent()

        val result = importer.importFromContent(content)

        assertTrue(result.success)
        assertEquals(Long.MIN_VALUE, requestSlot.captured.date)
        assertEquals("Long.MIN_VALUE is a valid provided date, so provider must not be called", 0, provider.nowCalls)
    }

    // ── Valid timestamp ───────────────────────────────────────────────────────

    @Test
    fun `valid timestamp is used when date absent`() = runTest {
        val provider = newCountingProvider()
        val importer = newImporter(provider)
        val requestSlot = captureRequest()

        val content = """
            {
              "schemaVersion": 2,
              "rows": [
                {"merchant": "Starbucks", "amount": 25.5, "timestamp": $providedDate}
              ]
            }
        """.trimIndent()

        val result = importer.importFromContent(content)

        assertTrue(result.success)
        assertEquals(providedDate, requestSlot.captured.date)
        assertEquals("Provider must not be called when a valid timestamp exists", 0, provider.nowCalls)
    }

    // ── Absent fields → fake provider fallback ────────────────────────────────

    @Test
    fun `absent date and timestamp fall back to provider once`() = runTest {
        val provider = newCountingProvider()
        val importer = newImporter(provider)
        val requestSlot = captureRequest()

        val content = """
            {
              "schemaVersion": 2,
              "rows": [
                {"merchant": "Starbucks", "amount": 25.5}
              ]
            }
        """.trimIndent()

        val result = importer.importFromContent(content)

        assertTrue(result.success)
        assertEquals(fixedNow, requestSlot.captured.date)
        assertEquals("Provider fallback must be consulted exactly once", 1, provider.nowCalls)
    }

    // ── Invalid / null fields ─────────────────────────────────────────────────

    @Test
    fun `invalid date falls back to valid timestamp without provider`() = runTest {
        val provider = newCountingProvider()
        val importer = newImporter(provider)
        val requestSlot = captureRequest()

        val content = """
            {
              "schemaVersion": 2,
              "rows": [
                {"merchant": "Starbucks", "amount": 25.5, "date": "not-a-date", "timestamp": $providedDate}
              ]
            }
        """.trimIndent()

        val result = importer.importFromContent(content)

        assertTrue(result.success)
        assertEquals(providedDate, requestSlot.captured.date)
        assertEquals("Invalid date must fall through to valid timestamp, not provider", 0, provider.nowCalls)
    }

    @Test
    fun `null date and null timestamp fall back to provider once`() = runTest {
        val provider = newCountingProvider()
        val importer = newImporter(provider)
        val requestSlot = captureRequest()

        val content = """
            {
              "schemaVersion": 2,
              "rows": [
                {"merchant": "Starbucks", "amount": 25.5, "date": null, "timestamp": null}
              ]
            }
        """.trimIndent()

        val result = importer.importFromContent(content)

        assertTrue(result.success)
        assertEquals(fixedNow, requestSlot.captured.date)
        assertEquals("Null date/timestamp must fall back to provider exactly once", 1, provider.nowCalls)
    }

    @Test
    fun `invalid date without timestamp falls back to provider once`() = runTest {
        val provider = newCountingProvider()
        val importer = newImporter(provider)
        val requestSlot = captureRequest()

        val content = """
            {
              "schemaVersion": 2,
              "rows": [
                {"merchant": "Starbucks", "amount": 25.5, "date": "garbage"}
              ]
            }
        """.trimIndent()

        val result = importer.importFromContent(content)

        assertTrue(result.success)
        assertEquals(fixedNow, requestSlot.captured.date)
        assertEquals("Invalid date with no timestamp must fall back to provider once", 1, provider.nowCalls)
    }

    // ── V1 rows share the same resolution ─────────────────────────────────────

    @Test
    fun `V1 valid date is used without provider`() = runTest {
        val provider = newCountingProvider()
        val importer = newImporter(provider)
        val requestSlot = captureRequest()

        val content = """
            {
              "schemaVersion": 1,
              "rows": [
                {"merchant": "Starbucks", "amount": 25.5, "date": $providedDate}
              ]
            }
        """.trimIndent()

        val result = importer.importFromContent(content)

        assertTrue(result.success)
        assertEquals(providedDate, requestSlot.captured.date)
        assertEquals("V1 valid date must not consult provider", 0, provider.nowCalls)
    }

    // ── GR-14u44b write-barrier gating ────────────────────────────────────────

    @Test
    fun `blocked mode returns controlled error and never touches the DAO`() = runTest {
        // GR-14u44b: the write barrier must gate the import entry before
        // any write-capable call, with a controlled-constant message
        // (never e.message) and no DAO interaction.
        val blockedImporter = JsonExpenseImporter(
            coordinator,
            categoryDao,
            FakeTimeProvider(fixedNow),
            writeBarrier(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)
        )

        val result = blockedImporter.importFromContent(
            """{"schemaVersion": 2, "rows": [{"merchant": "A", "amount": 1.0}]}"""
        )

        assertEquals(false, result.success)
        assertEquals(1, result.errorCount)
        assertEquals(
            "Import blocked: database maintenance in progress",
            result.errors.single()
        )
        coVerify(exactly = 0) { categoryDao.getByName(any()) }
        coVerify(exactly = 0) { categoryDao.insert(any()) }
        coVerify(exactly = 0) { coordinator.createExpense(any()) }
    }

    @Test
    fun `cancellation from the barrier propagates`() = runTest {
        val cancellingBarrier = mockk<DatabaseWriteBarrier>()
        coEvery { cancellingBarrier.checkWritesAllowed(any<String>()) } throws
            CancellationException("cancelled")
        val cancellingImporter = JsonExpenseImporter(
            coordinator,
            categoryDao,
            FakeTimeProvider(fixedNow),
            cancellingBarrier
        )

        val thrown = runCatching {
            cancellingImporter.importFromContent("""{"rows": []}""")
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        coVerify(exactly = 0) { coordinator.createExpense(any()) }
    }

    /**
     * Delegating [TimeProvider] that counts `now()` invocations while backing
     * every read with a deterministic [FakeTimeProvider].
     */
    private class CountingTimeProvider(
        private val delegate: TimeProvider
    ) : TimeProvider {
        var nowCalls: Int = 0
            private set

        override fun now(): Long {
            nowCalls++
            return delegate.now()
        }

        override fun nowFormatted(): String = delegate.nowFormatted()
    }
}
