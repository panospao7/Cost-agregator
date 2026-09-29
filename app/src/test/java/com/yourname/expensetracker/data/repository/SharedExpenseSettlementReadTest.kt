package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.GroupSettlementDao
import com.yourname.expensetracker.data.database.entity.GroupSettlementEntity
import com.yourname.expensetracker.domain.groups.SharedGroupSettlement
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.test.assertFailsWith

class SharedExpenseSettlementReadTest {
    private val dao = mockk<GroupSettlementDao>()
    private val adapter = SharedExpenseDataPortAdapter(
        writeBarrier = mockk(), groupDao = mockk(), memberDao = mockk(),
        groupExpenseDao = mockk(), transactionCoordinator = mockk(),
        timeProvider = mockk(), settlementDao = dao)

    @Test
    fun transportsBalanceFieldsWithoutDroppingHistoryOrWriting() = runTest {
        coEvery { dao.getSettlementsForGroup(7L) } returns listOf(
            GroupSettlementEntity(9L, 7L, 1L, 2L, 20.0, "EUR", 100L,
                status = "COMPLETED", notes = "private note"))
        assertEquals(listOf(SharedGroupSettlement(7L, 1L, 2L, 20.0, "EUR", "COMPLETED")),
            adapter.getGroupSettlementsOnce(7L))
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { dao.deleteSettlement(any()) }
    }

    @Test
    fun queryFailureIsNotConvertedToEmptyHistory() = runTest {
        val error = IllegalStateException("query failed")
        coEvery { dao.getSettlementsForGroup(7L) } throws error
        assertSame(error, assertFailsWith<IllegalStateException> { adapter.getGroupSettlementsOnce(7L) })
    }

    @Test
    fun cancellationPropagatesUnchanged() = runTest {
        val error = CancellationException("cancelled")
        coEvery { dao.getSettlementsForGroup(7L) } throws error
        assertSame(error, assertFailsWith<CancellationException> { adapter.getGroupSettlementsOnce(7L) })
    }
}
