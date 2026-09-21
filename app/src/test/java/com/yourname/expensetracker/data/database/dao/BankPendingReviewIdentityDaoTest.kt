package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.PendingReview
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RP-17 17-D / 17-E DAO-level contract for the bank review identity:
 *
 *  - atomic insert-if-absent: a second review with the same bankReviewIdentity
 *    is IGNOREd (returns -1L), which is the guarantee that two concurrent syncs
 *    for one connection can never yield two reviews for the same provider
 *    transaction;
 *  - NULL identities (non-bank reviews) never conflict;
 *  - [PendingReviewDao.deleteByBankConnectionScope] deletes ONLY the reviews
 *    scoped to one connection identity, leaving everything else untouched.
 *
 * Runs on an in-memory Room database via Robolectric (no device needed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BankPendingReviewIdentityDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: PendingReviewDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.pendingReviewDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun bankReview(
        id: Long = 0,
        identity: String,
        scopeHash: String,
        merchant: String = "Store"
    ) = PendingReview(
        rawNotificationId = null,
        suggestedAmount = 10.0,
        suggestedCurrency = "EUR",
        suggestedMerchant = merchant,
        suggestedType = "PURCHASE",
        suggestedCategoryId = null,
        confidence = 0.4f,
        packageName = "bank.sync.nbg",
        notificationTitle = null,
        notificationText = null,
        createdAt = 1_000L,
        bankReviewIdentity = identity,
        bankConnectionScopeHash = scopeHash
    )

    @Test
    fun `duplicate bank review identity insert is ignored atomically`() = runTest {
        val review = bankReview(identity = "id-1", scopeHash = "scope-1")
        val firstId = dao.insert(review)
        assertTrue(firstId > 0, "first insert must succeed")

        // Concurrent sync / re-sync of the same provider transaction:
        // the unique index turns the insert into a typed no-op (-1L).
        val secondId = dao.insert(bankReview(identity = "id-1", scopeHash = "scope-1"))
        assertEquals(-1L, secondId)

        assertEquals(1, dao.getPendingCount(), "only one review per identity")
    }

    @Test
    fun `null bank identities never conflict`() = runTest {
        // Non-bank reviews (notification/receipt path) keep NULL identity.
        val first = dao.insert(
            PendingReview(
                rawNotificationId = null,
                suggestedAmount = 5.0,
                suggestedCurrency = "EUR",
                suggestedMerchant = "A",
                suggestedType = "PURCHASE",
                suggestedCategoryId = null,
                confidence = 0.9f,
                packageName = "sms",
                notificationTitle = null,
                notificationText = null,
                createdAt = 1L
            )
        )
        val second = dao.insert(
            PendingReview(
                rawNotificationId = null,
                suggestedAmount = 7.0,
                suggestedCurrency = "EUR",
                suggestedMerchant = "B",
                suggestedType = "PURCHASE",
                suggestedCategoryId = null,
                confidence = 0.9f,
                packageName = "ocr",
                notificationTitle = null,
                notificationText = null,
                createdAt = 2L
            )
        )
        assertTrue(first > 0)
        assertTrue(second > 0)
        assertEquals(2, dao.getPendingCount())
    }

    @Test
    fun `deleteByBankConnectionScope removes only scoped reviews`() = runTest {
        dao.insert(bankReview(identity = "nbg-tx-1", scopeHash = "scope-nbg"))
        dao.insert(bankReview(identity = "nbg-tx-2", scopeHash = "scope-nbg"))
        dao.insert(bankReview(identity = "revolut-tx-1", scopeHash = "scope-revolut"))

        val deleted = dao.deleteByBankConnectionScope("scope-nbg")
        assertEquals(2, deleted)

        assertNull(dao.getByBankIdentity("nbg-tx-1"))
        assertNull(dao.getByBankIdentity("nbg-tx-2"))
        assertNotNull(dao.getByBankIdentity("revolut-tx-1"))
        assertEquals(1, dao.getPendingCount(), "other connections untouched")
    }
}
