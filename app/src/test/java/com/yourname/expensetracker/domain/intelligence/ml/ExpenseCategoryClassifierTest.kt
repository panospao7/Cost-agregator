package com.yourname.expensetracker.domain.intelligence.ml

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseCategoryClassifierTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context = mockk<Context>(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var filesDir: File

    @Before
    fun setup() {
        filesDir = tempFolder.newFolder("filesDir")
        every { context.filesDir } returns filesDir
    }

    private fun createClassifier(): ExpenseCategoryClassifier =
        ExpenseCategoryClassifier(context, ioDispatcher = testDispatcher, atRestEncryptionService = mockk(relaxed = true))

    private fun makeFeatures(merchant: String, tokens: List<String> = merchant.lowercase().split(" ")): ExpenseFeatures =
        ExpenseFeatures(
            merchantName = merchant,
            merchantTokens = tokens,
            notificationTitle = null,
            notificationText = null,
            allText = merchant,
            amount = 10.0,
            amountBucket = AmountBucket.SMALL,
            dayOfWeek = 1,
            hourOfDay = 12,
            isWeekend = false,
            sourcePackage = ""
        )

    // ---------------------------------------------------------------
    // 1. saveModel() awaits actual disk write
    // ---------------------------------------------------------------

    @Test
    fun `saveModel awaits disk write and file exists after return`() = runTest(testDispatcher) {
        val classifier = createClassifier()

        // Train a single sample
        classifier.train(makeFeatures("Starbucks"), categoryId = 1L)

        // Explicit save — should await disk I/O
        classifier.saveModel()

        // After saveModel returns the file must already exist on disk
        val modelFile = File(filesDir, "expense_category_model.json")
        assertTrue("Model file must exist after awaited save", modelFile.exists())

        // Verify the file contains valid JSON with the trained data
        val json = JSONObject(modelFile.readText())
        assertEquals(1, json.getInt("totalSamples"))
        assertTrue(json.has("categoryCounts"))
        assertTrue(json.has("wordCounts"))
        assertTrue(json.has("vocabulary"))
    }

    @Test
    fun `saveModel persists correct category counts`() = runTest(testDispatcher) {
        val classifier = createClassifier()

        classifier.train(makeFeatures("Starbucks Coffee"), categoryId = 1L)
        classifier.train(makeFeatures("Starbucks Espresso"), categoryId = 1L)
        classifier.train(makeFeatures("Walmart Groceries"), categoryId = 2L)

        classifier.saveModel()

        val json = JSONObject(File(filesDir, "expense_category_model.json").readText())
        assertEquals(3, json.getInt("totalSamples"))

        val counts = json.getJSONObject("categoryCounts")
        assertEquals(2, counts.getInt("1"))
        assertEquals(1, counts.getInt("2"))
    }

    // ---------------------------------------------------------------
    // 2. Persistence below old 100-sample threshold
    // ---------------------------------------------------------------

    @Test
    fun `learned state is persisted before old 100-sample threshold`() = runTest(testDispatcher) {
        val classifier = createClassifier()

        // Train fewer samples than the old BATCH_SAVE_THRESHOLD (100),
        // but at least DURABLE_SAVE_INTERVAL (5) to trigger auto-save.
        val trainCount = ExpenseCategoryClassifier.DURABLE_SAVE_INTERVAL
        repeat(trainCount) { i ->
            classifier.train(makeFeatures("Merchant_$i"), categoryId = 1L)
        }

        // The bounded save should have triggered automatically
        val modelFile = File(filesDir, "expense_category_model.json")
        assertTrue(
            "Model file must be auto-saved after $trainCount samples (well below old 100 threshold)",
            modelFile.exists()
        )

        val json = JSONObject(modelFile.readText())
        assertEquals(trainCount, json.getInt("totalSamples"))
    }

    @Test
    fun `explicit saveModel persists even a single unsaved sample`() = runTest(testDispatcher) {
        val classifier = createClassifier()

        // Train fewer than DURABLE_SAVE_INTERVAL so auto-save hasn't triggered
        classifier.train(makeFeatures("SingleStore"), categoryId = 1L)

        // No auto-save expected yet — explicitly save
        classifier.saveModel()

        val modelFile = File(filesDir, "expense_category_model.json")
        assertTrue("Explicit save must persist even 1 sample", modelFile.exists())
        val json = JSONObject(modelFile.readText())
        assertEquals(1, json.getInt("totalSamples"))
    }

    // ---------------------------------------------------------------
    // 3. Restart reload — fresh instance uses persisted model
    // ---------------------------------------------------------------

    @Test
    fun `fresh classifier loads persisted model and reflects trained state`() = runTest(testDispatcher) {
        // Phase 1: train and persist
        val classifier1 = createClassifier()
        classifier1.train(makeFeatures("Starbucks", listOf("starbucks")), categoryId = 1L)
        classifier1.train(makeFeatures("Walmart", listOf("walmart")), categoryId = 2L)
        classifier1.saveModel()

        val stats1 = classifier1.getStats()

        // Phase 2: create a "restarted" instance — same context/directory
        val classifier2 = createClassifier()

        // Force load by calling classify (loadModel is lazy)
        classifier2.classify(makeFeatures("anything"))

        val stats2 = classifier2.getStats()

        assertEquals(stats1.totalSamples, stats2.totalSamples)
        assertEquals(stats1.categoryCount, stats2.categoryCount)
        assertEquals(stats1.vocabularySize, stats2.vocabularySize)
    }

    @Test
    fun `fresh classifier produces consistent classification after reload`() = runTest(testDispatcher) {
        val classifier1 = createClassifier()

        // Train enough samples to exceed MIN_SAMPLES (20) so classify returns results
        repeat(21) { i ->
            classifier1.train(
                makeFeatures("coffee_shop_$i", listOf("coffee", "shop")),
                categoryId = 1L
            )
        }
        classifier1.saveModel()

        // Classify with original instance
        val result1 = classifier1.classify(makeFeatures("coffee shop visit", listOf("coffee", "shop")))

        // Fresh instance — should produce same results from persisted model
        val classifier2 = createClassifier()
        val result2 = classifier2.classify(makeFeatures("coffee shop visit", listOf("coffee", "shop")))

        assertEquals(result1.size, result2.size)
        if (result1.isNotEmpty() && result2.isNotEmpty()) {
            assertEquals(result1.first().categoryId, result2.first().categoryId)
            assertEquals(result1.first().score, result2.first().score, 0.001f)
        }
    }

    // ---------------------------------------------------------------
    // 4. Auto-save fires at DURABLE_SAVE_INTERVAL boundary
    // ---------------------------------------------------------------

    @Test
    fun `auto-save triggers exactly at DURABLE_SAVE_INTERVAL boundary`() = runTest(testDispatcher) {
        val classifier = createClassifier()
        val interval = ExpenseCategoryClassifier.DURABLE_SAVE_INTERVAL

        // Train interval - 1 samples: no auto-save yet
        repeat(interval - 1) { i ->
            classifier.train(makeFeatures("merchant_$i"), categoryId = 1L)
        }
        val modelFile = File(filesDir, "expense_category_model.json")
        assertFalse(
            "Model should NOT be auto-saved before reaching $interval samples",
            modelFile.exists()
        )

        // One more sample should trigger auto-save
        classifier.train(makeFeatures("merchant_trigger"), categoryId = 1L)
        assertTrue(
            "Model must be auto-saved after reaching $interval samples",
            modelFile.exists()
        )
    }

    // ---------------------------------------------------------------
    // 5. Edge cases
    // ---------------------------------------------------------------

    @Test
    fun `classify returns empty list when no model exists and no training done`() = runTest(testDispatcher) {
        val classifier = createClassifier()
        val results = classifier.classify(makeFeatures("anything"))
        assertTrue(results.isEmpty())
    }

    @Test
    fun `isReady returns false when samples are below MIN_SAMPLES`() = runTest(testDispatcher) {
        val classifier = createClassifier()
        classifier.train(makeFeatures("single"), categoryId = 1L)
        assertFalse(classifier.isReady())
    }

    @Test
    fun `isReady returns true after sufficient training`() = runTest(testDispatcher) {
        val classifier = createClassifier()
        repeat(20) { i ->
            classifier.train(makeFeatures("item_$i"), categoryId = 1L)
        }
        assertTrue(classifier.isReady())
    }

    @Test
    fun `model file backward compatibility with plain JSON`() = runTest(testDispatcher) {
        // Manually write a model file in the expected format
        val json = JSONObject().apply {
            put("totalSamples", 25)
            put("vocabulary", JSONObject(mapOf("coffee" to 1, "tea" to 1)))
            put("categoryCounts", JSONObject(mapOf("1" to 15, "2" to 10)))
            put("wordCounts", JSONObject().apply {
                put("1", JSONObject(mapOf("coffee" to 10, "tea" to 5)))
                put("2", JSONObject(mapOf("coffee" to 3, "tea" to 7)))
            })
        }
        File(filesDir, "expense_category_model.json").writeText(json.toString())

        // A fresh classifier should load this file successfully
        val classifier = createClassifier()
        val stats = classifier.getStats() // triggers loadModel
        // loadModel is lazy, so we need to call classify or train to trigger it
        classifier.classify(makeFeatures("coffee", listOf("coffee")))

        val statsAfterLoad = classifier.getStats()
        assertEquals(25, statsAfterLoad.totalSamples)
        assertEquals(2, statsAfterLoad.categoryCount)
        assertEquals(2, statsAfterLoad.vocabularySize)
        assertTrue(statsAfterLoad.isReady)
    }
}
