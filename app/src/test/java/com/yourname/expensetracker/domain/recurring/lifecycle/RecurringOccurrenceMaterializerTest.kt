package com.yourname.expensetracker.domain.recurring.lifecycle

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
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
import io.mockk.coVerifyOrder
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
 * RP-02 U-004: barrier ownership tests for [RecurringOccurrenceMaterializer].
 *
 * Both write entry points — [RecurringOccurrenceMaterializer.materialize] (opens
 * its own Room transaction) and
 * [RecurringOccurrenceMaterializer.materializeInCurrentTransaction]
 * (independently callable inside a coordinator-held transaction) — must check
 * the owned [DatabaseWriteBarrier] before any occurrence, planned-expense,
 * reminder, or lifecycle-event mutation. Caller-side checks (e.g.
 * RecurringLifecycleCoordinator) are NOT ownership.
 *
 * Harness: a REAL in-memory Room [AppDatabase] executes `withTransaction` for
 * real (mirroring RetentionTargetPurgeTest / the golden E2E harness), while the
 * DAOs and the barrier are mocked so the check-before-write order is verifiable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecurringOccurrenceMaterializerTest {

    private lateinit var database: AppDatabase
    private lateinit var occurrenceDao: RecurringOccurrenceDao
    private lateinit var reminderDeliveryDao: RecurringReminderDeliveryDao
    private lateinit var lifecycleEventDao: RecurringLifecycleEventDao
    private lateinit var plannedExpenseDao: PlannedExpenseDao
    private lateinit var writeBarrier: DatabaseWriteBarrier

    private val timeProvider: TimeProvider = object : TimeProvider { override fun now() = 1_712_000_000_000L }

    private lateinit var materializer: RecurringOccurrenceMaterializer

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
        writeBarrier = mockk(relaxed = true)

        coEvery { occurrenceDao.insert(any()) } returns 5L

        materializer = RecurringOccurrenceMaterializer(
            database = database,
            occurrenceDao = occurrenceDao,
            reminderDeliveryDao = reminderDeliveryDao,
            timeProvider = timeProvider,
            lifecycleEventDao = lifecycleEventDao,
            plannedExpenseDao = plannedExpenseDao,
            writeBarrier = writeBarrier
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun blockBarrier() {
        every { writeBarrier.checkWritesAllowed(any<String>()) } throws
            DatabaseAccessBlockedException(
                accessType = DatabaseAccessType.WRITE,
                operation = DatabaseAccessOperation("blocked"),
                mode = RestoreMaintenanceMode.Mode.RESTORE_SWAPPING
            )
    }

    @Test
    fun `materialize checks barrier before occurrence insert`() = runTest {
        val result = materializer.materialize(resolved, options)

        assertTrue(result.created == 1)
        // materialize() owns its entry check (before opening the transaction); the
        // inner materializeInCurrentTransaction check also fires — both must precede
        // the first DAO mutation (order-sensitive).
        coVerifyOrder {
            writeBarrier.checkWritesAllowed("recurring.occurrence.materialize")
            writeBarrier.checkWritesAllowed("recurring.occurrence.materialize.in_transaction")
            occurrenceDao.insert(any())
        }
    }

    @Test
    fun `materialize during restore is blocked before the transaction opens`() = runTest {
        blockBarrier()

        try {
            materializer.materialize(resolved, options)
            throw AssertionError("Expected DatabaseAccessBlockedException")
        } catch (e: DatabaseAccessBlockedException) {
            // typed restore-block surfaced as today
        }

        // No mutation may have occurred.
        coVerify(exactly = 0) { occurrenceDao.insert(any()) }
        coVerify(exactly = 0) { lifecycleEventDao.insert(any()) }
        coVerify(exactly = 0) { reminderDeliveryDao.insert(any()) }
    }

    @Test
    fun `materializeInCurrentTransaction checks barrier before any mutation`() = runTest {
        materializer.materializeInCurrentTransaction(resolved, options)

        coVerifyOrder {
            writeBarrier.checkWritesAllowed("recurring.occurrence.materialize.in_transaction")
            occurrenceDao.insert(any())
        }
    }

    @Test
    fun `materializeInCurrentTransaction during restore is blocked before any mutation`() = runTest {
        blockBarrier()

        try {
            materializer.materializeInCurrentTransaction(resolved, options)
            throw AssertionError("Expected DatabaseAccessBlockedException")
        } catch (e: DatabaseAccessBlockedException) {
            // typed restore-block surfaced as today
        }

        coVerify(exactly = 0) { occurrenceDao.insert(any()) }
        coVerify(exactly = 0) { lifecycleEventDao.insert(any()) }
        coVerify(exactly = 0) { plannedExpenseDao.fulfillByOccurrenceKey(any(), any(), any()) }
    }
}
