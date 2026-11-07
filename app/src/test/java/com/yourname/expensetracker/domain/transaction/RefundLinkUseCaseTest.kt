package com.yourname.expensetracker.domain.transaction

import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ExpenseRefundLinkDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ExpenseRefundLink
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * Tests for [RefundLinkUseCase] (refund-netting INV-16, INV-17, INV-3 candidate scope).
 *
 * The coordinator and the DAOs are mocks: the use case owns no write, so the assertions are on
 * delegation, on the ranked read result and on fail-closed read behaviour.
 */
class RefundLinkUseCaseTest {

    private val athens = ZoneId.of("Europe/Athens")
    private lateinit var originalZone: TimeZone

    private val coordinator = mockk<TransactionLifecycleCoordinator>()
    private val expenseDao = mockk<ExpenseDao>()
    private val linkDao = mockk<ExpenseRefundLinkDao>()
    private val groupDao = mockk<GroupExpenseDao>()
    private val readBarrier = mockk<DatabaseReadBarrier>()

    private lateinit var useCase: RefundLinkUseCase

    @Before
    fun setUp() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(athens))
        every { readBarrier.checkReadAllowed(any<String>()) } returns Unit
        coEvery { groupDao.existsForExpense(any()) } returns false
        useCase = RefundLinkUseCase(coordinator, expenseDao, linkDao, groupDao, readBarrier)
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalZone)
    }

    private fun at(month: Int, day: Int, hour: Int = 12): Long =
        ZonedDateTime.of(2026, month, day, hour, 0, 0, 0, athens).toInstant().toEpochMilli()

    private fun row(
        id: Long,
        type: TransactionType,
        amount: Double,
        date: Long,
        merchant: String = "Zara",
        currency: String = "EUR",
        netted: Double = 0.0,
        notMine: Boolean = false
    ) = Expense(
        id = id,
        amount = amount,
        currency = currency,
        merchant = merchant,
        transactionType = type,
        date = date,
        isNotMine = notMine,
        refundNettedAmount = netted
    )

    private fun refundRow(id: Long = 100L, amount: Double = 40.0) =
        row(id, TransactionType.DEPOSIT, amount, at(12, 2), merchant = "ZARA")

    private fun storedLink(
        id: Long,
        refundId: Long,
        purchaseId: Long,
        applied: Double,
        currency: String = "EUR",
        createdAt: Long = 5L
    ) = ExpenseRefundLink(
        id = id,
        refundExpenseId = refundId,
        purchaseExpenseId = purchaseId,
        appliedAmount = applied,
        currency = currency,
        createdAt = createdAt,
        source = RefundLinkSource.USER_CONFIRMED.name
    )

    private fun blocked() = DatabaseAccessBlockedException(
        DatabaseAccessType.READ,
        DatabaseAccessOperation("test"),
        RestoreMaintenanceMode.Mode.BACKUP_EXPORTING
    )

    private fun stubPurchases(refund: Expense, purchases: List<Expense>) {
        coEvery { expenseDao.getById(refund.id) } returns refund
        coEvery { expenseDao.getExpensesBetween(any(), any()) } returns purchases
    }

    // ---- INV-16 delegation -------------------------------------------------------------------

    @Test
    fun `INV-16 link delegates to the coordinator and returns its result unchanged`() = runTest {
        val linked = RefundLinkResult.Linked(7L, MoneyAmount(40.0, CurrencyCode.parse("EUR")!!))
        coEvery { coordinator.linkRefund(100L, 1L) } returns linked

        assertEquals(linked, useCase.link(100L, 1L))
        coVerify(exactly = 1) { coordinator.linkRefund(100L, 1L) }
    }

    @Test
    fun `INV-16 link passes refusals and failures through without a retry`() = runTest {
        coEvery { coordinator.linkRefund(100L, 1L) } returns
            RefundLinkResult.Refused(RefundLinkRefusal.CURRENCY_MISMATCH)
        assertEquals(RefundLinkResult.Refused(RefundLinkRefusal.CURRENCY_MISMATCH), useCase.link(100L, 1L))

        coEvery { coordinator.linkRefund(100L, 2L) } returns
            RefundLinkResult.Failed(RefundWriteFailure.WRITE_BLOCKED)
        assertEquals(RefundLinkResult.Failed(RefundWriteFailure.WRITE_BLOCKED), useCase.link(100L, 2L))

        coVerify(exactly = 1) { coordinator.linkRefund(100L, 1L) }
        coVerify(exactly = 1) { coordinator.linkRefund(100L, 2L) }
    }

    @Test
    fun `INV-16 unlink delegates to the coordinator for every result kind`() = runTest {
        coEvery { coordinator.unlinkRefund(100L) } returns RefundUnlinkResult.Unlinked(7L)
        assertEquals(RefundUnlinkResult.Unlinked(7L), useCase.unlink(100L))

        coEvery { coordinator.unlinkRefund(101L) } returns RefundUnlinkResult.NotLinked
        assertEquals(RefundUnlinkResult.NotLinked, useCase.unlink(101L))

        coEvery { coordinator.unlinkRefund(102L) } returns RefundUnlinkResult.Failed(RefundWriteFailure.INTERNAL)
        assertEquals(RefundUnlinkResult.Failed(RefundWriteFailure.INTERNAL), useCase.unlink(102L))
    }

    @Test
    fun `INV-16 link and unlink do not swallow cancellation`() = runTest {
        coEvery { coordinator.linkRefund(any(), any()) } throws CancellationException("cancelled")
        coEvery { coordinator.unlinkRefund(any()) } throws CancellationException("cancelled")

        try {
            useCase.link(1L, 2L)
            fail("link must rethrow CancellationException")
        } catch (expected: CancellationException) {
            // expected
        }
        try {
            useCase.unlink(1L)
            fail("unlink must rethrow CancellationException")
        } catch (expected: CancellationException) {
            // expected
        }
    }

    @Test
    fun `INV-16 candidate and link reads never call a coordinator write`() = runTest {
        stubPurchases(refundRow(), emptyList())
        coEvery { linkDao.getForExpense(any()) } returns emptyList()

        useCase.findCandidates(100L)
        useCase.linksOf(100L)

        coVerify(exactly = 0) { coordinator.linkRefund(any(), any()) }
        coVerify(exactly = 0) { coordinator.unlinkRefund(any()) }
    }

    // ---- INV-17 findCandidates ---------------------------------------------------------------

    @Test
    fun `INV-17 control - findCandidates returns ranked purchases from one range read (AE-21)`() = runTest {
        val refund = refundRow(amount = 40.0)
        val p11 = row(11L, TransactionType.PURCHASE, 40.0, at(11, 20))
        val p12 = row(12L, TransactionType.PURCHASE, 40.0, at(11, 25), merchant = "H&M")
        val p1 = row(1L, TransactionType.PURCHASE, 100.0, at(11, 3))
        stubPurchases(refund, listOf(p1, p12, p11))

        val result = useCase.findCandidates(100L)

        assertTrue(result is RefundCandidates.Available)
        result as RefundCandidates.Available
        assertEquals(listOf(11L, 12L, 1L), result.items.map { it.purchaseId })
        verify { readBarrier.checkReadAllowed(any<String>()) }
        coVerify(exactly = 1) { expenseDao.getExpensesBetween(any(), any()) }
    }

    @Test
    fun `INV-17 range read covers the detector window before the refund and one day after`() = runTest {
        val refund = refundRow()
        stubPurchases(refund, emptyList())

        useCase.findCandidates(100L)

        coVerify {
            expenseDao.getExpensesBetween(
                refund.date - RefundLikelihoodDetector.WINDOW_MS,
                refund.date + 24L * 60L * 60L * 1000L
            )
        }
    }

    @Test
    fun `INV-17 candidates keep a not-mine purchase because ownership never affects eligibility`() = runTest {
        val refund = refundRow(amount = 80.0)
        val notMine = row(6L, TransactionType.PURCHASE, 80.0, at(11, 3), notMine = true)
        stubPurchases(refund, listOf(notMine))

        val result = useCase.findCandidates(100L) as RefundCandidates.Available

        assertEquals(listOf(6L), result.items.map { it.purchaseId })
    }

    @Test
    fun `INV-17 group-linked purchases are skipped`() = runTest {
        val refund = refundRow()
        val grouped = row(2L, TransactionType.PURCHASE, 40.0, at(11, 20))
        val plain = row(3L, TransactionType.PURCHASE, 40.0, at(11, 21))
        stubPurchases(refund, listOf(grouped, plain))
        coEvery { groupDao.existsForExpense(2L) } returns true

        val result = useCase.findCandidates(100L) as RefundCandidates.Available

        assertEquals(listOf(3L), result.items.map { it.purchaseId })
    }

    @Test
    fun `INV-17 a group-linked refund gets an empty available list without a range read`() = runTest {
        val refund = refundRow()
        coEvery { expenseDao.getById(100L) } returns refund
        coEvery { groupDao.existsForExpense(100L) } returns true

        assertEquals(RefundCandidates.Available(emptyList()), useCase.findCandidates(100L))
        coVerify(exactly = 0) { expenseDao.getExpensesBetween(any(), any()) }
    }

    @Test
    fun `INV-17 a missing refund is a real empty result and not unavailable (AE-28)`() = runTest {
        coEvery { expenseDao.getById(404L) } returns null

        assertEquals(RefundCandidates.Available(emptyList()), useCase.findCandidates(404L))
    }

    @Test
    fun `INV-17 an already linked refund is offered no candidates`() = runTest {
        val linked = refundRow().copy(refundNettedAmount = 40.0)
        stubPurchases(linked, listOf(row(1L, TransactionType.PURCHASE, 100.0, at(11, 3))))

        assertEquals(RefundCandidates.Available(emptyList()), useCase.findCandidates(100L))
    }

    @Test
    fun `INV-17 result is capped at 50 candidates in rank order`() = runTest {
        val refund = refundRow(amount = 40.0)
        val many = (1L..60L).map { row(it, TransactionType.PURCHASE, 100.0, at(11, 3)) }
        stubPurchases(refund, many)

        val result = useCase.findCandidates(100L) as RefundCandidates.Available

        assertEquals(50, result.items.size)
        // Same amount, merchant and day: id descending, so the 10 lowest ids are cut.
        assertEquals((60L downTo 11L).toList(), result.items.map { it.purchaseId })
    }

    @Test
    fun `INV-17 the cap counts only candidates that survive the group filter`() = runTest {
        val refund = refundRow(amount = 40.0)
        val many = (1L..60L).map { row(it, TransactionType.PURCHASE, 100.0, at(11, 3)) }
        stubPurchases(refund, many)
        coEvery { groupDao.existsForExpense(60L) } returns true

        val result = useCase.findCandidates(100L) as RefundCandidates.Available

        assertEquals(50, result.items.size)
        assertEquals((59L downTo 10L).toList(), result.items.map { it.purchaseId })
    }

    @Test
    fun `INV-17 blocked read gives Unavailable READ_BLOCKED and never an empty list (AE-22)`() = runTest {
        every { readBarrier.checkReadAllowed(any<String>()) } throws blocked()

        val result = useCase.findCandidates(100L)

        assertEquals(RefundCandidates.Unavailable(RefundReadFailure.READ_BLOCKED), result)
        coVerify(exactly = 0) { expenseDao.getExpensesBetween(any(), any()) }
    }

    @Test
    fun `INV-17 a blocked read raised by the DAO is also READ_BLOCKED`() = runTest {
        coEvery { expenseDao.getById(100L) } throws blocked()

        assertEquals(
            RefundCandidates.Unavailable(RefundReadFailure.READ_BLOCKED),
            useCase.findCandidates(100L)
        )
    }

    @Test
    fun `INV-17 any other read error gives Unavailable READ_FAILED and never an empty list`() = runTest {
        coEvery { expenseDao.getById(100L) } throws IllegalStateException("db closed")
        assertEquals(RefundCandidates.Unavailable(RefundReadFailure.READ_FAILED), useCase.findCandidates(100L))

        coEvery { expenseDao.getById(101L) } returns refundRow(id = 101L)
        coEvery { groupDao.existsForExpense(101L) } returns false
        coEvery { expenseDao.getExpensesBetween(any(), any()) } throws IOException("io")
        assertEquals(RefundCandidates.Unavailable(RefundReadFailure.READ_FAILED), useCase.findCandidates(101L))
    }

    @Test
    fun `INV-17 findCandidates rethrows CancellationException`() = runTest {
        coEvery { expenseDao.getById(100L) } throws CancellationException("cancelled")

        try {
            useCase.findCandidates(100L)
            fail("findCandidates must rethrow CancellationException")
        } catch (expected: CancellationException) {
            // expected
        }
    }

    @Test
    fun `INV-17 a cancellation raised by the read barrier is rethrown and not mapped`() = runTest {
        every { readBarrier.checkReadAllowed(any<String>()) } throws CancellationException("cancelled")

        try {
            useCase.findCandidates(100L)
            fail("findCandidates must rethrow CancellationException")
        } catch (expected: CancellationException) {
            // expected
        }
    }

    // ---- INV-17 linksOf ----------------------------------------------------------------------

    @Test
    fun `INV-17 control - linksOf maps both sides of a link with the counterpart id`() = runTest {
        val asPurchase = storedLink(id = 7L, refundId = 100L, purchaseId = 1L, applied = 40.0, createdAt = 55L)
        val asRefund = storedLink(id = 8L, refundId = 200L, purchaseId = 1L, applied = 60.0, createdAt = 56L)
        coEvery { linkDao.getForExpense(1L) } returns listOf(asPurchase, asRefund)

        val result = useCase.linksOf(1L)

        val eur = CurrencyCode.parse("EUR")!!
        assertEquals(
            RefundLinks.Available(
                1L,
                listOf(
                    RefundLinkInfo(7L, 100L, MoneyAmount(40.0, eur), 55L),
                    RefundLinkInfo(8L, 200L, MoneyAmount(60.0, eur), 56L)
                )
            ),
            result
        )
    }

    @Test
    fun `INV-17 linksOf from the refund side names the purchase as counterpart`() = runTest {
        coEvery { linkDao.getForExpense(100L) } returns
            listOf(storedLink(id = 7L, refundId = 100L, purchaseId = 1L, applied = 40.0))

        val result = useCase.linksOf(100L) as RefundLinks.Available

        assertEquals(1L, result.links.single().counterpartId)
    }

    @Test
    fun `INV-17 linksOf with no link is an available empty list`() = runTest {
        coEvery { linkDao.getForExpense(9L) } returns emptyList()

        assertEquals(RefundLinks.Available(9L, emptyList()), useCase.linksOf(9L))
    }

    @Test
    fun `INV-17 linksOf blocked read gives Unavailable READ_BLOCKED`() = runTest {
        every { readBarrier.checkReadAllowed(any<String>()) } throws blocked()

        assertEquals(RefundLinks.Unavailable(RefundReadFailure.READ_BLOCKED), useCase.linksOf(1L))
        coVerify(exactly = 0) { linkDao.getForExpense(any()) }
    }

    @Test
    fun `INV-17 linksOf read error gives Unavailable READ_FAILED and never an empty list`() = runTest {
        coEvery { linkDao.getForExpense(1L) } throws IllegalStateException("db closed")

        assertEquals(RefundLinks.Unavailable(RefundReadFailure.READ_FAILED), useCase.linksOf(1L))
    }

    @Test
    fun `INV-17 linksOf with an invalid stored currency fails closed as READ_FAILED`() = runTest {
        coEvery { linkDao.getForExpense(1L) } returns
            listOf(storedLink(id = 7L, refundId = 100L, purchaseId = 1L, applied = 40.0, currency = "??"))

        assertEquals(RefundLinks.Unavailable(RefundReadFailure.READ_FAILED), useCase.linksOf(1L))
    }

    @Test
    fun `INV-17 linksOf rethrows CancellationException`() = runTest {
        coEvery { linkDao.getForExpense(1L) } throws CancellationException("cancelled")

        try {
            useCase.linksOf(1L)
            fail("linksOf must rethrow CancellationException")
        } catch (expected: CancellationException) {
            // expected
        }
    }
}
