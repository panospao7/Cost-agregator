package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.ReceiptItemCategorizationDao
import com.yourname.expensetracker.domain.ai.model.CategorizedReceiptItem
import com.yourname.expensetracker.domain.ai.model.CategorySuggestion
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The retry write path honours the database write barrier: while a restore blocks writes, the
 * repository throws the existing DatabaseAccessBlockedException and touches no DAO row.
 */
class ReceiptItemCategorizationRetryBarrierTest {

    @Test
    fun `retry write is blocked during restore and touches no dao row`() = runTest {
        val maintenanceMode = mockk<RestoreMaintenanceMode>()
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.RESTORE_PREPARING
        every { maintenanceMode.isWritesAllowed() } returns false
        val dao = mockk<ReceiptItemCategorizationDao>(relaxed = true)
        val repository = ReceiptItemCategorizationRepository(DatabaseWriteBarrier(maintenanceMode), dao)
        val item = CategorizedReceiptItem(
            itemDescription = "item",
            amount = 2.5,
            suggestedCategory = CategorySuggestion(categoryId = 3L, categoryName = "Groceries", confidence = 0.9f),
            confidence = 0.9f,
            rationale = "matches",
            alternatives = emptyList(),
            needsReview = false
        )

        val exception = runCatching {
            repository.updatePlaceholderRowsWithRetryResult(
                receiptId = 1L,
                rowIds = listOf(10L),
                items = listOf(item),
                now = 5_000L
            )
        }.exceptionOrNull()

        assertNotNull("retry write must throw during restore mode", exception)
        assertEquals(DatabaseAccessBlockedException::class, exception!!::class)
        coVerify(exactly = 0) { dao.updatePlaceholderRowsIfStillUncategorized(any(), any()) }
    }
}
