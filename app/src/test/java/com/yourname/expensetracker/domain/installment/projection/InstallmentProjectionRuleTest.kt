package com.yourname.expensetracker.domain.installment.projection

import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryKind
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * INV-15 projection bounds as a pure rule: only SCHEDULED entries of ACTIVE plans with
 * dueDate at or after today. Synthetic data; days are plain epoch-day numbers.
 */
class InstallmentProjectionRuleTest {

    private val today = 20_000L

    private fun entry(
        id: Long,
        dueDay: Long,
        status: InstallmentEntryStatus = InstallmentEntryStatus.SCHEDULED,
        seq: Int = id.toInt(),
        minor: Long = 5_000L,
        generation: Int = 0,
        kind: InstallmentEntryKind = InstallmentEntryKind.REGULAR
    ) = ProjectionEntryInput(id, seq, kind, status, dueDay, minor, generation)

    private fun plan(
        id: Long,
        entries: List<ProjectionEntryInput>,
        status: InstallmentPlanStatus = InstallmentPlanStatus.ACTIVE,
        currency: InstallmentCurrency = InstallmentCurrency.EUR
    ) = ProjectionPlanInput(id, "plan-uuid-$id", "Synthetic plan $id", currency, status, entries)

    private fun entryIds(result: List<InstallmentProjectedEntry>) = result.map { it.entryId }

    @Test
    fun `INV-15 an active plan projects its scheduled entries due today or later`() {
        val p = plan(1L, listOf(entry(1L, today), entry(2L, today + 30L)))

        val result = InstallmentProjectionRule.project(listOf(p), today)

        assertEquals(listOf(1L, 2L), entryIds(result))
        assertEquals(5_000L, result[0].amountMinor)
        assertEquals(InstallmentCurrency.EUR, result[0].currency)
    }

    @Test
    fun `INV-15 an entry due today is included and an entry due yesterday is not`() {
        val p = plan(1L, listOf(entry(1L, today - 1L), entry(2L, today), entry(3L, today + 1L)))

        assertEquals(listOf(2L, 3L), entryIds(InstallmentProjectionRule.project(listOf(p), today)))
    }

    @Test
    fun `INV-15 overdue scheduled entries are not projected`() {
        val p = plan(1L, listOf(entry(1L, today - 100L), entry(2L, today - 1L)))

        assertTrue(InstallmentProjectionRule.project(listOf(p), today).isEmpty())
    }

    @Test
    fun `INV-15 only scheduled entries project and every other status is excluded`() {
        for (status in InstallmentEntryStatus.entries.filter { it != InstallmentEntryStatus.SCHEDULED }) {
            val p = plan(1L, listOf(entry(1L, today + 10L, status = status)))

            assertTrue("status $status must not project", InstallmentProjectionRule.project(listOf(p), today).isEmpty())
        }
    }

    @Test
    fun `INV-15 paid before tracking and skipped entries are never projected beside a scheduled one`() {
        val p = plan(
            1L,
            listOf(
                entry(1L, today + 1L, status = InstallmentEntryStatus.PAID_BEFORE_TRACKING),
                entry(2L, today + 2L, status = InstallmentEntryStatus.SKIPPED),
                entry(3L, today + 3L)
            )
        )

        assertEquals(listOf(3L), entryIds(InstallmentProjectionRule.project(listOf(p), today)))
    }

    @Test
    fun `INV-15 only active plans project and completed or terminal plans project nothing`() {
        for (status in InstallmentPlanStatus.entries.filter { it != InstallmentPlanStatus.ACTIVE }) {
            val p = plan(1L, listOf(entry(1L, today + 10L)), status = status)

            assertTrue("plan status $status must not project", InstallmentProjectionRule.project(listOf(p), today).isEmpty())
        }
    }

    @Test
    fun `INV-15 an active plan next to a terminal plan projects only the active one`() {
        val active = plan(1L, listOf(entry(1L, today + 5L)))
        val cancelled = plan(2L, listOf(entry(2L, today + 5L)), status = InstallmentPlanStatus.CANCELLED)

        assertEquals(listOf(1L), entryIds(InstallmentProjectionRule.project(listOf(cancelled, active), today)))
    }

    @Test
    fun `INV-15 horizon end is inclusive and bounds the result`() {
        val p = plan(1L, listOf(entry(1L, today + 9L), entry(2L, today + 10L), entry(3L, today + 11L)))

        assertEquals(listOf(1L, 2L), entryIds(InstallmentProjectionRule.project(listOf(p), today, today + 10L)))
    }

    @Test
    fun `INV-15 no horizon means unbounded and a horizon before today projects nothing`() {
        val p = plan(1L, listOf(entry(1L, today + 5_000L)))

        assertEquals(listOf(1L), entryIds(InstallmentProjectionRule.project(listOf(p), today, null)))
        assertTrue(InstallmentProjectionRule.project(listOf(p), today, today - 1L).isEmpty())
    }

    @Test
    fun `INV-15 results are ordered by due date then plan id then sequence`() {
        val p1 = plan(1L, listOf(entry(11L, today + 20L, seq = 2), entry(10L, today + 10L, seq = 1)))
        val p2 = plan(2L, listOf(entry(20L, today + 10L, seq = 1), entry(21L, today + 10L, seq = 2)))

        val result = InstallmentProjectionRule.project(listOf(p2, p1), today)

        assertEquals(listOf(10L, 20L, 21L, 11L), entryIds(result))
    }

    @Test
    fun `INV-15 record generation plan uuid and sequence are passed through for the reminder key`() {
        val p = plan(7L, listOf(entry(1L, today + 1L, seq = 3, generation = 2)))

        val projected = InstallmentProjectionRule.project(listOf(p), today).single()

        assertEquals(2, projected.recordGeneration)
        assertEquals("plan-uuid-7", projected.planUuid)
        assertEquals(3, projected.seq)
        assertEquals(7L, projected.planId)
        assertEquals("Synthetic plan 7", projected.displayName)
    }

    @Test
    fun `INV-16 projected amounts keep the plan currency and are not combined`() {
        val eur = plan(1L, listOf(entry(1L, today + 1L, minor = 5_000L)))
        val usd = plan(2L, listOf(entry(2L, today + 1L, minor = 12_500L)), currency = InstallmentCurrency.USD)

        val result = InstallmentProjectionRule.project(listOf(eur, usd), today)

        assertEquals(2, result.size)
        assertEquals(InstallmentCurrency.EUR to 5_000L, result[0].currency to result[0].amountMinor)
        assertEquals(InstallmentCurrency.USD to 12_500L, result[1].currency to result[1].amountMinor)
    }

    @Test
    fun `INV-15 an empty input projects nothing`() {
        assertTrue(InstallmentProjectionRule.project(emptyList(), today).isEmpty())
        assertTrue(InstallmentProjectionRule.project(listOf(plan(1L, emptyList())), today).isEmpty())
    }
}
