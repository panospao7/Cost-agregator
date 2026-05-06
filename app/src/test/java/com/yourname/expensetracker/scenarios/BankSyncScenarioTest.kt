package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.BankConnection
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.SyncStatus
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scenario tests for bank connection and sync behaviour.
 *
 * These tests verify that [BankConnection] entities can be created, queried,
 * and that expenses imported from bank syncs can be checked for duplicates
 * using DAOs directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BankSyncScenarioTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private val now = 1_714_514_400_000L // 2024-05-01T00:00:00Z

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: Bank connection entity creation and querying
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `bank connection created and queryable by bankId`() = runTest {
        // GIVEN: a new bank connection for NBG
        val connection = BankConnection(
            bankId = "nbg",
            bankName = "National Bank of Greece",
            countryCode = "GR",
            isActive = true,
            isConnected = true,
            autoSync = true,
            syncFrequency = com.yourname.expensetracker.data.database.entity.SyncFrequency.DAILY,
            lastSyncStatus = SyncStatus.SUCCESS,
            lastSync = now,
            createdAt = now
        )

        // WHEN: inserting the connection
        val id = db.bankConnectionDao().insert(connection)
        assertTrue("connection id should be positive", id > 0L)

        // THEN: it can be retrieved by bankId
        val byBankId = db.bankConnectionDao().getByBankId("nbg")
        assertNotNull("Should find connection by bankId", byBankId)
        assertEquals("bankName should match", "National Bank of Greece", byBankId!!.bankName)
        assertEquals("countryCode should match", "GR", byBankId.countryCode)
        assertEquals("isActive should be true", true, byBankId.isActive)
        assertEquals("isConnected should be true", true, byBankId.isConnected)
        assertEquals("lastSyncStatus should be SUCCESS", SyncStatus.SUCCESS, byBankId.lastSyncStatus)

        // AND: it can be retrieved by id
        val byId = db.bankConnectionDao().getById(id)
        assertNotNull("Should find connection by id", byId)
        assertEquals("bankId should match", "nbg", byId!!.bankId)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: Bank sync creates expenses and duplicate check works
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `bank sync expense duplicate detection via expenseDao`() = runTest {
        // GIVEN: an expense representing a bank-synced transaction
        val expense = Expense(
            amount = 45.50,
            currency = "EUR",
            merchant = "SKLAVENITIS",
            transactionType = TransactionType.PURCHASE,
            date = now,
            source = "bank_sync_nbg",
            createdAt = now
        )
        val firstId = db.expenseDao().insert(expense)
        assertTrue("first expense id should be positive", firstId > 0L)

        // WHEN: checking for a duplicate with the same merchant, amount, and close date
        val isDuplicate = db.expenseDao().isDuplicate(
            amount = 45.50,
            merchant = "SKLAVENITIS",
            date = now,
            windowMs = 300_000L // 5 minutes
        )

        // THEN: the duplicate check returns true
        assertTrue("Expense should be detected as duplicate", isDuplicate)

        // AND: inserting the same expense again succeeds (dedup is coordinator's job, DAO inserts blindly)
        val secondId = db.expenseDao().insert(
            Expense(
                amount = 45.50,
                currency = "EUR",
                merchant = "SKLAVENITIS",
                transactionType = TransactionType.PURCHASE,
                date = now,
                source = "bank_sync_nbg",
                createdAt = now
            )
        )
        assertTrue("second insert should also succeed (DAO doesn't dedupe)", secondId > 0L)

        // THEN: both rows exist in DB
        val all = db.expenseDao().getAllUncapped()
        assertEquals("Should have 2 expenses after duplicate insert", 2, all.size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: Multiple bank connections and sync status tracking
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `multiple bank connections tracked with sync status`() = runTest {
        // GIVEN: two bank connections
        val nbgId = db.bankConnectionDao().insert(
            BankConnection(
                bankId = "nbg",
                bankName = "National Bank of Greece",
                countryCode = "GR",
                isActive = true,
                isConnected = true,
                lastSyncStatus = SyncStatus.SUCCESS,
                createdAt = now
            )
        )
        val eurobankId = db.bankConnectionDao().insert(
            BankConnection(
                bankId = "eurobank",
                bankName = "Eurobank",
                countryCode = "GR",
                isActive = true,
                isConnected = true,
                lastSyncStatus = SyncStatus.NEVER,
                createdAt = now
            )
        )

        // WHEN: updating sync status for both
        db.bankConnectionDao().updateSyncStatus(nbgId, now + 3600_000L, SyncStatus.SUCCESS)
        db.bankConnectionDao().updateSyncStatus(eurobankId, now + 3600_000L, SyncStatus.PARTIAL)

        // THEN: the connections reflect the updated status
        val nbg = db.bankConnectionDao().getById(nbgId)
        assertNotNull("NBG connection should exist", nbg)
        assertEquals("NBG lastSyncStatus should be SUCCESS", SyncStatus.SUCCESS, nbg!!.lastSyncStatus)
        assertEquals("NBG lastSync should be updated", now + 3600_000L, nbg.lastSync)

        val eurobank = db.bankConnectionDao().getById(eurobankId)
        assertNotNull("Eurobank connection should exist", eurobank)
        assertEquals("Eurobank lastSyncStatus should be PARTIAL", SyncStatus.PARTIAL, eurobank!!.lastSyncStatus)
        assertEquals("Eurobank lastSync should be updated", now + 3600_000L, eurobank.lastSync)

        // AND: connected count reflects the active-connected connections
        assertEquals("Connected count should be 2", 2, db.bankConnectionDao().getConnectedCount())

        // WHEN: disconnecting one
        db.bankConnectionDao().disconnect(nbgId)

        // THEN: connected count decreases
        assertEquals("Connected count should be 1 after disconnect", 1, db.bankConnectionDao().getConnectedCount())
    }
}
