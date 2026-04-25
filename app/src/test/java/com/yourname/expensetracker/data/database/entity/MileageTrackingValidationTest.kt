package com.yourname.expensetracker.data.database.entity

import org.junit.Assert.assertEquals
import org.junit.Test

class MileageTrackingValidationTest {

    @Test
    fun `MileageTracking entity remains constructible for legacy rows`() {
        val row = MileageTracking(
            id = 1L,
            date = 0L,
            startOdometer = null,
            endOdometer = null,
            distanceKm = 0.0,
            isBusinessTrip = false,
            tripPurpose = "",
            deductionRatePerKm = 0.0,
            createdAt = 0L
        )

        assertEquals(0.0, row.distanceKm, 0.0)
    }
}
