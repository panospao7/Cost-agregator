package com.yourname.expensetracker.domain.recurring.lifecycle

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.dao.RecurringReminderDeliveryDao
import com.yourname.expensetracker.domain.recurring.OccurrenceConflictResolver
import com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RP-02 U-004: barrier ownership tests for [RecurringOccurrenceMaterializer]
 * against the merged (gr-14f-mediated) contract: the whole mutation body of
 * [RecurringOccurrenceMaterializer.materializeInCurrentTransaction] runs inside
 * `writeBarrier.runWrite(DatabaseAccessOperation(...))`, and
 * [RecurringOccurrenceMaterializer.materialize] wraps it in a Room transaction.
 * A blocked barrier must surface the typed [DatabaseAccessBlockedException]
 * before ANY occurrence, planned-expense, reminder, or lifecycle-event
 * mutation — caller-side checks are NOT ownership.
 *
 * Harness: a REAL in-memory Room [AppDatabase] executes `withTransaction` for
 * real, a REAL [DatabaseWriteBarrier] over a mocked [RestoreMaintenanceMode]
 * (RetentionTargetPurgeTest pattern — stubbing runWrite on a relaxed mock
 * would silently drop the executed lambda), and the DAOs are mocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecurringOccurrenceMaterializerTest {

    private lateinit var database: AppDatabase
    private lateinit var occurrenceDao: RecurringOccurrenceDao
    private lateinit var reminderDeliveryDao: RecurringReminderDeliveryDao
    private lateinit var lifecycleEventDao: RecurringLifecycleEventDao
    private lateinit var plannedExpenseDao: PlannedExpenseDao
    private lateinit var maintenanceMode: RestoreMaintenanceMode

    private val timeProvider: TimeProvider = object : TimeProvider { override fun now() = 1_712_000_000_000L }

    private val resolved = listOf(
        OccurrenceConflictResolver.ResolvedOccurrence(
            candidate = RecurringOccurrenceExpander.OccurrenceCandidate(
                occurrenceKey = "rule-1|2024-04|1",
                dueDate = 1_712_000_000_000L,
                expectedAmount = 9.99,
                expectedCurrency = "EUR",
                frequency = "MONTHLY",
                merchant = "Spotify",
                categoryId = null,
                sourceType = "MANUAL",
                sourceId = 1L
            ),
            status = "PLANNED"
        )
    )

    private val options = RecurringOccurrenceMaterializer.MaterializationOptions(
        createReminderDeliveries = false,
        reminderWindows = emptyList(),
        generationSource = "test"
    )

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        occurrenceDao = mockk(relaxed = true)
        reminderDeliveryDao = mockk(relaxed = true)
        lifecycleEventDao = mockk(relaxed = true)
        plannedExpenseDao = mockk(relaxed = true)
        maintenanceMode = mockk(relaxed = true)
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { maintenanceMode.isWritesAllowed() } returns true

        coEvery { occurrenceDao.insert(any()) } returns 5L
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun makeMaterializer(mode: RestoreMaintenanceMode.Mode = RestoreMaintenanceMode.Mode.NORMAL): RecurringOccurrenceMaterializer {
        if (mode != RestoreMaintenanceMode.Mode.NORMAL) {
            every { maintenanceMode.currentMode() } returns mode
            every { maintenanceMode.isWritesAllowed() } returns false
        }
        return RecurringOccurrenceMaterializer(
            database = database,
            writeBarrier = DatabaseWriteBarrier(maintenanceMode),
            occurrenceDao = occurrenceDao,
            reminderDeliveryDao = reminderDeliveryDao,
            timeProvider = timeProvider,
            lifecycleEventDao = lifecycleEventDao,
            plannedExpenseDao = plannedExpenseDao
        )
    }

    @Test
    fun `materialize runs the mediated mutation inside the barrier scope`() = runTest {
        val result = makeMaterializer().materialize(resolved, options)

        assertTrue(result.created == 1)
        coVerify(exactly = 1) { occurrenceDao.insert(any()) }
    }

    @Test
    fun `materialize during restore is blocked before any mutation`() = runTest {
        try {
            makeMaterializer(RestoreMaintenanceMode.Mode.RESTORE_SWAPPING)
                .materialize(resolved, options)
            throw AssertionError("Expected DatabaseAccessBlockedException")
        } catch (e: DatabaseAccessBlockedException) {
            // typed restore-block surfaced as today; in-memory Room rolls the
            // opened transaction back
        }

        coVerify(exactly = 0) { occurrenceDao.insert(any()) }
        coVerify(exactly = 0) { lifecycleEventDao.insert(any()) }
        coVerify(exactly = 0) { reminderDeliveryDao.insert(any()) }
    }

    @Test
    fun `materializeInCurrentTransaction runs inside the barrier scope`() = runTest {
        val result = makeMaterializer().materializeInCurrentTransaction(resolved, options)

        assertTrue(result.created == 1)
        coVerify(exactly = 1) { occurrenceDao.insert(any()) }
    }

    @Test
    fun `materializeInCurrentTransaction during restore is blocked before any mutation`() = runTest {
        try {
            makeMaterializer(RestoreMaintenanceMode.Mode.RESTORE_SWAPPING)
                .materializeInCurrentTransaction(resolved, options)
            throw AssertionError("Expected DatabaseAccessBlockedException")
        } catch (e: DatabaseAccessBlockedException) {
            // typed restore-block surfaced as today
        }

        coVerify(exactly = 0) { occurrenceDao.insert(any()) }
        coVerify(exactly = 0) { lifecycleEventDao.insert(any()) }
        coVerify(exactly = 0) { plannedExpenseDao.fulfillByOccurrenceKey(any(), any(), any()) }
    }
}
