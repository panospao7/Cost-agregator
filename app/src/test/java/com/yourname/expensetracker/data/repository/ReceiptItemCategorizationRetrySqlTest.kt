package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.ReceiptItemCategorization
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.ai.model.CategorizedReceiptItem
import com.yourname.expensetracker.domain.ai.model.CategorySuggestion
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real in-memory Room: the retry write `updatePlaceholderRowsIfStillUncategorized` fills only
 * untouched placeholder rows of the named receipt, changes only the suggestion fields, and
 * returns the number of rows it updated. The repository rejects a rowIds/items count mismatch.
 * Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ReceiptItemCategorizationRetrySqlTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: ReceiptItemCategorizationRepository

    @Before
    fun setUp() {
        val maintenanceMode = mockk<RestoreMaintenanceMode>()
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { maintenanceMode.isWritesAllowed() } returns true
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ReceiptItemCategorizationRepository(
            DatabaseWriteBarrier(maintenanceMode),
            database.receiptItemCategorizationDao()
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun insertReceipt(): Long = database.scannedReceiptDao().insert(
        ScannedReceipt(
            imagePath = null,
            rawOcrText = "synthetic ocr",
            parsedTotal = 3.0,
            parsedMerchant = "Synthetic Shop",
            parsedDate = 1_000L,
            parsedItems = null,
            parsedTaxAmount = null,
            confidence = 0.9f
        )
    )

    private fun placeholderRow(receiptId: Long, description: String) = ReceiptItemCategorization(
        receiptId = receiptId,
        itemDescription = description,
        itemAmount = 2.5,
        suggestedCategoryId = null,
        suggestedCategoryName = null,
        confidence = 0f,
        aiRationale = "",
        alternativeCategoriesJson = "[]",
        userCorrectedCategoryId = null,
        userCorrectedCategoryName = null,
        userCorrectedAt = null,
        taxAmount = 0.2,
        isNewCategorySuggestion = false,
        createdAt = 100L,
        updatedAt = 100L
    )

    private fun realRow(receiptId: Long, description: String) = placeholderRow(receiptId, description).copy(
        suggestedCategoryId = 7L,
        suggestedCategoryName = "Original",
        confidence = 0.8f,
        aiRationale = "original rationale"
    )

    private fun replacement(id: Long, receiptId: Long) = ReceiptItemCategorization(
        id = id,
        receiptId = receiptId,
        itemDescription = "REPLACEMENT DESCRIPTION",
        itemAmount = 99.0,
        suggestedCategoryId = 3L,
        suggestedCategoryName = "Groceries",
        confidence = 0.9f,
        aiRationale = "matches",
        alternativeCategoriesJson = """[{"id":4,"name":"Home","confidence":0.4}]""",
        userCorrectedCategoryId = 55L,
        userCorrectedCategoryName = "ShouldNotLand",
        userCorrectedAt = 555L,
        taxAmount = 42.0,
        isNewCategorySuggestion = true,
        createdAt = 900L,
        updatedAt = 900L
    )

    private fun result(index: Int) = CategorizedReceiptItem(
        itemDescription = "item $index",
        amount = 2.5,
        suggestedCategory = CategorySuggestion(categoryId = 3L, categoryName = "Groceries", confidence = 0.9f),
        confidence = 0.9f,
        rationale = "matches",
        alternatives = emptyList(),
        needsReview = false
    )

    @Test
    fun `placeholder row is filled and only suggestion fields change`() = runTest {
        val dao = database.receiptItemCategorizationDao()
        val receiptId = insertReceipt()
        val id = dao.insert(placeholderRow(receiptId, "Stored Description"))

        val updated = dao.updatePlaceholderRowsIfStillUncategorized(receiptId, listOf(replacement(id, receiptId)))

        assertEquals(1, updated)
        val row = dao.getById(id)!!
        assertEquals(3L, row.suggestedCategoryId)
        assertEquals("Groceries", row.suggestedCategoryName)
        assertEquals(0.9f, row.confidence, 0f)
        assertEquals("matches", row.aiRationale)
        assertEquals("""[{"id":4,"name":"Home","confidence":0.4}]""", row.alternativeCategoriesJson)
        assertEquals(true, row.isNewCategorySuggestion)
        assertEquals(900L, row.updatedAt)
        // kept as stored
        assertEquals("Stored Description", row.itemDescription)
        assertEquals(2.5, row.itemAmount, 0.0)
        assertEquals(0.2, row.taxAmount!!, 0.0)
        assertEquals(100L, row.createdAt)
        assertEquals(receiptId, row.receiptId)
        assertNull(row.expenseId)
        assertNull(row.userCorrectedCategoryId)
        assertNull(row.userCorrectedCategoryName)
        assertNull(row.userCorrectedAt)
    }

    @Test
    fun `a placeholder with null alternatives and null rationale is also filled`() = runTest {
        val dao = database.receiptItemCategorizationDao()
        val receiptId = insertReceipt()
        val id = dao.insert(placeholderRow(receiptId, "d").copy(aiRationale = null, alternativeCategoriesJson = null))

        val updated = dao.updatePlaceholderRowsIfStillUncategorized(receiptId, listOf(replacement(id, receiptId)))

        assertEquals(1, updated)
        assertEquals(3L, dao.getById(id)!!.suggestedCategoryId)
    }

    @Test
    fun `a real row is skipped and left byte identical`() = runTest {
        val dao = database.receiptItemCategorizationDao()
        val receiptId = insertReceipt()
        val id = dao.insert(realRow(receiptId, "real"))
        val before = dao.getById(id)!!

        val updated = dao.updatePlaceholderRowsIfStillUncategorized(receiptId, listOf(replacement(id, receiptId)))

        assertEquals(0, updated)
        assertEquals(before, dao.getById(id))
    }

    @Test
    fun `a user corrected placeholder is skipped for each correction field`() = runTest {
        val dao = database.receiptItemCategorizationDao()
        val receiptId = insertReceipt()
        val byId = dao.insert(placeholderRow(receiptId, "a").copy(userCorrectedCategoryId = 9L))
        val byName = dao.insert(placeholderRow(receiptId, "b").copy(userCorrectedCategoryName = "Mine"))
        val byTime = dao.insert(placeholderRow(receiptId, "c").copy(userCorrectedAt = 77L))
        val ids = listOf(byId, byName, byTime)
        val before = ids.map { dao.getById(it)!! }

        val updated = dao.updatePlaceholderRowsIfStillUncategorized(
            receiptId, ids.map { replacement(it, receiptId) }
        )

        assertEquals(0, updated)
        assertEquals(before, ids.map { dao.getById(it) })
    }

    @Test
    fun `a row of another receipt is skipped`() = runTest {
        val dao = database.receiptItemCategorizationDao()
        val receiptId = insertReceipt()
        val otherReceiptId = insertReceipt()
        val otherId = dao.insert(placeholderRow(otherReceiptId, "other"))
        val before = dao.getById(otherId)!!

        val updated = dao.updatePlaceholderRowsIfStillUncategorized(
            receiptId, listOf(replacement(otherId, receiptId))
        )

        assertEquals(0, updated)
        assertEquals(before, dao.getById(otherId))
    }

    @Test
    fun `an unknown row id is skipped without failing`() = runTest {
        val dao = database.receiptItemCategorizationDao()
        val receiptId = insertReceipt()

        val updated = dao.updatePlaceholderRowsIfStillUncategorized(
            receiptId, listOf(replacement(9_999L, receiptId))
        )

        assertEquals(0, updated)
    }

    @Test
    fun `return value counts only the rows updated in a mixed batch`() = runTest {
        val dao = database.receiptItemCategorizationDao()
        val receiptId = insertReceipt()
        val otherReceiptId = insertReceipt()
        val p1 = dao.insert(placeholderRow(receiptId, "p1"))
        val real = dao.insert(realRow(receiptId, "real"))
        val corrected = dao.insert(placeholderRow(receiptId, "corrected").copy(userCorrectedCategoryId = 9L))
        val wrongReceipt = dao.insert(placeholderRow(otherReceiptId, "wrong"))
        val p2 = dao.insert(placeholderRow(receiptId, "p2"))

        val updated = dao.updatePlaceholderRowsIfStillUncategorized(
            receiptId,
            listOf(p1, real, corrected, wrongReceipt, p2).map { replacement(it, receiptId) }
        )

        assertEquals(2, updated)
        assertEquals(3L, dao.getById(p1)!!.suggestedCategoryId)
        assertEquals(3L, dao.getById(p2)!!.suggestedCategoryId)
        assertEquals(7L, dao.getById(real)!!.suggestedCategoryId)
        assertNull(dao.getById(corrected)!!.suggestedCategoryId)
        assertNull(dao.getById(wrongReceipt)!!.suggestedCategoryId)
    }

    @Test
    fun `applying the same replacement twice updates the row once and the second pass changes nothing`() = runTest {
        val dao = database.receiptItemCategorizationDao()
        val receiptId = insertReceipt()
        val id = dao.insert(placeholderRow(receiptId, "p"))
        val replacements = listOf(replacement(id, receiptId))

        assertEquals(1, dao.updatePlaceholderRowsIfStillUncategorized(receiptId, replacements))
        val afterFirst = dao.getById(id)
        assertEquals(0, dao.updatePlaceholderRowsIfStillUncategorized(receiptId, replacements))
        assertEquals(afterFirst, dao.getById(id))
    }

    @Test
    fun `repository fills the placeholder rows by id with no tax and no user correction`() = runTest {
        val dao = database.receiptItemCategorizationDao()
        val receiptId = insertReceipt()
        val p1 = dao.insert(placeholderRow(receiptId, "p1"))
        val real = dao.insert(realRow(receiptId, "real"))
        val p2 = dao.insert(placeholderRow(receiptId, "p2"))

        val updated = repository.updatePlaceholderRowsWithRetryResult(
            receiptId = receiptId,
            rowIds = listOf(p1, p2),
            items = listOf(result(0), result(1)),
            now = 5_000L
        )

        assertEquals(2, updated)
        val rows = repository.getByReceiptIdAsSnapshots(receiptId)
        assertEquals(listOf(p1, real, p2), rows.map { it.id })
        assertEquals(3L, rows[0].suggestedCategoryId)
        assertEquals(7L, rows[1].suggestedCategoryId)
        assertEquals(3L, rows[2].suggestedCategoryId)
        assertEquals(5_000L, rows[0].updatedAt)
        assertEquals(0.2, rows[0].taxAmount!!, 0.0)
        assertEquals("p1", rows[0].itemDescription)
        assertNull(rows[0].userCorrectedCategoryId)
    }

    @Test
    fun `repository count mismatch throws RECEIPT_ITEM_RETRY_COUNT_MISMATCH and writes nothing`() = runTest {
        val dao = database.receiptItemCategorizationDao()
        val receiptId = insertReceipt()
        val p1 = dao.insert(placeholderRow(receiptId, "p1"))
        val p2 = dao.insert(placeholderRow(receiptId, "p2"))
        val before = repository.getByReceiptIdAsSnapshots(receiptId)

        try {
            repository.updatePlaceholderRowsWithRetryResult(
                receiptId = receiptId,
                rowIds = listOf(p1, p2),
                items = listOf(result(0)),
                now = 5_000L
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("RECEIPT_ITEM_RETRY_COUNT_MISMATCH", e.message)
        }
        assertEquals(before, repository.getByReceiptIdAsSnapshots(receiptId))

        try {
            repository.updatePlaceholderRowsWithRetryResult(
                receiptId = receiptId,
                rowIds = listOf(p1),
                items = listOf(result(0), result(1)),
                now = 5_000L
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message == "RECEIPT_ITEM_RETRY_COUNT_MISMATCH")
        }
        assertEquals(before, repository.getByReceiptIdAsSnapshots(receiptId))
    }
}
