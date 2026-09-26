package com.yourname.expensetracker.domain.groups

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.GroupDetailsAggregate
import com.yourname.expensetracker.data.repository.GroupsRepository
import com.yourname.expensetracker.domain.currency.ConversionResult
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import dagger.Lazy
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import timber.log.Timber
import org.robolectric.shadows.ShadowLog
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SharedExpenseBudgetOffsetEngineTest {

    private val groupsRepository = mockk<GroupsRepository>()
    private val expenseRepository = mockk<ExpenseRepository>()

    private lateinit var engine: SharedExpenseBudgetOffsetEngine
    private lateinit var converter: CurrencyConverter
    private lateinit var currencySettingsRepository: CurrencySettingsRepository

    @Before
    fun setup() {
        val localCurrencyConverter = mockk<CurrencyConverter>(relaxed = true)
        converter = localCurrencyConverter
        currencySettingsRepository = mockk()
        every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
        coEvery { localCurrencyConverter.convertAsOf(any<Double>(), any<String>(), any<String>(), any<Long>()) } answers {
            val amount = firstArg<Double>()
            val from = secondArg<String>()
            val to = thirdArg<String>()
            val at = arg<Long>(3)
            com.yourname.expensetracker.domain.currency.ConversionResult(
                originalAmount = amount,
                originalCurrency = from,
                convertedAmount = amount,
                targetCurrency = to,
                rateUsed = 1.0,
                timestamp = at
            )
        }
        coEvery { localCurrencyConverter.convert(any<Double>(), any<String>(), any<String>()) } answers {
            val amount = firstArg<Double>()
            val from = secondArg<String>()
            val to = thirdArg<String>()
            com.yourname.expensetracker.domain.currency.ConversionResult(
                originalAmount = amount,
                originalCurrency = from,
                convertedAmount = amount,
                targetCurrency = to,
                rateUsed = 1.0,
                timestamp = 0L
            )
        }
        engine = SharedExpenseBudgetOffsetEngine(
            groupsRepository = object : Lazy<GroupsRepository> { override fun get() = groupsRepository },
            expenseRepository = expenseRepository,
            ioDispatcher = Dispatchers.Unconfined,
            currencySettingsRepository = currencySettingsRepository,
            currencyConverter = localCurrencyConverter,
        )
    }

    @Test
    fun `failed personal shared and reimbursement conversions expose codes and counts only`() = runTest {
        val start = FIXED_NOW - 7L * DAY_MS
        coEvery { expenseRepository.getExpensesBetween(start, FIXED_NOW) } returns listOf(
            expense(id = 1L, amount = 1234.56, categoryId = 1L, isShared = false)
        )
        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns listOf(
            groupAggregate(
                groupId = 10L,
                members = listOf(
                    GroupMember(id = 100L, groupId = 10L, name = "Me", isCurrentUser = true),
                    GroupMember(id = 101L, groupId = 10L, name = "Friend")
                ),
                expenses = listOf(
                    GroupExpense(
                        id = 700L, groupId = 10L, expenseId = null, paidById = 100L,
                        date = start + DAY_MS, description = "Sensitive merchant",
                        totalAmount = 5432.10, splitType = SplitType.EQUAL,
                        isReimbursable = true, reimbursedAmount = 100.0
                    )
                )
            )
        )
        coEvery { converter.convertAsOf(any<Double>(), any<String>(), any<String>(), any<Long>()) } returns null

        val result = engine.calculateEffectiveBudgetSpend(start, FIXED_NOW)

        assertTrue(result.isPartial)
        assertEquals(3, result.failedConversionCount)
        assertEquals(listOf("MISSING_RATE", "MISSING_RATE", "MISSING_RATE"), result.conversionWarnings)
        assertApproxEquals(0.0, result.effectiveBudgetSpend, 0.0)
        assertApproxEquals(0.0, result.totalReimbursed, 0.0)
    }

    @Test
    fun `personal conversion failure contributes one controlled warning`() = runTest {
        val start = FIXED_NOW - 7L * DAY_MS
        coEvery { expenseRepository.getExpensesBetween(start, FIXED_NOW) } returns listOf(
            expense(id = 1L, amount = 25.0, categoryId = 1L, isShared = false)
        )
        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns emptyList()
        coEvery { converter.convertAsOf(any<Double>(), any<String>(), any<String>(), any<Long>()) } returns null

        val result = engine.calculateEffectiveBudgetSpend(start, FIXED_NOW)

        assertTrue(result.isPartial)
        assertEquals(1, result.failedConversionCount)
        assertEquals(listOf("MISSING_RATE"), result.conversionWarnings)
        assertApproxEquals(0.0, result.totalPersonalSpend, 0.0)
    }

    @Test
    fun `shared conversion failure contributes one controlled warning`() = runTest {
        val start = FIXED_NOW - 7L * DAY_MS
        coEvery { expenseRepository.getExpensesBetween(start, FIXED_NOW) } returns emptyList()
        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns listOf(
            groupAggregate(
                groupId = 10L,
                members = listOf(
                    GroupMember(id = 100L, groupId = 10L, name = "Me", isCurrentUser = true),
                    GroupMember(id = 101L, groupId = 10L, name = "Friend")
                ),
                expenses = listOf(
                    GroupExpense(
                        id = 700L,
                        groupId = 10L,
                        expenseId = null,
                        paidById = 101L,
                        date = start + DAY_MS,
                        description = "Shared purchase",
                        totalAmount = 100.0,
                        splitType = SplitType.EQUAL
                    )
                )
            )
        )
        coEvery { converter.convertAsOf(any<Double>(), any<String>(), any<String>(), any<Long>()) } returns null

        val result = engine.calculateEffectiveBudgetSpend(start, FIXED_NOW)

        assertTrue(result.isPartial)
        assertEquals(1, result.failedConversionCount)
        assertEquals(listOf("MISSING_RATE"), result.conversionWarnings)
        assertApproxEquals(0.0, result.totalSharedSpend, 0.0)
    }

    @Test
    fun `reimbursement conversion failure is counted independently`() = runTest {
        val start = FIXED_NOW - 7L * DAY_MS
        coEvery { expenseRepository.getExpensesBetween(start, FIXED_NOW) } returns emptyList()
        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns listOf(
            groupAggregate(
                groupId = 10L,
                members = listOf(
                    GroupMember(id = 100L, groupId = 10L, name = "Me", isCurrentUser = true),
                    GroupMember(id = 101L, groupId = 10L, name = "Friend")
                ),
                expenses = listOf(
                    GroupExpense(
                        id = 700L,
                        groupId = 10L,
                        expenseId = null,
                        paidById = 100L,
                        date = start + DAY_MS,
                        description = "Shared purchase",
                        totalAmount = 100.0,
                        splitType = SplitType.EQUAL,
                        isReimbursable = true,
                        reimbursedAmount = 20.0
                    )
                )
            )
        )
        coEvery { converter.convertAsOf(any<Double>(), any<String>(), any<String>(), any<Long>()) } answers {
            val amount = firstArg<Double>()
            if (amount == 20.0) null else successfulConversion(amount)
        }

        val result = engine.calculateEffectiveBudgetSpend(start, FIXED_NOW)

        assertTrue(result.isPartial)
        assertEquals(1, result.failedConversionCount)
        assertEquals(listOf("MISSING_RATE"), result.conversionWarnings)
        assertApproxEquals(50.0, result.totalSharedSpend, 0.0001)
        assertApproxEquals(0.0, result.totalReimbursed, 0.0)
    }

    @Test
    fun `successful conversions expose no warnings and are not partial`() = runTest {
        val start = FIXED_NOW - 7L * DAY_MS
        coEvery { expenseRepository.getExpensesBetween(start, FIXED_NOW) } returns listOf(
            expense(id = 1L, amount = 25.0, categoryId = 1L, isShared = false)
        )
        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns emptyList()

        val result = engine.calculateEffectiveBudgetSpend(start, FIXED_NOW)

        assertFalse(result.isPartial)
        assertEquals(0, result.failedConversionCount)
        assertTrue(result.conversionWarnings.isEmpty())
        assertApproxEquals(25.0, result.totalPersonalSpend, 0.0001)
    }

    @Test
    fun `conversion cancellation propagates`() = runTest {
        val start = FIXED_NOW - 7L * DAY_MS
        coEvery { expenseRepository.getExpensesBetween(start, FIXED_NOW) } returns listOf(
            expense(id = 1L, amount = 25.0, categoryId = 1L, isShared = false)
        )
        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns emptyList()
        coEvery {
            converter.convertAsOf(any<Double>(), any<String>(), any<String>(), any<Long>())
        } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            engine.calculateEffectiveBudgetSpend(start, FIXED_NOW)
        }
    }

    @Test
    fun `real historical converter missing rates logs no currency amount merchant or time`() = runTest {
        val start = FIXED_NOW - 7L * DAY_MS
        val store = mockk<ExchangeRateStore>()
        coEvery { store.getRateAsOf(any(), any(), any()) } returns null
        val realConverter = CurrencyConverter(store, mockk(relaxed = true))
        val realEngine = SharedExpenseBudgetOffsetEngine(
            groupsRepository = object : Lazy<GroupsRepository> { override fun get() = groupsRepository },
            expenseRepository = expenseRepository,
            currencyConverter = realConverter,
            currencySettingsRepository = currencySettingsRepository,
            ioDispatcher = Dispatchers.Unconfined
        )
        val personal = expense(id = 1L, amount = 1234.56, categoryId = 1L, isShared = false)
            .copy(currency = "USD", merchant = "Sensitive merchant")
        coEvery { expenseRepository.getExpensesBetween(start, FIXED_NOW) } returns listOf(personal)
        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns listOf(groupAggregate(
            groupId = 10L,
            members = listOf(
                GroupMember(id = 100L, groupId = 10L, name = "Me", isCurrentUser = true),
                GroupMember(id = 101L, groupId = 10L, name = "Friend")
            ),
            expenses = listOf(GroupExpense(
                id = 700L, groupId = 10L, expenseId = null, paidById = 100L,
                date = start + DAY_MS, description = "Sensitive merchant",
                totalAmount = 5432.10, currency = "USD", splitType = SplitType.EQUAL,
                isReimbursable = true, reimbursedAmount = 100.0
            ))
        ))
        val logs = mutableListOf<Pair<Throwable?, String>>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logs += t to message
            }
        }
        Timber.plant(tree)
        ShadowLog.clear()
        try {
            val result = realEngine.calculateEffectiveBudgetSpend(start, FIXED_NOW)
            assertTrue(result.isPartial)
            assertEquals(3, result.failedConversionCount)
            assertEquals(listOf("MISSING_RATE", "MISSING_RATE", "MISSING_RATE"), result.conversionWarnings)
            assertApproxEquals(0.0, result.effectiveBudgetSpend, 0.0)
            assertApproxEquals(0.0, result.totalReimbursed, 0.0)
            coVerify(atLeast = 3) { store.getRateAsOf("USD", "EUR", any()) }
            assertEquals(3, logs.count { it.second == "CurrencyConverter: MISSING_RATE stage=historical_conversion" })
            val forbidden = listOf("Sensitive merchant", "1234.56", "5432.10", "USD", "EUR", start.toString())
            assertTrue(logs.all { (throwable, message) -> throwable == null && forbidden.none { message.contains(it) } })
            assertFalse(ShadowLog.getLogs().any { entry -> forbidden.any { entry.msg.contains(it) } })
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test
    fun `calculateEffectiveBudgetSpend excludes linked legacy system expense from personal spend`() = runTest {
        val periodStart = FIXED_NOW - 30L * DAY_MS
        val periodEnd = FIXED_NOW

        val personalFood = expense(id = 1L, amount = 100.0, categoryId = 1L, isShared = false)
        val personalTravel = expense(id = 2L, amount = 50.0, categoryId = 2L, isShared = false)
        val linkedSharedExpense = expense(id = 3L, amount = 120.0, categoryId = 1L, isShared = false)

        coEvery { expenseRepository.getExpensesBetween(periodStart, periodEnd) } returns
            listOf(personalFood, personalTravel, linkedSharedExpense)

        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns listOf(
            groupAggregate(
                groupId = 10L,
                members = listOf(
                    GroupMember(id = 100L, groupId = 10L, name = "Me", isCurrentUser = true),
                    GroupMember(id = 101L, groupId = 10L, name = "Alex", isCurrentUser = false),
                ),
                expenses = listOf(
                    GroupExpense(
                        id = 700L,
                        groupId = 10L,
                        expenseId = 3L,
                        paidById = 100L,
                        date = periodStart + DAY_MS,
                        description = "Shared groceries",
                        totalAmount = 120.0,
                        splitType = SplitType.EQUAL,
                        isReimbursable = true,
                        reimbursedAmount = 20.0
                    )
                )
            )
        )

        val result = engine.calculateEffectiveBudgetSpend(periodStart, periodEnd)

        assertApproxEquals(150.0, result.totalPersonalSpend, 0.0001)
        assertApproxEquals(60.0, result.totalSharedSpend, 0.0001)
        assertApproxEquals(20.0, result.totalReimbursed, 0.0001)
        assertApproxEquals(60.0, result.netSharedLiability, 0.0001)
        assertApproxEquals(210.0, result.effectiveBudgetSpend, 0.0001)
    }

    @Test
    fun `calculateEffectiveBudgetSpend uses SplitCalculator fallback for malformed custom splits`() = runTest {
        val periodStart = FIXED_NOW - 7L * DAY_MS
        val periodEnd = FIXED_NOW

        coEvery { expenseRepository.getExpensesBetween(periodStart, periodEnd) } returns emptyList()
        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns listOf(
            groupAggregate(
                groupId = 10L,
                members = listOf(
                    GroupMember(id = 100L, groupId = 10L, name = "Me", isCurrentUser = true),
                    GroupMember(id = 101L, groupId = 10L, name = "Alex", isCurrentUser = false)
                ),
                expenses = listOf(
                    GroupExpense(
                        id = 701L,
                        groupId = 10L,
                        expenseId = null,
                        paidById = 101L,
                        date = periodStart + DAY_MS,
                        description = "Broken custom split",
                        totalAmount = 90.0,
                        splitType = SplitType.CUSTOM_AMOUNT,
                        customSplitsJson = "100:bad-data"
                    )
                )
            )
        )

        val result = engine.calculateEffectiveBudgetSpend(periodStart, periodEnd)

        assertApproxEquals(0.0, result.totalPersonalSpend, 0.0)
        assertApproxEquals(45.0, result.totalSharedSpend, 0.0001)
        assertApproxEquals(45.0, result.effectiveBudgetSpend, 0.0001)
    }

    @Test
    fun `calculateEffectiveBudgetSpend avoids N+1 by fetching expenses once and mapping in memory`() = runTest {
        val periodStart = FIXED_NOW - 7L * DAY_MS
        val periodEnd = FIXED_NOW

        val allExpenses = listOf(
            expense(id = 1L, amount = 40.0, categoryId = 1L, isShared = false),
            expense(id = 2L, amount = 80.0, categoryId = 2L, isShared = true),
            expense(id = 3L, amount = 90.0, categoryId = 3L, isShared = true)
        )
        coEvery { expenseRepository.getExpensesBetween(periodStart, periodEnd) } returns allExpenses

        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns listOf(
            groupAggregate(
                groupId = 1L,
                members = listOf(
                    GroupMember(id = 10L, groupId = 1L, name = "Me", isCurrentUser = true),
                    GroupMember(id = 11L, groupId = 1L, name = "A")
                ),
                expenses = listOf(
                    GroupExpense(
                        id = 101L,
                        groupId = 1L,
                        expenseId = 2L,
                        paidById = 11L,
                        date = periodStart + 1,
                        description = "Dinner",
                        totalAmount = 80.0,
                        splitType = SplitType.EQUAL
                    )
                )
            ),
            groupAggregate(
                groupId = 2L,
                members = listOf(
                    GroupMember(id = 20L, groupId = 2L, name = "Me", isCurrentUser = true),
                    GroupMember(id = 21L, groupId = 2L, name = "B")
                ),
                expenses = listOf(
                    GroupExpense(
                        id = 102L,
                        groupId = 2L,
                        expenseId = 3L,
                        paidById = 21L,
                        date = periodStart + 2,
                        description = "Taxi",
                        totalAmount = 90.0,
                        splitType = SplitType.EQUAL
                    )
                )
            )
        )

        engine.calculateEffectiveBudgetSpend(periodStart, periodEnd)

        coVerify(exactly = 1) { expenseRepository.getExpensesBetween(periodStart, periodEnd) }
        coVerify(exactly = 1) { groupsRepository.getActiveGroupsWithDetails() }
    }

    @Test
    fun `calculateEffectiveBudgetSpend applies category filtering for personal and shared expenses`() = runTest {
        val periodStart = FIXED_NOW - 10L * DAY_MS
        val periodEnd = FIXED_NOW

        coEvery { expenseRepository.getExpensesBetween(periodStart, periodEnd) } returns listOf(
            expense(id = 1L, amount = 100.0, categoryId = 1L, isShared = false),
            expense(id = 2L, amount = 60.0, categoryId = 2L, isShared = false),
            expense(id = 3L, amount = 80.0, categoryId = 1L, isShared = true),
            expense(id = 4L, amount = 50.0, categoryId = 2L, isShared = true)
        )
        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns listOf(
            groupAggregate(
                groupId = 5L,
                members = listOf(
                    GroupMember(id = 501L, groupId = 5L, name = "Me", isCurrentUser = true),
                    GroupMember(id = 502L, groupId = 5L, name = "Friend")
                ),
                expenses = listOf(
                    GroupExpense(
                        id = 201L,
                        groupId = 5L,
                        expenseId = 3L,
                        paidById = 502L,
                        date = periodStart + DAY_MS,
                        description = "Food split",
                        totalAmount = 80.0,
                        splitType = SplitType.EQUAL
                    ),
                    GroupExpense(
                        id = 202L,
                        groupId = 5L,
                        expenseId = 4L,
                        paidById = 502L,
                        date = periodStart + DAY_MS,
                        description = "Travel split",
                        totalAmount = 50.0,
                        splitType = SplitType.EQUAL
                    )
                )
            )
        )

        val onlyCategory1 = engine.calculateEffectiveBudgetSpend(periodStart, periodEnd, categoryId = 1L)

        assertApproxEquals(100.0, onlyCategory1.totalPersonalSpend, 0.0001)
        assertApproxEquals(40.0, onlyCategory1.totalSharedSpend, 0.0001)
        assertApproxEquals(140.0, onlyCategory1.effectiveBudgetSpend, 0.0001)
    }

    @Test
    fun `calculateEffectiveBudgetSpend edge case no groups returns personal only`() = runTest {
        val periodStart = FIXED_NOW - 5L * DAY_MS
        val periodEnd = FIXED_NOW

        coEvery { expenseRepository.getExpensesBetween(periodStart, periodEnd) } returns listOf(
            expense(id = 1L, amount = 35.0, categoryId = 1L, isShared = false)
        )
        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns emptyList()

        val result = engine.calculateEffectiveBudgetSpend(periodStart, periodEnd)

        assertApproxEquals(35.0, result.totalPersonalSpend, 0.0001)
        assertApproxEquals(0.0, result.totalSharedSpend, 0.0)
        assertApproxEquals(35.0, result.effectiveBudgetSpend, 0.0001)
    }

    @Test
    fun `calculateEffectiveBudgetSpend edge case no shared expenses in groups`() = runTest {
        val periodStart = FIXED_NOW - 5L * DAY_MS
        val periodEnd = FIXED_NOW

        coEvery { expenseRepository.getExpensesBetween(periodStart, periodEnd) } returns listOf(
            expense(id = 1L, amount = 20.0, categoryId = 1L, isShared = false)
        )
        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns listOf(
            groupAggregate(
                groupId = 9L,
                members = listOf(
                    GroupMember(id = 901L, groupId = 9L, name = "Me", isCurrentUser = true),
                    GroupMember(id = 902L, groupId = 9L, name = "Friend")
                ),
                expenses = emptyList()
            )
        )

        val result = engine.calculateEffectiveBudgetSpend(periodStart, periodEnd)

        assertApproxEquals(20.0, result.totalPersonalSpend, 0.0001)
        assertApproxEquals(0.0, result.totalSharedSpend, 0.0)
        assertApproxEquals(20.0, result.effectiveBudgetSpend, 0.0001)
    }

    @Test
    fun `calculateEffectiveBudgetSpend edge case empty period returns zeros`() = runTest {
        val periodStart = FIXED_NOW
        val periodEnd = FIXED_NOW

        coEvery { expenseRepository.getExpensesBetween(periodStart, periodEnd) } returns emptyList()
        coEvery { groupsRepository.getActiveGroupsWithDetails() } returns emptyList()

        val result = engine.calculateEffectiveBudgetSpend(periodStart, periodEnd)

        assertApproxEquals(0.0, result.totalPersonalSpend, 0.0)
        assertApproxEquals(0.0, result.totalSharedSpend, 0.0)
        assertApproxEquals(0.0, result.totalReimbursed, 0.0)
        assertApproxEquals(0.0, result.netSharedLiability, 0.0)
        assertApproxEquals(0.0, result.effectiveBudgetSpend, 0.0)
    }

    @Test
    fun `calculateEffectiveBudgetSpend propagates repository failures`() = runTest {
        val periodStart = FIXED_NOW - 5L * DAY_MS
        val periodEnd = FIXED_NOW

        coEvery { expenseRepository.getExpensesBetween(periodStart, periodEnd) } throws IllegalStateException("boom")

        assertFailsWith<IllegalStateException> {
            engine.calculateEffectiveBudgetSpend(periodStart, periodEnd)
        }
    }

    private fun groupAggregate(
        groupId: Long,
        members: List<GroupMember>,
        expenses: List<GroupExpense>
    ): GroupDetailsAggregate = GroupDetailsAggregate(
        group = ExpenseGroup(id = groupId, name = "Group $groupId"),
        members = members,
        expenses = expenses
    )

    private fun expense(
        id: Long,
        amount: Double,
        categoryId: Long,
        isShared: Boolean
    ): Expense = Expense(
        id = id,
        amount = amount,
        merchant = "M$id",
        transactionType = TransactionType.PURCHASE,
        date = FIXED_NOW - DAY_MS,
        categoryId = categoryId,
        createdAt = System.currentTimeMillis(),
        isSharedExpense = isShared,
        isNotMine = false
    )

    private fun successfulConversion(amount: Double): ConversionResult = ConversionResult(
        originalAmount = amount,
        originalCurrency = "EUR",
        convertedAmount = amount,
        targetCurrency = "EUR",
        rateUsed = 1.0,
        timestamp = FIXED_NOW
    )

    companion object {
        private const val FIXED_NOW = 1_730_000_000_000L
        private const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
