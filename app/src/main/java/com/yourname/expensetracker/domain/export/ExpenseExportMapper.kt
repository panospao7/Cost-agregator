package com.yourname.expensetracker.domain.export

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.EntitySourceLink
import com.yourname.expensetracker.data.database.entity.PaymentMethod

/**
 * Shared accounting export mapper.
 *
 * P12-P1-04 / P12-P1-07: Extended to populate all currency conversion audit fields
 * (baseAmount, baseCurrency, exchangeRateUsed, effectiveAmount) and business/tax
 * fields (isBusinessExpense, businessPurpose, businessCategory, businessProject,
 * requiresReceipt) so that exports are audit-ready and roundtrip-safe.
 *
 * PR7: Extended to populate source link provenance data via [mapWithSourceLinks].
 *
 * BAK-14: All Double values are guarded with [isFinite] before serialization
 * to prevent NaN or Infinite values from corrupting the export output.
 */
object ExpenseExportMapper {
    fun map(expense: Expense): ExportTransaction {
        return ExportTransaction(
            id = expense.id,
            date = expense.date,
            createdAt = expense.createdAt,

            // Core amount fields
            // BAK-14: Guard against NaN/Infinite values that would produce corrupt rows.
            amount = expense.amount.takeIf { it.isFinite() } ?: 0.0,
            effectiveAmount = expense.effectiveAmount.takeIf { it.isFinite() } ?: 0.0,
            originalAmount = expense.amount.takeIf { it.isFinite() },

            merchant = expense.merchant,
            notes = expense.notes,
            categoryId = expense.categoryId,
            currency = expense.currency,
            transactionType = expense.transactionType,
            sourceAccountName = expense.paymentMethod.toExportSourceAccountName(),
            source = expense.source,
            paymentMethod = expense.paymentMethod.name,

            // Currency conversion audit snapshot (populated by TransactionLifecycleCoordinator at creation)
            originalCurrency = expense.currency,
            homeCurrency = expense.baseCurrency,
            baseAmount = expense.baseAmount.takeIf { it.isFinite() } ?: 0.0,
            baseCurrency = expense.baseCurrency,
            exchangeRateUsed = expense.exchangeRateUsed.takeIf { it.isFinite() } ?: 0.0,

            // Business / tax fields
            isBusinessExpense = expense.isBusinessExpense,
            businessPurpose = expense.businessPurpose,
            businessCategory = expense.businessCategory,
            businessProject = expense.businessProject,
            requiresReceipt = expense.requiresReceipt
        )
    }

    /**
     * PR7: Maps an expense to ExportTransaction with source link provenance data.
     *
     * @param sourceLinks Pre-loaded source links for this expense (from bulk DAO query).
     */
    fun mapWithSourceLinks(
        expense: Expense,
        sourceLinks: List<EntitySourceLink>
    ): ExportTransaction {
        val refs = sourceLinks.map { SourceLinkExportRef.fromEntitySourceLink(it) }
        val sourceLinksJson = if (refs.isNotEmpty()) {
            org.json.JSONArray().apply {
                refs.forEach { ref ->
                    put(org.json.JSONObject().apply {
                        put("sourceType", ref.sourceType)
                        put("sourceEntityType", ref.sourceEntityType)
                        ref.sourceEntityLocalId?.let { put("sourceEntityLocalId", it) }
                        put("linkRole", ref.linkRole)
                        put("linkStatus", ref.linkStatus)
                        put("isPrimary", ref.isPrimary)
                        ref.metadataJson?.let { put("metadataJson", it) }
                    })
                }
            }.toString()
        } else {
            null
        }
        return map(expense).copy(
            sourceLinks = refs,
            sourceLinksJson = sourceLinksJson
        )
    }
}

fun Expense.toExportTransaction(): ExportTransaction = ExpenseExportMapper.map(this)

internal fun PaymentMethod.toExportSourceAccountName(): String = when (this) {
    PaymentMethod.CARD -> "Credit Card"
    PaymentMethod.CASH -> "Cash"
    PaymentMethod.BANK_TRANSFER -> "Bank Account"
    PaymentMethod.UNKNOWN -> "Unknown Funding Source"
}
