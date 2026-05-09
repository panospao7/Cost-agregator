package com.yourname.expensetracker.domain.export

import com.yourname.expensetracker.data.database.entity.TransactionType

/**
 * Domain DTO used by export formatters.
 *
 * P12-P1-04 / P12-P1-07: Extended with full currency conversion audit fields,
 * transaction metadata (source, paymentMethod, createdAt), and business/tax fields
 * so that both generic CSV/JSON exports and accounting exporters can produce
 * complete, audit-ready rows without silently dropping app data.
 */
data class ExportTransaction(
    val id: Long,
    val date: Long,
    val createdAt: Long = 0L,
    /** Full transaction amount in original currency (before ownership share reduction). */
    val amount: Double,
    /** Effective spending amount — excludes isNotMine, applies myShareAmount/Percentage. */
    val effectiveAmount: Double = amount,
    val merchant: String,
    val notes: String?,
    val categoryId: Long?,
    /** ISO 4217 currency code of the original transaction. */
    val currency: String,
    val transactionType: TransactionType = TransactionType.UNKNOWN,
    /** Payment source account label derived from PaymentMethod. */
    val sourceAccountName: String = "Unknown Funding Source",
    /** Raw expense source (e.g. "NOTIFICATION", "MANUAL", "EMAIL_RECEIPT"). */
    val source: String? = null,
    /** Payment method name (CARD, CASH, BANK_TRANSFER, UNKNOWN). */
    val paymentMethod: String = "UNKNOWN",

    // ── Currency conversion audit ─────────────────────────────────
    /** Original transaction currency (same as [currency] if no conversion). */
    val originalCurrency: String = currency,
    /** Original amount in [originalCurrency] before conversion. */
    val originalAmount: Double? = null,
    /** Home (reporting) currency — equals [baseCurrency] from the expense snapshot. */
    val homeCurrency: String = currency,
    /** Converted amount in [homeCurrency]. 0.0 if no conversion was recorded. */
    val baseAmount: Double = 0.0,
    /** ISO 4217 code of the home/reporting currency. */
    val baseCurrency: String = currency,
    /** Exchange rate applied at transaction time. 0.0 = not recorded / identity. */
    val exchangeRateUsed: Double = 0.0,
    /** Exchange rate used for formatting — alias of [exchangeRateUsed] for exporter compatibility. */
    val conversionRateUsed: Double? = if (exchangeRateUsed > 0.0) exchangeRateUsed else null,

    // ── Business / tax fields ─────────────────────────────────────
    val isBusinessExpense: Boolean = false,
    val businessPurpose: String? = null,
    val businessCategory: String? = null,
    val businessProject: String? = null,
    val requiresReceipt: Boolean = false
)
