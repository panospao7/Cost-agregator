package com.yourname.expensetracker.domain.intelligence.ml

import android.content.Context
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Naive Bayes Classifier for Expense Categorization.
 * Uses multinomial Naive Bayes with Laplace smoothing.
 */
@Singleton
class ExpenseCategoryClassifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ExpenseCategoryNB"
        private const val MODEL_FILE = "expense_category_model.json"
        private const val SMOOTHING = 1.0
        private const val MIN_SAMPLES = 20
        private const val BATCH_SAVE_THRESHOLD = 100
    }

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private var categoryCounts = mutableMapOf<Long, Int>()
    private var totalSamples = 0
    private var wordCounts = mutableMapOf<Long, MutableMap<String, Int>>()
    private var wordTotals = mutableMapOf<Long, Int>()
    private var vocabulary = mutableSetOf<String>()
    private var isLoaded = false
    
    private var unsavedChanges = 0

    suspend fun classify(features: ExpenseFeatures): List<CategoryScore> {
        if (!isLoaded) loadModel()
        
        return mutex.withLock {
            if (totalSamples < MIN_SAMPLES || categoryCounts.isEmpty()) {
                emptyList()
            } else {
                val scores = mutableMapOf<Long, Double>()
                categoryCounts.keys.forEach { categoryId ->
                    scores[categoryId] = calculateLogProbabilityInternal(features, categoryId)
                }

                val maxLog = scores.values.maxOrNull() ?: 0.0
                val expScores = scores.mapValues { Math.exp(it.value - maxLog).coerceAtLeast(1e-10) }
                val sumExp = expScores.values.sum()
                
                expScores
                    .map { (categoryId, expVal) ->
                        CategoryScore(
                            categoryId = categoryId,
                            categoryName = "Category_$categoryId",
                            score = (expVal / sumExp).toFloat()
                        )
                    }
                    .filter { it.score > 0.01f }
                    .sortedByDescending { it.score }
            }
        }
    }

    suspend fun train(features: ExpenseFeatures, categoryId: Long) {
        if (!isLoaded) loadModel()
        
        mutex.withLock {
            categoryCounts[categoryId] = (categoryCounts[categoryId] ?: 0) + 1
            totalSamples++

            val catWordCounts = wordCounts.getOrPut(categoryId) { mutableMapOf() }
            features.merchantTokens.forEach { token ->
                catWordCounts[token] = (catWordCounts[token] ?: 0) + 1
                vocabulary.add(token)
            }
            
            wordTotals[categoryId] = (wordTotals[categoryId] ?: 0) + features.merchantTokens.size
            
            unsavedChanges++
            
            if (unsavedChanges >= BATCH_SAVE_THRESHOLD) {
                saveModelInternal()
                unsavedChanges = 0
            }
        }
    }

    private fun calculateLogProbabilityInternal(features: ExpenseFeatures, categoryId: Long): Double {
        var logProb = Math.log(
            (categoryCounts[categoryId] ?: 1).toDouble() / 
            totalSamples.coerceAtLeast(1)
        )

        val catWordCounts = wordCounts[categoryId] ?: mutableMapOf()
        val catWordTotal = wordTotals[categoryId] ?: 0
        val vocabSize = vocabulary.size.coerceAtLeast(1)

        features.merchantTokens.forEach { token ->
            val wordCount = catWordCounts[token]?.toDouble() ?: 0.0
            val wordProb = (wordCount + SMOOTHING) / (catWordTotal + SMOOTHING * vocabSize)
            logProb += Math.log(wordProb.coerceAtLeast(1e-10))
        }

        return logProb
    }

    fun isReady(): Boolean = totalSamples >= MIN_SAMPLES

    fun getStats(): CategoryClassifierStats {
        return CategoryClassifierStats(
            totalSamples = totalSamples,
            categoryCount = categoryCounts.size,
            vocabularySize = vocabulary.size,
            isReady = isReady()
        )
    }

    suspend fun saveModel() {
        mutex.withLock {
            saveModelInternal()
            unsavedChanges = 0
        }
    }

    private fun saveModelInternal() {
        try {
            val json = JSONObject().apply {
                put("totalSamples", totalSamples)
                put("vocabulary", JSONObject(vocabulary.associateWith { 1 }))
                
                val countsJson = JSONObject()
                categoryCounts.forEach { (id, count) -> countsJson.put(id.toString(), count) }
                put("categoryCounts", countsJson)
                
                val wordCountsJson = JSONObject()
                wordCounts.forEach { (catId, words) ->
                    val wordsJson = JSONObject()
                    words.forEach { (word, count) -> wordsJson.put(word, count) }
                    wordCountsJson.put(catId.toString(), wordsJson)
                }
                put("wordCounts", wordCountsJson)
            }
            scope.launch {
                File(context.filesDir, MODEL_FILE).writeText(json.toString())
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to save model")
        }
    }

    private suspend fun loadModel() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val file = File(context.filesDir, MODEL_FILE)
                if (!file.exists()) {
                    isLoaded = true
                    return@withContext
                }
                
                val json = JSONObject(file.readText())
                totalSamples = json.getInt("totalSamples")
                
                categoryCounts.clear()
                val catCounts = json.getJSONObject("categoryCounts")
                catCounts.keys().forEach { key -> categoryCounts[key.toLong()] = catCounts.getInt(key) }
                
                vocabulary.clear()
                val vocab = json.getJSONObject("vocabulary")
                vocab.keys().forEach { vocabulary.add(it) }
                
                wordCounts.clear()
                val wc = json.getJSONObject("wordCounts")
                wc.keys().forEach { catId ->
                    val wordsJson = wc.getJSONObject(catId)
                    val words = mutableMapOf<String, Int>()
                    wordsJson.keys().forEach { word -> words[word] = wordsJson.getInt(word) }
                    wordCounts[catId.toLong()] = words
                }
                
                wordTotals.clear()
                wordCounts.forEach { (catId, words) -> wordTotals[catId] = words.values.sum() }
                
                isLoaded = true
                unsavedChanges = 0
            } catch (e: Exception) {
                Timber.e(e, "Failed to load model")
                isLoaded = true
            }
        }
    }
}

data class CategoryClassifierStats(
    val totalSamples: Int,
    val categoryCount: Int,
    val vocabularySize: Int,
    val isReady: Boolean
)
