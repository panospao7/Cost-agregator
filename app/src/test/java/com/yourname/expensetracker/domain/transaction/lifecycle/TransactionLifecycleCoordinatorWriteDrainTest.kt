package com.yourname.expensetracker.domain.transaction.lifecycle

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.TransactionEventDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * R27 S2a: mutations that were previously protected only by an early
 * `checkWritesAllowed` must be counted as in-flight writes so a backup/restore
 * drain ([DatabaseWriteBarrier.awaitNoInFlightWrites]) waits for them.
 *
 * Uses a REAL [DatabaseWriteBarrier] (a relaxed mock would not run the block).
 */
class TransactionLifecycleCoordinatorWriteDrainTest {

    private var mode = RestoreMaintenanceMode.Mode.NORMAL
    private lateinit var barrier: DatabaseWriteBarrier
    private lateinit var database: AppDatabase
    private lateinit var expenseDao: ExpenseDao
    private lateinit var coordinator: TransactionLifecycleCoordinator

    private val now = 1_712_000_000_000L

    @Before
    fun setup() {
        val maintenanceMode = mockk<RestoreMaintenanceMode>()
        every { maintenanceMode.currentMode() } answers { mode }
        every { maintenanceMode.isWritesAllowed() } answers { mode == RestoreMaintenanceMode.Mode.NORMAL }
        barrier = DatabaseWriteBarrier(maintenanceMode)

        database = mockk(relaxed = true)
        expenseDao = mockk(relaxed = true)
        // withTransaction compiles to the top-level static facade; see
        // TransactionLifecycleCoordinatorUpdateTest for the measured rationale.
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }

        val timeProvider = mockk<TimeProvider>(relaxed = true)
        every { timeProvider.now() } returns now

        coordinator = TransactionLifecycleCoordinator(
            database = database,
            expenseDao = expenseDao,
            transactionEventDao = mockk<TransactionEventDao>(relaxed = true),
            timeProvider = timeProvider,
            currencyConverter = mockk<CurrencyConverter>(relaxed = true),
            sideEffectDispatcher = mockk(relaxed = true),
            planner = mockk(relaxed = true),
            runner = mockk(relaxed = true),
            recurringLifecycleCoordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true),
            writeBarrier = barrier,
            currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true),
            sourceLinkWriter = mockk(relaxed = true),
            transactionValidator = mockk(relaxed = true),
            diagnosticEventWriter = mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    private fun expenseWithLocation() = Expense(
        id = 1L,
        amount = 50.0,
        currency = "EUR",
        merchant = "Test",
        merchantKey = "test",
        transactionType = TransactionType.PURCHASE,
        date = now,
        latitude = 1.0,
        longitude = 2.0
    )

    @Test
    fun `drain waits for a migrated updateLocation write that was previously check-only`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { expenseDao.getById(1L) } coAnswers {
            entered.complete(Unit)
            release.await()
            expenseWithLocation()
        }

        // Same coordinates as the stored row, so the gated transaction ends as NoChange.
        val writer = launch { coordinator.updateLocation(expenseId = 1L, latitude = 1.0, longitude = 2.0) }
        entered.await()

        assertEquals(1, barrier.inFlightWriteCount())
        assertFalse(
            "drain must time out while the migrated write is still in flight",
            barrier.awaitNoInFlightWrites(100L)
        )

        release.complete(Unit)
        writer.join()

        assertEquals(0, barrier.inFlightWriteCount())
        assertTrue(barrier.awaitNoInFlightWrites(100L))
    }

    @Test
    fun `migrated updateLocation fails closed when maintenance mode is entered and is not counted`() = runTest {
        mode = RestoreMaintenanceMode.Mode.RESTORE_SWAPPING

        assertFailsWith<DatabaseAccessBlockedException> {
            coordinator.updateLocation(expenseId = 1L, latitude = 1.0, longitude = 2.0)
        }

        assertEquals(0, barrier.inFlightWriteCount())
    }
}
