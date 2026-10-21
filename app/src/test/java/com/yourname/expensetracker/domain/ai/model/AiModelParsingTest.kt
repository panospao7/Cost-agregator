package com.yourname.expensetracker.domain.ai.model

import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Constructor invariants of small AI parsing models (merged from CategorizationAssistInputTest,
 * NotificationParsingModelsTest and WarrantyExtractionModelsTest).
 */
class AiModelParsingTest {

    private data class ModelRow(val name: String, val build: () -> Any)

    private fun categorizationInput(amount: Double) = CategorizationAssistInput(
        targetType = AiTargetType.PENDING_REVIEW,
        targetId = 1L,
        merchant = "Store",
        amount = amount,
        currency = "EUR",
        transactionType = DomainTransactionType.PURCHASE,
        date = null,
        currentCategoryId = null,
        deterministicMatchType = null,
        deterministicExplanation = null,
        candidateCategories = listOf(CategoryOption(1L, "Food"))
    )

    private fun notificationResult(amount: Double, confidence: Float) = NotificationParseResult(
        amount = amount,
        currency = "EUR",
        merchant = "Store",
        transactionType = ParsedTransactionType.PURCHASE,
        direction = null,
        confidence = confidence,
        reasoning = null
    )

    private fun warrantyResult(warrantyMonths: Int, confidence: Float) = WarrantyExtractionResult(
        productName = "Laptop",
        warrantyMonths = warrantyMonths,
        warrantyType = "Limited",
        supportPhone = null,
        supportEmail = null,
        returnDays = 14,
        returnConditions = null,
        confidence = confidence
    )

    @Test
    fun `model constructors reject invalid values with IllegalArgumentException`() {
        val rows = listOf(
            ModelRow("CategorizationAssistInput non-finite amount") { categorizationInput(Double.NaN) },
            ModelRow("NotificationParseResult non-positive amount") { notificationResult(0.0, 0.8f) },
            ModelRow("NotificationParseResult out-of-range confidence") { notificationResult(10.0, 1.2f) },
            ModelRow("WarrantyExtractionResult non-finite confidence") { warrantyResult(24, Float.NaN) },
            ModelRow("WarrantyExtractionResult non-positive warranty months") { warrantyResult(0, 0.7f) }
        )
        assertEquals("rejection table size guard", 5, rows.size)

        rows.forEach { row ->
            try {
                row.build()
                fail("[${row.name}] expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `model constructors accept the valid control values`() {
        val rows = listOf(
            ModelRow("CategorizationAssistInput finite amount") { categorizationInput(25.0) },
            ModelRow("NotificationParseResult positive amount and in-range confidence") {
                notificationResult(10.0, 0.8f)
            },
            ModelRow("WarrantyExtractionResult valid months and confidence") { warrantyResult(24, 0.7f) }
        )
        assertEquals("control table size guard", 3, rows.size)

        rows.forEach { row ->
            try {
                row.build()
            } catch (e: IllegalArgumentException) {
                throw AssertionError("[${row.name}] valid control values must be accepted: ${e.message}", e)
            }
        }
    }
}
