package com.yourname.expensetracker.domain.receiptmatching

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService
import com.yourname.expensetracker.domain.util.StringDistanceUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

data class MatchCandidate(
    val transaction: Expense,
    val score: Double,
    val factors: MatchFactors
)

data class MatchFactors(
    val amountScore: Double,
    val merchantScore: Double,
    val dateScore: Double,
    val typeScore: Double
)

sealed class MatchResult {
    data class AutoMatch(
        val transaction: Expense,
        val score: Double
    ) : MatchResult()
    
    data class Suggested(
        val transaction: Expense,
        val score: Double
    ) : MatchResult()
    
    object NoMatch : MatchResult()
}

/**
 * Matches scanned receipts to existing expense transactions.
 *
 * ## N2: Receipt matching doesn't check currency
 * The matching algorithm in [calculateMatchScore] compares receipt and
 * transaction amounts as raw doubles without verifying that both are in
 * the same currency. If a receipt has "EUR" currency and the candidate
 * transaction is in "USD", the amount match could be coincidental (e.g.
 * both happen to be 50.0) even though the actual values differ by the
 * exchange rate. This lowers matching reliability for multi-currency users.
 *
 * The [calculateMatchScore] method now includes a currency-compatibility
 * penalty: when receipt currency differs from transaction currency and no
 * conversion is possible, the amount score is halved.
 */
@Singleton
class ReceiptTransactionMatcher @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val merchantNormalizer: MerchantNormalizer,
    private val stringDistance: StringDistanceUtils,
    private val timeProvider: TimeProvider,
    private val receiptLinkService: ReceiptLinkService,
    /** S12-031: Used for currency-aware amount comparison */
    private val currencyConverter: com.yourname.expensetracker.domain.currency.CurrencyConverter
) {
    suspend fun findBestMatch(
        receipt: ScannedReceipt,
        lookbackDays: Int = 7
    ): MatchResult = withContext(Dispatchers.Default) {
        // RCP-21: Skip matching for bank-statement receipts — their individual
        // transactions are already linked during statement processing.
        if (receipt.documentType == "BANK_STATEMENT") {
            return@withContext MatchResult.NoMatch
        }

        // Get candidate transactions from last N days
        val now = timeProvider.now()
        val startDate = receipt.parsedDate?.let { it - (lookbackDays * 86400000) } 
            ?: (now - (lookbackDays * 86400000))
        val endDate = receipt.parsedDate?.let { it + (lookbackDays * 86400000) } 
            ?: (now + (lookbackDays * 86400000))
        
        val candidates = expenseRepository.getExpensesBetween(startDate, endDate)
            .filter(::isReceiptCompatibleTransaction)
        
        if (candidates.isEmpty()) {
            return@withContext MatchResult.NoMatch
        }
        
        // Score each candidate
        val scored = mutableListOf<MatchCandidate>()
        for (transaction in candidates) {
            val score = calculateMatchScore(receipt, transaction)
            scored.add(MatchCandidate(transaction, score.first, score.second))
        }        
        // Find best match
        val best = scored.maxByOrNull { it.score }
        
        return@withContext when {
            best == null -> MatchResult.NoMatch
            best.score >= 0.95 -> MatchResult.AutoMatch(best.transaction, best.score)
            best.score >= 0.80 -> MatchResult.Suggested(best.transaction, best.score)
            else -> MatchResult.NoMatch
        }
    }
    
    private suspend fun calculateMatchScore(
        receipt: ScannedReceipt,
        transaction: Expense
    ): Pair<Double, MatchFactors> {
        // 1. Amount match (35% weight)
        // S12-031: Try currency conversion when currencies differ; fall back to penalty if unavailable
        val receiptAmount = receipt.parsedTotal ?: 0.0
        val receiptCurrency = receipt.currency
        val txCurrency = transaction.currency
        val currenciesMatch = receiptCurrency.equals(txCurrency, ignoreCase = true)
        val comparableReceiptAmount = if (!currenciesMatch && receiptAmount > 0) {
            currencyConverter.convert(receiptAmount, receiptCurrency, txCurrency)
                ?.convertedAmount ?: receiptAmount // fall back to raw if conversion unavailable
        } else {
            receiptAmount
        }
        val conversionAvailable = currenciesMatch || (comparableReceiptAmount != receiptAmount)
        val amountDiff = abs(comparableReceiptAmount - transaction.effectiveAmount)
        val amountScore = if (transaction.effectiveAmount > 0) {
            val rawScore = 1.0 - (amountDiff / transaction.effectiveAmount).coerceIn(0.0, 1.0)
            // Apply penalty only when currencies differ AND conversion was unavailable
            if (!currenciesMatch && !conversionAvailable) rawScore * 0.5 else rawScore
        } else {
            0.0
        }
        
        // 2. Merchant match (40% weight) - fuzzy string matching
        val receiptMerchant = receipt.parsedMerchant ?: ""
        val normalizedReceipt = normalizeMerchant(receiptMerchant)
        val normalizedTx = normalizeMerchant(transaction.merchant)
        val merchantScore = stringDistance.levenshteinSimilarity(normalizedReceipt, normalizedTx)
        
        // 3. Date proximity (20% weight) - within 48 hours ideal
        val receiptDate = receipt.parsedDate ?: receipt.createdAt
        val dateDiffHours = abs(receiptDate - transaction.date) / (1000.0 * 60 * 60)
        val dateScore = (1.0 - (dateDiffHours / 48.0)).coerceIn(0.0, 1.0)
        
        // 4. Transaction type (5% weight)
        val typeScore = if (isReceiptCompatibleTransaction(transaction)) {
            1.0
        } else {
            0.5
        }
        
        // Weighted sum
        val totalScore = (amountScore * 0.35 + merchantScore * 0.40 + 
                dateScore * 0.20 + typeScore * 0.05).coerceIn(0.0, 1.0)
        
        return Pair(totalScore, MatchFactors(amountScore, merchantScore, dateScore, typeScore))
    }
    
    private fun normalizeMerchant(merchant: String): String {
        return Normalizer.normalize(merchant, Normalizer.Form.NFKD)
            .lowercase(Locale.ROOT)
            .replace("\\p{M}+".toRegex(), "")
            .replace("[^\\p{L}\\p{N}]".toRegex(), "")
            .trim()
    }

    private fun isReceiptCompatibleTransaction(transaction: Expense): Boolean {
        if (transaction.effectiveAmount <= 0.0) return false

        return transaction.transactionType.name == ExpenseDao.SPENDING_TYPE &&
            transaction.transactionType == TransactionType.PURCHASE
    }
}
