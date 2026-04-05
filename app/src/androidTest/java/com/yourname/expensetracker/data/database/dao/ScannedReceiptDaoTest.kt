package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScannedReceiptDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: ScannedReceiptDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.scannedReceiptDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun makeReceipt(
        rawOcrText: String = "TOTAL 12.50",
        parsedMerchant: String = "Test Store",
        createdAt: Long = System.currentTimeMillis()
    ) = ScannedReceipt(
        imagePath = null,
        rawOcrText = rawOcrText,
        parsedTotal = 12.50,
        parsedMerchant = parsedMerchant,
        parsedDate = createdAt,
        parsedItems = null,
        parsedTaxAmount = 1.50,
        confidence = 0.92f,
        createdAt = createdAt
    )

    @Test
    fun insertScannedReceipt_andRetrieve() = runBlocking {
        val receipt = makeReceipt(rawOcrText = "TOTAL 9.99", parsedMerchant = "My Market")
        val id = dao.insert(receipt)

        val loaded = dao.getById(id)
        assertNotNull(loaded)
        assertEquals("TOTAL 9.99", loaded!!.rawOcrText)
        assertEquals("My Market", loaded.parsedMerchant)
    }

    @Test
    fun queryReceiptsByDateRange_returnsExpectedWindow() = runBlocking {
        val base = 1_700_000_000_000L
        val oldTs = base - 10_000L
        val inRangeTs = base - 1_000L
        val newestTs = base + 1_000L

        dao.insert(makeReceipt(rawOcrText = "old", createdAt = oldTs))
        dao.insert(makeReceipt(rawOcrText = "in-range", createdAt = inRangeTs))
        dao.insert(makeReceipt(rawOcrText = "newest", createdAt = newestTs))

        val results = dao.getRecentReceipts(base - 2_000L)

        assertEquals(2, results.size)
        assertEquals("newest", results[0].rawOcrText)
        assertEquals("in-range", results[1].rawOcrText)
    }

    @Test
    fun updateOcrText_persistsChange() = runBlocking {
        val id = dao.insert(makeReceipt(rawOcrText = "TOTAL ???"))
        val original = dao.getById(id)!!

        dao.update(original.copy(rawOcrText = "TOTAL 25.40"))

        val updated = dao.getById(id)
        assertNotNull(updated)
        assertEquals("TOTAL 25.40", updated!!.rawOcrText)
    }

    @Test
    fun deleteReceipt_verifiesRemoved() = runBlocking {
        val id = dao.insert(makeReceipt())
        val inserted = dao.getById(id)!!

        dao.delete(inserted)

        assertNull(dao.getById(id))
        assertEquals(0, dao.getCount())
    }
}
