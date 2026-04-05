package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WarrantyDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var warrantyDao: WarrantyDao
    private lateinit var scannedReceiptDao: ScannedReceiptDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

        warrantyDao = database.warrantyDao()
        scannedReceiptDao = database.scannedReceiptDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private suspend fun insertReceipt(createdAt: Long = System.currentTimeMillis()): Long {
        return scannedReceiptDao.insert(
            ScannedReceipt(
                imagePath = null,
                rawOcrText = "raw",
                parsedTotal = 120.0,
                parsedMerchant = "Test Merchant",
                parsedDate = createdAt,
                parsedItems = null,
                parsedTaxAmount = null,
                confidence = 0.9f,
                createdAt = createdAt
            )
        )
    }

    private fun makeWarranty(
        receiptId: Long,
        warrantyEndDate: Long,
        status: WarrantyStatus = WarrantyStatus.ACTIVE,
        updatedAt: Long = System.currentTimeMillis()
    ) = Warranty(
        receiptId = receiptId,
        expenseId = null,
        productName = "Laptop",
        merchantName = "Tech Store",
        purchaseDate = 1_700_000_000_000L,
        warrantyDurationMonths = 24,
        warrantyEndDate = warrantyEndDate,
        status = status,
        createdAt = 1_700_000_000_000L,
        updatedAt = updatedAt
    )

    @Test
    fun insertWarranty_retrieveByReceiptId() = runBlocking {
        val receiptId = insertReceipt()
        warrantyDao.insertWarranty(makeWarranty(receiptId, warrantyEndDate = 1_800_000_000_000L))

        val fetched = warrantyDao.getWarrantyByReceiptId(receiptId)
        assertNotNull(fetched)
        assertEquals(receiptId, fetched!!.receiptId)
        assertEquals("Laptop", fetched.productName)
    }

    @Test
    fun queryActiveWarranties_returnsOnlyNotExpiredActive() = runBlocking {
        val now = 1_700_000_000_000L

        val activeReceipt = insertReceipt(now)
        val expiredReceipt = insertReceipt(now + 1)
        val claimedReceipt = insertReceipt(now + 2)

        warrantyDao.insertWarranty(makeWarranty(activeReceipt, warrantyEndDate = now + 86_400_000L, status = WarrantyStatus.ACTIVE))
        warrantyDao.insertWarranty(makeWarranty(expiredReceipt, warrantyEndDate = now - 1, status = WarrantyStatus.ACTIVE))
        warrantyDao.insertWarranty(makeWarranty(claimedReceipt, warrantyEndDate = now + 86_400_000L, status = WarrantyStatus.CLAIMED))

        val active = warrantyDao.getActiveWarranties(now).first()

        assertEquals(1, active.size)
        assertEquals(activeReceipt, active[0].receiptId)
    }

    @Test
    fun queryExpiredWarranties_returnsOnlyRecentlyExpiredActive() = runBlocking {
        val now = 1_700_000_000_000L

        val expiredActiveReceipt = insertReceipt(now)
        val futureActiveReceipt = insertReceipt(now + 1)
        val expiredAlreadyMarkedReceipt = insertReceipt(now + 2)

        warrantyDao.insertWarranty(makeWarranty(expiredActiveReceipt, warrantyEndDate = now - 10_000, status = WarrantyStatus.ACTIVE))
        warrantyDao.insertWarranty(makeWarranty(futureActiveReceipt, warrantyEndDate = now + 10_000, status = WarrantyStatus.ACTIVE))
        warrantyDao.insertWarranty(makeWarranty(expiredAlreadyMarkedReceipt, warrantyEndDate = now - 10_000, status = WarrantyStatus.EXPIRED))

        val expired = warrantyDao.getRecentlyExpiredWarranties(now)

        assertEquals(1, expired.size)
        assertEquals(expiredActiveReceipt, expired[0].receiptId)
    }

    @Test
    fun updateWarrantyStatus_changesStatusAndUpdatedAt() = runBlocking {
        val now = 1_700_000_000_000L
        val receiptId = insertReceipt(now)
        val warrantyId = warrantyDao.insertWarranty(
            makeWarranty(
                receiptId = receiptId,
                warrantyEndDate = now + 100_000,
                status = WarrantyStatus.ACTIVE,
                updatedAt = now
            )
        )

        val newUpdatedAt = now + 5_000
        warrantyDao.updateWarrantyStatus(warrantyId, WarrantyStatus.CLAIMED, newUpdatedAt)

        val updated = warrantyDao.getWarrantyByReceiptId(receiptId)
        assertNotNull(updated)
        assertEquals(WarrantyStatus.CLAIMED, updated!!.status)
        assertEquals(newUpdatedAt, updated.updatedAt)
        assertNull(updated.claimedAt) // query updates status/updatedAt only
    }
}
