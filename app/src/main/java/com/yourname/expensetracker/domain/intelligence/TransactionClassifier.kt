// domain/intelligence/TransactionClassifier.kt
package com.yourname.expensetracker.domain.intelligence

import android.content.Context
import android.util.Log
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln

/**
 * Lightweight on-device text classifier using Naive Bayes.
 * No TensorFlow needed. Learns from user corrections.
 */
@Singleton
class TransactionClassifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userCorrectionDao: UserCorrectionDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var saveJob: Job? = null

    companion object {
        private const val TAG = "TxClassifier"
        private const val MODEL_FILE = "naive_bayes_model.json"
        private const val MIN_TRAINING_SAMPLES = 20
        private const val LAPLACE_SMOOTHING = 1.0
    }

    private val positiveWordCounts = mutableMapOf<String, Int>()
    private val negativeWordCounts = mutableMapOf<String, Int>()
    private var totalPositive = 0
    private var totalNegative = 0
    private var vocabularySize = 0

    private val positiveBigramCounts = mutableMapOf<String, Int>()
    private val negativeBigramCounts = mutableMapOf<String, Int>()

    private val _stats = MutableStateFlow(getStats())
    val stats: StateFlow<ClassifierStats> = _stats.asStateFlow()

    private val mutex = Mutex()
    @Volatile
    private var isLoaded = false
    private var lastTrainingCount = 0

    suspend fun initialize() {
        if (isLoaded) return
        mutex.withLock {
            if (isLoaded) return

            if (loadFromDisk()) {
                isLoaded = true
                _stats.value = getStats()
                Log.d(TAG, "Loaded model from disk: +$totalPositive/-$totalNegative samples")
            }

            val correctionCount = userCorrectionDao.getCount()
            if (correctionCount > lastTrainingCount && correctionCount >= MIN_TRAINING_SAMPLES) {
                retrainFromCorrectionsInternal()
            }

            isLoaded = true
        }
    }

    suspend fun predict(text: String): Float {
        if (!isLoaded) initialize()

        if (totalPositive + totalNegative < MIN_TRAINING_SAMPLES) {
            return 0.5f 
        }

        val features = extractFeatures(text)
        return mutex.withLock {
            calculateProbability(features)
        }
    }

    suspend fun train(text: String, isTransaction: Boolean) {
        mutex.withLock {
            val features = extractFeatures(text)
            addTrainingSample(features, isTransaction)
            scheduleSave()
        }
    }

    suspend fun retrainFromCorrections() {
        mutex.withLock {
            retrainFromCorrectionsInternal()
        }
    }

    private suspend fun retrainFromCorrectionsInternal() {
        val corrections = userCorrectionDao.getAll()
        if (corrections.size < MIN_TRAINING_SAMPLES) {
            Log.d(TAG, "Not enough corrections to train: ${corrections.size}/$MIN_TRAINING_SAMPLES")
            return
        }

        positiveWordCounts.clear()
        negativeWordCounts.clear()
        positiveBigramCounts.clear()
        negativeBigramCounts.clear()
        totalPositive = 0
        totalNegative = 0

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

        scheduleSave()
        Log.d(TAG, "Retrained from ${corrections.size} corrections: +$totalPositive/-$totalNegative")
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(2000)
            saveToDisk()
        }
    }

    fun getStats(): ClassifierStats {
        return ClassifierStats(
            totalPositive = totalPositive,
            totalNegative = totalNegative,
            vocabularySize = vocabularySize,
            isReady = totalPositive + totalNegative >= MIN_TRAINING_SAMPLES
        )
    }

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
        _stats.value = getStats()
    }

    private fun calculateProbability(features: FeatureSet): Float {
        val total = totalPositive + totalNegative
        if (total == 0) return 0.5f

        var logProbPos = ln(totalPositive.toDouble() / total)
        var logProbNeg = ln(totalNegative.toDouble() / total)

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
            .replace(Regex("[^a-zα-ωά-ώ0-9€$£ ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val words = normalized.split(" ")
            .filter { it.length >= 2 }
            .toMutableList()

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
