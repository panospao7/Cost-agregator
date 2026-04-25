package com.yourname.expensetracker.domain.location

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TravelDetectionEngineTest {

    private val engine = TravelDetectionEngine()

    @Test
    fun `compute uses first non blank token for one part travel address`() {
        val homeExpenses = (1L..5L).map { id ->
            Expense(
                id = id,
                amount = 10.0,
                merchant = "Home",
                transactionType = TransactionType.PURCHASE,
                date = id,
                latitude = 37.98,
                longitude = 23.72,
                resolvedAddress = "Athens"
            )
        }
        val travelExpense = Expense(
            id = 6,
            amount = 20.0,
            merchant = "Trip",
            transactionType = TransactionType.PURCHASE,
            date = 10_000,
            latitude = 40.64,
            longitude = 22.94,
            resolvedAddress = "Thessaloniki"
        )

        val result = engine.compute(homeExpenses + travelExpense)

        assertNotNull(result)
        assertEquals("Thessaloniki", result!!.travelTrips.single().destinationHint)
    }
}
