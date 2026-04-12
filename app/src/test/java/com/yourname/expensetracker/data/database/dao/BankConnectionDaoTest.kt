package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.BankConnection
import com.yourname.expensetracker.data.database.entity.SyncStatus
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

/**
 * Focused unit / DAO tests for [BankConnectionDao.disconnect].
 *
 * Verifies that calling disconnect(id) on a persisted [BankConnection]:
 *  - clears accessToken  → NULL
 *  - clears refreshToken → NULL
 *  - clears tokenExpiry  → NULL
 *  - resets tokenEncryptionVersion → 0
 *  - sets isConnected → false
 *  - sets isActive    → false
 *
 * All other fields (bankId, bankName, countryCode, createdAt, …) must remain
 * untouched.
 *
 * Uses an in-memory Room database driven by Robolectric so no Android device
 * or emulator is required.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BankConnectionDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: BankConnectionDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()

        dao = database.bankConnectionDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun connectedRow(
        bankId: String = "nbg",
        accessToken: String = "enc:v1:aaaa:bbbb",
        refreshToken: String = "enc:v1:cccc:dddd",
        tokenExpiry: Long = System.currentTimeMillis() + 3_600_000L,
        tokenEncryptionVersion: Int = 2
    ): BankConnection = BankConnection(
        bankId = bankId,
        bankName = "National Bank of Greece",
        countryCode = "GR",
        accessToken = accessToken,
        refreshToken = refreshToken,
        tokenExpiry = tokenExpiry,
        tokenEncryptionVersion = tokenEncryptionVersion,
        isActive = true,
        isConnected = true,
        lastSyncStatus = SyncStatus.SUCCESS
    )

    // -------------------------------------------------------------------------
    // disconnect() — credential fields wiped
    // -------------------------------------------------------------------------

    @Test
    fun `disconnect clears accessToken to null`() = runTest {
        val id = dao.insert(connectedRow())

        dao.disconnect(id)

        val row = dao.getById(id)
        assertNotNull(row)
        assertNull(row.accessToken, "accessToken must be NULL after disconnect")
    }

    @Test
    fun `disconnect clears refreshToken to null`() = runTest {
        val id = dao.insert(connectedRow())

        dao.disconnect(id)

        val row = dao.getById(id)
        assertNotNull(row)
        assertNull(row.refreshToken, "refreshToken must be NULL after disconnect")
    }

    @Test
    fun `disconnect clears tokenExpiry to null`() = runTest {
        val id = dao.insert(connectedRow())

        dao.disconnect(id)

        val row = dao.getById(id)
        assertNotNull(row)
        assertNull(row.tokenExpiry, "tokenExpiry must be NULL after disconnect")
    }

    @Test
    fun `disconnect resets tokenEncryptionVersion to 0`() = runTest {
        val id = dao.insert(connectedRow(tokenEncryptionVersion = 3))

        dao.disconnect(id)

        val row = dao.getById(id)
        assertNotNull(row)
        assertEquals(0, row.tokenEncryptionVersion, "tokenEncryptionVersion must be 0 after disconnect")
    }

    @Test
    fun `disconnect sets isConnected to false`() = runTest {
        val id = dao.insert(connectedRow())

        dao.disconnect(id)

        val row = dao.getById(id)
        assertNotNull(row)
        assertEquals(false, row.isConnected, "isConnected must be false after disconnect")
    }

    @Test
    fun `disconnect sets isActive to false`() = runTest {
        val id = dao.insert(connectedRow())

        dao.disconnect(id)

        val row = dao.getById(id)
        assertNotNull(row)
        assertEquals(false, row.isActive, "isActive must be false after disconnect")
    }

    @Test
    fun `disconnect wipes all credential and flag fields in one call`() = runTest {
        // Single comprehensive assertion covering the full contract in one shot.
        val id = dao.insert(
            connectedRow(
                accessToken = "enc:v1:tok:val",
                refreshToken = "enc:v1:ref:val",
                tokenExpiry = System.currentTimeMillis() + 7_200_000L,
                tokenEncryptionVersion = 5
            )
        )

        dao.disconnect(id)

        val row = dao.getById(id)
        assertNotNull(row)
        assertNull(row.accessToken,            "accessToken must be NULL")
        assertNull(row.refreshToken,           "refreshToken must be NULL")
        assertNull(row.tokenExpiry,            "tokenExpiry must be NULL")
        assertEquals(0,     row.tokenEncryptionVersion, "tokenEncryptionVersion must be 0")
        assertEquals(false, row.isConnected,            "isConnected must be false")
        assertEquals(false, row.isActive,               "isActive must be false")
    }

    @Test
    fun `disconnect preserves non-credential fields untouched`() = runTest {
        val created = System.currentTimeMillis()
        val original = connectedRow(bankId = "eurobank").copy(
            bankName = "Eurobank",
            countryCode = "GR",
            autoSync = true,
            lastSyncStatus = SyncStatus.SUCCESS
        )
        val id = dao.insert(original)

        dao.disconnect(id)

        val row = dao.getById(id)
        assertNotNull(row)
        // Identity fields must be unchanged
        assertEquals("eurobank",   row.bankId)
        assertEquals("Eurobank",   row.bankName)
        assertEquals("GR",         row.countryCode)
        // Behavioural fields not touched by disconnect
        assertEquals(true,                row.autoSync)
        assertEquals(SyncStatus.SUCCESS,  row.lastSyncStatus)
    }

    @Test
    fun `disconnect on non-existent id is a no-op and does not throw`() = runTest {
        // Calling disconnect on a row that does not exist should be silent.
        dao.disconnect(999_999L)
        // No exception → pass; also confirm nothing was affected in a populated table
        val id = dao.insert(connectedRow())
        dao.disconnect(999_999L)
        val row = dao.getById(id)
        assertNotNull(row)
        assertEquals(true, row.isConnected) // untouched
    }

    @Test
    fun `disconnect affects only the targeted row when multiple connections exist`() = runTest {
        val idA = dao.insert(connectedRow(bankId = "nbg"))
        val idB = dao.insert(connectedRow(bankId = "eurobank"))

        dao.disconnect(idA)

        val rowA = dao.getById(idA)
        val rowB = dao.getById(idB)

        assertNotNull(rowA)
        assertNotNull(rowB)

        // Row A must be disconnected
        assertNull(rowA.accessToken)
        assertNull(rowA.refreshToken)
        assertEquals(false, rowA.isConnected)
        assertEquals(false, rowA.isActive)

        // Row B must be completely untouched
        assertNotNull(rowB.accessToken)
        assertNotNull(rowB.refreshToken)
        assertEquals(true, rowB.isConnected)
        assertEquals(true, rowB.isActive)
    }

    @Test
    fun `disconnect is idempotent when called twice`() = runTest {
        val id = dao.insert(connectedRow())

        dao.disconnect(id)
        dao.disconnect(id) // second call must not throw or corrupt state

        val row = dao.getById(id)
        assertNotNull(row)
        assertNull(row.accessToken)
        assertNull(row.refreshToken)
        assertNull(row.tokenExpiry)
        assertEquals(0,     row.tokenEncryptionVersion)
        assertEquals(false, row.isConnected)
        assertEquals(false, row.isActive)
    }

    // -------------------------------------------------------------------------
    // getConnectedCount reflects disconnect
    // -------------------------------------------------------------------------

    @Test
    fun `getConnectedCount decrements after disconnect`() = runTest {
        val idA = dao.insert(connectedRow(bankId = "nbg"))
        val idB = dao.insert(connectedRow(bankId = "eurobank"))

        assertEquals(2, dao.getConnectedCount())

        dao.disconnect(idA)

        assertEquals(1, dao.getConnectedCount())

        dao.disconnect(idB)

        assertEquals(0, dao.getConnectedCount())
    }
}
