package com.yourname.expensetracker.data.store

import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class ExpenseStoreTest {

    private val maintenanceMode = mockk<RestoreMaintenanceMode>()
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private lateinit var writeBarrier: DatabaseWriteBarrier
    private lateinit var readStore: ExpenseReadStore
    private lateinit var writeStore: ExpenseWriteStore

    private val expense = mockk<Expense>(relaxed = true)

    @Before
    fun setup() {
        writeBarrier = DatabaseWriteBarrier(maintenanceMode)
        readStore = ExpenseReadStore(expenseDao)
        writeStore = ExpenseWriteStore(writeBarrier, expenseDao)
    }

    private fun setMode(mode: RestoreMaintenanceMode.Mode) {
        every { maintenanceMode.currentMode() } returns mode
        every { maintenanceMode.isWritesAllowed() } returns (mode == RestoreMaintenanceMode.Mode.NORMAL)
    }

    // ── Write store blocks in non-NORMAL modes ────────────────────

    @Test
    fun write_store_insert_blocked_during_restore() = runTest {
        setMode(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)
        assertThrows(DatabaseAccessBlockedException::class.java) {
            runTest { writeStore.insert(expense) }
        }
        coVerify(exactly = 0) { expenseDao.insert(any()) }
    }

    @Test
    fun write_store_update_blocked_during_restore() = runTest {
        setMode(RestoreMaintenanceMode.Mode.RESTORE_SWAPPING)
        assertThrows(DatabaseAccessBlockedException::class.java) {
            runTest { writeStore.update(expense) }
        }
        coVerify(exactly = 0) { expenseDao.update(any()) }
    }

    @Test
    fun write_store_delete_blocked_during_restore() = runTest {
        setMode(RestoreMaintenanceMode.Mode.RESTORE_VERIFYING)
        assertThrows(DatabaseAccessBlockedException::class.java) {
            runTest { writeStore.delete(expense) }
        }
        coVerify(exactly = 0) { expenseDao.delete(any()) }
    }

    @Test
    fun write_store_updateMerchantKey_blocked_during_restore() = runTest {
        setMode(RestoreMaintenanceMode.Mode.RESTORE_COMPLETE_RESTART_REQUIRED)
        assertThrows(DatabaseAccessBlockedException::class.java) {
            runTest { writeStore.updateMerchantKey(1L, "key") }
        }
    }

    @Test
    fun write_store_incrementBackfillAttempts_blocked_during_restore() = runTest {
        setMode(RestoreMaintenanceMode.Mode.RESETTING_DATABASE)
        assertThrows(DatabaseAccessBlockedException::class.java) {
            runTest { writeStore.incrementBackfillAttempts(1L) }
        }
    }

    // ── Write store delegates to DAO in NORMAL mode ───────────────

    @Test
    fun write_store_insert_delegates_in_NORMAL() = runTest {
        setMode(RestoreMaintenanceMode.Mode.NORMAL)
        coEvery { expenseDao.insert(expense) } returns 42L
        val id = writeStore.insert(expense)
        assertEquals(42L, id)
        coVerify(exactly = 1) { expenseDao.insert(expense) }
    }

    @Test
    fun write_store_update_delegates_in_NORMAL() = runTest {
        setMode(RestoreMaintenanceMode.Mode.NORMAL)
        writeStore.update(expense)
        coVerify(exactly = 1) { expenseDao.update(expense) }
    }

    @Test
    fun write_store_updateCategory_delegates_in_NORMAL() = runTest {
        setMode(RestoreMaintenanceMode.Mode.NORMAL)
        writeStore.updateCategory(1L, 5L)
        coVerify(exactly = 1) { expenseDao.updateCategory(1L, 5L) }
    }

    // ── Read store always delegates (no barrier) ──────────────────

    @Test
    fun read_store_getById_delegates_to_dao() = runTest {
        coEvery { expenseDao.getById(1L) } returns expense
        val result = readStore.getById(1L)
        assertEquals(expense, result)
    }

    @Test
    fun read_store_getTotalCount_delegates_to_dao() = runTest {
        coEvery { expenseDao.getTotalCount() } returns 99
        assertEquals(99, readStore.getTotalCount())
    }

    // ── Exception carries correct metadata ───────────────────────

    @Test
    fun write_store_exception_names_the_operation() = runTest {
        setMode(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)
        val ex = try {
            writeStore.updateMerchantKey(1L, "key")
            null
        } catch (e: DatabaseAccessBlockedException) { e }
        assertEquals("ExpenseWriteStore.updateMerchantKey", ex?.operation?.name)
        assertEquals("P2", ex?.operation?.pipeline)
        assertEquals("Expense", ex?.operation?.entity)
    }
}
