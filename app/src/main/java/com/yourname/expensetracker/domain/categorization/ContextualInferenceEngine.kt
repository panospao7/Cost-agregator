package com.yourname.expensetracker.domain.categorization

import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

data class ContextPrediction(
    val categoryName: String,
    val confidence: Double,
    val reason: String
)

@Singleton
class ContextualInferenceEngine @Inject constructor(
    private val timeProvider: TimeProvider
) {

    // TODO (C13): Expand CategorizationContext with additional signals:
    // - Day-of-week (already partially: dayOfWeek param on inferFromContext)
    // - Time-of-day (already present via hour)
    // - Geographic location (GPS-based merchant category hints)
    // - Recent spending patterns (last-N-transactions category distribution)
    // - Recurring expense detection (known recurring vs one-off)
    // - Notification source app package (already partially: notificationSource param)
    // - Merchant proximity to known locations (via MerchantLocationRepository)
    // - User correction history (which categories the user manually reassigns)
    // Each signal should feed into a weighted scoring model rather than
    // flat confidence boosts.
    
    companion object {
        // Confidence thresholds
        const val MIN_CONFIDENCE_THRESHOLD = 0.45
        
        // Amount thresholds
        const val SMALL_AMOUNT = 3.0
        const val MEDIUM_AMOUNT = 8.0
        const val LARGE_AMOUNT = 20.0
        const val XL_AMOUNT = 50.0
        const val XXL_AMOUNT = 100.0
        const val GROCERY_MIN = 20.0
        const val GROCERY_MAX = 150.0
        
        // Confidence boosts
        const val BOOST_TINY = 0.25
        const val BOOST_SMALL = 0.40
        const val BOOST_MEDIUM = 0.35
        const val BOOST_LARGE = 0.30
        const val BOOST_XL = 0.25
        const val BOOST_XXL = 0.35
        
        // Grocery-specific boosts
        const val BOOST_GROCERY_RANGE = 0.45
        const val BOOST_WEEKEND_GROCERY = 0.25
        
        // Time-based boosts
        const val BOOST_BREAKFAST = 0.35
        const val BOOST_LUNCH = 0.40
        const val BOOST_MORNING = 0.20
        const val BOOST_DINNER = 0.30
        const val BOOST_NIGHT = 0.25
        const val BOOST_AFTERNOON = 0.15
        const val BOOST_WEEKEND_ENTERTAINMENT = 0.20
        const val BOOST_WEEKEND_SHOPPING = 0.15
        const val BOOST_WEEKEND_FOOD = 0.10
        
        // Source boosts
        const val BOOST_REVOLUT = 0.10
        const val BOOST_BANK = 0.10
        const val BOOST_WALLET = 0.15
    }
    
    private val GREEK_SURNAME_ENDINGS = listOf(
        "is", "as", "os", "ou", "akis", "idis", "idis", "opoulos", 
        "atos", "itou", "ellis", "eas", "oudis", "akos", "ikos",
        "aros", "oy", "iti", "ates",
        // Feminine endings
        "poulou", "dou", "idou", "opoulou", "aki", "ara", "ea"
    )
    
    private val GREEK_SURNAME_PREFIXES = listOf(
        "papad", "nikola", "georg", "constantin", "ioann", "athanasi",
        "michae", "dimitri", "stefan", "vasilei", "pavl", "alexandr",
        "makar", "petr", "antoni", "kostas", "giann", "theodor",
        "lysandr", "diamant", "chatz", "kats", "mavrid", "mixail"
    )
    
    private val BUSINESS_INDICATORS = listOf(
        "shop", "store", "cafe", "restaurant", "bar", "grill",
        "pizza", "bakery", "market", "mini market", "kafeneio",
        "taverna", "souvlaki", "gyros", "kebab", "ltd", "sa", "ae", "ab"
    )
    
    fun isLikelySurname(merchant: String): Boolean {
        val normalized = merchant.lowercase().trim()
        val words = normalized.split(" ").filter { it.length >= 3 }
        
        if (words.isEmpty()) return false
        
        if (words.size == 1) {
            val word = words[0]
            return GREEK_SURNAME_ENDINGS.any { word.endsWith(it) } ||
                   GREEK_SURNAME_PREFIXES.any { word.startsWith(it) }
        }
        
        // For multi-word input, require all terms to look surname-like and reject business terms.
        return words.none { it in BUSINESS_INDICATORS } &&
            words.all { word ->
                GREEK_SURNAME_ENDINGS.any { word.endsWith(it) } ||
                    GREEK_SURNAME_PREFIXES.any { word.startsWith(it) }
            }
    }
    
    fun inferFromContext(
        amount: Double,
        timestamp: Long,
        dayOfWeek: Int? = null,
        notificationSource: String? = null
    ): ContextPrediction? {
        
        // C18-FIXED: java.time replaces Calendar.getInstance().
        val zoned = java.time.Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault())
        val hour = zoned.hour
        val day = dayOfWeek ?: zoned.dayOfWeek.value
        
        val scores = mutableMapOf<String, Double>()
        
        // Amount-based inference
        when {
            amount < SMALL_AMOUNT -> {
                scores["Food"] = (scores["Food"] ?: 0.0) + BOOST_TINY
            }
            amount in SMALL_AMOUNT..MEDIUM_AMOUNT -> {
                scores["Food"] = (scores["Food"] ?: 0.0) + BOOST_SMALL
            }
            amount in MEDIUM_AMOUNT..LARGE_AMOUNT -> {
                scores["Food"] = (scores["Food"] ?: 0.0) + BOOST_MEDIUM
                scores["Transport"] = (scores["Transport"] ?: 0.0) + BOOST_SMALL
            }
            amount in LARGE_AMOUNT..XL_AMOUNT -> {
                scores["Shopping"] = (scores["Shopping"] ?: 0.0) + BOOST_LARGE
                scores["Food"] = (scores["Food"] ?: 0.0) + BOOST_TINY
            }
            amount in XL_AMOUNT..XXL_AMOUNT -> {
                scores["Shopping"] = (scores["Shopping"] ?: 0.0) + BOOST_XXL
                scores["Transport"] = (scores["Transport"] ?: 0.0) + BOOST_LARGE
            }
            amount > XXL_AMOUNT -> {
                scores["Shopping"] = (scores["Shopping"] ?: 0.0) + BOOST_XXL
                scores["Transport"] = (scores["Transport"] ?: 0.0) + BOOST_XXL
            }
        }
        
        // Grocery-specific amount bracket (€20 - €150)
        if (amount in GROCERY_MIN..GROCERY_MAX) {
            scores["Groceries"] = (scores["Groceries"] ?: 0.0) + BOOST_GROCERY_RANGE
        }
        
        // Time-based inference
        when (hour) {
            in 6..9 -> {
                scores["Food"] = (scores["Food"] ?: 0.0) + BOOST_BREAKFAST
                scores["Transport"] = (scores["Transport"] ?: 0.0) + BOOST_LARGE
            }
            in 10..11 -> {
                scores["Food"] = (scores["Food"] ?: 0.0) + BOOST_MORNING
            }
            in 12..14 -> {
                scores["Food"] = (scores["Food"] ?: 0.0) + BOOST_LUNCH
                scores["Transport"] = (scores["Transport"] ?: 0.0) + BOOST_TINY
            }
            in 15..17 -> {
                scores["Shopping"] = (scores["Shopping"] ?: 0.0) + BOOST_AFTERNOON
            }
            in 18..21 -> {
                scores["Food"] = (scores["Food"] ?: 0.0) + BOOST_DINNER
                scores["Entertainment"] = (scores["Entertainment"] ?: 0.0) + BOOST_TINY
            }
            in 22..23 -> {
                scores["Entertainment"] = (scores["Entertainment"] ?: 0.0) + BOOST_NIGHT
                scores["Food"] = (scores["Food"] ?: 0.0) + BOOST_TINY
            }
        }
        
        // Day-based inference
        when (day) {
            java.time.DayOfWeek.SATURDAY.value, java.time.DayOfWeek.SUNDAY.value -> {
                scores["Entertainment"] = (scores["Entertainment"] ?: 0.0) + BOOST_WEEKEND_ENTERTAINMENT
                scores["Shopping"] = (scores["Shopping"] ?: 0.0) + BOOST_WEEKEND_SHOPPING
                scores["Food"] = (scores["Food"] ?: 0.0) + BOOST_WEEKEND_FOOD
                // Weekend grocery runs
                if (amount in SMALL_AMOUNT..GROCERY_MAX) {
                    scores["Groceries"] = (scores["Groceries"] ?: 0.0) + BOOST_WEEKEND_GROCERY
                }
            }
        }
        
        // Source-based inference
        when (notificationSource) {
            "com.revolut.revolut" -> {
                scores["Food"] = (scores["Food"] ?: 0.0) + BOOST_REVOLUT
            }
            "gr.nbg.mobilebanking", "com.eurobank.mobile", "gr.alpha.mobile" -> {
                scores["Transport"] = (scores["Transport"] ?: 0.0) + BOOST_BANK
            }
            "com.google.android.apps.walletnfcrel" -> {
                scores["Shopping"] = (scores["Shopping"] ?: 0.0) + BOOST_WALLET
            }
        }
        
        val best = scores.maxByOrNull { it.value } ?: return null
        
        if (best.value >= MIN_CONFIDENCE_THRESHOLD) {
            return ContextPrediction(
                categoryName = best.key,
                confidence = best.value,
                reason = buildReason(hour, amount, day, notificationSource)
            )
        }
        
        return null
    }
    
    private fun buildReason(
        hour: Int,
        amount: Double,
        day: Int,
        source: String?
    ): String {
        val reasons = mutableListOf<String>()
        
        when {
            amount < SMALL_AMOUNT -> reasons.add("small amount")
            amount < LARGE_AMOUNT -> reasons.add("medium amount")
            amount > XL_AMOUNT -> reasons.add("large amount")
        }
        
        when (hour) {
            in 6..9 -> reasons.add("morning")
            in 12..14 -> reasons.add("lunch time")
            in 18..21 -> reasons.add("evening")
            in 22..23 -> reasons.add("night")
        }
        
        when (day) {
            java.time.DayOfWeek.SATURDAY.value, java.time.DayOfWeek.SUNDAY.value -> reasons.add("weekend")
        }
        
        source?.let {
            when {
                it.contains("revolut") -> reasons.add("revolut")
                it.contains("nbg") || it.contains("alpha") || it.contains("eurobank") -> reasons.add("bank")
            }
        }
        
        return reasons.joinToString(", ").ifEmpty { "context inference" }
    }
}
