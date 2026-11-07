package com.yourname.expensetracker.domain.transaction

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ExpenseRefundLink
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.transaction.RefundNettingPolicy.LinkDecision
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * Pure tests for [RefundNettingPolicy] (refund-netting INV-3, INV-4, INV-5, INV-19, D-7).
 *
 * Expected values come from the domain spec examples (AE-1..6, 11, 12, 18, 20), not from the
 * policy's own formulas. Calendar-day rules run in Europe/Athens, the spec zone.
 */
class RefundNettingPolicyTest {

    private val athens = ZoneId.of("Europe/Athens")
    private lateinit var originalZone: TimeZone

    @Before
    fun setZone() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(athens))
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    private fun at(month: Int, day: Int, hour: Int = 12, minute: Int = 0): Long =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, athens).toInstant().toEpochMilli()

    private fun purchase(
        id: Long = 1L,
        amount: Double = 100.0,
        currency: String = "EUR",
        merchant: String = "Zara",
        type: TransactionType = TransactionType.PURCHASE,
        date: Long = at(11, 3),
        netted: Double = 0.0,
        exclusion: String? = null,
        notMine: Boolean = false,
        shared: Boolean = false
    ): Expense = Expense(
        id = id,
        amount = amount,
        currency = currency,
        merchant = merchant,
        transactionType = type,
        date = date,
        isNotMine = notMine,
        isSharedExpense = shared,
        spendingExclusionReason = exclusion,
        refundNettedAmount = netted
    )

    private fun deposit(
        id: Long = 2L,
        amount: Double = 40.0,
        currency: String = "EUR",
        merchant: String = "ZARA",
        type: TransactionType = TransactionType.DEPOSIT,
        date: Long = at(12, 2),
        netted: Double = 0.0,
        exclusion: String? = null
    ): Expense = purchase(
        id = id, amount = amount, currency = currency, merchant = merchant, type = type,
        date = date, netted = netted, exclusion = exclusion
    )

    private fun link(
        id: Long = 10L,
        refundId: Long,
        purchaseId: Long,
        applied: Double,
        currency: String = "EUR"
    ) = ExpenseRefundLink(
        id = id,
        refundExpenseId = refundId,
        purchaseExpenseId = purchaseId,
        appliedAmount = applied,
        currency = currency,
        createdAt = 1L,
        source = RefundLinkSource.USER_CONFIRMED.name
    )

    private fun decide(
        refund: Expense?,
        purchase: Expense?,
        refundLinks: List<ExpenseRefundLink> = emptyList(),
        purchaseLinks: List<ExpenseRefundLink> = emptyList(),
        refundGroupLinked: Boolean = false,
        purchaseGroupLinked: Boolean = false,
        refundInstallmentLinked: Boolean = false,
        purchaseInstallmentLinked: Boolean = false
    ): LinkDecision = RefundNettingPolicy.decideLink(
        refund, purchase, refundLinks, purchaseLinks, refundGroupLinked, purchaseGroupLinked,
        refundInstallmentLinked, purchaseInstallmentLinked
    )

    private fun assertApplied(expected: String, scale: Int, decision: LinkDecision) {
        assertTrue("expected Apply but was $decision", decision is LinkDecision.Apply)
        decision as LinkDecision.Apply
        assertEquals(scale, decision.scale)
        assertEquals(
            "applied ${decision.applied} != $expected",
            0,
            BigDecimal(expected).compareTo(decision.applied)
        )
    }

    private fun assertRefused(code: RefundLinkRefusal, decision: LinkDecision) {
        assertEquals(LinkDecision.Refused(code), decision)
    }

    // ---- INV-3 eligibility: pair rule --------------------------------------------------------

    @Test
    fun `INV-3 control - deposit to purchase in the same currency applies the refund amount (AE-1)`() {
        assertApplied("40.00", 2, decide(deposit(), purchase()))
    }

    @Test
    fun `INV-3 missing rows are refused`() {
        assertRefused(RefundLinkRefusal.REFUND_NOT_FOUND, decide(null, purchase()))
        assertRefused(RefundLinkRefusal.PURCHASE_NOT_FOUND, decide(deposit(), null))
    }

    @Test
    fun `INV-3 a row cannot be linked to itself`() {
        val row = deposit(id = 5L)
        assertRefused(RefundLinkRefusal.SAME_ROW, decide(row, row))
    }

    @Test
    fun `INV-3 refund side must be a deposit`() {
        for (type in listOf(
            TransactionType.PURCHASE, TransactionType.WITHDRAWAL, TransactionType.TRANSFER, TransactionType.UNKNOWN
        )) {
            assertRefused(
                RefundLinkRefusal.NOT_A_DEPOSIT,
                decide(deposit(type = type), purchase())
            )
        }
    }

    @Test
    fun `INV-3 purchase side must be a purchase`() {
        for (type in listOf(
            TransactionType.DEPOSIT, TransactionType.WITHDRAWAL, TransactionType.TRANSFER, TransactionType.UNKNOWN
        )) {
            assertRefused(
                RefundLinkRefusal.NOT_A_PURCHASE,
                decide(deposit(), purchase(type = type))
            )
        }
    }

    @Test
    fun `INV-3 non-positive or non-finite amounts are refused on either side`() {
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertRefused(RefundLinkRefusal.INVALID_AMOUNT, decide(deposit(amount = bad), purchase()))
            assertRefused(RefundLinkRefusal.INVALID_AMOUNT, decide(deposit(), purchase(amount = bad)))
        }
    }

    @Test
    fun `INV-3 not-mine and shared purchases stay eligible (spec 1_6, AE-9)`() {
        assertApplied("80.00", 2, decide(deposit(amount = 80.0), purchase(amount = 80.0, notMine = true)))
        assertApplied("40.00", 2, decide(deposit(), purchase(shared = true)))
    }

    // ---- D-7 currency ------------------------------------------------------------------------

    @Test
    fun `INV-3 cross-currency link is refused with no FX (AE-6, D-7)`() {
        assertRefused(
            RefundLinkRefusal.CURRENCY_MISMATCH,
            decide(deposit(amount = 46.0, currency = "EUR"), purchase(amount = 50.0, currency = "USD"))
        )
    }

    @Test
    fun `INV-3 currency comparison ignores case and padding`() {
        assertApplied("40.00", 2, decide(deposit(currency = " eur "), purchase(currency = "EUR")))
    }

    @Test
    fun `INV-3 unknown currency digits are refused and never guessed`() {
        assertRefused(
            RefundLinkRefusal.CURRENCY_DIGITS_UNAVAILABLE,
            decide(deposit(currency = "ZZZ"), purchase(currency = "ZZZ"))
        )
    }

    // ---- INV-3 posting-lag date rule (AE-12) ------------------------------------------------

    @Test
    fun `INV-3 refund two calendar days before the purchase is refused (AE-12)`() {
        assertRefused(
            RefundLinkRefusal.REFUND_BEFORE_PURCHASE,
            decide(deposit(date = at(11, 1)), purchase(date = at(11, 3)))
        )
    }

    @Test
    fun `INV-3 refund one calendar day before the purchase is allowed even at 2330 (AE-12)`() {
        assertApplied(
            "40.00", 2,
            decide(deposit(date = at(11, 2, 23, 30)), purchase(date = at(11, 3, 0, 15)))
        )
    }

    @Test
    fun `INV-3 refund on the purchase day and later is allowed`() {
        assertApplied("40.00", 2, decide(deposit(date = at(11, 3)), purchase(date = at(11, 3))))
        assertApplied("40.00", 2, decide(deposit(date = at(12, 2)), purchase(date = at(11, 3))))
    }

    // ---- INV-19 installment and group --------------------------------------------------------

    @Test
    fun `INV-19 installment-managed rows are refused on either side (AE-11)`() {
        assertRefused(
            RefundLinkRefusal.INSTALLMENT_MANAGED,
            decide(deposit(), purchase(exclusion = "INSTALLMENT_PURCHASE"))
        )
        assertRefused(
            RefundLinkRefusal.INSTALLMENT_MANAGED,
            decide(deposit(exclusion = "INSTALLMENT_PAYMENT"), purchase())
        )
    }

    @Test
    fun `INV-19 group-linked rows are refused on either side (AE-11)`() {
        assertRefused(RefundLinkRefusal.GROUP_LINKED, decide(deposit(), purchase(), purchaseGroupLinked = true))
        assertRefused(RefundLinkRefusal.GROUP_LINKED, decide(deposit(), purchase(), refundGroupLinked = true))
    }

    // ---- INV-4 one purchase per refund, many refunds per purchase ---------------------------

    @Test
    fun `INV-4 the exact pair already linked is AlreadyLinked with the existing id (AE-5)`() {
        val existing = link(id = 77L, refundId = 2L, purchaseId = 1L, applied = 40.0)
        assertEquals(
            LinkDecision.AlreadyLinked(77L),
            decide(
                deposit(netted = 40.0), purchase(netted = 40.0),
                refundLinks = listOf(existing), purchaseLinks = listOf(existing)
            )
        )
    }

    @Test
    fun `INV-4 AlreadyLinked holds even when the purchase is fully refunded`() {
        val existing = link(id = 78L, refundId = 2L, purchaseId = 1L, applied = 100.0)
        assertEquals(
            LinkDecision.AlreadyLinked(78L),
            decide(
                deposit(amount = 100.0, netted = 100.0), purchase(netted = 100.0),
                refundLinks = listOf(existing), purchaseLinks = listOf(existing)
            )
        )
    }

    @Test
    fun `INV-4 a refund linked to a different purchase is refused (AE-5)`() {
        val other = link(id = 79L, refundId = 2L, purchaseId = 9L, applied = 40.0)
        assertRefused(
            RefundLinkRefusal.REFUND_LINKED_ELSEWHERE,
            decide(deposit(netted = 40.0), purchase(id = 1L), refundLinks = listOf(other))
        )
    }

    @Test
    fun `INV-4 many refunds may link to one purchase and each sees the remainder (AE-2)`() {
        val first = link(id = 11L, refundId = 3L, purchaseId = 1L, applied = 40.0)
        assertApplied(
            "60.00", 2,
            decide(deposit(id = 4L, amount = 60.0), purchase(netted = 40.0), purchaseLinks = listOf(first))
        )
    }

    // ---- INV-5 capacity and applied amount ---------------------------------------------------

    @Test
    fun `INV-5 applied is the smaller of refund and purchase remaining (AE-4)`() {
        assertApplied("30.00", 2, decide(deposit(amount = 35.0), purchase(amount = 30.0)))
        assertApplied("30.00", 2, decide(deposit(amount = 30.0), purchase(amount = 35.0)))
    }

    @Test
    fun `INV-5 purchase remaining after earlier links caps the applied amount`() {
        val links = listOf(
            link(id = 11L, refundId = 3L, purchaseId = 1L, applied = 40.0),
            link(id = 12L, refundId = 4L, purchaseId = 1L, applied = 30.0)
        )
        assertApplied(
            "30.00", 2,
            decide(deposit(id = 5L, amount = 50.0), purchase(netted = 70.0), purchaseLinks = links)
        )
    }

    @Test
    fun `INV-5 fully refunded purchase leaves nothing to apply (AE-3)`() {
        val links = listOf(
            link(id = 11L, refundId = 3L, purchaseId = 1L, applied = 40.0),
            link(id = 12L, refundId = 4L, purchaseId = 1L, applied = 60.0)
        )
        assertRefused(
            RefundLinkRefusal.NOTHING_TO_APPLY,
            decide(deposit(id = 5L, amount = 10.0), purchase(netted = 100.0), purchaseLinks = links)
        )
    }

    @Test
    fun `INV-5 remainder of exactly one minor unit still applies and zero does not`() {
        val almost = listOf(link(id = 11L, refundId = 3L, purchaseId = 1L, applied = 99.99))
        assertApplied("0.01", 2, decide(deposit(id = 5L, amount = 10.0), purchase(), purchaseLinks = almost))
        val full = listOf(link(id = 11L, refundId = 3L, purchaseId = 1L, applied = 100.0))
        assertRefused(
            RefundLinkRefusal.NOTHING_TO_APPLY,
            decide(deposit(id = 5L, amount = 10.0), purchase(), purchaseLinks = full)
        )
    }

    @Test
    fun `INV-5 a refund below one minor unit has nothing to apply`() {
        assertRefused(RefundLinkRefusal.NOTHING_TO_APPLY, decide(deposit(amount = 0.004), purchase()))
    }

    @Test
    fun `INV-5 rounding conservation - 33_33 33_33 33_34 fill exactly 100 and no more (AE-20)`() {
        val l1 = link(id = 11L, refundId = 3L, purchaseId = 1L, applied = 33.33)
        val l2 = link(id = 12L, refundId = 4L, purchaseId = 1L, applied = 33.33)
        assertApplied(
            "33.34", 2,
            decide(deposit(id = 5L, amount = 33.34), purchase(netted = 66.66), purchaseLinks = listOf(l1, l2))
        )
        val l3 = link(id = 13L, refundId = 5L, purchaseId = 1L, applied = 33.34)
        assertRefused(
            RefundLinkRefusal.NOTHING_TO_APPLY,
            decide(
                deposit(id = 6L, amount = 0.01),
                purchase(netted = 100.0),
                purchaseLinks = listOf(l1, l2, l3)
            )
        )
    }

    @Test
    fun `INV-5 imported half-minor value rounds half-even to the currency scale (AE-20 EUR)`() {
        assertApplied("10.00", 2, decide(deposit(amount = 20.0), purchase(amount = 10.005)))
    }

    @Test
    fun `INV-5 zero-digit currency uses scale 0 (AE-20 JPY)`() {
        assertApplied(
            "333", 0,
            decide(deposit(amount = 333.0, currency = "JPY"), purchase(amount = 1000.0, currency = "JPY"))
        )
    }

    @Test
    fun `INV-5 three-digit currency uses scale 3 (AE-20 BHD)`() {
        assertApplied(
            "2.005", 3,
            decide(deposit(amount = 2.005, currency = "BHD"), purchase(amount = 10.125, currency = "BHD"))
        )
    }

    // ---- helpers (INV-1 recompute inputs) ---------------------------------------------------

    @Test
    fun `INV-1 sumApplied is an exact decimal sum and not a running double`() {
        val links = listOf(
            link(id = 1L, refundId = 3L, purchaseId = 1L, applied = 33.33),
            link(id = 2L, refundId = 4L, purchaseId = 1L, applied = 33.33),
            link(id = 3L, refundId = 5L, purchaseId = 1L, applied = 33.34)
        )
        assertEquals(0, BigDecimal("100.00").compareTo(RefundNettingPolicy.sumApplied(links)))
        assertEquals(100.0, RefundNettingPolicy.nettedAmountOf(links), 0.0)
        assertEquals(0.0, RefundNettingPolicy.nettedAmountOf(emptyList()), 0.0)
    }

    // ---- rankCandidates ----------------------------------------------------------------------

    private fun idsOf(refund: Expense, purchases: List<Expense>): List<Long> =
        RefundNettingPolicy.rankCandidates(refund, purchases).map { it.purchaseId }

    @Test
    fun `rank orders exact amount then merchant then day distance then id descending (AE-21)`() {
        val refund = deposit(id = 100L, amount = 40.0, merchant = "ZARA", date = at(12, 2))
        val p11 = purchase(id = 11L, amount = 40.0, merchant = "Zara", date = at(11, 20))
        val p12 = purchase(id = 12L, amount = 40.0, merchant = "H&M", date = at(11, 25))
        val p1 = purchase(id = 1L, amount = 100.0, merchant = "Zara", date = at(11, 3))
        // exact amount first (merchant match breaks the tie), then non-exact merchant match.
        assertEquals(listOf(11L, 12L, 1L), idsOf(refund, listOf(p1, p12, p11)))
    }

    @Test
    fun `rank ties on amount and merchant fall back to nearer day then higher id`() {
        val refund = deposit(id = 100L, amount = 40.0, merchant = "ZARA", date = at(12, 2))
        val far = purchase(id = 30L, amount = 40.0, merchant = "Zara", date = at(11, 10))
        val nearLowId = purchase(id = 21L, amount = 40.0, merchant = "Zara", date = at(12, 1))
        val nearHighId = purchase(id = 22L, amount = 40.0, merchant = "Zara", date = at(12, 1))
        assertEquals(listOf(22L, 21L, 30L), idsOf(refund, listOf(far, nearLowId, nearHighId)))
    }

    @Test
    fun `rank puts a merchant match ahead of a closer non-matching merchant when amounts tie`() {
        val refund = deposit(id = 100L, amount = 40.0, merchant = "ZARA", date = at(12, 2))
        val otherNear = purchase(id = 41L, amount = 90.0, merchant = "Ikea", date = at(12, 1))
        val zaraFar = purchase(id = 40L, amount = 90.0, merchant = "Zara", date = at(11, 5))
        assertEquals(listOf(40L, 41L), idsOf(refund, listOf(otherNear, zaraFar)))
    }

    @Test
    fun `rank reports remaining capacity amount day delta and match flags`() {
        val refund = deposit(id = 100L, amount = 40.0, merchant = "ZARA", date = at(12, 2))
        val p = purchase(id = 1L, amount = 100.0, merchant = "Zara", date = at(11, 3), netted = 40.0)
        val candidate = RefundNettingPolicy.rankCandidates(refund, listOf(p)).single()
        val eur = CurrencyCode.parse("EUR")!!
        assertEquals(1L, candidate.purchaseId)
        assertEquals(at(11, 3), candidate.purchaseDate)
        assertEquals(MoneyAmount(100.0, eur), candidate.amount)
        assertEquals(MoneyAmount(60.0, eur), candidate.remaining)
        assertEquals(false, candidate.exactAmountMatch)
        assertEquals(true, candidate.merchantMatch)
        assertEquals(29, candidate.dayDelta)
    }

    @Test
    fun `rank drops rows that fail the pair rule`() {
        val refund = deposit(id = 100L, amount = 40.0, merchant = "ZARA", date = at(12, 2))
        val good = purchase(id = 1L)
        val rows = listOf(
            good,
            purchase(id = 2L, type = TransactionType.WITHDRAWAL),
            purchase(id = 3L, type = TransactionType.DEPOSIT),
            purchase(id = 4L, currency = "USD"),
            purchase(id = 5L, exclusion = "INSTALLMENT_PURCHASE"),
            purchase(id = 6L, amount = 100.0, netted = 100.0),
            purchase(id = 7L, amount = 0.0),
            purchase(id = 8L, date = at(12, 10)).copy(id = 100L), // same id as the refund
            purchase(id = 9L, date = at(12, 4)) // refund (Dec 2) is two days before this purchase
        )
        assertEquals(listOf(1L), idsOf(refund, rows))
    }

    @Test
    fun `rank keeps a refund dated one calendar day before the purchase`() {
        val refund = deposit(id = 100L, date = at(11, 2, 23, 30))
        val p = purchase(id = 1L, date = at(11, 3, 0, 15))
        val candidate = RefundNettingPolicy.rankCandidates(refund, listOf(p)).single()
        assertEquals(-1, candidate.dayDelta)
    }

    @Test
    fun `rank keeps not-mine and shared purchases (spec 1_6)`() {
        val refund = deposit(id = 100L, amount = 80.0)
        val notMine = purchase(id = 1L, amount = 80.0, notMine = true)
        val shared = purchase(id = 2L, amount = 80.0, shared = true)
        assertEquals(listOf(2L, 1L), idsOf(refund, listOf(notMine, shared)))
    }

    @Test
    fun `rank keeps a partially refunded purchase and shows only its remainder`() {
        val refund = deposit(id = 100L, amount = 30.0)
        val p = purchase(id = 1L, amount = 100.0, netted = 99.99)
        val candidate = RefundNettingPolicy.rankCandidates(refund, listOf(p)).single()
        assertEquals(0.01, candidate.remaining.amount, 1e-9)
    }

    @Test
    fun `rank offers nothing when the refund itself is ineligible`() {
        val p = purchase(id = 1L)
        assertTrue(idsOf(deposit(id = 100L, type = TransactionType.PURCHASE), listOf(p)).isEmpty())
        assertTrue(idsOf(deposit(id = 100L, type = TransactionType.TRANSFER), listOf(p)).isEmpty())
        assertTrue(idsOf(deposit(id = 100L, netted = 40.0), listOf(p)).isEmpty())
        assertTrue(idsOf(deposit(id = 100L, exclusion = "INSTALLMENT_PAYMENT"), listOf(p)).isEmpty())
        assertTrue(idsOf(deposit(id = 100L, amount = 0.0), listOf(p)).isEmpty())
        assertTrue(idsOf(deposit(id = 100L, amount = 0.004), listOf(p)).isEmpty())
        assertTrue(idsOf(deposit(id = 100L, currency = "ZZZ"), listOf(purchase(currency = "ZZZ"))).isEmpty())
    }

    @Test
    fun `rank with no purchases is an empty list`() {
        assertTrue(RefundNettingPolicy.rankCandidates(deposit(), emptyList()).isEmpty())
    }

    @Test
    fun `rank is not capped by the policy and the cap constant is 50`() {
        assertEquals(50, RefundNettingPolicy.MAX_CANDIDATES)
        val refund = deposit(id = 1000L, amount = 40.0, date = at(12, 2))
        val many = (1L..60L).map { purchase(id = it, amount = 100.0, date = at(11, 3)) }
        assertEquals(60, RefundNettingPolicy.rankCandidates(refund, many).size)
    }
}
