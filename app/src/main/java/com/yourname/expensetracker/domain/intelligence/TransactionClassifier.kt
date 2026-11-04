// domain/intelligence/TransactionClassifier.kt
package com.yourname.expensetracker.domain.intelligence

import android.content.Context
import com.yourname.expensetracker.data.privacy.AtRestEncryptionService
import com.yourname.expensetracker.data.repository.UserCorrectionRepository
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawStorageMode
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
    private val userCorrectionRepository: UserCorrectionRepository,
    private val atRestEncryptionService: AtRestEncryptionService,
    private val privacySettingsRepository: PrivacySettingsRepository
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
        // AIML-15: Call saveToDisk() before cancellation for model persistence durability.
        // This ensures the in-memory model state is persisted to disk even if a pending
        // save job is cancelled, preventing data loss when the app goes to background.
        kotlinx.coroutines.runBlocking { saveToDisk() }
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

    /** J-03-1: set by [purgeModelState]; blocks model persistence until STORE_RAW training resumes. */
    @Volatile private var persistenceBlocked = false

    /** Set to true when a legacy plaintext model file was loaded and needs re-saving (encrypted). */
    private var legacyFileMigrated = false

    suspend fun initialize() {
        if (isLoaded.get()) return
        // J-03-1: a definite non-STORE_RAW mode purges instead of loading the stored model.
        // Read failure does not purge; loading proceeds (training stays gated separately).
        // The read and purge happen before taking the mutex (purge takes it itself).
        val initMode = readRawStorageMode()
        if (initMode != null && initMode != RawStorageMode.STORE_RAW) {
            purgeModelState()
            return
        }
        // Switch to IO before taking the lock: the suspending reads below (correction count,
        // settings) would otherwise resume on the caller's dispatcher, and a Main caller blocked
        // in onBackground()'s runBlocking could never run the lock holder's continuation.
        withContext(Dispatchers.IO) {
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
        // AIML-16: Re-save legacy plaintext model file as encrypted
        if (legacyFileMigrated) {
            legacyFileMigrated = false
            saveToDisk()
        }
    }

    open suspend fun predict(text: String): Float {
        // J-03-1: mode is read per call via the settings repository (DataStore snapshot, no
        // extra cached state; predict runs once per notification). Read failure -> 0.5 with no
        // purge; definite non-STORE_RAW -> purge, then 0.5 (same as an unready model).
        when (readRawStorageMode()) {
            null -> return 0.5f
            RawStorageMode.STORE_RAW -> Unit
            else -> {
                purgeModelState()
                return 0.5f
            }
        }
        if (!isLoaded.get()) initialize()

        if (totalPositive + totalNegative < MIN_TRAINING_SAMPLES) {
            return 0.5f 
        }

        val features = extractFeatures(text)
        // Switch to IO before queuing for the lock (see train); nothing inside the lock suspends.
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                calculateProbability(features)
            }
        }
    }

    /**
     * J-03-1 / D4: learning from notification text is allowed only under
     * [RawStorageMode.STORE_RAW]. Any other mode, or any settings read failure, skips training
     * (fail closed). A skip changes no model state, stats or save schedule, and never throws,
     * so callers behave exactly as before. Cancellation is always rethrown.
     */
    private suspend fun isTrainingAllowed(): Boolean {
        val mode = readRawStorageMode() ?: return false
        if (mode != RawStorageMode.STORE_RAW) {
            Timber.d("Classifier training skipped: reason=raw_storage_not_store_raw")
            return false
        }
        return true
    }

    /**
     * Current raw notification storage mode, or null when the read failed (callers must
     * fail closed without purging). Takes no classifier lock. Cancellation is rethrown.
     */
    private suspend fun readRawStorageMode(): RawStorageMode? = try {
        // A fail-closed load state (DataStore IOException or corruption sentinel) carries
        // placeholder DO_NOT_STORE settings, not a user choice. It is a read failure here:
        // no purge, so a transient read error cannot destroy a learned STORE_RAW model.
        when (val state = privacySettingsRepository.getLoadState()) {
            is PrivacySettingsLoadState.CorruptedFailClosed -> {
                Timber.w("Classifier training skipped: reason=settings_load_fail_closed")
                null
            }
            is PrivacySettingsLoadState.Loaded -> state.settings.rawNotificationStorageMode
            is PrivacySettingsLoadState.FirstRunDefault -> state.settings.rawNotificationStorageMode
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w("Classifier training skipped: reason=settings_read_failed")
        null
    }

    /**
     * J-03-1: a definite non-STORE_RAW mode must leave no learned notification-text model
     * behind. Clears all in-memory state and stats, cancels the pending save and deletes the
     * model file, all under the mutex (never call while already holding it). Idempotent: once
     * purged, [persistenceBlocked] short-circuits repeated calls until STORE_RAW training resumes.
     */
    private suspend fun purgeModelState() {
        // Deadlock guard: the dispatcher switch happens BEFORE the lock is taken, and nothing
        // inside the lock suspends. A Main caller blocked in onBackground()'s runBlocking can
        // therefore never wait on a lock holder that needs Main to resume.
        val newStats: ClassifierStats? = withContext(Dispatchers.IO) {
            // A failed delete must retry: the flag alone does not prove the file is gone.
            if (persistenceBlocked && !File(context.filesDir, MODEL_FILE).exists()) {
                null
            } else {
                mutex.withLock {
                    positiveWordCounts.clear()
                    negativeWordCounts.clear()
                    positiveBigramCounts.clear()
                    negativeBigramCounts.clear()
                    vocabulary.clear()
                    totalPositive = 0
                    totalNegative = 0
                    vocabularySize = 0
                    lastTrainingCount = 0
                    legacyFileMigrated = false
                    persistenceBlocked = true
                    isLoaded.set(true)
                    synchronized(jobLock) {
                        saveJob?.cancel()
                        saveJob = null
                    }
                    try {
                        val modelFile = File(context.filesDir, MODEL_FILE)
                        if (modelFile.exists() && !modelFile.delete()) {
                            Timber.w("Classifier model purge incomplete: reason=file_delete_returned_false")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w("Classifier model purge incomplete: reason=file_delete_failed")
                    }
                    getStatsInternal()
                }
            }
        }
        if (newStats == null) return
        _stats.value = newStats
        Timber.d("Classifier model purged: reason=raw_storage_not_store_raw")
    }

    suspend fun train(text: String, isTransaction: Boolean) {
        val mode = readRawStorageMode() ?: return
        if (mode != RawStorageMode.STORE_RAW) {
            Timber.d("Classifier training skipped: reason=raw_storage_not_store_raw")
            purgeModelState()
            return
        }
        // Deadlock guard: switch to IO BEFORE queuing for the lock, and nothing inside the lock
        // suspends. A Main caller queued here while retrain holds the lock would otherwise be
        // granted the lock on Main after onBackground()'s runBlocking (also on Main) queued behind it.
        val newStats: ClassifierStats = withContext(Dispatchers.IO) {
            mutex.withLock {
                val features = extractFeatures(text)
                addTrainingSample(features, isTransaction)
                val stats = getStatsInternal()
                scheduleSave()
                stats
            }
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
        // J-03-1 / D4: stored correction text is raw notification text, so it may only train the
        // model under STORE_RAW. Checked before anything is read or cleared; on a skip the current
        // model, stats, lastTrainingCount and save schedule are left untouched. The settings read
        // takes no classifier lock, so it is safe under the caller's mutex.
        if (!isTrainingAllowed()) return

        val allCorrections = userCorrectionRepository.getAll()
        // R-LEARN-1: retention redaction nulls notificationTitle/notificationText. Rows with no
        // notification text carry only the merchant name, so they must not train the model.
        // Filter first so the sample-count gate sees only usable rows; if too few remain we
        // return before clearing anything and the previous model is kept.
        // R-LEARN-2: the STORE_REDACTED placeholder is not notification text either.
        val corrections = allCorrections.filter {
            isUsableTrainingText(it.notificationTitle) || isUsableTrainingText(it.notificationText)
        }
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
        // Total row count (not the filtered count) so it stays comparable with
        // userCorrectionRepository.getCount() in initialize() and avoids a retrain on every start.
        lastTrainingCount = allCorrections.size

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
        // Switch to IO before queuing for the lock (see train); nothing inside the lock suspends.
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                getStatsInternal()
            }
        }
    }

    private fun addTrainingSample(features: FeatureSet, isTransaction: Boolean) {
        // Training under STORE_RAW starts a fresh model after any purge; persistence is allowed again.
        persistenceBlocked = false
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

    private fun isUsableTrainingText(value: String?): Boolean =
        !value.isNullOrBlank() &&
            value.trim() != com.yourname.expensetracker.domain.privacy.RawContentSanitizer.REDACTED_PLACEHOLDER

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
                // J-03-1: snapshot, encrypt and write under the mutex so a concurrent
                // purge (which deletes the file under the same mutex) can never be
                // followed by a stale write. A purged model is never persisted.
                mutex.withLock {
                    if (!persistenceBlocked) {
                        val json = JSONObject().apply {
                            put("version", MODEL_VERSION)
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

                        // AIML-16: At-rest encryption for ML model
                        val encrypted = atRestEncryptionService.encrypt(json.toString().toByteArray(Charsets.UTF_8))
                        File(context.filesDir, MODEL_FILE).writeBytes(encrypted)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e("Failed to save ML model")
            }
        }
    }

    private fun loadFromDisk(): Boolean {
        return try {
            val file = File(context.filesDir, MODEL_FILE)
            if (!file.exists()) return false

            // AIML-16: Try to read as encrypted; fall back to legacy plaintext
            val fileBytes = file.readBytes()
            val jsonText = try {
                String(atRestEncryptionService.decrypt(fileBytes), Charsets.UTF_8)
            } catch (e: Exception) {
                Timber.d("Could not decrypt model file, trying legacy plaintext")
                legacyFileMigrated = true
                String(fileBytes, Charsets.UTF_8)
            }

            val json = JSONObject(jsonText)
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
