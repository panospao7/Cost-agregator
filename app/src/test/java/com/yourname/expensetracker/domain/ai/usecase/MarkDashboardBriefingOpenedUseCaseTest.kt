package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.ai.model.AiEngagementState
import com.yourname.expensetracker.domain.ai.service.AiEngagementRepository
import com.yourname.expensetracker.domain.ai.usecase.MarkDashboardBriefingOpenedUseCase.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * S10: [MarkDashboardBriefingOpenedUseCase] owns the only persistent write a deep link can cause
 * (INV-16; AE-17, AE-18, AE-19 write side, AE-20). The key is written only when it equals the last
 * delivered key read at apply time.
 */
class MarkDashboardBriefingOpenedUseCaseTest {

    private class FakeEngagementRepository(
        var delivered: String? = null,
        var opened: String? = null
    ) : AiEngagementRepository {
        var readFailure: Throwable? = null
        var writeFailure: Throwable? = null
        var deliveredWrites = 0
        var openedWrites = 0

        override fun engagementState(): Flow<AiEngagementState> = emptyFlow()

        override suspend fun getLastDeliveredDashboardBriefingKey(): String? {
            readFailure?.let { throw it }
            return delivered
        }

        override suspend fun setLastDeliveredDashboardBriefingKey(targetKey: String) {
            deliveredWrites++
            delivered = targetKey
        }

        override suspend fun getLastOpenedDashboardBriefingKey(): String? = opened

        override suspend fun setLastOpenedDashboardBriefingKey(targetKey: String) {
            writeFailure?.let { throw it }
            openedWrites++
            opened = targetKey
        }
    }

    private val delivered = "dashboard_home:2027-03-12"
    private val other = "dashboard_home:2027-03-11"

    @Test
    fun `AE-18 a key equal to the last delivered key sets lastOpened and writes nothing else`() = runTest {
        val repo = FakeEngagementRepository(delivered = delivered, opened = other)

        val outcome = MarkDashboardBriefingOpenedUseCase(repo)(delivered)

        assertEquals(Outcome.WRITTEN, outcome)
        assertEquals(delivered, repo.opened)
        assertEquals(1, repo.openedWrites)
        assertEquals(0, repo.deliveredWrites)
        assertEquals(delivered, repo.delivered)
    }

    @Test
    fun `AE-17 a key that was not delivered leaves lastOpened unchanged`() = runTest {
        val repo = FakeEngagementRepository(delivered = other, opened = null)

        val outcome = MarkDashboardBriefingOpenedUseCase(repo)(delivered)

        assertEquals(Outcome.NOT_DELIVERED, outcome)
        assertNull(repo.opened)
        assertEquals(0, repo.openedWrites)
        assertEquals(0, repo.deliveredWrites)
    }

    @Test
    fun `AE-17 no delivered key at all writes nothing`() = runTest {
        val repo = FakeEngagementRepository(delivered = null, opened = other)

        val outcome = MarkDashboardBriefingOpenedUseCase(repo)(delivered)

        assertEquals(Outcome.NOT_DELIVERED, outcome)
        assertEquals(other, repo.opened)
        assertEquals(0, repo.openedWrites)
    }

    @Test
    fun `INV-16 the delivered key is read at apply time on every call`() = runTest {
        val repo = FakeEngagementRepository(delivered = other)
        val useCase = MarkDashboardBriefingOpenedUseCase(repo)

        assertEquals(Outcome.NOT_DELIVERED, useCase(delivered))
        assertNull(repo.opened)

        // A new briefing is delivered between the two taps.
        repo.delivered = delivered
        assertEquals(Outcome.WRITTEN, useCase(delivered))
        assertEquals(delivered, repo.opened)
    }

    @Test
    fun `AE-20 an unreadable engagement store writes nothing and reports unreadable`() = runTest {
        val repo = FakeEngagementRepository(delivered = delivered, opened = null)
        repo.readFailure = IOException("store unreadable")

        val outcome = MarkDashboardBriefingOpenedUseCase(repo)(delivered)

        assertEquals(Outcome.STATE_UNREADABLE, outcome)
        assertNull(repo.opened)
        assertEquals(0, repo.openedWrites)
    }

    @Test
    fun `a failed write is reported as unreadable and is not thrown`() = runTest {
        val repo = FakeEngagementRepository(delivered = delivered, opened = null)
        repo.writeFailure = IOException("disk full")

        val outcome = MarkDashboardBriefingOpenedUseCase(repo)(delivered)

        assertEquals(Outcome.STATE_UNREADABLE, outcome)
        assertNull(repo.opened)
    }

    @Test
    fun `cancellation during the read propagates and writes nothing`() = runTest {
        val repo = FakeEngagementRepository(delivered = delivered)
        repo.readFailure = CancellationException("cancelled")

        try {
            MarkDashboardBriefingOpenedUseCase(repo)(delivered)
            fail("CancellationException must propagate")
        } catch (e: CancellationException) {
            assertTrue(e.message == "cancelled")
        }
        assertEquals(0, repo.openedWrites)
    }

    @Test
    fun `cancellation during the write propagates`() = runTest {
        val repo = FakeEngagementRepository(delivered = delivered)
        repo.writeFailure = CancellationException("cancelled")

        try {
            MarkDashboardBriefingOpenedUseCase(repo)(delivered)
            fail("CancellationException must propagate")
        } catch (e: CancellationException) {
            assertTrue(e.message == "cancelled")
        }
        assertNull(repo.opened)
    }
}
