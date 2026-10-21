package com.yourname.expensetracker.util

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.domain.provenance.CreateExpenseSourceLinkRequirements
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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

    private data class DateRow(
        val name: String,
        val schemaVersion: Int,
        val rowFields: String,
        val expectedDate: Long,
        val expectedNowCalls: Int
    )

    // ── Date/timestamp resolution contract ────────────────────────────────────

    @Test
    fun `date resolution table prefers date then timestamp then provider exactly once`() = runTest {
        val rows = listOf(
            DateRow("valid date is used, provider not consulted", 2, """"date": $providedDate""", providedDate, 0),
            DateRow(
                "valid date takes precedence over valid timestamp",
                2,
                """"date": $providedDate, "timestamp": ${providedDate - 1000}""",
                providedDate,
                0
            ),
            // Long.MIN_VALUE is a valid provided date, so the provider must not be called.
            DateRow("Long.MIN_VALUE date is a valid provided value", 2, """"date": ${Long.MIN_VALUE}""", Long.MIN_VALUE, 0),
            DateRow("valid timestamp is used when date absent", 2, """"timestamp": $providedDate""", providedDate, 0),
            DateRow("absent date and timestamp fall back to provider once", 2, "", fixedNow, 1),
            DateRow(
                "invalid date falls back to valid timestamp without provider",
                2,
                """"date": "not-a-date", "timestamp": $providedDate""",
                providedDate,
                0
            ),
            DateRow(
                "null date and null timestamp fall back to provider once",
                2,
                """"date": null, "timestamp": null""",
                fixedNow,
                1
            ),
            DateRow("invalid date without timestamp falls back to provider once", 2, """"date": "garbage"""", fixedNow, 1),
            DateRow("V1 valid date is used without provider", 1, """"date": $providedDate""", providedDate, 0)
        )
        assertEquals("date resolution table size", 9, rows.size)

        val failures = mutableListOf<String>()
        rows.forEach { row ->
            val provider = newCountingProvider()
            val importer = newImporter(provider)
            val captured = mutableListOf<CreateExpenseRequest>()
            coEvery { coordinator.createExpense(capture(captured)) } returns CreateExpenseResult.Created(1L)

            val extra = if (row.rowFields.isEmpty()) "" else ", ${row.rowFields}"
            val result = importer.importFromContent(
                """{"schemaVersion": ${row.schemaVersion}, "rows": [{"merchant": "Starbucks", "amount": 25.5$extra}]}"""
            )

            if (!result.success) failures += "row '${row.name}': import did not succeed"
            else if (captured.size != 1) failures += "row '${row.name}': expected 1 request, got ${captured.size}"
            else {
                if (captured.single().date != row.expectedDate) {
                    failures += "row '${row.name}': date expected ${row.expectedDate} but was ${captured.single().date}"
                }
                if (provider.nowCalls != row.expectedNowCalls) {
                    failures += "row '${row.name}': provider nowCalls expected ${row.expectedNowCalls} but was ${provider.nowCalls}"
                }
            }
        }
        assertTrue("${failures.size}/${rows.size} rows failed:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    // ── Provenance ────────────────────────────────────────────────────────────

    @Test
    fun `JSON rows carry import provenance batch id and row number`() = runTest {
        val requests = mutableListOf<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requests)) } returns CreateExpenseResult.Created(1L)

        val result = newImporter(newCountingProvider()).importFromContent(
            """
                {
                  "schemaVersion": 2,
                  "rows": [
                    {"merchant": "A", "amount": 1.00},
                    {"merchant": "B", "amount": 2.00},
                    {"merchant": "C", "amount": 3.00}
                  ]
                }
            """.trimIndent()
        )

        assertTrue(result.success)
        assertEquals(3, requests.size)
        requests.forEach { request ->
            assertTrue(CreateExpenseSourceLinkRequirements.missingRequirements(request).isEmpty())
        }
        assertEquals(listOf(1, 2, 3), requests.map { it.csvRowNumber })
        assertEquals(1, requests.map { it.csvImportBatchId }.toSet().size)
        assertTrue(requests.first().csvImportBatchId?.isNotEmpty() == true)
    }

    @Test
    fun `legacy JSON rows carry import provenance`() = runTest {
        val requests = mutableListOf<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requests)) } returns CreateExpenseResult.Created(1L)

        val result = newImporter(newCountingProvider()).importFromContent(
            """{"rows":[{"merchant":"Legacy","amount":4.50}]}"""
        )

        assertTrue(result.success)
        assertEquals(1, requests.size)
        assertTrue(CreateExpenseSourceLinkRequirements.missingRequirements(requests.single()).isEmpty())
        assertEquals(1, requests.single().csvRowNumber)
        assertTrue(requests.single().csvImportBatchId?.isNotEmpty() == true)
    }

    @Test
    fun `each JSON import run mints a distinct batch id`() = runTest {
        val requests = mutableListOf<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requests)) } returns CreateExpenseResult.Created(1L)
        val content = """{"schemaVersion":2,"rows":[{"merchant":"A","amount":1.00}]}"""

        newImporter(newCountingProvider()).importFromContent(content)
        newImporter(newCountingProvider()).importFromContent(content)

        assertEquals(2, requests.size)
        assertTrue(requests[0].csvImportBatchId != requests[1].csvImportBatchId)
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
