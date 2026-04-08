package com.yourname.expensetracker.domain.intelligence.ml

import com.yourname.expensetracker.data.database.entity.Expense
import java.util.Calendar

/**
 * Extracts features from expenses and notifications for ML classification.
 */
class FeatureExtractor {

    companion object {
        private val STOP_WORDS = setOf(
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
            "of", "with", "by", "from", "as", "is", "was", "are", "were", "been",
            // Greek stop words
            "και", "το", "η", "τα", "του", "την", "των", "με", "σε", "για"
        )
        
        private val WORD_PATTERN = Regex("""[\p{L}\p{N}]+""")
    }

    /**
     * Extract features from an expense.
     */
    fun extractFromExpense(
        expense: Expense,
        notificationTitle: String? = null,
        notificationText: String? = null,
        packageName: String = ""
    ): ExpenseFeatures {
        val calendar = Calendar.getInstance().apply { timeInMillis = expense.date }
        
        val allText = listOfNotNull(
            expense.merchant,
            notificationTitle,
            notificationText
        ).joinToString(" ")

        val tokens = tokenize(allText)
        
        return ExpenseFeatures(
            merchantName = expense.merchant,
            merchantTokens = tokens,
            notificationTitle = notificationTitle,
            notificationText = notificationText,
            allText = allText,
            amount = expense.amount,
            amountBucket = AmountBucket.fromAmount(expense.amount),
            dayOfWeek = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7, // 0 = Monday
            hourOfDay = calendar.get(Calendar.HOUR_OF_DAY),
            isWeekend = calendar.get(Calendar.DAY_OF_WEEK) in listOf(Calendar.SATURDAY, Calendar.SUNDAY),
            sourcePackage = packageName
        )
    }

    /**
     * Extract features from notification text (before expense is created).
     *
     * @param eventTimeMillis explicit event timestamp in millis since epoch.
     *   Callers MUST provide an explicit value from an injected [TimeProvider]
     *   or from the notification timestamp — never from [System.currentTimeMillis]
     *   directly. The sentinel value 0L signals a missing/unset timestamp; any
     *   caller that receives 0L back in the extracted features has failed to
     *   supply an explicit boundary timestamp.
     */
    fun extractFromNotification(
        title: String?,
        text: String?,
        packageName: String,
        amount: Double,
        merchant: String,
        eventTimeMillis: Long = 0L // sentinel — callers MUST supply an explicit boundary timestamp
    ): ExpenseFeatures {
        val calendar = Calendar.getInstance().apply { timeInMillis = eventTimeMillis }
        
        val allText = listOfNotNull(title, text, merchant).joinToString(" ")
        val tokens = tokenize(allText)

        return ExpenseFeatures(
            merchantName = merchant,
            merchantTokens = tokens,
            notificationTitle = title,
            notificationText = text,
            allText = allText,
            amount = amount,
            amountBucket = AmountBucket.fromAmount(amount),
            dayOfWeek = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7,
            hourOfDay = calendar.get(Calendar.HOUR_OF_DAY),
            isWeekend = calendar.get(Calendar.DAY_OF_WEEK) in listOf(Calendar.SATURDAY, Calendar.SUNDAY),
            sourcePackage = packageName
        )
    }

    /**
     * Tokenize text into words.
     */
    fun tokenize(text: String): List<String> {
        val normalized = text
            .lowercase()
            .replace("&", " ")
            .replace("/", " ")
            .replace("-", " ")
            .replace("'", "")
        return WORD_PATTERN.findAll(normalized)
            .map { it.value }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .toList()
    }
}
