package com.yourname.expensetracker.data.repository

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.InstallmentPlanDao
import com.yourname.expensetracker.data.database.entity.InstallmentEntry
import com.yourname.expensetracker.data.database.entity.InstallmentPlan
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [InstallmentEntryLinkReaderImpl] on a real in-memory Room database: an expense that pays an
 * entry resolves to that entry's id whatever the entry status; an expense with no entry (unlinked
 * entry, other expense, empty store) resolves to null. Synthetic data only. The reader only reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InstallmentEntryLinkReaderImplTest {

    private lateinit var database: AppDatabase
    private lateinit var planDao: InstallmentPlanDao
    private lateinit var reader: InstallmentEntryLinkReaderImpl

    @Before
    fun setUp() {
        database = AppDatabaseTestFactory.create(ApplicationProvider.getApplicationContext())
        planDao = database.installmentPlanDao()
        reader = InstallmentEntryLinkReaderImpl(planDao)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    private fun plan(uuid: String, purchaseExpenseId: Long? = null, status: String = "ACTIVE") = InstallmentPlan(
        planUuid = uuid,
        kind = if (purchaseExpenseId == null) "STANDALONE" else "PURCHASE_ATTACHED",
        purchaseExpenseId = purchaseExpenseId,
        displayName = "Synthetic plan",
        currency = "EUR",
        splitMethod = "EQUAL_SPLIT",
        declaredTotal = 300.0,
        installmentCount = 3,
        frequency = "MONTHLY",
        firstDueDate = 20_000L,
        anchorDayOfMonth = 15,
        residualPlacement = "LAST",
        fulfilmentMode = "CONFIRM",
        declaredOnDate = 19_999L,
        status = status,
        createdAt = NOW,
        updatedAt = NOW
    )

    private fun entry(planId: Long, seq: Int, status: String, linkedExpenseId: Long?) = InstallmentEntry(
        planId = planId,
        seq = seq,
        kind = "REGULAR",
        dueDate = 20_000L + seq,
        scheduledAmount = 100.0,
        status = status,
        linkedExpenseId = linkedExpenseId,
        linkOrigin = if (linkedExpenseId == null) null else "USER_LINKED",
        statusChangedAt = NOW
    )

    @Test
    fun `linked expense resolves to the id of its entry`() = runTest {
        val planId = planDao.insertPlan(plan("link-1"))
        val ids = planDao.insertEntries(
            listOf(
                entry(planId, 1, "PAID", linkedExpenseId = 900L),
                entry(planId, 2, "SCHEDULED", linkedExpenseId = null)
            )
        )

        assertEquals(ids[0], reader.linkedEntryIdFor(900L))
    }

    @Test
    fun `a link counts whatever the entry status`() = runTest {
        val planId = planDao.insertPlan(plan("link-2"))
        val ids = planDao.insertEntries(
            listOf(
                entry(planId, 1, "PAID", linkedExpenseId = 901L),
                entry(planId, 2, "REVERSED", linkedExpenseId = 902L),
                entry(planId, 3, "CANCELLED", linkedExpenseId = 903L)
            )
        )

        assertEquals(ids[0], reader.linkedEntryIdFor(901L))
        assertEquals(ids[1], reader.linkedEntryIdFor(902L))
        assertEquals(ids[2], reader.linkedEntryIdFor(903L))
    }

    @Test
    fun `unlinked entries and other expenses resolve to null`() = runTest {
        val planId = planDao.insertPlan(plan("unlinked-1"))
        planDao.insertEntries(
            listOf(
                entry(planId, 1, "SCHEDULED", linkedExpenseId = null),
                entry(planId, 2, "PAID", linkedExpenseId = 910L)
            )
        )

        assertNull(reader.linkedEntryIdFor(911L))
        assertNull(reader.linkedEntryIdFor(0L))
    }

    @Test
    fun `missing expense with no plan rows at all resolves to null`() = runTest {
        assertNull(reader.linkedEntryIdFor(404L))
    }

    @Test
    fun `a plan row with the expense as purchase record is a plan purchase`() = runTest {
        planDao.insertPlan(plan("purchase-1", purchaseExpenseId = 920L))

        assertTrue(reader.isPlanPurchase(920L))
    }

    @Test
    fun `a plan purchase counts whatever the plan status`() = runTest {
        planDao.insertPlan(plan("purchase-2", purchaseExpenseId = 921L, status = "CANCELLED"))

        assertTrue(reader.isPlanPurchase(921L))
    }

    @Test
    fun `other expenses, standalone plans and an empty store are not plan purchases`() = runTest {
        assertFalse(reader.isPlanPurchase(404L))
        planDao.insertPlan(plan("standalone-1"))
        planDao.insertPlan(plan("purchase-3", purchaseExpenseId = 922L))

        assertFalse(reader.isPlanPurchase(923L))
        assertFalse(reader.isPlanPurchase(0L))
    }

    @Test
    fun `an entry link alone does not make a plan purchase`() = runTest {
        val planId = planDao.insertPlan(plan("link-only"))
        planDao.insertEntries(listOf(entry(planId, 1, "PAID", linkedExpenseId = 930L)))

        assertFalse(reader.isPlanPurchase(930L))
    }

    // ── batch forms (S6c-2b) ─────────────────────────────────────

    @Test
    fun `linkedExpenseIds returns every linked expense of any entry status and agrees with the single read`() = runTest {
        val planId = planDao.insertPlan(plan("batch-link"))
        planDao.insertEntries(
            listOf(
                entry(planId, 1, "PAID", linkedExpenseId = 940L),
                entry(planId, 2, "REVERSED", linkedExpenseId = 941L),
                entry(planId, 3, "SCHEDULED", linkedExpenseId = null)
            )
        )

        val ids = reader.linkedExpenseIds()

        assertEquals(setOf(940L, 941L), ids)
        for (expenseId in listOf(939L, 940L, 941L, 942L)) {
            assertEquals(reader.linkedEntryIdFor(expenseId) != null, expenseId in ids)
        }
    }

    @Test
    fun `planPurchaseExpenseIds returns every plan purchase of any status and agrees with the single read`() = runTest {
        planDao.insertPlan(plan("batch-purchase-1", purchaseExpenseId = 950L))
        planDao.insertPlan(plan("batch-purchase-2", purchaseExpenseId = 951L, status = "CANCELLED"))
        planDao.insertPlan(plan("batch-standalone"))

        val ids = reader.planPurchaseExpenseIds()

        assertEquals(setOf(950L, 951L), ids)
        for (expenseId in listOf(949L, 950L, 951L, 952L)) {
            assertEquals(reader.isPlanPurchase(expenseId), expenseId in ids)
        }
    }

    @Test
    fun `batch forms are empty on an empty store and keep the two sets separate`() = runTest {
        assertTrue(reader.linkedExpenseIds().isEmpty())
        assertTrue(reader.planPurchaseExpenseIds().isEmpty())

        val planId = planDao.insertPlan(plan("batch-separate", purchaseExpenseId = 960L))
        planDao.insertEntries(listOf(entry(planId, 1, "PAID", linkedExpenseId = 961L)))

        assertEquals(setOf(961L), reader.linkedExpenseIds())
        assertEquals(setOf(960L), reader.planPurchaseExpenseIds())
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
