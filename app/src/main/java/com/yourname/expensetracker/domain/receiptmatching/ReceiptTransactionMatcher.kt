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

@Singleton
class ReceiptTransactionMatcher @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val merchantNormalizer: MerchantNormalizer,
    private val stringDistance: StringDistanceUtils,
    private val timeProvider: TimeProvider,
    private val receiptLinkService: ReceiptLinkService
) {
    suspend fun findBestMatch(
        receipt: ScannedReceipt,
        lookbackDays: Int = 7
    ): MatchResult = withContext(Dispatchers.Default) {
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
    
    private fun calculateMatchScore(
        receipt: ScannedReceipt,
        transaction: Expense
    ): Pair<Double, MatchFactors> {
        // 1. Amount match (35% weight)
        val receiptAmount = receipt.parsedTotal ?: 0.0
        val amountDiff = abs(receiptAmount - transaction.effectiveAmount)
        val amountScore = if (transaction.effectiveAmount > 0) {
            1.0 - (amountDiff / transaction.effectiveAmount).coerceIn(0.0, 1.0)
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
