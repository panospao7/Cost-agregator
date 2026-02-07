

# Phase 2: On-Device ML — Complete Implementation

Phase 2 adds intelligence that learns from the user corrections collected in Phase 1.

---

## Phase 2A: Naive Bayes On-Device Classifier

### `TransactionClassifier.kt`

```kotlin
// domain/intelligence/TransactionClassifier.kt
package com.yourname.expensetracker.domain.intelligence

import android.content.Context
import android.util.Log
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln

/**
 * Lightweight on-device text classifier using Naive Bayes.
 * No TensorFlow needed. Learns from user corrections.
 *
 * Trained automatically from UserCorrection records:
 * - wasApproved=true → positive (IS_TRANSACTION)
 * - wasRejected=true → negative (NOT_TRANSACTION)
 *
 * After ~20+ corrections it becomes remarkably accurate
 * for that specific user's notification patterns.
 */
@Singleton
class TransactionClassifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userCorrectionDao: UserCorrectionDao
) {
    companion object {
        private const val TAG = "TxClassifier"
        private const val MODEL_FILE = "naive_bayes_model.json"
        private const val MIN_TRAINING_SAMPLES = 20
        private const val LAPLACE_SMOOTHING = 1.0
    }

    // Word → how many times it appeared in REAL transactions
    private val positiveWordCounts = mutableMapOf<String, Int>()
    // Word → how many times it appeared in NON-transactions
    private val negativeWordCounts = mutableMapOf<String, Int>()
    private var totalPositive = 0
    private var totalNegative = 0
    private var vocabularySize = 0

    // Bigram counts for better accuracy
    private val positiveBigramCounts = mutableMapOf<String, Int>()
    private val negativeBigramCounts = mutableMapOf<String, Int>()

    private val mutex = Mutex()
    private var isLoaded = false
    private var lastTrainingCount = 0

    /**
     * Initialize: load persisted model or train from corrections
     */
    suspend fun initialize() {
        mutex.withLock {
            if (isLoaded) return

            // Try loading persisted model first
            if (loadFromDisk()) {
                isLoaded = true
                Log.d(TAG, "Loaded model from disk: +$totalPositive/-$totalNegative samples")
            }

            // Check if we need to retrain (new corrections available)
            val correctionCount = userCorrectionDao.getCount()
            if (correctionCount > lastTrainingCount && correctionCount >= MIN_TRAINING_SAMPLES) {
                retrainFromCorrections()
            }

            isLoaded = true
        }
    }

    /**
     * Predict whether text is a transaction notification.
     * Returns probability 0.0 (not transaction) to 1.0 (definitely transaction).
     * Returns 0.5 (uncertain) if not enough training data.
     */
    suspend fun predict(text: String): Float {
        if (!isLoaded) initialize()

        if (totalPositive + totalNegative < MIN_TRAINING_SAMPLES) {
            return 0.5f // Not enough data to make predictions
        }

        val features = extractFeatures(text)
        return mutex.withLock {
            calculateProbability(features)
        }
    }

    /**
     * Train on a single example (called when user approves/rejects)
     */
    suspend fun train(text: String, isTransaction: Boolean) {
        mutex.withLock {
            val features = extractFeatures(text)
            addTrainingSample(features, isTransaction)
            saveToDisk()
        }
    }

    /**
     * Retrain the entire model from all stored user corrections
     */
    suspend fun retrainFromCorrections() {
        val corrections = userCorrectionDao.getAll()
        if (corrections.size < MIN_TRAINING_SAMPLES) {
            Log.d(TAG, "Not enough corrections to train: ${corrections.size}/$MIN_TRAINING_SAMPLES")
            return
        }

        mutex.withLock {
            // Reset model
            positiveWordCounts.clear()
            negativeWordCounts.clear()
            positiveBigramCounts.clear()
            negativeBigramCounts.clear()
            totalPositive = 0
            totalNegative = 0

            // Train from all corrections
            for (correction in corrections) {
                val text = buildTrainingText(correction)
                if (text.isNotBlank()) {
                    val features = extractFeatures(text)
                    if (correction.wasRejected) {
                        addTrainingSample(features, isTransaction = false)
                    } else if (correction.wasApproved) {
                        addTrainingSample(features, isTransaction = true)
                    }
                }
            }

            vocabularySize = (positiveWordCounts.keys + negativeWordCounts.keys).toSet().size
            lastTrainingCount = corrections.size

            saveToDisk()
            Log.d(TAG, "Retrained from ${corrections.size} corrections: +$totalPositive/-$totalNegative")
        }
    }

    /**
     * Get model statistics
     */
    fun getStats(): ClassifierStats {
        return ClassifierStats(
            totalPositive = totalPositive,
            totalNegative = totalNegative,
            vocabularySize = vocabularySize,
            isReady = totalPositive + totalNegative >= MIN_TRAINING_SAMPLES
        )
    }

    // === Internal Methods ===

    private fun addTrainingSample(features: FeatureSet, isTransaction: Boolean) {
        if (isTransaction) {
            totalPositive++
            features.words.forEach {
                positiveWordCounts[it] = (positiveWordCounts[it] ?: 0) + 1
            }
            features.bigrams.forEach {
                positiveBigramCounts[it] = (positiveBigramCounts[it] ?: 0) + 1
            }
        } else {
            totalNegative++
            features.words.forEach {
                negativeWordCounts[it] = (negativeWordCounts[it] ?: 0) + 1
            }
            features.bigrams.forEach {
                negativeBigramCounts[it] = (negativeBigramCounts[it] ?: 0) + 1
            }
        }
        vocabularySize = (positiveWordCounts.keys + negativeWordCounts.keys).toSet().size
    }

    private fun calculateProbability(features: FeatureSet): Float {
        val total = totalPositive + totalNegative
        if (total == 0) return 0.5f

        // Prior probabilities
        var logProbPos = ln(totalPositive.toDouble() / total)
        var logProbNeg = ln(totalNegative.toDouble() / total)

        // Word likelihoods with Laplace smoothing
        val vocabSize = vocabularySize.coerceAtLeast(1)

        for (word in features.words) {
            val posCount = (positiveWordCounts[word] ?: 0) + LAPLACE_SMOOTHING
            val negCount = (negativeWordCounts[word] ?: 0) + LAPLACE_SMOOTHING
            val posDenom = totalPositive + vocabSize * LAPLACE_SMOOTHING
            val negDenom = totalNegative + vocabSize * LAPLACE_SMOOTHING

            logProbPos += ln(posCount / posDenom)
            logProbNeg += ln(negCount / negDenom)
        }

        // Bigram likelihoods (weighted less than unigrams)
        val bigramWeight = 0.5
        val bigramVocabSize = (positiveBigramCounts.keys + negativeBigramCounts.keys).toSet().size.coerceAtLeast(1)

        for (bigram in features.bigrams) {
            val posCount = (positiveBigramCounts[bigram] ?: 0) + LAPLACE_SMOOTHING
            val negCount = (negativeBigramCounts[bigram] ?: 0) + LAPLACE_SMOOTHING
            val posDenom = totalPositive + bigramVocabSize * LAPLACE_SMOOTHING
            val negDenom = totalNegative + bigramVocabSize * LAPLACE_SMOOTHING

            logProbPos += bigramWeight * ln(posCount / posDenom)
            logProbNeg += bigramWeight * ln(negCount / negDenom)
        }

        // Sigmoid normalization: convert log-odds to probability
        val logOdds = logProbPos - logProbNeg
        val clampedLogOdds = logOdds.coerceIn(-20.0, 20.0) // Prevent overflow
        return (1.0 / (1.0 + Math.exp(-clampedLogOdds))).toFloat()
    }

    /**
     * Extract features from notification text.
     * Includes unigrams, bigrams, and meta-features.
     */
    private fun extractFeatures(text: String): FeatureSet {
        val normalized = text.lowercase()
            .replace(Regex("[^a-zα-ωά-ώ0-9€$£ ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val words = normalized.split(" ")
            .filter { it.length >= 2 }
            .toMutableList()

        // Add meta-features based on patterns
        if (Regex("""\d+[.,]\d{2}""").containsMatchIn(text)) {
            words.add("__HAS_DECIMAL_AMOUNT__")
        }
        if (Regex("""[€$£]""").containsMatchIn(text)) {
            words.add("__HAS_CURRENCY_SYMBOL__")
        }
        if (Regex("""(?i)(EUR|USD|GBP)""").containsMatchIn(text)) {
            words.add("__HAS_CURRENCY_CODE__")
        }
        if (Regex("""(?i)(paid|payment|purchase|charged|debit)""").containsMatchIn(text)) {
            words.add("__HAS_PAYMENT_KEYWORD__")
        }
        if (Regex("""(?i)(πληρωμ|αγορ|χρέωσ|συναλλαγ)""").containsMatchIn(text)) {
            words.add("__HAS_GREEK_PAYMENT_KEYWORD__")
        }
        if (Regex("""(?i)(offer|discount|promo|sale|free|δωρεάν|προσφορά|έκπτωση)""").containsMatchIn(text)) {
            words.add("__HAS_PROMO_KEYWORD__")
        }
        if (Regex("""(?i)(otp|code|verify|κωδικός)""").containsMatchIn(text)) {
            words.add("__HAS_OTP_KEYWORD__")
        }
        if (Regex("""(?i)(balance|υπόλοιπο)""").containsMatchIn(text)) {
            words.add("__HAS_BALANCE_KEYWORD__")
        }

        // Build bigrams from actual words (not meta-features)
        val actualWords = normalized.split(" ").filter { it.length >= 2 }
        val bigrams = if (actualWords.size >= 2) {
            actualWords.zipWithNext().map { (a, b) -> "${a}_$b" }
        } else {
            emptyList()
        }

        return FeatureSet(words, bigrams)
    }

    private fun buildTrainingText(
        correction: com.yourname.expensetracker.data.database.entity.UserCorrection
    ): String {
        return listOfNotNull(
            correction.notificationTitle,
            correction.notificationText,
            correction.originalMerchant
        ).joinToString(" ")
    }

    // === Persistence ===

    private fun saveToDisk() {
        try {
            val json = JSONObject().apply {
                put("totalPositive", totalPositive)
                put("totalNegative", totalNegative)
                put("vocabularySize", vocabularySize)
                put("lastTrainingCount", lastTrainingCount)

                put("positiveWords", JSONObject().apply {
                    positiveWordCounts.forEach { (k, v) -> put(k, v) }
                })
                put("negativeWords", JSONObject().apply {
                    negativeWordCounts.forEach { (k, v) -> put(k, v) }
                })
                put("positiveBigrams", JSONObject().apply {
                    positiveBigramCounts.forEach { (k, v) -> put(k, v) }
                })
                put("negativeBigrams", JSONObject().apply {
                    negativeBigramCounts.forEach { (k, v) -> put(k, v) }
                })
            }

            File(context.filesDir, MODEL_FILE).writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save model", e)
        }
    }

    private fun loadFromDisk(): Boolean {
        return try {
            val file = File(context.filesDir, MODEL_FILE)
            if (!file.exists()) return false

            val json = JSONObject(file.readText())
            totalPositive = json.getInt("totalPositive")
            totalNegative = json.getInt("totalNegative")
            vocabularySize = json.optInt("vocabularySize", 0)
            lastTrainingCount = json.optInt("lastTrainingCount", 0)

            val posWords = json.getJSONObject("positiveWords")
            positiveWordCounts.clear()
            posWords.keys().forEach { key ->
                positiveWordCounts[key] = posWords.getInt(key)
            }

            val negWords = json.getJSONObject("negativeWords")
            negativeWordCounts.clear()
            negWords.keys().forEach { key ->
                negativeWordCounts[key] = negWords.getInt(key)
            }

            // Bigrams (optional — might not exist in older models)
            json.optJSONObject("positiveBigrams")?.let { posBi ->
                positiveBigramCounts.clear()
                posBi.keys().forEach { key ->
                    positiveBigramCounts[key] = posBi.getInt(key)
                }
            }
            json.optJSONObject("negativeBigrams")?.let { negBi ->
                negativeBigramCounts.clear()
                negBi.keys().forEach { key ->
                    negativeBigramCounts[key] = negBi.getInt(key)
                }
            }

            vocabularySize = (positiveWordCounts.keys + negativeWordCounts.keys).toSet().size
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            false
        }
    }
}

data class FeatureSet(
    val words: List<String>,
    val bigrams: List<String>
)

data class ClassifierStats(
    val totalPositive: Int,
    val totalNegative: Int,
    val vocabularySize: Int,
    val isReady: Boolean
)
```

---

## Phase 2B: Enhanced Merchant Normalizer

Update the existing `MerchantNormalizer.kt` with fuzzy matching capabilities:

```kotlin
// domain/intelligence/MerchantNormalizer.kt
package com.yourname.expensetracker.domain.intelligence

import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Smart merchant normalization that handles variations:
 * "SKLAVENITIS ATH001" → "SKLAVENITIS"
 * "SHELL STATION 2345" → "SHELL"
 * "STARBUCKS #1234 ATHENS" → "STARBUCKS"
 *
 * Also applies user corrections learned over time.
 */
@Singleton
class MerchantNormalizer @Inject constructor(
    private val userCorrectionDao: UserCorrectionDao
) {
    // Suffixes/noise to strip
    private val NOISE_PATTERNS = listOf(
        Regex("""\s*#?\d{3,}.*$"""),
        Regex("""\s*\*+\d+.*$"""),
        Regex("""\s+(?:GR|ATH|THES|ATHENS|THESSALONIKI|THESSALONIK).*$""", RegexOption.IGNORE_CASE),
        Regex("""\s+(?:BRANCH|STORE|SHOP|KATAST|ΚΑΤΑΣΤ)\s*\d*$""", RegexOption.IGNORE_CASE),
        Regex("""\s+\d{1,2}/\d{1,2}/?\d{0,4}$"""),
        Regex("""\s+(?:SA|AE|ΑΕ|EPE|ΕΠΕ|IKE|ΙΚΕ|LTD|GMBH|SRL|OE|ΟΕ|EE|ΕΕ)\s*$""", RegexOption.IGNORE_CASE),
        Regex("""\s+(?:CARD|VISA|MASTER|MC|AMEX)\s*\**\d*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*-\s*\d+$"""),  // trailing dash + numbers
        Regex("""\s+\d{4,}$"""),   // trailing long number
    )

    // Known merchant aliases (common variations → canonical name)
    private val KNOWN_ALIASES = mapOf(
        "SKLAVENITIS" to "Sklavenitis",
        "ΣΚΛΑΒΕΝΙΤΗΣ" to "Sklavenitis",
        "AB VASILOPOULOS" to "AB Vassilopoulos",
        "AB ΒΑΣΙΛΟΠΟΥΛΟΣ" to "AB Vassilopoulos",
        "LIDL" to "Lidl",
        "STARBUCKS" to "Starbucks",
        "SHELL" to "Shell",
        "BP" to "BP",
        "EFOOD" to "e-food",
        "WOLT" to "Wolt",
        "NETFLIX" to "Netflix",
        "SPOTIFY" to "Spotify",
        "AMAZON" to "Amazon",
        "UBER" to "Uber",
        "BOLT" to "Bolt",
        "COSMOTE" to "Cosmote",
        "VODAFONE" to "Vodafone",
        "WIND" to "Wind",
        "DEH" to "DEH",
        "ΔΕΗ" to "DEH",
        "EYDAP" to "EYDAP",
        "ΕΥΔΑΠ" to "EYDAP",
    )

    fun normalize(merchant: String): String {
        var result = merchant.uppercase().trim()

        // Apply noise removal patterns
        for (pattern in NOISE_PATTERNS) {
            result = result.replace(pattern, "")
        }

        result = result
            .replace(Regex("[^A-ZΑ-Ω0-9 &]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        return result
    }

    /**
     * Full normalization: strip noise, apply known aliases, apply user corrections
     */
    suspend fun normalizeAndCorrect(merchant: String): String {
        val stripped = normalize(merchant)

        // Check known aliases
        for ((key, canonical) in KNOWN_ALIASES) {
            if (stripped.contains(key)) {
                return canonical
            }
        }

        // Check user corrections
        val userCorrection = userCorrectionDao.getMostCommonMerchantCorrection(stripped)
        if (userCorrection != null) {
            return userCorrection
        }

        // Return cleaned version with proper casing
        return toTitleCase(stripped)
    }

    /**
     * Apply user corrections only (for pipeline use)
     */
    suspend fun applyUserCorrections(merchant: String): String {
        val normalized = normalize(merchant)

        // Check known aliases first
        for ((key, canonical) in KNOWN_ALIASES) {
            if (normalized.contains(key)) {
                return canonical
            }
        }

        val corrected = userCorrectionDao.getMostCommonMerchantCorrection(normalized)
        return corrected ?: merchant
    }

    /**
     * Jaccard similarity for matching merchant names
     */
    fun similarity(a: String, b: String): Float {
        val na = normalize(a)
        val nb = normalize(b)
        if (na == nb) return 1.0f
        if (na.isEmpty() || nb.isEmpty()) return 0f
        if (na.contains(nb) || nb.contains(na)) return 0.9f

        // Word overlap (Jaccard)
        val wordsA = na.split(" ").toSet()
        val wordsB = nb.split(" ").toSet()
        val intersection = wordsA.intersect(wordsB)
        val union = wordsA.union(wordsB)
        return if (union.isNotEmpty()) intersection.size.toFloat() / union.size else 0f
    }

    /**
     * Levenshtein distance for close matches
     */
    fun levenshteinDistance(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,       // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        return dp[m][n]
    }

    /**
     * Normalized Levenshtein similarity (0.0 to 1.0)
     */
    fun levenshteinSimilarity(a: String, b: String): Float {
        val na = normalize(a)
        val nb = normalize(b)
        if (na == nb) return 1.0f
        val maxLen = maxOf(na.length, nb.length)
        if (maxLen == 0) return 1.0f
        return 1.0f - levenshteinDistance(na, nb).toFloat() / maxLen
    }

    /**
     * Find best matching merchant name from a list
     */
    fun findBestMatch(merchant: String, candidates: List<String>, threshold: Float = 0.7f): String? {
        var bestMatch: String? = null
        var bestScore = 0f

        for (candidate in candidates) {
            val jaccardScore = similarity(merchant, candidate)
            val levenScore = levenshteinSimilarity(merchant, candidate)
            // Weighted combination
            val score = jaccardScore * 0.4f + levenScore * 0.6f

            if (score > bestScore && score >= threshold) {
                bestScore = score
                bestMatch = candidate
            }
        }

        return bestMatch
    }

    private fun toTitleCase(text: String): String {
        return text.split(" ").joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercaseChar() }
        }
    }
}
```

---

## Phase 2C: Integrate Classifier into Confidence Router

Update `ConfidenceRouter.kt` to use the classifier:

```kotlin
// domain/intelligence/ConfidenceRouter.kt
package com.yourname.expensetracker.domain.intelligence

import com.yourname.expensetracker.data.database.dao.SourceStatsDao
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import javax.inject.Inject
import javax.inject.Singleton

enum class RoutingDecision {
    AUTO_ACCEPT,
    NEEDS_REVIEW,
    AUTO_REJECT
}

data class RoutingResult(
    val decision: RoutingDecision,
    val adjustedConfidence: Float,
    val reason: String
)

@Singleton
class ConfidenceRouter @Inject constructor(
    private val sourceStatsDao: SourceStatsDao,
    private val userCorrectionDao: UserCorrectionDao,
    private val classifier: TransactionClassifier
) {
    companion object {
        const val AUTO_ACCEPT_THRESHOLD = 0.85f
        const val REVIEW_THRESHOLD = 0.50f
    }

    suspend fun route(
        parsed: ParsedTransaction,
        packageName: String,
        notificationText: String? = null
    ): RoutingResult {
        var adjustedConfidence = parsed.confidence
        val reasons = mutableListOf<String>()

        // 1. ML classifier prediction (if ready)
        if (notificationText != null) {
            val mlPrediction = classifier.predict(notificationText)
            val classifierStats = classifier.getStats()

            if (classifierStats.isReady) {
                // Blend parser confidence with ML prediction
                // Weight: 60% parser, 40% ML (ML gets more weight as it trains more)
                val mlWeight = calculateMlWeight(classifierStats)
                val parserWeight = 1.0f - mlWeight

                adjustedConfidence = parsed.confidence * parserWeight + mlPrediction * mlWeight

                if (mlPrediction < 0.3f) {
                    reasons.add("ML: ${(mlPrediction * 100).toInt()}% likely transaction")
                } else if (mlPrediction > 0.8f) {
                    reasons.add("ML: ${(mlPrediction * 100).toInt()}% confident")
                }
            }
        }

        // 2. Adjust based on source trust score
        val sourceStats = sourceStatsDao.getByPackage(packageName)
        if (sourceStats != null && sourceStats.totalNotifications > 10) {
            val trustModifier = calculateTrustModifier(sourceStats)
            adjustedConfidence *= trustModifier
            if (trustModifier < 0.9f) {
                reasons.add("Source trust: ${(sourceStats.trustScore * 100).toInt()}%")
            }
        }

        // 3. Adjust based on user correction history for this merchant
        val merchantRejectionRate = getMerchantRejectionRate(parsed.merchant)
        if (merchantRejectionRate > 0.5f) {
            adjustedConfidence *= 0.5f
            reasons.add("Merchant often rejected")
        }

        // 4. Package rejection rate
        val packageRejectionRate = getPackageRejectionRate(packageName)
        if (packageRejectionRate > 0.7f) {
            adjustedConfidence *= 0.3f
            reasons.add("Package mostly rejected")
        }

        // 5. Boost if user has previously approved similar transactions
        val previouslyApproved = hasPreviousApprovals(parsed.merchant, packageName)
        if (previouslyApproved) {
            adjustedConfidence = (adjustedConfidence * 1.2f).coerceAtMost(1.0f)
            reasons.add("Previously approved merchant")
        }

        // Clamp
        adjustedConfidence = adjustedConfidence.coerceIn(0f, 1f)

        // Route
        val decision = when {
            adjustedConfidence >= AUTO_ACCEPT_THRESHOLD -> RoutingDecision.AUTO_ACCEPT
            adjustedConfidence >= REVIEW_THRESHOLD -> RoutingDecision.NEEDS_REVIEW
            else -> RoutingDecision.AUTO_REJECT
        }

        val reason = if (reasons.isEmpty()) {
            "Base confidence: ${(parsed.confidence * 100).toInt()}%"
        } else {
            reasons.joinToString("; ")
        }

        return RoutingResult(decision, adjustedConfidence, reason)
    }

    /**
     * ML weight increases with more training data
     */
    private fun calculateMlWeight(stats: ClassifierStats): Float {
        val totalSamples = stats.totalPositive + stats.totalNegative
        return when {
            totalSamples < 20 -> 0f       // Not ready
            totalSamples < 50 -> 0.2f     // Low confidence in ML
            totalSamples < 100 -> 0.3f    // Growing confidence
            totalSamples < 200 -> 0.35f   // Moderate
            else -> 0.4f                   // Maxed out — never fully trust ML alone
        }
    }

    private fun calculateTrustModifier(stats: SourceStats): Float {
        return when {
            stats.isLikelySpam -> 0.2f
            stats.trustScore > 0.8f -> 1.1f
            stats.trustScore > 0.5f -> 1.0f
            stats.trustScore > 0.2f -> 0.8f
            else -> 0.5f
        }
    }

    private suspend fun getMerchantRejectionRate(merchant: String): Float {
        val corrections = userCorrectionDao.getAll()
        val merchantCorrections = corrections.filter {
            it.originalMerchant.equals(merchant, ignoreCase = true)
        }
        if (merchantCorrections.size < 3) return 0f
        val rejections = merchantCorrections.count { it.wasRejected }
        return rejections.toFloat() / merchantCorrections.size
    }

    private suspend fun getPackageRejectionRate(packageName: String): Float {
        val total = userCorrectionDao.getTotalCorrections(packageName)
        if (total < 5) return 0f
        val rejections = userCorrectionDao.getRejectionCount(packageName)
        return rejections.toFloat() / total
    }

    private suspend fun hasPreviousApprovals(merchant: String, packageName: String): Boolean {
        val corrections = userCorrectionDao.getByPackage(packageName)
        return corrections.any {
            it.originalMerchant.equals(merchant, ignoreCase = true) && it.wasApproved
        }
    }

    suspend fun ensureSourceStats(packageName: String) {
        val existing = sourceStatsDao.getByPackage(packageName)
        if (existing == null) {
            sourceStatsDao.upsert(SourceStats(packageName = packageName))
        }
    }
}
```

---

## Phase 2D: Update Repository to Feed Classifier

Update `NotificationRepository.kt` — add classifier training on approve/reject and pass notification text to router:

```kotlin
// data/repository/NotificationRepository.kt
// Only showing the CHANGED methods — rest stays the same as Phase 1

// Add to constructor:
// private val classifier: TransactionClassifier

// In processAndSave(), change the routing call:
```

Here's the updated `NotificationRepository.kt` with all classifier integration:

```kotlin
// data/repository/NotificationRepository.kt
package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.*
import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.MerchantNormalizer
import com.yourname.expensetracker.domain.intelligence.RoutingDecision
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRepository @Inject constructor(
    private val dao: RawNotificationDao,
    private val blockedPackageDao: BlockedPackageDao,
    private val expenseDao: ExpenseDao,
    private val merchantCategoryDao: MerchantCategoryDao,
    private val pendingReviewDao: PendingReviewDao,
    private val userCorrectionDao: UserCorrectionDao,
    private val sourceStatsDao: SourceStatsDao,
    private val parserRegistry: AppParserRegistry,
    private val categorizationEngine: CategorizationEngine,
    private val confidenceRouter: ConfidenceRouter,
    private val merchantNormalizer: MerchantNormalizer,
    private val classifier: TransactionClassifier
) {
    // === Notification access ===
    fun getAllNotifications(): Flow<List<RawNotification>> = dao.getAllFlow()
    fun getRecentNotifications(limit: Int = 100): Flow<List<RawNotification>> =
        dao.getRecentFlow(limit)
    fun getNotificationsByPackage(packageName: String): Flow<List<RawNotification>> =
        dao.getByPackageFlow(packageName)
    fun getAllPackages(): Flow<List<String>> = dao.getAllPackagesFlow()
    fun getCount(): Flow<Int> = dao.getCountFlow()
    suspend fun save(notification: RawNotification): Long = dao.insert(notification)
    suspend fun exists(packageName: String, timestamp: Long): Boolean =
        dao.exists(packageName, timestamp)

    // === Review Queue ===
    fun getPendingReviews(): Flow<List<PendingReview>> = pendingReviewDao.getPendingFlow()
    fun getPendingReviewCount(): Flow<Int> = pendingReviewDao.getPendingCountFlow()

    // === Source Stats ===
    fun getSourceStats(): Flow<List<SourceStats>> = sourceStatsDao.getAllFlow()

    // === Classifier Stats ===
    fun getClassifierStats() = classifier.getStats()

    // === Core Processing Pipeline ===
    suspend fun processAndSave(notification: RawNotification) {
        // 0. Deduplication check
        if (dao.exists(notification.packageName, notification.timestamp)) {
            return
        }

        // 1. Save raw notification
        val rawId = dao.insert(notification)

        // 2. Ensure source stats exist, then increment total
        confidenceRouter.ensureSourceStats(notification.packageName)
        sourceStatsDao.incrementTotal(notification.packageName)

        // 3. Initialize classifier if needed
        classifier.initialize()

        // 4. Try to parse
        val parsed = parserRegistry.parse(
            title = notification.title,
            text = notification.text,
            bigText = notification.bigText,
            subText = notification.subText,
            packageName = notification.packageName
        )

        if (parsed == null) {
            sourceStatsDao.incrementAutoRejected(notification.packageName)
            dao.markRelevance(rawId, false)
            return
        }

        // 5. Apply merchant normalization & user corrections
        val correctedMerchant = merchantNormalizer.applyUserCorrections(parsed.merchant)

        // 6. Build full notification text for ML classifier
        val fullNotificationText = listOfNotNull(
            notification.title,
            notification.text,
            notification.bigText
        ).joinToString(" ")

        // 7. Route through confidence system (now includes ML)
        val routingResult = confidenceRouter.route(
            parsed = parsed,
            packageName = notification.packageName,
            notificationText = fullNotificationText
        )

        when (routingResult.decision) {
            RoutingDecision.AUTO_ACCEPT -> {
                val isDuplicate = expenseDao.isDuplicate(
                    amount = parsed.amount,
                    merchant = correctedMerchant,
                    date = notification.timestamp
                )
                if (isDuplicate) {
                    dao.markRelevance(rawId, false)
                    return
                }

                val categoryId = categorizationEngine.categorize(correctedMerchant)

                val expense = Expense(
                    amount = parsed.amount,
                    currency = parsed.currency,
                    merchant = correctedMerchant,
                    transactionType = parsed.type,
                    date = notification.timestamp,
                    rawNotificationId = rawId,
                    categoryId = categoryId
                )
                expenseDao.insert(expense)
                dao.markRelevance(rawId, true)
                sourceStatsDao.incrementAccepted(notification.packageName)

                // Train classifier: auto-accepted = positive example
                classifier.train(fullNotificationText, isTransaction = true)
            }

            RoutingDecision.NEEDS_REVIEW -> {
                val suggestedCategoryId = categorizationEngine.categorize(correctedMerchant)

                val review = PendingReview(
                    rawNotificationId = rawId,
                    suggestedAmount = parsed.amount,
                    suggestedCurrency = parsed.currency,
                    suggestedMerchant = correctedMerchant,
                    suggestedType = parsed.type.name,
                    suggestedCategoryId = suggestedCategoryId,
                    confidence = routingResult.adjustedConfidence,
                    packageName = notification.packageName,
                    notificationTitle = notification.title,
                    notificationText = notification.text ?: notification.bigText
                )
                pendingReviewDao.insert(review)
                sourceStatsDao.incrementPending(notification.packageName)
            }

            RoutingDecision.AUTO_REJECT -> {
                dao.markRelevance(rawId, false)
                sourceStatsDao.incrementAutoRejected(notification.packageName)

                // Train classifier: auto-rejected by low confidence = negative example
                // But only if parser DID find something (to avoid training on non-parsed)
                classifier.train(fullNotificationText, isTransaction = false)
            }
        }
    }

    // === Review Actions ===

    suspend fun approveReview(
        reviewId: Long,
        finalAmount: Double? = null,
        finalMerchant: String? = null,
        finalCategoryId: Long? = null
    ) {
        val review = pendingReviewDao.getById(reviewId) ?: return

        val amount = finalAmount ?: review.suggestedAmount
        val merchant = finalMerchant ?: review.suggestedMerchant
        val categoryId = finalCategoryId ?: review.suggestedCategoryId
        val type = try {
            TransactionType.valueOf(review.suggestedType)
        } catch (e: Exception) {
            TransactionType.PURCHASE
        }

        val isDuplicate = expenseDao.isDuplicate(
            amount = amount,
            merchant = merchant,
            date = review.createdAt
        )
        if (!isDuplicate) {
            val expense = Expense(
                amount = amount,
                currency = review.suggestedCurrency,
                merchant = merchant,
                transactionType = type,
                date = review.createdAt,
                rawNotificationId = review.rawNotificationId,
                categoryId = categoryId
            )
            expenseDao.insert(expense)
        }

        pendingReviewDao.updateStatus(reviewId, "APPROVED")
        dao.markRelevance(review.rawNotificationId, true)
        sourceStatsDao.incrementAccepted(review.packageName)

        // Record user correction
        val correction = UserCorrection(
            packageName = review.packageName,
            originalMerchant = review.suggestedMerchant,
            correctedMerchant = if (finalMerchant != null && finalMerchant != review.suggestedMerchant)
                finalMerchant else null,
            originalAmount = review.suggestedAmount,
            correctedAmount = if (finalAmount != null && finalAmount != review.suggestedAmount)
                finalAmount else null,
            originalCategoryId = review.suggestedCategoryId,
            correctedCategoryId = if (finalCategoryId != null && finalCategoryId != review.suggestedCategoryId)
                finalCategoryId else null,
            wasRejected = false,
            wasApproved = true,
            notificationTitle = review.notificationTitle,
            notificationText = review.notificationText
        )
        userCorrectionDao.insert(correction)

        // Train classifier: user approved = positive
        val trainingText = listOfNotNull(
            review.notificationTitle,
            review.notificationText
        ).joinToString(" ")
        if (trainingText.isNotBlank()) {
            classifier.train(trainingText, isTransaction = true)
        }

        // Learn merchant → category mapping
        if (categoryId != null) {
            val pattern = categorizationEngine.normalize(merchant)
            if (pattern.isNotEmpty()) {
                merchantCategoryDao.insert(
                    MerchantCategory(
                        merchantPattern = pattern,
                        categoryId = categoryId,
                        confidence = 1.0f
                    )
                )
            }
        }
    }

    suspend fun rejectReview(reviewId: Long) {
        val review = pendingReviewDao.getById(reviewId) ?: return

        pendingReviewDao.updateStatus(reviewId, "REJECTED")
        dao.markRelevance(review.rawNotificationId, false)
        sourceStatsDao.incrementRejected(review.packageName)

        // Record rejection
        val correction = UserCorrection(
            packageName = review.packageName,
            originalMerchant = review.suggestedMerchant,
            correctedMerchant = null,
            originalAmount = review.suggestedAmount,
            correctedAmount = null,
            originalCategoryId = review.suggestedCategoryId,
            correctedCategoryId = null,
            wasRejected = true,
            wasApproved = false,
            notificationTitle = review.notificationTitle,
            notificationText = review.notificationText
        )
        userCorrectionDao.insert(correction)

        // Train classifier: user rejected = negative
        val trainingText = listOfNotNull(
            review.notificationTitle,
            review.notificationText
        ).joinToString(" ")
        if (trainingText.isNotBlank()) {
            classifier.train(trainingText, isTransaction = false)
        }
    }

    // === Classifier Management ===

    suspend fun retrainClassifier() {
        classifier.retrainFromCorrections()
    }

    // === Rest unchanged from Phase 1 ===

    suspend fun markAsRelevant(id: Long, isRelevant: Boolean) =
        dao.markRelevance(id, isRelevant)

    suspend fun deleteAll() = dao.deleteAll()
    suspend fun deleteAllExpenses() = expenseDao.deleteAll()
    suspend fun deleteExpense(expense: Expense) = expenseDao.delete(expense)

    suspend fun updateExpenseCategory(expense: Expense, newCategoryId: Long) {
        expenseDao.updateCategory(expense.id, newCategoryId)
        val pattern = categorizationEngine.normalize(expense.merchant)
        if (pattern.isNotEmpty()) {
            merchantCategoryDao.insert(
                MerchantCategory(
                    merchantPattern = pattern,
                    categoryId = newCategoryId,
                    confidence = 1.0f
                )
            )
        }
        val correction = UserCorrection(
            packageName = "manual_edit",
            originalMerchant = expense.merchant,
            correctedMerchant = null,
            originalAmount = expense.amount,
            correctedAmount = null,
            originalCategoryId = expense.categoryId,
            correctedCategoryId = newCategoryId,
            wasRejected = false,
            wasApproved = true,
            notificationTitle = null,
            notificationText = null
        )
        userCorrectionDao.insert(correction)
    }

    suspend fun delete(notification: RawNotification) = dao.delete(notification)
    suspend fun blockPackage(packageName: String) =
        blockedPackageDao.block(BlockedPackage(packageName))
    suspend fun unblockPackage(packageName: String) =
        blockedPackageDao.unblock(packageName)
    suspend fun isPackageBlocked(packageName: String): Boolean =
        blockedPackageDao.isBlocked(packageName)
    fun getBlockedPackages(): Flow<List<BlockedPackage>> =
        blockedPackageDao.getAllFlow()
    fun getTotalSpent(): Flow<Double?> = expenseDao.getTotalSpentFlow()
    fun getAllExpenses(): Flow<List<Expense>> = expenseDao.getAllFlow()
}
```

---

## Phase 2E: ML Stats in Debug Screen

Add a section to DebugScreen showing classifier stats and source trust scores. Update `DebugViewModel.kt`:

```kotlin
// ui/screens/debug/DebugViewModel.kt
package com.yourname.expensetracker.ui.screens.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val repository: NotificationRepository
) : ViewModel() {

    val notifications: StateFlow<List<RawNotification>> = repository
        .getRecentNotifications(200)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val notificationCount: StateFlow<Int> = repository
        .getCount()
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val packages: StateFlow<List<String>> = repository
        .getAllPackages()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val blockedPackages: StateFlow<List<com.yourname.expensetracker.data.database.entity.BlockedPackage>> = repository
        .getBlockedPackages()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val totalSpent: StateFlow<Double> = repository
        .getTotalSpent()
        .map { it ?: 0.0 }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0.0)

    val sourceStats: StateFlow<List<SourceStats>> = repository
        .getSourceStats()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _classifierStats = MutableStateFlow(repository.getClassifierStats())
    val classifierStats: StateFlow<ClassifierStats> = _classifierStats

    private val _selectedPackageFilter = MutableStateFlow<String?>(null)
    val selectedPackageFilter: StateFlow<String?> = _selectedPackageFilter

    val filteredNotifications: StateFlow<List<RawNotification>> = combine(
        notifications,
        _selectedPackageFilter
    ) { notifs, filter ->
        if (filter == null) notifs
        else notifs.filter { it.packageName == filter }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun setPackageFilter(packageName: String?) {
        _selectedPackageFilter.value = packageName
    }

    fun clearAll() {
        viewModelScope.launch { repository.deleteAll() }
    }

    fun resetExpenses() {
        viewModelScope.launch { repository.deleteAllExpenses() }
    }

    fun markAsRelevant(id: Long, isRelevant: Boolean) {
        viewModelScope.launch { repository.markAsRelevant(id, isRelevant) }
    }

    fun blockPackage(packageName: String) {
        viewModelScope.launch { repository.blockPackage(packageName) }
    }

    fun unblockPackage(packageName: String) {
        viewModelScope.launch { repository.unblockPackage(packageName) }
    }

    fun retrainClassifier() {
        viewModelScope.launch {
            repository.retrainClassifier()
            _classifierStats.value = repository.getClassifierStats()
        }
    }

    fun simulateTestNotification() {
        viewModelScope.launch {
            val fakeNotification = com.yourname.expensetracker.data.database.entity.RawNotification(
                packageName = "com.test.bank",
                appName = "Test Bank",
                title = "Purchase Alert",
                text = "You paid €12.50 at Amazon",
                timestamp = System.currentTimeMillis(),
                capturedAt = System.currentTimeMillis()
            )
            repository.processAndSave(fakeNotification)
        }
    }

    fun triggerManualSync(context: android.content.Context) {
        val intent = android.content.Intent(context,
            com.yourname.expensetracker.service.NotificationCaptureService::class.java).apply {
            action = com.yourname.expensetracker.service.NotificationCaptureService.ACTION_REFRESH_NOTIFICATIONS
        }
        context.startService(intent)
    }
}
```

Now add the ML Stats section to `DebugScreen.kt`. Add this **composable** and call it inside the DebugScreen Column (after the buttons, before the notification list):

```kotlin
// Add this composable to DebugScreen.kt

@Composable
fun MlStatsSection(
    classifierStats: ClassifierStats,
    sourceStats: List<SourceStats>,
    onRetrain: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "🧠 ML Classifier",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "Status: ${if (classifierStats.isReady) "✅ Active" else "⏳ Training"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Positive samples: ${classifierStats.totalPositive}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Negative samples: ${classifierStats.totalNegative}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Vocabulary: ${classifierStats.vocabularySize} words",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedButton(
                    onClick = onRetrain,
                    enabled = classifierStats.totalPositive + classifierStats.totalNegative >= 20
                ) {
                    Text("Retrain", fontSize = 12.sp)
                }
            }

            // Source trust scores
            if (sourceStats.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "📊 Source Trust Scores",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))

                sourceStats.take(5).forEach { stats ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stats.packageName.split(".").lastOrNull() ?: stats.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${stats.acceptedAsExpense}/${stats.totalNotifications}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val trustColor = when {
                            stats.trustScore > 0.7f -> Color(0xFF4CAF50)
                            stats.trustScore > 0.3f -> Color(0xFFFFC107)
                            else -> Color(0xFFFF5722)
                        }
                        Text(
                            text = "${(stats.trustScore * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = trustColor
                        )
                    }
                }
            }
        }
    }
}
```

And insert it in the DebugScreen Column (add these lines in the `DebugScreen` composable, after the Reset button and before the package filter chips):

```kotlin
// Inside DebugScreen composable, after the Reset Expenses button:

Spacer(modifier = Modifier.height(8.dp))

// ML Stats
val classifierStats by viewModel.classifierStats.collectAsState()
val sourceStatsList by viewModel.sourceStats.collectAsState()

MlStatsSection(
    classifierStats = classifierStats,
    sourceStats = sourceStatsList,
    onRetrain = { viewModel.retrainClassifier() }
)

Spacer(modifier = Modifier.height(8.dp))
```

---

## Updated File Structure After Phase 2

```
ExpenseTracker/
├── app/src/main/java/com/yourname/expensetracker/
│   ├── data/
│   │   └── repository/
│   │       └── NotificationRepository.kt     ← MODIFIED (classifier integration)
│   ├── domain/
│   │   └── intelligence/
│   │       ├── ConfidenceRouter.kt            ← MODIFIED (ML blending)
│   │       ├── MerchantNormalizer.kt          ← ENHANCED (fuzzy matching, aliases)
│   │       └── TransactionClassifier.kt       ← NEW (Naive Bayes on-device ML)
│   └── ui/
│       └── screens/
│           └── debug/
│               ├── DebugScreen.kt             ← MODIFIED (ML stats section)
│               └── DebugViewModel.kt          ← MODIFIED (classifier stats, retrain)
```

---

## How It All Connects — Data Flow

```
Notification arrives
       │
       ▼
   Parse (parser engine)
       │
       ▼
   Normalize merchant (MerchantNormalizer)
       │    ├── Strip noise patterns
       │    ├── Check known aliases
       │    └── Apply user corrections
       │
       ▼
   Route (ConfidenceRouter)
       │    ├── Parser confidence (60% weight)
       │    ├── ML prediction (0-40% weight based on training)
       │    ├── Source trust modifier
       │    ├── Merchant rejection history
       │    └── Previous approval boost
       │
       ├── ≥ 0.85 → AUTO_ACCEPT → Create expense + train ML (positive)
       ├── 0.50-0.84 → REVIEW → Add to queue
       └── < 0.50 → AUTO_REJECT → Drop + train ML (negative)
                                           │
User approves review ──────────────────────┤── train ML (positive)
User rejects review ───────────────────────┤── train ML (negative)
User edits & approves ─────────────────────┘── train ML (positive)
                                                + record correction
                                                + learn merchant name
                                                + learn category
```

---

## Summary of Phase 2 Changes

| Component | What It Does |
|-----------|-------------|
| **TransactionClassifier** | Naive Bayes text classifier trained on-device from user corrections. Persists model to JSON file. Uses unigrams, bigrams, and meta-features (currency symbols, payment keywords, etc.) |
| **Enhanced MerchantNormalizer** | Added Levenshtein distance, known merchant aliases, `findBestMatch()` for fuzzy lookup, proper title-casing |
| **Updated ConfidenceRouter** | Now blends parser confidence with ML prediction. ML weight grows from 0% → 40% as training data increases. Never fully trusts ML alone |
| **Updated Repository** | Trains classifier on every auto-accept, auto-reject, user approve, and user reject. Feeds notification text to router for ML scoring |
| **Debug ML Stats** | Shows classifier status (ready/training), sample counts, vocabulary size, retrain button, per-source trust scores with color coding |

**Key behaviors:**
- First 20 corrections: ML is dormant, pure parser-based routing
- 20-50 corrections: ML starts influencing (20% weight)
- 50-100 corrections: ML weight grows to 30%
- 100+ corrections: ML stabilizes at 35-40% weight
- Model persists across app restarts (JSON on internal storage)
- Retrain button lets user force a full re-learn from all corrections

Ready for Phase 3 (Analytics & Visualization) whenever you are!