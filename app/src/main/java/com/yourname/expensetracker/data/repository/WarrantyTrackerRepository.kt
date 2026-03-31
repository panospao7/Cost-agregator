package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.ai.provider.CloudWarrantyExtractionService
import com.yourname.expensetracker.data.database.dao.ReturnWindowDao
import com.yourname.expensetracker.data.database.dao.WarrantyDao
import com.yourname.expensetracker.data.database.entity.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WarrantyTrackerRepository @Inject constructor(
    private val warrantyDao: WarrantyDao,
    private val returnWindowDao: ReturnWindowDao,
    private val receiptRepository: ReceiptRepository,
    private val cloudExtractionService: CloudWarrantyExtractionService
) {
    // Warranty operations
    fun getAllWarranties(): Flow<List<Warranty>> = warrantyDao.getAllWarranties()
    
    fun getActiveWarranties(): Flow<List<Warranty>> = warrantyDao.getActiveWarranties()
    
    fun getWarrantiesByStatus(status: WarrantyStatus): Flow<List<Warranty>> = 
        warrantyDao.getWarrantiesByStatus(status)
    
    suspend fun getWarrantiesExpiringSoon(days: Int): List<Warranty> {
        val futureTime = System.currentTimeMillis() + (days * 24 * 60 * 60 * 1000)
        return warrantyDao.getWarrantiesExpiringSoon(futureTime)
    }
    
    suspend fun getWarrantyByReceiptId(receiptId: Long): Warranty? = 
        warrantyDao.getWarrantyByReceiptId(receiptId)
    
    suspend fun addWarranty(warranty: Warranty): Long = warrantyDao.insertWarranty(warranty)
    
    suspend fun updateWarranty(warranty: Warranty) = warrantyDao.updateWarranty(warranty)
    
    suspend fun deleteWarranty(warranty: Warranty) = warrantyDao.deleteWarranty(warranty)
    
    suspend fun markWarrantyAsClaimed(warrantyId: Long) = 
        warrantyDao.updateWarrantyStatus(warrantyId, WarrantyStatus.CLAIMED)
    
    suspend fun getActiveWarrantyCount(): Int = warrantyDao.getActiveWarrantyCount()
    
    suspend fun getTotalProtectedValue(): Double = warrantyDao.getTotalProtectedValue() ?: 0.0
    
    // Return window operations
    fun getAllReturnWindows(): Flow<List<ReturnWindow>> = returnWindowDao.getAllReturnWindows()
    
    fun getActiveReturnWindows(): Flow<List<ReturnWindow>> = returnWindowDao.getActiveReturnWindows()
    
    suspend fun getReturnWindowsExpiringSoon(days: Int): List<ReturnWindow> {
        val futureTime = System.currentTimeMillis() + (days * 24 * 60 * 60 * 1000)
        return returnWindowDao.getReturnWindowsExpiringSoon(futureTime)
    }
    
    suspend fun addReturnWindow(returnWindow: ReturnWindow): Long = 
        returnWindowDao.insertReturnWindow(returnWindow)
    
    suspend fun updateReturnWindow(returnWindow: ReturnWindow) = 
        returnWindowDao.updateReturnWindow(returnWindow)
    
    suspend fun markAsReturned(
        returnWindowId: Long, 
        refundAmount: Double? = null
    ) = returnWindowDao.markAsReturned(returnWindowId, refundAmount = refundAmount)
    
    // AI extraction
    suspend fun extractWarrantyFromReceipt(receipt: ScannedReceipt): Warranty? {
        return cloudExtractionService.extractWarranty(receipt)
    }
    
    // Extract return window from warranty extraction result
    suspend fun extractReturnWindow(receipt: ScannedReceipt, warranty: Warranty?): ReturnWindow? {
        // Try to extract return policy from the same receipt
        // For now, use common defaults based on merchant type
        val returnDays = when {
            receipt.parsedMerchant?.contains("Amazon", ignoreCase = true) == true -> 30
            receipt.parsedMerchant?.contains("Best Buy", ignoreCase = true) == true -> 15
            receipt.parsedMerchant?.contains("Apple", ignoreCase = true) == true -> 14
            else -> 30 // Default
        }
        
        val purchaseDate = receipt.parsedDate ?: receipt.createdAt
        val returnDeadline = purchaseDate + (returnDays * 24 * 60 * 60 * 1000L)
        
        return ReturnWindow(
            receiptId = receipt.id,
            expenseId = receipt.expenseId,
            productName = warranty?.productName ?: "Purchase from ${receipt.parsedMerchant ?: "Unknown"}",
            merchantName = receipt.parsedMerchant ?: "Unknown",
            purchaseDate = purchaseDate,
            returnDays = returnDays,
            returnDeadline = returnDeadline
        )
    }
    
    // Batch operations for receipt scanning
    suspend fun processReceiptForWarranty(receipt: ScannedReceipt): Pair<Warranty?, ReturnWindow?> {
        val warranty = extractWarrantyFromReceipt(receipt)
        val returnWindow = if (warranty != null) {
            extractReturnWindow(receipt, warranty)
        } else null
        
        return Pair(warranty, returnWindow)
    }
}
