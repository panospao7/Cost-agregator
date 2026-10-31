package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.InstallmentPlanDao
import com.yourname.expensetracker.data.database.entity.InstallmentEntry
import com.yourname.expensetracker.data.database.entity.InstallmentPlan
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * [InstallmentProjectionPortImpl] over a mocked plan DAO (reads only). INV-15 bounds, civil
 * "today" from the injected TimeProvider in the device zone, fail-closed decoding (an entry
 * with an unconvertible amount is omitted, never shown as zero). Synthetic data only.
 */
class InstallmentProjectionPortImplTest {

    private val today = LocalDate.of(2027, 5, 10)

    private lateinit var originalZone: TimeZone
    private lateinit var planDao: InstallmentPlanDao
    private var nowMillis = 0L
    private val timeProvider = object : TimeProvider {
        override fun now(): Long = nowMillis
    }
    private lateinit var port: InstallmentProjectionPortImpl

    @Before
    fun setUp() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        planDao = mockk()
        nowMillis = today.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        port = InstallmentProjectionPortImpl(planDao, timeProvider)
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalZone)
    }

    private fun planRow(id: Long, currency: String = "EUR") = InstallmentPlan(
        id = id,
        planUuid = "plan-uuid-$id",
        kind = "PURCHASE_ATTACHED",
        displayName = "Synthetic plan $id",
        currency = currency,
        splitMethod = "EQUAL_SPLIT",
        installmentCount = 12,
        frequency = "MONTHLY",
        firstDueDate = today.toEpochDay(),
        anchorDayOfMonth = 10,
        residualPlacement = "LAST",
        fulfilmentMode = "AUTO_RECORD",
        declaredOnDate = today.toEpochDay(),
        status = "ACTIVE",
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun entryRow(
        id: Long,
        planId: Long,
        dueOffsetDays: Long,
        amount: Double = 50.0,
        status: String = "SCHEDULED",
        kind: String = "REGULAR",
        generation: Int = 0,
        seq: Int = id.toInt()
    ) = InstallmentEntry(
        id = id,
        planId = planId,
        seq = seq,
        kind = kind,
        dueDate = today.toEpochDay() + dueOffsetDays,
        scheduledAmount = amount,
        status = status,
        recordGeneration = generation,
        statusChangedAt = 0L
    )

    private fun givenActive(plans: List<InstallmentPlan>, entries: List<InstallmentEntry>) {
        coEvery { planDao.getPlansByStatus("ACTIVE") } returns plans
        coEvery { planDao.getEntriesForPlan(any()) } answers { entries.filter { e -> e.planId == firstArg<Long>() } }
    }

    private fun setZone(id: String) = TimeZone.setDefault(TimeZone.getTimeZone(id))

    @Test
    fun `INV-15 reads only active plans and projects scheduled entries due today or later`() = runTest {
        givenActive(
            listOf(planRow(1L)),
            listOf(
                entryRow(1L, 1L, -1L),
                entryRow(2L, 1L, 0L),
                entryRow(3L, 1L, 30L),
                entryRow(4L, 1L, 60L, status = "PAID"),
                entryRow(5L, 1L, 90L, status = "PAID_BEFORE_TRACKING"),
                entryRow(6L, 1L, 120L, status = "SKIPPED")
            )
        )

        val result = port.upcomingEntries()

        assertEquals(listOf(2L, 3L), result.map { it.entryId })
        coVerify(exactly = 1) { planDao.getPlansByStatus("ACTIVE") }
        coVerify(exactly = 0) { planDao.getAllPlans() }
    }

    @Test
    fun `INV-15 horizon end is inclusive`() = runTest {
        givenActive(listOf(planRow(1L)), listOf(entryRow(1L, 1L, 9L), entryRow(2L, 1L, 10L), entryRow(3L, 1L, 11L)))

        val result = port.upcomingEntries(today.toEpochDay() + 10L)

        assertEquals(listOf(1L, 2L), result.map { it.entryId })
    }

    @Test
    fun `INV-15 amount is the scheduled amount in minor units in the plan currency and generation is passed`() = runTest {
        givenActive(
            listOf(planRow(1L, currency = "USD")),
            listOf(entryRow(1L, 1L, 5L, amount = 125.0, generation = 3, seq = 4))
        )

        val projected = port.upcomingEntries().single()

        assertEquals(12_500L, projected.amountMinor)
        assertEquals(InstallmentCurrency.USD, projected.currency)
        assertEquals(3, projected.recordGeneration)
        assertEquals(4, projected.seq)
        assertEquals("plan-uuid-1", projected.planUuid)
    }

    @Test
    fun `an unconvertible amount is omitted and never projected as zero`() = runTest {
        givenActive(
            listOf(planRow(1L)),
            listOf(
                entryRow(1L, 1L, 5L, amount = Double.NaN),
                entryRow(2L, 1L, 6L, amount = 1_000_000_000.0),
                entryRow(3L, 1L, 7L, amount = 50.0)
            )
        )

        val result = port.upcomingEntries()

        assertEquals(listOf(3L), result.map { it.entryId })
        assertTrue(result.none { it.amountMinor == 0L })
    }

    @Test
    fun `an unknown stored code skips that row and keeps the others`() = runTest {
        givenActive(
            listOf(planRow(1L), planRow(2L, currency = "GBP")),
            listOf(
                entryRow(1L, 1L, 5L, status = "BOGUS"),
                entryRow(2L, 1L, 6L, kind = "BOGUS"),
                entryRow(3L, 1L, 7L),
                entryRow(4L, 2L, 8L)
            )
        )

        val result = port.upcomingEntries()

        assertEquals(listOf(3L), result.map { it.entryId })
    }

    @Test
    fun `INV-15 results are ordered by due date then plan id then sequence`() = runTest {
        givenActive(
            listOf(planRow(2L), planRow(1L)),
            listOf(
                entryRow(11L, 1L, 20L, seq = 2),
                entryRow(10L, 1L, 10L, seq = 1),
                entryRow(21L, 2L, 10L, seq = 1)
            )
        )

        assertEquals(listOf(10L, 21L, 11L), port.upcomingEntries().map { it.entryId })
    }

    @Test
    fun `today comes from the time provider in the device zone at the Auckland midnight boundary`() = runTest {
        setZone("Pacific/Auckland")
        val zone = ZoneId.of("Pacific/Auckland")
        val boundary = LocalDate.of(2026, 10, 25)
        givenActive(
            listOf(planRow(1L)),
            listOf(
                InstallmentEntry(
                    id = 1L, planId = 1L, seq = 1, kind = "REGULAR", dueDate = boundary.toEpochDay(),
                    scheduledAmount = 50.0, status = "SCHEDULED", statusChangedAt = 0L
                )
            )
        )

        nowMillis = ZonedDateTime.of(LocalDateTime.of(2026, 10, 25, 0, 0), zone).toInstant().toEpochMilli()
        assertEquals("due today is projected", listOf(1L), port.upcomingEntries().map { it.entryId })

        nowMillis = ZonedDateTime.of(LocalDateTime.of(2026, 10, 26, 0, 0), zone).toInstant().toEpochMilli()
        assertTrue("due yesterday is not projected", port.upcomingEntries().isEmpty())
    }

    @Test
    fun `today stays the previous civil day late in the evening in Los Angeles`() = runTest {
        setZone("America/Los_Angeles")
        val zone = ZoneId.of("America/Los_Angeles")
        val target = LocalDate.of(2026, 10, 24)
        givenActive(
            listOf(planRow(1L)),
            listOf(
                InstallmentEntry(
                    id = 1L, planId = 1L, seq = 1, kind = "REGULAR", dueDate = target.toEpochDay(),
                    scheduledAmount = 50.0, status = "SCHEDULED", statusChangedAt = 0L
                )
            )
        )

        // 23:59 local on 10-24 is already 10-25 in UTC; the entry due 10-24 is still due today here.
        nowMillis = ZonedDateTime.of(LocalDateTime.of(2026, 10, 24, 23, 59), zone).toInstant().toEpochMilli()

        assertEquals(listOf(1L), port.upcomingEntries().map { it.entryId })
    }

    @Test
    fun `a cancellation from the data source propagates and is not swallowed`() = runTest {
        coEvery { planDao.getPlansByStatus("ACTIVE") } throws CancellationException("cancelled")

        try {
            port.upcomingEntries()
            fail("CancellationException must propagate")
        } catch (expected: CancellationException) {
            // expected
        }
    }
}
