package com.yourname.expensetracker.domain.transaction.lifecycle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.transaction.LifecycleEventType
import com.yourname.expensetracker.domain.transaction.validation.DefaultTransactionDatePolicy
import com.yourname.expensetracker.domain.transaction.validation.TransactionValidationException
import com.yourname.expensetracker.domain.transaction.validation.TransactionValidator
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFailsWith

/**
 * P-02-4: merchant renames to a blank or placeholder name are rejected by the lifecycle
 * coordinator before any DAO write or event (bulk returns a typed failure, single throws).
 * P-02-5: BULK_UPDATED event metadata carries merchant keys and counts only, never the
 * merchant display names.
 *
 * Real Room and a REAL [TransactionValidator]; peripheral collaborators are mocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransactionLifecycleCoordinatorMerchantValidationTest {

    private lateinit var db: AppDatabase
    private lateinit var coordinator: TransactionLifecycleCoordinator
    private lateinit var planner: TransactionSideEffectPlanner
    private lateinit var runner: PostCommitActionRunner

    private val oldName = "Zorbas Taverna"
    private val newName = "Kafeneio Alpha"

    @Before
    fun setUp() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
        val timeProvider = FakeTimeProvider.forDate(2026, 5, 1)

        val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        coEvery {
            writeBarrier.runWrite(
                any<DatabaseAccessOperation>(),
                any<suspend () -> Any?>()
            )
        } coAnswers { secondArg<suspend () -> Any?>().invoke() }

        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))

        planner = mockk(relaxed = true)
        runner = mockk(relaxed = true)

        coordinator = TransactionLifecycleCoordinator(
            database = db,
            expenseDao = db.expenseDao(),
            transactionEventDao = db.transactionEventDao(),
            timeProvider = timeProvider,
            currencyConverter = mockk(relaxed = true),
            sideEffectDispatcher = mockk(relaxed = true),
            planner = planner,
            runner = runner,
            recurringLifecycleCoordinator = mockk(relaxed = true),
            writeBarrier = writeBarrier,
            currencySettingsRepository = currencySettingsRepository,
            sourceLinkWriter = mockk(relaxed = true),
            transactionValidator = TransactionValidator(timeProvider, DefaultTransactionDatePolicy()),
            diagnosticEventWriter = mockk(relaxed = true),
            receiptLinkService = com.yourname.expensetracker.testfixtures.noLinkedReceiptsLinkService()
        )
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private suspend fun seed(merchant: String, amount: Double): Long {
        val id = db.expenseDao().insert(
            Expense(
                amount = amount,
                currency = "EUR",
                merchant = merchant,
                merchantKey = MerchantKeyGenerator.generate(merchant),
                transactionType = TransactionType.PURCHASE,
                date = 1_777_000_000_000L
            )
        )
        assertTrue(id > 0L)
        return id
    }

    private suspend fun eventCount(): Int = db.transactionEventDao().getRecentEvents(100).size

    private suspend fun assertNothingWritten(ids: List<Long>) {
        ids.forEach { id ->
            val row = db.expenseDao().getById(id)!!
            assertEquals(oldName, row.merchant)
            assertEquals(MerchantKeyGenerator.generate(oldName), row.merchantKey)
        }
        assertEquals("no lifecycle event may be written", 0, eventCount())
        coVerify(exactly = 0) { planner.planBulkUpdated(any(), any(), any(), any()) }
        coVerify(exactly = 0) { planner.planUpdated(any(), any(), any(), any()) }
    }

    // ── P-02-4: bulkUpdateMerchant ───────────────────────────────────────────

    @Test
    fun `P-02-4 control - bulkUpdateMerchant to a normal name renames rows and writes one event`() = runTest {
        val ids = listOf(seed(oldName, 10.0), seed(oldName, 20.0))

        val result = coordinator.bulkUpdateMerchant(oldName, newName)

        assertTrue("expected success, got $result", result.isSuccess)
        ids.forEach { assertEquals(newName, db.expenseDao().getById(it)!!.merchant) }
        assertEquals(1, db.transactionEventDao().countByType(LifecycleEventType.BULK_UPDATED.name))
    }

    @Test
    fun `P-02-4 bulkUpdateMerchant to Unknown fails with TransactionValidationException and writes nothing`() = runTest {
        val ids = listOf(seed(oldName, 10.0), seed(oldName, 20.0))

        val result = coordinator.bulkUpdateMerchant(oldName, "Unknown")

        assertTrue("expected failure, got $result", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("got $error", error is TransactionValidationException)
        assertEquals(
            listOf("MERCHANT_PLACEHOLDER"),
            (error as TransactionValidationException).errors.map { it.code }
        )
        assertNothingWritten(ids)
    }

    @Test
    fun `P-02-4 bulkUpdateMerchant to Parsing Failed fails and writes nothing`() = runTest {
        val ids = listOf(seed(oldName, 10.0))

        val result = coordinator.bulkUpdateMerchant(oldName, "Parsing Failed")

        assertTrue(result.exceptionOrNull() is TransactionValidationException)
        assertNothingWritten(ids)
    }

    @Test
    fun `P-02-4 bulkUpdateMerchant to a blank name fails with TransactionValidationException and writes nothing`() = runTest {
        val ids = listOf(seed(oldName, 10.0), seed(oldName, 20.0))

        val result = coordinator.bulkUpdateMerchant(oldName, "   ")

        assertTrue("expected failure, got $result", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("got $error", error is TransactionValidationException)
        assertEquals(
            listOf("MERCHANT_BLANK"),
            (error as TransactionValidationException).errors.map { it.code }
        )
        assertNothingWritten(ids)
    }

    // ── P-02-4: updateMerchant ───────────────────────────────────────────────

    @Test
    fun `P-02-4 control - updateMerchant to a normal name renames the row and writes an event`() = runTest {
        val id = seed(oldName, 10.0)

        coordinator.updateMerchant(id, newName)

        assertEquals(newName, db.expenseDao().getById(id)!!.merchant)
        assertEquals(1, db.transactionEventDao().getEventsForExpense(id).size)
    }

    @Test
    fun `P-02-4 updateMerchant to Unknown throws TransactionValidationException and writes nothing`() = runTest {
        val id = seed(oldName, 10.0)

        val e = assertFailsWith<TransactionValidationException> {
            coordinator.updateMerchant(id, "Unknown")
        }

        assertEquals(listOf("MERCHANT_PLACEHOLDER"), e.errors.map { it.code })
        assertNothingWritten(listOf(id))
    }

    @Test
    fun `P-02-4 updateMerchant to Parsing Failed throws and writes nothing`() = runTest {
        val id = seed(oldName, 10.0)

        assertFailsWith<TransactionValidationException> {
            coordinator.updateMerchant(id, "Parsing Failed")
        }

        assertNothingWritten(listOf(id))
    }

    @Test
    fun `P-02-4 updateMerchant to a blank name throws and writes nothing`() = runTest {
        val id = seed(oldName, 10.0)

        val e = assertFailsWith<TransactionValidationException> {
            coordinator.updateMerchant(id, "")
        }

        assertEquals(listOf("MERCHANT_BLANK"), e.errors.map { it.code })
        assertNothingWritten(listOf(id))
    }

    // ── P-02-5: event metadata carries no merchant names ─────────────────────

    @Test
    fun `P-02-5 bulkUpdateMerchant BULK_UPDATED metadata contains neither the old nor the new merchant name`() = runTest {
        seed(oldName, 10.0)
        seed(oldName, 20.0)

        val result = coordinator.bulkUpdateMerchant(oldName, newName)
        assertTrue("expected success, got $result", result.isSuccess)

        val event = db.transactionEventDao()
            .getEventsByType(LifecycleEventType.BULK_UPDATED.name)
            .single()
        val metadata = event.metadata
        assertNotNull(metadata)
        assertFalse("old name leaked: $metadata", metadata!!.contains(oldName, ignoreCase = true))
        assertFalse("new name leaked: $metadata", metadata.contains(newName, ignoreCase = true))

        val json = JSONObject(metadata)
        assertFalse(json.has("oldMerchant"))
        assertFalse(json.has("newMerchant"))
        // Keys and counts are kept.
        assertEquals(MerchantKeyGenerator.generate(oldName), json.getString("oldMerchantKey"))
        assertEquals(MerchantKeyGenerator.generate(newName), json.getString("newMerchantKey"))
        assertEquals(2, json.getInt("affectedCount"))
    }

    @Test
    fun `P-02-5 bulkUpdateCategory by merchant BULK_UPDATED metadata contains no merchant name`() = runTest {
        val categoryId = db.categoryDao().insert(
            Category(name = "Synthetic Dining", icon = "x", color = "#FFFFFF")
        )
        seed(oldName, 10.0)

        coordinator.bulkUpdateCategory(merchant = oldName, newCategoryId = categoryId)

        val event = db.transactionEventDao()
            .getEventsByType(LifecycleEventType.BULK_UPDATED.name)
            .single()
        val metadata = event.metadata
        assertNotNull(metadata)
        assertFalse("merchant name leaked: $metadata", metadata!!.contains(oldName, ignoreCase = true))

        val json = JSONObject(metadata)
        assertFalse(json.has("merchant"))
        // Keys and counts are kept.
        assertEquals(MerchantKeyGenerator.generate(oldName), json.getString("merchantKey"))
        assertEquals(categoryId, json.getLong("newCategoryId"))
        assertEquals(1, json.getInt("affectedCount"))
    }
}
