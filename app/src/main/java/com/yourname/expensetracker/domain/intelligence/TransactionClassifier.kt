// domain/intelligence/TransactionClassifier.kt
package com.yourname.expensetracker.domain.intelligence

import android.content.Context
import com.yourname.expensetracker.data.repository.UserCorrectionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import timber.log.Timber

/**
 * Lightweight on-device text classifier using Naive Bayes.
 * No TensorFlow needed. Learns from user corrections.
 */
@Singleton
open class TransactionClassifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userCorrectionRepository: UserCorrectionRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Single synchronization owner for all job-handle read/cancel/replace operations.
    // A plain JVM monitor is used (rather than the coroutine Mutex) because lifecycle
    // methods are non-suspend functions that must also cancel both jobs safely.
    private val jobLock = Any()
    private var saveJob: Job? = null
    private var retrainJob: Job? = null

    /**
     * Non-destructive lifecycle callback for routine app backgrounding.
     *
     * Cancels any pending save/retrain jobs without killing the parent scope,
     * so that future [scheduleSave] and [retrainFromCorrections] calls can
     * still launch new coroutines in the same process.
     */
    fun onBackground() {
        synchronized(jobLock) {
            saveJob?.cancel()
            saveJob = null
            retrainJob?.cancel()
            retrainJob = null
        }
    }

    /**
     * Permanently cancels this classifier's coroutine scope.
     *
     * After this call, no further coroutines can be launched.
     * Use only when the classifier instance is truly being disposed of
     * (e.g., in tests or when the process is being terminated).
     */
    fun destroy() {
        onBackground()
        scope.cancel()
    }

    /**
     * Legacy cleanup method. Preserved for backward compatibility.
     *
     * Calling this method permanently cancels the classifier's scope,
     * which means no future save/retrain work can be scheduled.
     * Prefer [onBackground] for routine app backgrounding and
     * [destroy] for true scope disposal.
     */
    @Deprecated(
        message = "Use onBackground() for routine backgrounding or destroy() for permanent disposal",
        replaceWith = ReplaceWith("onBackground()")
    )
    fun cleanup() {
        destroy()
    }

    companion object {
        private const val MODEL_FILE = "naive_bayes_model.json"
        private const val MODEL_VERSION = 1
        private const val MIN_TRAINING_SAMPLES = 20
        private const val LAPLACE_SMOOTHING = 1.0

        private val regexNonAlphanumeric = Regex("[^a-zα-ωά-ώ0-9€$£ ]")
        private val regexWhitespace = Regex("\\s+")
        private val regexDecimalAmount = Regex("""\d+[.,]\d{2}""")
        private val regexCurrencySymbol = Regex("""[€$£]""")
        private val regexCurrencyCode = Regex("""(?i)(EUR|USD|GBP)""")
        private val regexPaymentKeyword = Regex("""(?i)(paid|payment|purchase|charged|debit)""")
        private val regexGreekPaymentKeyword = Regex("""(?i)(πληρωμ|αγορ|χρέωσ|συναλλαγ)""")
        private val regexPromoKeyword = Regex("""(?i)(offer|discount|promo|sale|free|δωρεάν|προσφορά|έκπτωση)""")
        private val regexOtpKeyword = Regex("""(?i)(otp|code|verify|κωδικός)""")
        private val regexBalanceKeyword = Regex("""(?i)(balance|υπόλοιπο)""")
    }

    private val mutex = Mutex()
    private val positiveWordCounts = mutableMapOf<String, Int>()
    private val negativeWordCounts = mutableMapOf<String, Int>()
    private var totalPositive = 0
    private var totalNegative = 0
    private val vocabulary = mutableSetOf<String>()
    private var vocabularySize = 0

    private val positiveBigramCounts = mutableMapOf<String, Int>()
    private val negativeBigramCounts = mutableMapOf<String, Int>()

    private val _stats = MutableStateFlow(
        ClassifierStats(0, 0, 0, false)
    )
    val stats: StateFlow<ClassifierStats> = _stats.asStateFlow()

    private val isLoaded = AtomicBoolean(false)
    private var lastTrainingCount = 0

    suspend fun initialize() {
        if (isLoaded.get()) return
        mutex.withLock {
            if (!isLoaded.get()) {

                if (loadFromDisk()) {
                    isLoaded.set(true)
                    _stats.value = getStatsInternal()
                    Timber.d("Loaded ML model")
                }

                val correctionCount = userCorrectionRepository.getCount()
                if (correctionCount > lastTrainingCount && correctionCount >= MIN_TRAINING_SAMPLES) {
                    retrainFromCorrectionsInternal()
                }

                isLoaded.set(true)
            }
        }
    }

    open suspend fun predict(text: String): Float {
        if (!isLoaded.get()) initialize()

        if (totalPositive + totalNegative < MIN_TRAINING_SAMPLES) {
            return 0.5f 
        }

        val features = extractFeatures(text)
        return mutex.withLock {
            calculateProbability(features)
        }
    }

    suspend fun train(text: String, isTransaction: Boolean) {
        val newStats: ClassifierStats
        mutex.withLock {
            val features = extractFeatures(text)
            addTrainingSample(features, isTransaction)
            newStats = getStatsInternal()
            scheduleSave()
        }
        // Emit outside lock to prevent potential deadlock
        _stats.value = newStats
    }

    fun retrainFromCorrections() {
        val newJob = scope.launch {
            delay(2000) // Debounce for 2 seconds
            mutex.withLock {
                retrainFromCorrectionsInternal()
            }
        }
        synchronized(jobLock) {
            retrainJob?.cancel()
            retrainJob = newJob
        }
    }

    private suspend fun retrainFromCorrectionsInternal() {
        val corrections = userCorrectionRepository.getAll()
        if (corrections.size < MIN_TRAINING_SAMPLES) {
            Timber.d("Not enough corrections to train")
            return
        }

        positiveWordCounts.clear()
        negativeWordCounts.clear()
        positiveBigramCounts.clear()
        negativeBigramCounts.clear()
        totalPositive = 0
        totalNegative = 0

        // LOG-012 Fix: Balance dataset
        val positiveCorrections = corrections.filter { it.wasApproved }
        val negativeCorrections = corrections.filter { it.wasRejected }
        
        // Cap negatives to 3x positives to prevent skew
        val maxNegatives = (positiveCorrections.size * 3).coerceAtLeast(MIN_TRAINING_SAMPLES)
        val selectedNegatives = negativeCorrections.shuffled().take(maxNegatives)
        
        val trainingSet = positiveCorrections + selectedNegatives
        
        for (correction in trainingSet) {
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

        vocabularySize = vocabulary.size
        lastTrainingCount = corrections.size

        scheduleSave()
        Timber.d("Retrained ML model")
    }

    private fun scheduleSave() {
        val newJob = scope.launch {
            delay(2000)
            saveToDisk()
        }
        synchronized(jobLock) {
            saveJob?.cancel()
            saveJob = newJob
        }
    }

    // Internal helper to get stats without locking (caller must hold mutex)
    private fun getStatsInternal(): ClassifierStats {
        return ClassifierStats(
            totalPositive = totalPositive,
            totalNegative = totalNegative,
            vocabularySize = vocabularySize,
            isReady = totalPositive + totalNegative >= MIN_TRAINING_SAMPLES
        )
    }

    // Public suspend version that acquires lock
    open suspend fun getStats(): ClassifierStats {
        return mutex.withLock {
            getStatsInternal()
        }
    }

    private fun addTrainingSample(features: FeatureSet, isTransaction: Boolean) {
        if (isTransaction) {
            totalPositive++
            features.words.forEach {
                positiveWordCounts[it] = (positiveWordCounts[it] ?: 0) + 1
                vocabulary.add(it)
            }
            features.bigrams.forEach {
                positiveBigramCounts[it] = (positiveBigramCounts[it] ?: 0) + 1
            }
        } else {
            totalNegative++
            features.words.forEach {
                negativeWordCounts[it] = (negativeWordCounts[it] ?: 0) + 1
                vocabulary.add(it)
            }
            features.bigrams.forEach {
                negativeBigramCounts[it] = (negativeBigramCounts[it] ?: 0) + 1
            }
        }
        vocabularySize = vocabulary.size
    }

    private fun calculateProbability(features: FeatureSet): Float {
        val total = totalPositive + totalNegative
        if (total == 0) return 0.5f

        // Guard against ln(0) which returns -Infinity
        // If a class has 0 samples, we treat its prior probability as extremely low (-20.0 in log space ~= 2e-9)
        var logProbPos = if (totalPositive > 0) ln(totalPositive.toDouble() / total) else -20.0
        var logProbNeg = if (totalNegative > 0) ln(totalNegative.toDouble() / total) else -20.0

        val vocabSize = vocabularySize.coerceAtLeast(1)

        for (word in features.words) {
            val posCount = (positiveWordCounts[word] ?: 0).toDouble() + LAPLACE_SMOOTHING
            val negCount = (negativeWordCounts[word] ?: 0).toDouble() + LAPLACE_SMOOTHING
            val posDenom = totalPositive.toDouble() + vocabSize * LAPLACE_SMOOTHING
            val negDenom = totalNegative.toDouble() + vocabSize * LAPLACE_SMOOTHING

            logProbPos += ln(posCount / posDenom)
            logProbNeg += ln(negCount / negDenom)
        }

        val bigramWeight = 0.5
        val bigramVocabSize = (positiveBigramCounts.keys + negativeBigramCounts.keys).toSet().size.coerceAtLeast(1)

        for (bigram in features.bigrams) {
            val posCount = (positiveBigramCounts[bigram] ?: 0).toDouble() + LAPLACE_SMOOTHING
            val negCount = (negativeBigramCounts[bigram] ?: 0).toDouble() + LAPLACE_SMOOTHING
            val posDenom = totalPositive.toDouble() + bigramVocabSize * LAPLACE_SMOOTHING
            val negDenom = totalNegative.toDouble() + bigramVocabSize * LAPLACE_SMOOTHING

            logProbPos += bigramWeight * ln(posCount / posDenom)
            logProbNeg += bigramWeight * ln(negCount / negDenom)
        }

        val logOdds = logProbPos - logProbNeg
        val clampedLogOdds = logOdds.coerceIn(-20.0, 20.0)
        return (1.0 / (1.0 + Math.exp(-clampedLogOdds))).toFloat()
    }

    private fun extractFeatures(text: String): FeatureSet {
        val normalized = text.lowercase()
            .replace(regexNonAlphanumeric, " ")
            .replace(regexWhitespace, " ")
            .trim()

        val words = normalized.split(" ")
            .filter { it.length >= 2 }
            .toMutableList()

        if (regexDecimalAmount.containsMatchIn(text)) {
            words.add("__HAS_DECIMAL_AMOUNT__")
        }
        if (regexCurrencySymbol.containsMatchIn(text)) {
            words.add("__HAS_CURRENCY_SYMBOL__")
        }
        if (regexCurrencyCode.containsMatchIn(text)) {
            words.add("__HAS_CURRENCY_CODE__")
        }
        if (regexPaymentKeyword.containsMatchIn(text)) {
            words.add("__HAS_PAYMENT_KEYWORD__")
        }
        if (regexGreekPaymentKeyword.containsMatchIn(text)) {
            words.add("__HAS_GREEK_PAYMENT_KEYWORD__")
        }
        if (regexPromoKeyword.containsMatchIn(text)) {
            words.add("__HAS_PROMO_KEYWORD__")
        }
        if (regexOtpKeyword.containsMatchIn(text)) {
            words.add("__HAS_OTP_KEYWORD__")
        }
        if (regexBalanceKeyword.containsMatchIn(text)) {
            words.add("__HAS_BALANCE_KEYWORD__")
        }

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

    private suspend fun saveToDisk() {
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("version", MODEL_VERSION)
                    mutex.withLock {
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
                }

                File(context.filesDir, MODEL_FILE).writeText(json.toString())
            } catch (e: Exception) {
                Timber.e("Failed to save ML model")
            }
        }
    }

    private fun loadFromDisk(): Boolean {
        return try {
            val file = File(context.filesDir, MODEL_FILE)
            if (!file.exists()) return false

            val json = JSONObject(file.readText())
            val version = json.optInt("version", 0)
            if (version != MODEL_VERSION) {
                Timber.w("ML model version mismatch")
                return false
            }

            totalPositive = json.getInt("totalPositive")
            totalNegative = json.getInt("totalNegative")
            vocabularySize = json.optInt("vocabularySize", 0)
            lastTrainingCount = json.optInt("lastTrainingCount", 0)

            val posWords = json.getJSONObject("positiveWords")
            positiveWordCounts.clear()
            posWords.keys().forEach { key ->
                val count = posWords.getInt(key)
                positiveWordCounts[key] = count
                vocabulary.add(key)
            }

            val negWords = json.getJSONObject("negativeWords")
            negativeWordCounts.clear()
            negWords.keys().forEach { key ->
                val count = negWords.getInt(key)
                negativeWordCounts[key] = count
                vocabulary.add(key)
            }

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

            vocabularySize = vocabulary.size
            true
        } catch (e: Exception) {
            Timber.e("Failed to load ML model")
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
