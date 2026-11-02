package com.yourname.expensetracker.data.repository

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseAccessType
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ReturnWindowDao
import com.yourname.expensetracker.data.database.dao.WarrantyDao
import com.yourname.expensetracker.data.database.dao.WarrantyLifecycleEventDao
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyLifecycleEvent
import com.yourname.expensetracker.data.database.entity.WarrantyLifecycleEventTypes
import com.yourname.expensetracker.data.database.entity.WarrantyStatus
import com.yourname.expensetracker.data.database.entity.WarrantyType
import com.yourname.expensetracker.data.repository.WarrantyTrackerRepository.ConfirmWarrantyResult
import com.yourname.expensetracker.data.repository.WarrantyTrackerRepository.EditWarrantyResult
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyEditError
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyEditInput
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.GlobalTimeZoneTestLock
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

/**
 * warranty-edit S2: repository behaviour of [WarrantyTrackerRepository.editWarranty] and the
 * fail-closed [WarrantyTrackerRepository.updateWarranty] (AE-2, 3, 7-11, 13-15, 18-20; INV-2, 3,
 * 5-10, 12-14, 17-20).
 *
 * The DAOs are strict mocks backed by a tiny in-memory store (one row plus an ordered event list)
 * so the nested confirm reads exactly what the preceding update wrote, like the real database.
 * Real Room rollback is covered in [WarrantyTrackerRepositoryTransactionTest]. All data is
 * synthetic. Expected dates are calendar-date literals converted with java.time in a pinned
 * Europe/Athens zone, never computed with the function under test.
 */
class WarrantyTrackerRepositoryEditTest {

    private companion object {
        const val ID = 7L
        const val ATHENS = "Europe/Athens"
        const val SENSITIVE_PRODUCT = "Zorblax Phone Nine"
        const val SENSITIVE_MERCHANT = "Quuxcorp Merchant"
        const val SENSITIVE_PHONE = "+00 555 0142"
        const val SENSITIVE_EXCEPTION_MESSAGE = "EXC_MSG_SENSITIVE_731"
        const val REPOSITORY_SOURCE =
            "com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt"
    }

    private val zone: ZoneId = ZoneId.of(ATHENS)
    private lateinit var originalZone: TimeZone

    private fun midnight(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDate.of(year, month, day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val now = at(2026, 10, 26, 10, 0)

    private val database: AppDatabase = mockk(relaxed = true)
    private val warrantyDao: WarrantyDao = mockk()
    private val returnWindowDao: ReturnWindowDao = mockk()
    private val eventDao: WarrantyLifecycleEventDao = mockk()
    private val receiptRepository: ReceiptRepository = mockk()
    private val barrier: DatabaseWriteBarrier = mockk(relaxed = true)
    private val timeProvider = FakeTimeProvider(0L)
    private var receiptRepositoryResolutions = 0
    private lateinit var repository: WarrantyTrackerRepository

    /** In-memory stand-ins for the DAO tables. */
    private var stored: Warranty? = null
    private val updates = mutableListOf<Warranty>()
    private val events = mutableListOf<WarrantyLifecycleEvent>()

    private data class LogLine(val priority: Int, val message: String, val throwable: Throwable?)

    private val logs = mutableListOf<LogLine>()
    private val logTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs += LogLine(priority, message, t)
        }
    }

    @Before
    fun setUp() {
        GlobalTimeZoneTestLock.acquire()
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ATHENS))

        timeProvider.setTime(now)
        // withTransaction compiles to the top-level static facade (see WarrantyTrackerRepositoryTest).
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
        coEvery { warrantyDao.getWarrantyById(any()) } coAnswers {
            val id = firstArg<Long>()
            stored?.takeIf { it.id == id }
        }
        coEvery { warrantyDao.updateWarranty(any()) } coAnswers {
            val written = firstArg<Warranty>()
            updates += written
            stored = written
            Unit
        }
        coEvery { eventDao.insert(any()) } coAnswers {
            events += firstArg<WarrantyLifecycleEvent>()
            events.size.toLong()
        }
        repository = newRepository()
        Timber.plant(logTree)
    }

    @After
    fun tearDown() {
        Timber.uproot(logTree)
        unmockkStatic("androidx.room.RoomDatabaseKt")
        try {
            TimeZone.setDefault(originalZone)
        } finally {
            GlobalTimeZoneTestLock.release()
        }
    }

    private fun newRepository(writeBarrier: DatabaseWriteBarrier = barrier) = WarrantyTrackerRepository(
        database = database,
        warrantyDao = warrantyDao,
        returnWindowDao = returnWindowDao,
        warrantyLifecycleEventDao = eventDao,
        receiptRepository = object : Lazy<ReceiptRepository> {
            override fun get(): ReceiptRepository {
                receiptRepositoryResolutions++
                return receiptRepository
            }
        },
        timeProvider = timeProvider,
        currencyConverter = mockk(relaxed = true),
        currencySettingsRepository = mockk(relaxed = true),
        writeBarrier = writeBarrier,
        receiptLifecycleEventWriter = mockk(relaxed = true),
        transactionRunner = mockk(relaxed = true)
    )

    // ------------------------------------------------------------------ fixtures

    /**
     * Auto-detected row with every provenance field populated (so INV-3 has something to preserve).
     * Default dates are canonical: purchase 2026-06-01 + 12 months -> exclusive end 2027-06-02.
     */
    private fun row(
        status: WarrantyStatus = WarrantyStatus.ACTIVE,
        needsReview: Boolean = false,
        purchase: Long = midnight(2026, 6, 1),
        months: Int = 12,
        end: Long = midnight(2027, 6, 2),
        product: String = "Alpha Product",
        merchant: String = "Beta Merchant",
        type: WarrantyType = WarrantyType.MANUFACTURER,
        phone: String? = "+00 555 0100"
    ) = Warranty(
        id = ID,
        receiptId = 11L,
        expenseId = 3L,
        productName = product,
        merchantName = merchant,
        purchaseDate = purchase,
        warrantyDurationMonths = months,
        warrantyEndDate = end,
        warrantyType = type,
        supportPhone = phone,
        supportEmail = "support@example.test",
        warrantyDocumentUrl = "synthetic://document",
        notes = "synthetic note",
        status = status,
        claimedAt = null,
        createdAt = 1_600_000_000_000L,
        updatedAt = 1_600_000_000_001L,
        autoDetected = true,
        extractionConfidence = 0.82,
        extractionSource = "ocr",
        needsReview = needsReview
    )

    private fun pendingDraft(end: Long = midnight(2027, 1, 1)) =
        row(status = WarrantyStatus.PENDING_REVIEW, needsReview = true, end = end)

    private fun inputOf(
        w: Warranty,
        productName: String = w.productName,
        merchantName: String = w.merchantName,
        purchaseDate: Long = w.purchaseDate,
        durationMonths: Int = w.warrantyDurationMonths,
        warrantyType: WarrantyType = w.warrantyType,
        supportPhone: String? = w.supportPhone
    ) = WarrantyEditInput(
        productName = productName,
        merchantName = merchantName,
        purchaseDate = purchaseDate,
        durationMonths = durationMonths,
        warrantyType = warrantyType,
        supportPhone = supportPhone
    )

    private fun seed(w: Warranty): Warranty {
        stored = w
        updates.clear()
        events.clear()
        return w
    }

    private fun eventTypes(): List<String> = events.map { it.eventType }

    private fun assertNothingWritten() {
        assertTrue("no row write expected", updates.isEmpty())
        assertTrue("no event expected", events.isEmpty())
    }

    private fun assertEventsAreConstantAndSafe() {
        val allowed = mapOf(
            WarrantyLifecycleEventTypes.UPDATED to "WARRANTY_UPDATED",
            WarrantyLifecycleEventTypes.CONFIRMED to "WARRANTY_CONFIRMED"
        )
        events.forEach { event ->
            assertEquals(allowed[event.eventType], event.description)
            assertNull("event metadata stays null", event.metadata)
            assertEquals(ID, event.warrantyId)
            assertEquals(now, event.occurredAt)
        }
    }

    private fun blocked(operation: String) = DatabaseAccessBlockedException(
        accessType = DatabaseAccessType.WRITE,
        operation = DatabaseAccessOperation(operation),
        mode = RestoreMaintenanceMode.Mode.RESTORE_STAGING
    )

    // ------------------------------------------------------------------ INV-2 / AE-2

    @Test
    fun `INV-2 AE-2 phone-only edit preserves a non-canonical stored end and purchase date`() = runTest(timeout = 60.seconds) {
        val original = seed(
            row(purchase = at(2026, 6, 1, 15, 30), end = midnight(2027, 3, 15))
        )

        val result = repository.editWarranty(
            ID,
            inputOf(original, purchaseDate = midnight(2026, 6, 1), supportPhone = "+00 555 0199")
        )

        assertEquals(EditWarrantyResult.Edited(ID, WarrantyStatus.ACTIVE, false), result)
        val written = updates.single()
        assertEquals("stored purchase date is kept byte-for-byte", original.purchaseDate, written.purchaseDate)
        assertEquals("stored end is kept byte-for-byte", original.warrantyEndDate, written.warrantyEndDate)
        assertEquals("+00 555 0199", written.supportPhone)
        assertEquals(listOf(WarrantyLifecycleEventTypes.UPDATED), eventTypes())
    }

    @Test
    fun `INV-2 AE-2 same local day stored in another zone is not recomputed`() = runTest(timeout = 60.seconds) {
        // New York midnight is 07:00 in Athens on the same calendar day.
        val newYorkMidnight = LocalDate.of(2026, 6, 1)
            .atStartOfDay(ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        val original = seed(row(purchase = newYorkMidnight, end = midnight(2027, 3, 15)))

        val result = repository.editWarranty(
            ID,
            inputOf(original, purchaseDate = midnight(2026, 6, 1), supportPhone = "+00 555 0188")
        )

        assertTrue(result is EditWarrantyResult.Edited)
        val written = updates.single()
        assertEquals(newYorkMidnight, written.purchaseDate)
        assertEquals(midnight(2027, 3, 15), written.warrantyEndDate)
    }

    @Test
    fun `INV-2 changed purchase day recomputes the exclusive end and stores the day start`() = runTest(timeout = 60.seconds) {
        val original = seed(row())

        val result = repository.editWarranty(
            ID,
            inputOf(original, purchaseDate = at(2026, 7, 10, 17, 45))
        )

        assertEquals(EditWarrantyResult.Edited(ID, WarrantyStatus.ACTIVE, false), result)
        val written = updates.single()
        assertEquals(midnight(2026, 7, 10), written.purchaseDate)
        assertEquals(midnight(2027, 7, 11), written.warrantyEndDate)
        assertEquals(12, written.warrantyDurationMonths)
    }

    @Test
    fun `INV-2 changed months recomputes the exclusive end`() = runTest(timeout = 60.seconds) {
        val original = seed(row())

        val result = repository.editWarranty(ID, inputOf(original, durationMonths = 24))

        assertEquals(EditWarrantyResult.Edited(ID, WarrantyStatus.ACTIVE, false), result)
        val written = updates.single()
        assertEquals(midnight(2026, 6, 1), written.purchaseDate)
        assertEquals(midnight(2028, 6, 2), written.warrantyEndDate)
        assertEquals(24, written.warrantyDurationMonths)
    }

    @Test
    fun `INV-1 month-end purchase clamps the end and a past end expires the row`() = runTest(timeout = 60.seconds) {
        val original = seed(row())

        val result = repository.editWarranty(
            ID,
            inputOf(original, purchaseDate = midnight(2026, 1, 31), durationMonths = 1)
        )

        assertEquals(EditWarrantyResult.Edited(ID, WarrantyStatus.EXPIRED, false), result)
        assertEquals(midnight(2026, 3, 1), updates.single().warrantyEndDate)
    }

    // ------------------------------------------------------------------ INV-3 / AE-3

    @Test
    fun `INV-3 AE-3 provenance fields are value-equal after an edit of an auto-detected active row`() = runTest(timeout = 60.seconds) {
        val before = seed(row())

        repository.editWarranty(
            ID,
            inputOf(
                before,
                productName = "Gamma Product",
                merchantName = "Delta Merchant",
                purchaseDate = midnight(2026, 7, 1),
                durationMonths = 24,
                warrantyType = WarrantyType.EXTENDED,
                supportPhone = "+00 555 0177"
            )
        )

        assertProvenanceUnchanged(before, stored!!)
        assertEquals("Gamma Product", stored!!.productName)
        assertEquals(WarrantyType.EXTENDED, stored!!.warrantyType)
    }

    @Test
    fun `INV-3 AE-3 provenance fields are value-equal after an edit that leaves review`() = runTest(timeout = 60.seconds) {
        val before = seed(pendingDraft())

        val result = repository.editWarranty(ID, inputOf(before, productName = "Gamma Product"))

        assertEquals(EditWarrantyResult.Edited(ID, WarrantyStatus.ACTIVE, true), result)
        assertProvenanceUnchanged(before, stored!!)
    }

    private fun assertProvenanceUnchanged(before: Warranty, after: Warranty) {
        assertEquals(before.id, after.id)
        assertEquals(before.receiptId, after.receiptId)
        assertEquals(before.expenseId, after.expenseId)
        assertEquals(before.warrantyDocumentUrl, after.warrantyDocumentUrl)
        assertEquals(before.autoDetected, after.autoDetected)
        assertEquals(before.extractionConfidence, after.extractionConfidence, 0.0)
        assertEquals(before.extractionSource, after.extractionSource)
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(before.claimedAt, after.claimedAt)
        assertEquals(before.supportEmail, after.supportEmail)
        assertEquals(before.notes, after.notes)
    }

    // ------------------------------------------------------------------ INV-4 (repository enforcement)

    @Test
    fun `INV-4 invalid input returns the bounded reason with no write and no event`() = runTest(timeout = 60.seconds) {
        val original = seed(row())
        val cases = listOf(
            inputOf(original, productName = "   ") to WarrantyEditError.PRODUCT_BLANK,
            inputOf(original, merchantName = "") to WarrantyEditError.MERCHANT_BLANK,
            inputOf(original, merchantName = "m".repeat(121)) to WarrantyEditError.NAME_TOO_LONG,
            inputOf(original, supportPhone = "9".repeat(41)) to WarrantyEditError.PHONE_TOO_LONG,
            inputOf(original, purchaseDate = midnight(1969, 12, 31)) to
                WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE,
            inputOf(original, durationMonths = 0) to WarrantyEditError.DURATION_OUT_OF_RANGE,
            inputOf(original, durationMonths = 121) to WarrantyEditError.DURATION_OUT_OF_RANGE
        )

        cases.forEach { (input, reason) ->
            assertEquals(EditWarrantyResult.Invalid(reason), repository.editWarranty(ID, input))
        }

        assertNothingWritten()
        assertEquals("row unchanged", original, stored)
    }

    @Test
    fun `INV-4 limit values are accepted`() = runTest(timeout = 60.seconds) {
        val original = seed(row())

        val result = repository.editWarranty(
            ID,
            inputOf(
                original,
                productName = "p".repeat(120),
                merchantName = "m".repeat(120),
                durationMonths = 120,
                supportPhone = "9".repeat(40)
            )
        )

        assertTrue(result is EditWarrantyResult.Edited)
    }

    // ------------------------------------------------------------------ INV-7 / AE-7

    @Test
    fun `INV-7 AE-7 claimed row is not editable and nothing is written`() = runTest(timeout = 60.seconds) {
        val claimed = seed(row(status = WarrantyStatus.CLAIMED).copy(claimedAt = now - 1_000L))

        val result = repository.editWarranty(ID, inputOf(claimed, productName = "Gamma Product"))

        assertEquals(EditWarrantyResult.NotEditable, result)
        assertNothingWritten()
        assertEquals(claimed, stored)
    }

    @Test
    fun `INV-7 AE-7 transferred row is not editable even when it is flagged for review`() = runTest(timeout = 60.seconds) {
        val transferred = seed(row(status = WarrantyStatus.TRANSFERRED, needsReview = true))

        val result = repository.editWarranty(ID, inputOf(transferred, productName = "Gamma Product"))

        assertEquals(EditWarrantyResult.NotEditable, result)
        assertNothingWritten()
        assertEquals(transferred, stored)
    }

    @Test
    fun `INV-7 AE-7 not-editable is decided before validation`() = runTest(timeout = 60.seconds) {
        val claimed = seed(row(status = WarrantyStatus.CLAIMED).copy(claimedAt = now - 1_000L))

        val result = repository.editWarranty(ID, inputOf(claimed, productName = "   "))

        assertEquals(EditWarrantyResult.NotEditable, result)
    }

    @Test
    fun `INV-7 AE-7 missing id returns NotFound with no write and no event`() = runTest(timeout = 60.seconds) {
        seed(row())

        val result = repository.editWarranty(9_999L, inputOf(row(), productName = "Gamma Product"))

        assertEquals(EditWarrantyResult.NotFound, result)
        assertNothingWritten()
    }

    @Test
    fun `INV-7 AE-7 deleted row returns NotFound even for invalid input`() = runTest(timeout = 60.seconds) {
        stored = null
        updates.clear()
        events.clear()

        val result = repository.editWarranty(ID, inputOf(row(), productName = "   "))

        assertEquals(EditWarrantyResult.NotFound, result)
        assertNothingWritten()
    }

    // ------------------------------------------------------------------ INV-8 / AE-8

    @Test
    fun `INV-8 AE-8 editing a pending review draft confirms it in the same call`() = runTest(timeout = 60.seconds) {
        val draft = seed(pendingDraft())

        val result = repository.editWarranty(
            ID,
            inputOf(draft, productName = "Gamma Product", durationMonths = 24)
        )

        assertEquals(EditWarrantyResult.Edited(ID, WarrantyStatus.ACTIVE, true), result)
        val after = stored!!
        assertEquals("Gamma Product", after.productName)
        assertEquals(24, after.warrantyDurationMonths)
        assertEquals(WarrantyStatus.ACTIVE, after.status)
        assertFalse("review is cleared", after.needsReview)
        assertEquals(
            listOf(WarrantyLifecycleEventTypes.UPDATED, WarrantyLifecycleEventTypes.CONFIRMED),
            eventTypes()
        )
        assertEventsAreConstantAndSafe()
    }

    @Test
    fun `INV-8 identical input on a review row still leaves review`() = runTest(timeout = 60.seconds) {
        val draft = seed(pendingDraft())

        val result = repository.editWarranty(ID, inputOf(draft))

        assertEquals(EditWarrantyResult.Edited(ID, WarrantyStatus.ACTIVE, true), result)
        assertFalse(stored!!.needsReview)
        assertEquals(
            listOf(WarrantyLifecycleEventTypes.UPDATED, WarrantyLifecycleEventTypes.CONFIRMED),
            eventTypes()
        )
    }

    @Test
    fun `INV-8 pending draft whose end is already past is confirmed EXPIRED`() = runTest(timeout = 60.seconds) {
        val draft = seed(pendingDraft(end = midnight(2026, 5, 1)))

        val result = repository.editWarranty(ID, inputOf(draft, productName = "Gamma Product"))

        assertEquals(EditWarrantyResult.Edited(ID, WarrantyStatus.EXPIRED, true), result)
        assertEquals(WarrantyStatus.EXPIRED, stored!!.status)
        assertFalse(stored!!.needsReview)
    }

    @Test
    fun `INV-8 a row that is not in review never gets needsReview set`() = runTest(timeout = 60.seconds) {
        val confirmed = seed(row())

        repository.editWarranty(ID, inputOf(confirmed, productName = "Gamma Product"))

        assertTrue(updates.isNotEmpty())
        assertTrue("needsReview is never written true", updates.none { it.needsReview })
        assertFalse(stored!!.needsReview)
        assertEquals(listOf(WarrantyLifecycleEventTypes.UPDATED), eventTypes())
        assertEventsAreConstantAndSafe()
    }

    // ------------------------------------------------------------------ INV-9 / AE-9 / AE-10

    @Test
    fun `INV-9 AE-9 expired unconfirmed row moved into the future becomes ACTIVE and leaves review`() = runTest(timeout = 60.seconds) {
        val expiredDraft = seed(
            row(
                status = WarrantyStatus.EXPIRED,
                needsReview = true,
                purchase = midnight(2025, 4, 30),
                end = midnight(2026, 5, 1)
            )
        )

        val result = repository.editWarranty(
            ID,
            inputOf(expiredDraft, purchaseDate = midnight(2026, 9, 1), durationMonths = 24)
        )

        assertEquals(EditWarrantyResult.Edited(ID, WarrantyStatus.ACTIVE, true), result)
        assertEquals(midnight(2028, 9, 2), stored!!.warrantyEndDate)
        assertEquals(WarrantyStatus.ACTIVE, stored!!.status)
        assertFalse(stored!!.needsReview)
        assertEquals(
            listOf(WarrantyLifecycleEventTypes.UPDATED, WarrantyLifecycleEventTypes.CONFIRMED),
            eventTypes()
        )
    }

    @Test
    fun `INV-9 AE-9 active row edited to an end in the past becomes EXPIRED`() = runTest(timeout = 60.seconds) {
        val active = seed(row())

        val result = repository.editWarranty(
            ID,
            inputOf(active, purchaseDate = midnight(2025, 9, 30))
        )

        assertEquals(EditWarrantyResult.Edited(ID, WarrantyStatus.EXPIRED, false), result)
        assertEquals(midnight(2026, 10, 1), stored!!.warrantyEndDate)
        assertEquals(WarrantyStatus.EXPIRED, stored!!.status)
    }

    @Test
    fun `INV-9 AE-9 end exactly at now is not expired and one millisecond later is`() = runTest(timeout = 60.seconds) {
        val endsAtMidnight = midnight(2026, 10, 26)
        timeProvider.setTime(endsAtMidnight)
        val active = seed(row())

        val atBoundary = repository.editWarranty(
            ID,
            inputOf(active, purchaseDate = midnight(2025, 10, 25))
        )

        assertEquals(EditWarrantyResult.Edited(ID, WarrantyStatus.ACTIVE, false), atBoundary)
        assertEquals(endsAtMidnight, stored!!.warrantyEndDate)

        timeProvider.setTime(endsAtMidnight + 1L)
        seed(row())
        val afterBoundary = repository.editWarranty(
            ID,
            inputOf(active, purchaseDate = midnight(2025, 10, 25))
        )

        assertEquals(EditWarrantyResult.Edited(ID, WarrantyStatus.EXPIRED, false), afterBoundary)
    }

    @Test
    fun `INV-9 AE-10 name-only edit keeps an expired row EXPIRED`() = runTest(timeout = 60.seconds) {
        val expired = seed(
            row(
                status = WarrantyStatus.EXPIRED,
                purchase = midnight(2025, 4, 30),
                end = midnight(2026, 5, 1)
            )
        )

        val result = repository.editWarranty(ID, inputOf(expired, productName = "Gamma Product"))

        assertEquals(EditWarrantyResult.Edited(ID, WarrantyStatus.EXPIRED, false), result)
        assertEquals(WarrantyStatus.EXPIRED, stored!!.status)
        assertEquals(midnight(2026, 5, 1), stored!!.warrantyEndDate)
        assertEquals(listOf(WarrantyLifecycleEventTypes.UPDATED), eventTypes())
    }

    @Test
    fun `INV-9 AE-10 expired row that is not in review moves to ACTIVE only when its end moves to the future`() = runTest(timeout = 60.seconds) {
        val expired = seed(
            row(
                status = WarrantyStatus.EXPIRED,
                purchase = midnight(2025, 4, 30),
                end = midnight(2026, 5, 1)
            )
        )

        val result = repository.editWarranty(ID, inputOf(expired, purchaseDate = midnight(2026, 9, 1)))

        assertEquals(EditWarrantyResult.Edited(ID, WarrantyStatus.ACTIVE, false), result)
        assertEquals(WarrantyStatus.ACTIVE, stored!!.status)
        assertEquals(listOf(WarrantyLifecycleEventTypes.UPDATED), eventTypes())
    }

    @Test
    fun `INV-9 an unchanged end leaves the stored status untouched`() = runTest(timeout = 60.seconds) {
        // ACTIVE with a past end (the sweep has not run yet): a name-only edit must not expire it.
        val staleActive = seed(
            row(purchase = midnight(2025, 4, 30), end = midnight(2026, 5, 1))
        )

        val result = repository.editWarranty(ID, inputOf(staleActive, productName = "Gamma Product"))

        assertEquals(EditWarrantyResult.Edited(ID, WarrantyStatus.ACTIVE, false), result)
        assertEquals(WarrantyStatus.ACTIVE, stored!!.status)
    }

    // ------------------------------------------------------------------ INV-10 / AE-11

    @Test
    fun `INV-10 AE-11 identical input after trim on a confirmed row is NoChange`() = runTest(timeout = 60.seconds) {
        val confirmed = seed(row())

        val result = repository.editWarranty(
            ID,
            inputOf(
                confirmed,
                productName = "  ${confirmed.productName}  ",
                merchantName = "\t${confirmed.merchantName} ",
                supportPhone = " ${confirmed.supportPhone} "
            )
        )

        assertEquals(EditWarrantyResult.NoChange, result)
        assertNothingWritten()
        assertEquals("updatedAt unchanged", confirmed, stored)
    }

    @Test
    fun `INV-10 AE-11 blank phone against a null stored phone is NoChange`() = runTest(timeout = 60.seconds) {
        val confirmed = seed(row(phone = null))

        val result = repository.editWarranty(ID, inputOf(confirmed, supportPhone = "   "))

        assertEquals(EditWarrantyResult.NoChange, result)
        assertNothingWritten()
    }

    @Test
    fun `INV-10 same local day at another time of day is NoChange`() = runTest(timeout = 60.seconds) {
        val confirmed = seed(row())

        val result = repository.editWarranty(
            ID,
            inputOf(confirmed, purchaseDate = at(2026, 6, 1, 18, 20))
        )

        assertEquals(EditWarrantyResult.NoChange, result)
        assertNothingWritten()
    }

    @Test
    fun `INV-10 NoChange applies to an expired row that is not in review`() = runTest(timeout = 60.seconds) {
        val expired = seed(
            row(status = WarrantyStatus.EXPIRED, purchase = midnight(2025, 4, 30), end = midnight(2026, 5, 1))
        )

        val result = repository.editWarranty(ID, inputOf(expired))

        assertEquals(EditWarrantyResult.NoChange, result)
        assertNothingWritten()
    }

    @Test
    fun `INV-10 changing any single content field is not NoChange`() = runTest(timeout = 60.seconds) {
        val base = row()
        val changes = mapOf(
            "product" to inputOf(base, productName = "Gamma Product"),
            "merchant" to inputOf(base, merchantName = "Delta Merchant"),
            "purchase day" to inputOf(base, purchaseDate = midnight(2026, 6, 2)),
            "months" to inputOf(base, durationMonths = 13),
            "type" to inputOf(base, warrantyType = WarrantyType.STORE),
            "phone" to inputOf(base, supportPhone = "+00 555 0111"),
            "phone cleared" to inputOf(base, supportPhone = null)
        )

        changes.forEach { (label, input) ->
            seed(base)
            val result = repository.editWarranty(ID, input)
            assertTrue("$label change must be written, got $result", result is EditWarrantyResult.Edited)
            assertEquals("$label: one UPDATED event", listOf(WarrantyLifecycleEventTypes.UPDATED), eventTypes())
            assertEquals("$label: updatedAt from the clock", now, stored!!.updatedAt)
        }
    }

    // ------------------------------------------------------------------ INV-12

    @Test
    fun `INV-12 promoteReviewDraft after a user edit returns AlreadyConfirmed and never invokes its edit`() = runTest(timeout = 60.seconds) {
        val draft = seed(pendingDraft())
        repository.editWarranty(ID, inputOf(draft, productName = "User Edited Product"))
        val writesAfterEdit = updates.size
        val eventsAfterEdit = events.size
        var invocations = 0

        val result = repository.promoteReviewDraft(ID) { fresh ->
            invocations++
            fresh.copy(productName = "Receipt Reprocessed Product")
        }

        assertEquals(ConfirmWarrantyResult.AlreadyConfirmed, result)
        assertEquals(0, invocations)
        assertEquals("User Edited Product", stored!!.productName)
        assertEquals(writesAfterEdit, updates.size)
        assertEquals(eventsAfterEdit, events.size)
    }

    // ------------------------------------------------------------------ INV-5 / AE-13

    @Test
    fun `INV-5 AE-13 edit commits first then confirm returns AlreadyConfirmed with one CONFIRMED event total`() = runTest(timeout = 60.seconds) {
        val draft = seed(pendingDraft())
        repository.editWarranty(ID, inputOf(draft, productName = "Gamma Product"))

        val confirm = repository.confirmWarranty(ID)

        assertEquals(ConfirmWarrantyResult.AlreadyConfirmed, confirm)
        assertEquals(1, events.count { it.eventType == WarrantyLifecycleEventTypes.CONFIRMED })
    }

    @Test
    fun `INV-5 AE-13 confirm first then edit leaves needsReview false and writes only UPDATED`() = runTest(timeout = 60.seconds) {
        val draft = seed(pendingDraft())
        assertEquals(
            ConfirmWarrantyResult.Confirmed(ID, WarrantyStatus.ACTIVE),
            repository.confirmWarranty(ID)
        )
        updates.clear()
        events.clear()

        val result = repository.editWarranty(ID, inputOf(draft, productName = "Gamma Product"))

        assertEquals(EditWarrantyResult.Edited(ID, WarrantyStatus.ACTIVE, false), result)
        assertFalse(stored!!.needsReview)
        assertEquals(listOf(WarrantyLifecycleEventTypes.UPDATED), eventTypes())
    }

    @Test
    fun `INV-5 event insert failure during an edit propagates`() = runTest(timeout = 60.seconds) {
        val original = seed(row())
        coEvery { eventDao.insert(any()) } throws RuntimeException("Simulated DB failure")

        val thrown = runCatching {
            repository.editWarranty(ID, inputOf(original, productName = "Gamma Product"))
        }

        assertEquals("Simulated DB failure", thrown.exceptionOrNull()?.message)
    }

    @Test
    fun `INV-5 CONFIRMED event failure after UPDATED propagates`() = runTest(timeout = 60.seconds) {
        val draft = seed(pendingDraft())
        coEvery { eventDao.insert(any()) } coAnswers {
            val event = firstArg<WarrantyLifecycleEvent>()
            if (event.eventType == WarrantyLifecycleEventTypes.CONFIRMED) {
                throw RuntimeException("Simulated DB failure")
            }
            events += event
            events.size.toLong()
        }

        val thrown = runCatching {
            repository.editWarranty(ID, inputOf(draft, productName = "Gamma Product"))
        }

        assertEquals("Simulated DB failure", thrown.exceptionOrNull()?.message)
        assertEquals(
            "UPDATED was attempted before the failing CONFIRMED",
            listOf(WarrantyLifecycleEventTypes.UPDATED),
            eventTypes()
        )
    }

    @Test
    fun `INV-5 nested confirm that does not return Confirmed fails closed with a constant message`() = runTest(timeout = 60.seconds) {
        val draft = pendingDraft()
        val unexpectedSecondReads = listOf<Warranty?>(
            null,
            draft.copy(needsReview = false, status = WarrantyStatus.ACTIVE)
        )
        unexpectedSecondReads.forEach { secondRead ->
            seed(draft)
            coEvery { warrantyDao.getWarrantyById(ID) } returnsMany listOf(draft, secondRead)

            val thrown = runCatching {
                repository.editWarranty(
                    ID,
                    inputOf(
                        draft,
                        productName = SENSITIVE_PRODUCT,
                        merchantName = SENSITIVE_MERCHANT,
                        supportPhone = SENSITIVE_PHONE
                    )
                )
            }

            val failure = thrown.exceptionOrNull()
            assertTrue("must throw, got $failure", failure is IllegalStateException)
            assertFalse(failure!!.message.orEmpty().contains(SENSITIVE_PRODUCT))
            assertFalse(failure.message.orEmpty().contains(SENSITIVE_MERCHANT))
            assertFalse(failure.message.orEmpty().contains(SENSITIVE_PHONE))
        }
        assertTrue("fail-closed path logs nothing", logs.isEmpty())
    }

    // ------------------------------------------------------------------ INV-6 / AE-21 (repository part)

    @Test
    fun `INV-6 cancellation from the row update propagates and writes no event`() = runTest(timeout = 60.seconds) {
        val original = seed(row())
        coEvery { warrantyDao.updateWarranty(any()) } throws
            kotlinx.coroutines.CancellationException("synthetic cancellation")

        val thrown = runCatching {
            repository.editWarranty(ID, inputOf(original, productName = "Gamma Product"))
        }

        assertTrue(thrown.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals("synthetic cancellation", thrown.exceptionOrNull()?.message)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `INV-6 cancellation from the UPDATED event insert propagates unchanged`() = runTest(timeout = 60.seconds) {
        val original = seed(row())
        coEvery { eventDao.insert(any()) } throws
            kotlinx.coroutines.CancellationException("synthetic cancellation")

        val thrown = runCatching {
            repository.editWarranty(ID, inputOf(original, productName = "Gamma Product"))
        }

        assertTrue(thrown.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals("synthetic cancellation", thrown.exceptionOrNull()?.message)
    }

    @Test
    fun `INV-6 cancellation from the CONFIRMED event insert propagates unchanged`() = runTest(timeout = 60.seconds) {
        val draft = seed(pendingDraft())
        coEvery { eventDao.insert(any()) } coAnswers {
            val event = firstArg<WarrantyLifecycleEvent>()
            if (event.eventType == WarrantyLifecycleEventTypes.CONFIRMED) {
                throw kotlinx.coroutines.CancellationException("synthetic cancellation")
            }
            events += event
            events.size.toLong()
        }

        val thrown = runCatching {
            repository.editWarranty(ID, inputOf(draft, productName = "Gamma Product"))
        }

        assertTrue(thrown.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals("synthetic cancellation", thrown.exceptionOrNull()?.message)
    }

    // ------------------------------------------------------------------ barrier

    @Test
    fun `INV-5 edit under maintenance mode fails closed and does not read write or open a transaction`() = runTest(timeout = 60.seconds) {
        val original = seed(row())
        val op = "WarrantyTrackerRepository.editWarranty"
        every { barrier.checkWritesAllowed(op) } throws blocked(op)

        val thrown = runCatching {
            repository.editWarranty(ID, inputOf(original, productName = "Gamma Product"))
        }

        assertTrue(thrown.exceptionOrNull() is DatabaseAccessBlockedException)
        coVerify(exactly = 0) { database.withTransaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { warrantyDao.getWarrantyById(any()) }
        coVerify(exactly = 0) { warrantyDao.updateWarranty(any()) }
        coVerify(exactly = 0) { eventDao.insert(any()) }
        assertEquals(original, stored)
    }

    // ------------------------------------------------------------------ INV-13 / AE-14

    @Test
    fun `INV-13 AE-14 an edit never touches return windows or receipts`() = runTest(timeout = 60.seconds) {
        // returnWindowDao and receiptRepository are strict mocks with no stubs: any call throws.
        val draft = seed(pendingDraft())
        repository.editWarranty(ID, inputOf(draft, productName = "Gamma Product"))
        val confirmed = seed(row())
        repository.editWarranty(ID, inputOf(confirmed, productName = "Delta Product"))

        confirmVerified(returnWindowDao, receiptRepository)
        assertEquals("receipt repository is never resolved", 0, receiptRepositoryResolutions)
    }

    // ------------------------------------------------------------------ INV-14 / AE-15

    @Test
    fun `INV-14 AE-15 no product merchant phone or exception text reaches Timber on any outcome`() = runTest(timeout = 60.seconds) {
        val original = row()
        val sensitiveInput = inputOf(
            original,
            productName = SENSITIVE_PRODUCT,
            merchantName = SENSITIVE_MERCHANT,
            supportPhone = SENSITIVE_PHONE
        )

        // Success (review row), success (confirmed row), NoChange, Invalid, NotFound, NotEditable.
        seed(pendingDraft()); repository.editWarranty(ID, sensitiveInput)
        seed(original); repository.editWarranty(ID, sensitiveInput)
        seed(original); repository.editWarranty(ID, inputOf(original))
        seed(original); repository.editWarranty(ID, sensitiveInput.copy(durationMonths = 0))
        stored = null; repository.editWarranty(ID, sensitiveInput)
        seed(row(status = WarrantyStatus.CLAIMED)); repository.editWarranty(ID, sensitiveInput)

        // Failure during the write: exception message must not be logged either.
        seed(original)
        coEvery { eventDao.insert(any()) } throws RuntimeException(SENSITIVE_EXCEPTION_MESSAGE)
        assertNotNull(runCatching { repository.editWarranty(ID, sensitiveInput) }.exceptionOrNull())
        seed(pendingDraft())
        assertNotNull(runCatching { repository.editWarranty(ID, sensitiveInput) }.exceptionOrNull())

        // Blocked by the write barrier.
        val op = "WarrantyTrackerRepository.editWarranty"
        every { barrier.checkWritesAllowed(op) } throws blocked(op)
        assertNotNull(runCatching { repository.editWarranty(ID, sensitiveInput) }.exceptionOrNull())

        val forbidden = listOf(
            SENSITIVE_PRODUCT, SENSITIVE_MERCHANT, SENSITIVE_PHONE, SENSITIVE_EXCEPTION_MESSAGE,
            "Alpha Product", "Beta Merchant", "+00 555 0100", "synthetic note"
        )
        logs.forEach { line ->
            forbidden.forEach { token ->
                assertFalse("log line leaks \"$token\"", line.message.contains(token))
            }
            assertNull("no raw throwable (message/stack) in logs", line.throwable)
        }
    }

    @Test
    fun `INV-14 AE-15 event rows carry only the constant description and null metadata`() = runTest(timeout = 60.seconds) {
        val draft = seed(pendingDraft())

        repository.editWarranty(
            ID,
            inputOf(
                draft,
                productName = SENSITIVE_PRODUCT,
                merchantName = SENSITIVE_MERCHANT,
                supportPhone = SENSITIVE_PHONE
            )
        )

        assertEquals(2, events.size)
        assertEventsAreConstantAndSafe()
        events.forEach { event ->
            listOf(SENSITIVE_PRODUCT, SENSITIVE_MERCHANT, SENSITIVE_PHONE).forEach { token ->
                assertFalse(event.description.orEmpty().contains(token))
            }
        }
    }

    // ------------------------------------------------------------------ INV-17 / AE-18

    @Test
    fun `INV-17 AE-18 status uses the fake clock on a DST transition day not the wall clock`() = runTest(timeout = 60.seconds) {
        // Athens DST began 2020-03-29. The wall clock is long past the end below; the fake clock is not.
        timeProvider.setTime(at(2020, 3, 29, 12, 0))
        val active = seed(row())

        val result = repository.editWarranty(
            ID,
            inputOf(active, purchaseDate = midnight(2020, 3, 28), durationMonths = 1)
        )

        assertEquals(EditWarrantyResult.Edited(ID, WarrantyStatus.ACTIVE, false), result)
        assertEquals(midnight(2020, 4, 29), stored!!.warrantyEndDate)
        assertEquals("updatedAt is the fake clock", at(2020, 3, 29, 12, 0), stored!!.updatedAt)
    }

    @Test
    fun `INV-17 AE-18 purchase day bound follows the fake clock`() = runTest(timeout = 60.seconds) {
        timeProvider.setTime(at(2020, 3, 29, 12, 0))
        val active = seed(row())

        val atLimit = repository.editWarranty(
            ID,
            inputOf(active, purchaseDate = midnight(2021, 3, 29))
        )
        seed(row())
        val beyondLimit = repository.editWarranty(
            ID,
            inputOf(active, purchaseDate = midnight(2021, 3, 30))
        )

        assertTrue("today + 365 days is accepted", atLimit is EditWarrantyResult.Edited)
        assertEquals(
            EditWarrantyResult.Invalid(WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE),
            beyondLimit
        )
    }

    @Test
    fun `INV-17 AE-18 end on a DST day is the next local midnight`() = runTest(timeout = 60.seconds) {
        timeProvider.setTime(at(2020, 3, 29, 12, 0))
        val active = seed(row())

        repository.editWarranty(
            ID,
            inputOf(active, purchaseDate = midnight(2020, 2, 29), durationMonths = 1)
        )

        // 2020-02-29 + 1 month = 2020-03-29 (a 23-hour day); the exclusive end is 2020-03-30 00:00.
        assertEquals(midnight(2020, 3, 30), stored!!.warrantyEndDate)
    }

    // ------------------------------------------------------------------ INV-18 / AE-19

    @Test
    fun `INV-18 AE-19 review row is written before it is confirmed and ends ACTIVE when the end moves to the future`() = runTest(timeout = 60.seconds) {
        val expiredDraft = seed(
            row(
                status = WarrantyStatus.PENDING_REVIEW,
                needsReview = true,
                purchase = midnight(2025, 4, 30),
                end = midnight(2026, 5, 1)
            )
        )

        val result = repository.editWarranty(
            ID,
            inputOf(expiredDraft, purchaseDate = midnight(2026, 9, 1), durationMonths = 24)
        )

        assertEquals(EditWarrantyResult.Edited(ID, WarrantyStatus.ACTIVE, true), result)
        coVerifyOrder {
            barrier.checkWritesAllowed("WarrantyTrackerRepository.editWarranty")
            warrantyDao.getWarrantyById(ID)
            barrier.checkWritesAllowed("WarrantyTrackerRepository.updateWarranty")
            warrantyDao.updateWarranty(
                match { it.status == WarrantyStatus.ACTIVE && it.needsReview && it.warrantyDurationMonths == 24 }
            )
            eventDao.insert(match { it.eventType == WarrantyLifecycleEventTypes.UPDATED })
            barrier.checkWritesAllowed("WarrantyTrackerRepository.confirmWarranty")
            warrantyDao.getWarrantyById(ID)
            warrantyDao.updateWarranty(match { !it.needsReview && it.status == WarrantyStatus.ACTIVE })
            eventDao.insert(match { it.eventType == WarrantyLifecycleEventTypes.CONFIRMED })
        }
        assertEquals("first write keeps needsReview true", true, updates.first().needsReview)
        assertEquals(WarrantyStatus.ACTIVE, stored!!.status)
        assertFalse(stored!!.needsReview)
        assertEquals(
            listOf(WarrantyLifecycleEventTypes.UPDATED, WarrantyLifecycleEventTypes.CONFIRMED),
            eventTypes()
        )
    }

    @Test
    fun `INV-18 AE-19 review row whose end stays in the past ends EXPIRED with both events`() = runTest(timeout = 60.seconds) {
        val expiredDraft = seed(
            row(
                status = WarrantyStatus.PENDING_REVIEW,
                needsReview = true,
                purchase = midnight(2025, 4, 30),
                end = midnight(2026, 5, 1)
            )
        )

        val result = repository.editWarranty(
            ID,
            inputOf(expiredDraft, purchaseDate = midnight(2026, 3, 1), durationMonths = 1)
        )

        assertEquals(EditWarrantyResult.Edited(ID, WarrantyStatus.EXPIRED, true), result)
        assertEquals(2, updates.size)
        assertEquals(WarrantyStatus.EXPIRED, updates[0].status)
        assertTrue("first write keeps needsReview true", updates[0].needsReview)
        assertFalse("second write clears review", updates[1].needsReview)
        assertEquals(WarrantyStatus.EXPIRED, stored!!.status)
        assertEquals(
            listOf(WarrantyLifecycleEventTypes.UPDATED, WarrantyLifecycleEventTypes.CONFIRMED),
            eventTypes()
        )
    }

    // ------------------------------------------------------------------ INV-19 / AE-20 / INV-20 source pins

    private fun repositorySource(): String {
        val roots = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
        )
        val file = roots.map { File(it, REPOSITORY_SOURCE) }.firstOrNull { it.isFile }
            ?: error("Could not locate WarrantyTrackerRepository.kt. user.dir=${System.getProperty("user.dir")}")
        return file.readText()
    }

    private fun stripComments(code: String): String =
        code.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")

    /** Body between the braces of the function whose declaration starts with [declaration]. */
    private fun bodyOf(source: String, declaration: String): String {
        val start = source.indexOf(declaration)
        assertTrue("declaration not found: $declaration", start >= 0)
        assertEquals("declaration must be unique: $declaration", start, source.lastIndexOf(declaration))
        val open = source.indexOf('{', start)
        var depth = 0
        var index = open
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return stripComments(source.substring(open + 1, index))
                }
            }
            index++
        }
        error("unbalanced braces after $declaration")
    }

    @Test
    fun `INV-19 AE-20 editWarranty body only reads through the DAO and writes only via updateWarranty then confirmWarranty`() {
        val body = bodyOf(repositorySource(), "suspend fun editWarranty(")

        val daoCalls = Regex("""warrantyDao\s*\.\s*(\w+)""").findAll(body).map { it.groupValues[1] }.toSet()
        assertEquals("only DAO read allowed", setOf("getWarrantyById"), daoCalls)
        listOf("warrantyLifecycleEventDao", "returnWindowDao", "receiptRepository", "returnWindow").forEach {
            assertFalse("editWarranty must not reference $it", body.contains(it))
        }
        val qualifiedMutations =
            Regex("""\b\w+\s*\.\s*(insert|update|delete)\w*\s*\(""").findAll(body).map { it.value }.toList()
        assertTrue("no qualified insert/update/delete call, found $qualifiedMutations", qualifiedMutations.isEmpty())
        assertFalse(
            "needsReview is never assigned",
            Regex("""needsReview\s*=(?!=)""").containsMatchIn(body)
        )
        assertFalse("fail-closed: no try/catch", Regex("""\b(try|catch)\b""").containsMatchIn(body))

        val updateAt = body.indexOf("updateWarranty(")
        val confirmAt = body.indexOf("confirmWarranty(")
        assertTrue("updateWarranty( call present", updateAt >= 0)
        assertTrue("confirmWarranty( call present", confirmAt >= 0)
        assertTrue("updateWarranty must come before confirmWarranty (INV-18)", updateAt < confirmAt)
        assertEquals("single write call", updateAt, body.lastIndexOf("updateWarranty("))
    }

    @Test
    fun `INV-20 updateWarranty keeps barrier first then one transaction with the row update and event and no try catch`() {
        val body = bodyOf(repositorySource(), "suspend fun updateWarranty(warranty: Warranty)")

        assertTrue(
            "barrier check is the first statement",
            body.trimStart().startsWith("""writeBarrier.checkWritesAllowed("WarrantyTrackerRepository.updateWarranty")""")
        )
        val barrierAt = body.indexOf("writeBarrier.checkWritesAllowed(")
        val transactionAt = body.indexOf("database.withTransaction")
        val rowAt = body.indexOf("warrantyDao.updateWarranty(")
        val eventAt = body.indexOf("warrantyLifecycleEventDao.insert(")
        assertTrue(barrierAt in 0 until transactionAt)
        assertTrue("row update inside the transaction", transactionAt in 0 until rowAt)
        assertTrue("event insert after the row update", rowAt in 0 until eventAt)
        assertFalse("fail-closed: no try/catch", Regex("""\b(try|catch)\b""").containsMatchIn(body))
        assertTrue(body.contains("WarrantyLifecycleEventTypes.UPDATED"))
        assertTrue(body.contains("\"WARRANTY_UPDATED\""))
    }
}
