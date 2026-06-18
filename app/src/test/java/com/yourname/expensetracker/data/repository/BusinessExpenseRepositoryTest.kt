package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.MileageTrackingDao
import com.yourname.expensetracker.data.database.entity.MileageTracking
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class BusinessExpenseRepositoryTest {

    private lateinit var expenseDao: ExpenseDao
    private lateinit var mileageDao: MileageTrackingDao
    private lateinit var writeBarrier: DatabaseWriteBarrier
    private lateinit var repository: BusinessExpenseRepository

    @Before
    fun setup() {
        expenseDao = mockk(relaxed = true)
        mileageDao = mockk(relaxed = true)
        writeBarrier = mockk(relaxed = true)
        repository = BusinessExpenseRepository(writeBarrier, expenseDao, mileageDao)
    }

    @Test
    fun `addMileage inserts valid mileage`() = runTest {
        val mileage = validMileage()
        coEvery { mileageDao.insert(mileage) } returns 77L

        val insertedId = repository.addMileage(mileage)

        assertEquals(77L, insertedId)
        coVerify(exactly = 1) { mileageDao.insert(mileage) }
    }

    @Test
    fun `addMileage rejects impossible values before dao insert`() = runTest {
        val invalidMileage = validMileage(distanceKm = Double.NaN)

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repository.addMileage(invalidMileage) }
        }
        coVerify(exactly = 0) { mileageDao.insert(any()) }
    }

    private fun validMileage(
        distanceKm: Double = 12.5,
        startOdometer: Double? = 1000.0,
        endOdometer: Double? = 1012.5
    ): MileageTracking {
        return MileageTracking(
            id = 0,
            date = 1_700_000_000_000,
            startOdometer = startOdometer,
            endOdometer = endOdometer,
            distanceKm = distanceKm,
            isBusinessTrip = true,
            tripPurpose = "Client visit",
            deductionRatePerKm = 0.3,
            createdAt = 1_700_000_000_100
        )
    }
}
