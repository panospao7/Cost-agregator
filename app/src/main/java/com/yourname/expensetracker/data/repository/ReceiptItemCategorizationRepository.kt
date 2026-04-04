package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ReceiptItemCategorizationDao
import com.yourname.expensetracker.data.database.entity.ReceiptItemCategorization
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceiptItemCategorizationRepository @Inject constructor(
    private val dao: ReceiptItemCategorizationDao
) {
    suspend fun getByReceiptId(receiptId: Long): List<ReceiptItemCategorization> =
        dao.getByReceiptId(receiptId)

    suspend fun updateUserCorrection(
        itemId: Long,
        categoryId: Long?,
        categoryName: String?,
        timestamp: Long
    ) = dao.updateUserCorrection(
        itemId = itemId,
        categoryId = categoryId,
        categoryName = categoryName,
        timestamp = timestamp
    )
}
