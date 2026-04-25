package com.yourname.expensetracker.domain.ai.model

import org.junit.Assert.assertThrows
import org.junit.Test

class WarrantyExtractionModelsTest {

    @Test
    fun `WarrantyExtractionResult rejects invalid confidence`() {
        assertThrows(IllegalArgumentException::class.java) {
            WarrantyExtractionResult(
                productName = "Laptop",
                warrantyMonths = 24,
                warrantyType = "Limited",
                supportPhone = null,
                supportEmail = null,
                returnDays = 14,
                returnConditions = null,
                confidence = Float.NaN
            )
        }
    }

    @Test
    fun `WarrantyExtractionResult rejects non-positive day fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            WarrantyExtractionResult(
                productName = "Laptop",
                warrantyMonths = 0,
                warrantyType = "Limited",
                supportPhone = null,
                supportEmail = null,
                returnDays = 14,
                returnConditions = null,
                confidence = 0.7f
            )
        }
    }
}
