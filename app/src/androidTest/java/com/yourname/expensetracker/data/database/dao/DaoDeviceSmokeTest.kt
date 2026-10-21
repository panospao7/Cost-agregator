package com.yourname.expensetracker.data.database.dao

import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * Device-SQLite smoke set for DAO-level constraints.
 *
 * The JVM Robolectric DAO tests prove these behaviours only on Robolectric's
 * bundled SQLite. This class re-proves the same five SQLite-owned behaviours on
 * the real device SQLite build, using the same entity construction and the same
 * exact assertions as the JVM tests it mirrors:
 *
 * 1. UNIQUE `dedupeKey` index -> `DedupeKeyUniquenessRegressionTest.sameType_secondInsert_blockedByUniqueIndex`
 * 2. FK RESTRICT on `group_expenses.paidById` -> `GroupMemberDaoTest.deleteMemberWithExpenses_isRestrictedByForeignKey`
 * 3. FK CASCADE `group_members.groupId` -> `ExpenseGroupDaoTest.deleteGroupWithMembers_cascadesMembers`
 * 4. `COLLATE NOCASE` category names -> `CategoryDaoTest.get_or_insert_by_name_reuses_case_insensitive_category_without_overwriting_it`
 * 5. `localtime` weekly bucketing -> `ExpenseDaoTest.getWeeklyTotalsForPeriod_crossYearWeekProducesSingleBucket`
 *
 * Setup mirrors `FreshInstallIndexParityTest`: a real in-memory Room database
 * built with [AppDatabase.inMemoryBuilder].
 */
@Suppress("DEPRECATION_ERROR")
@OptIn(RestrictedExpenseDaoMutation::class)
@RunWith(AndroidJUnit4::class)
class DaoDeviceSmokeTest {

    private lateinit var database: AppDatabase

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
    }

    @After
    fun teardown() {
        database.close()
    }

    // 1. UNIQUE dedupeKey (mirrors DedupeKeyUniquenessRegressionTest.sameType_secondInsert_blockedByUniqueIndex)

    @Test
    fun uniqueDedupeKey_sameTypeSecondInsert_blockedByUniqueIndex() = runBlocking<Unit> {
        val expenseDao = database.expenseDao()
        val amount = 10.0
        val merchant = "Acme"
        val date = 1_700_000_000_000L
        val currency = "EUR"
        val purchaseExpense = Expense(
            amount = amount,
            currency = currency,
            merchant = merchant,
            transactionType = TransactionType.PURCHASE,
            date = date,
            dedupeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
                amount, merchant, date, currency, TransactionType.PURCHASE
            )
        )

        val firstId = expenseDao.insertAtomic(purchaseExpense)
        assertTrue("First PURCHASE insertAtomic must succeed, got $firstId", firstId > 0L)

        val secondId = expenseDao.insertAtomic(purchaseExpense.copy(id = 0))
        assertEquals(
            "Second PURCHASE insertAtomic with the same dedupeKey must be rejected " +
                "by the unique index (OnConflictStrategy.IGNORE -> returns -1)",
            -1L,
            secondId
        )

        assertEquals(
            "Only the first PURCHASE row must be present; the duplicate must not have been inserted",
            1,
            expenseDao.getTotalCount()
        )
    }

    // 2. FK RESTRICT (mirrors GroupMemberDaoTest.deleteMemberWithExpenses_isRestrictedByForeignKey)

    @Test
    fun foreignKeyRestrict_deleteMemberWithExpenses_throwsConstraintAndKeepsMember() = runBlocking<Unit> {
        val expenseGroupDao = database.expenseGroupDao()
        val groupMemberDao = database.groupMemberDao()
        val groupExpenseDao = database.groupExpenseDao()

        val groupId = expenseGroupDao.insert(ExpenseGroup(name = "Trip", description = "Test group"))
        val memberId = groupMemberDao.insert(
            GroupMember(groupId = groupId, name = "Payer", email = null, isCurrentUser = false)
        )

        val groupExpenseId = groupExpenseDao.insert(
            GroupExpense(
                groupId = groupId,
                expenseId = null,
                paidById = memberId,
                date = 1_700_000_000_000L,
                description = "Dinner",
                totalAmount = 42.0
            )
        )
        assertTrue(groupExpenseId > 0)

        val member = groupMemberDao.getById(memberId)
        assertNotNull(member)

        var failedAsExpected = false
        try {
            groupMemberDao.delete(member!!)
        } catch (_: SQLiteConstraintException) {
            failedAsExpected = true
        }
        if (!failedAsExpected) {
            fail("Deleting referenced member should fail with SQLiteConstraintException due to FK RESTRICT")
        }

        assertNotNull(groupMemberDao.getById(memberId))
    }

    // 3. FK CASCADE (mirrors ExpenseGroupDaoTest.deleteGroupWithMembers_cascadesMembers)

    @Test
    fun foreignKeyCascade_deleteGroupWithMembers_cascadesMembers() = runBlocking<Unit> {
        val expenseGroupDao = database.expenseGroupDao()
        val groupMemberDao = database.groupMemberDao()

        val groupId = expenseGroupDao.insert(
            ExpenseGroup(
                name = "Roommates",
                description = "Test group",
                isActive = true,
                createdAt = 1_700_000_000_000L
            )
        )
        groupMemberDao.insert(GroupMember(groupId = groupId, name = "Alice", email = "Alice@test.com"))
        groupMemberDao.insert(GroupMember(groupId = groupId, name = "Bob", email = "Bob@test.com"))

        val group = expenseGroupDao.getById(groupId)
        assertNotNull(group)

        expenseGroupDao.delete(group!!)

        assertNull(expenseGroupDao.getById(groupId))
        assertEquals(0, groupMemberDao.getMemberCount(groupId))
    }

    // 4. COLLATE NOCASE (mirrors CategoryDaoTest.get_or_insert_by_name_reuses_case_insensitive_category_without_overwriting_it)

    @Test
    fun collateNocase_getOrInsertByNameNoCase_reusesCaseInsensitiveCategoryWithoutOverwriting() = runBlocking<Unit> {
        val categoryDao = database.categoryDao()

        val first = categoryDao.getOrInsertByNameNoCase(
            Category(name = "Travel", icon = "T", color = "#3949AB", isDefault = false)
        )
        val second = categoryDao.getOrInsertByNameNoCase(
            Category(name = "travel", icon = "t", color = "#00897B", isDefault = false)
        )

        assertTrue(first.id > 0)
        assertEquals(first, second)
        assertEquals(1, categoryDao.getCount())
        assertEquals(first, categoryDao.getByName("TRAVEL"))
    }

    // 5. localtime weekly bucketing (mirrors ExpenseDaoTest.getWeeklyTotalsForPeriod_crossYearWeekProducesSingleBucket)

    @Test
    fun localtimeWeekly_crossYearWeekProducesSingleBucket() = runBlocking<Unit> {
        val expenseDao = database.expenseDao()

        // Fixed local civil times: Mon 2025-12-29 .. Sun 2026-01-04.
        val start = localDateAtHourMs(2025, 12, 29, 0)
        val end = localDateAtHourMs(2026, 1, 5, 0)

        expenseDao.insert(makeExpense(amount = 10.0, date = localDateAtHourMs(2025, 12, 29, 9)))
        expenseDao.insert(makeExpense(amount = 20.0, date = localDateAtHourMs(2025, 12, 31, 15)))
        expenseDao.insert(makeExpense(amount = 30.0, date = localDateAtHourMs(2026, 1, 2, 10)))
        expenseDao.insert(makeExpense(amount = 40.0, date = localDateAtHourMs(2026, 1, 4, 20)))

        val weekly = expenseDao.getWeeklyTotalsForPeriod(start, end)

        assertEquals(1, weekly.size)
        assertEquals("2025-12-29", weekly.first().weekKey)
        assertEquals(100.0, weekly.first().total, 0.01)
        assertEquals(4, weekly.first().txCount)
    }

    // Helpers

    private fun makeExpense(
        amount: Double,
        date: Long,
        merchant: String = "Test"
    ) = Expense(
        amount = amount,
        currency = "EUR",
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = date
    )

    private fun localDateAtHourMs(year: Int, month: Int, day: Int, hour: Int): Long =
        LocalDate.of(year, month, day)
            .atTime(hour, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
