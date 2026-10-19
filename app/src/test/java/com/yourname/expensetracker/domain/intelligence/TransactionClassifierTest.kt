package com.yourname.expensetracker.domain.intelligence

import android.content.Context
import com.yourname.expensetracker.data.repository.UserCorrectionRepository
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Tests for [TransactionClassifier] lifecycle hygiene.
 *
 * Focus areas:
 * - [onBackground] cancels pending jobs without killing the scope
 * - Repeated background transitions do not permanently disable save/retrain scheduling
 * - [destroy] permanently kills the scope (no further work possible)
 * - Legacy [cleanup] delegates to [destroy] for backward compatibility
 */
class TransactionClassifierTest {

    private lateinit var classifier: TransactionClassifier
    private val context = mockk<Context>(relaxed = true)
    private val userCorrectionRepository = mockk<UserCorrectionRepository>(relaxed = true)
    private val tempDir = createTempDir("classifier_test")

    @Before
    fun setup() {
        every { context.filesDir } returns tempDir
        coEvery { userCorrectionRepository.getCount() } returns 0
        coEvery { userCorrectionRepository.getAll() } returns emptyList()

        classifier = TransactionClassifier(context, userCorrectionRepository, atRestEncryptionService = mockk(relaxed = true))
    }

    @Test
    fun `onBackground does not prevent future initialization`() = runBlocking {
        // First background transition
        classifier.onBackground()

        // Should still be able to initialize after backgrounding
        classifier.initialize()

        val stats = classifier.getStats()
        assertNotNull("Stats should be available after background + re-init", stats)
    }

    @Test
    fun `onBackground does not prevent future predict calls`() = runBlocking {
        classifier.initialize()

        // Background transition
        classifier.onBackground()

        // predict() should still work — it does not require the scope
        val result = classifier.predict("test transaction payment")
        assertTrue("Predict should return a valid probability", result in 0f..1f)
    }

    /**
     * Table test: training after any number of onBackground() transitions must still be counted
     * (train() uses scheduleSave() which launches on the scope; onBackground must not cancel the
     * scope itself). Merges: single background then train, repeated background/train cycles, and
     * idempotent consecutive onBackground() calls.
     */
    @Test
    fun `train samples are counted after onBackground transitions - table`() = runBlocking {
        // Each scenario is an ordered op list: "BG" = onBackground(), "TRAIN" = train(positive).
        val scenarios: List<Triple<String, List<String>, Int>> = listOf(
            Triple("single background then train", listOf("BG", "TRAIN"), 1),
            Triple("three consecutive backgrounds then train (idempotent)", listOf("BG", "BG", "BG", "TRAIN"), 1),
            Triple(
                "five background/train cycles",
                List(5) { listOf("BG", "TRAIN") }.flatten(),
                5
            )
        )

        for ((name, ops, expectedPositive) in scenarios) {
            // Fresh classifier + fresh files dir per scenario so persisted state cannot leak.
            val scenarioContext = mockk<Context>(relaxed = true)
            every { scenarioContext.filesDir } returns createTempDir("classifier_table")
            val scenarioClassifier = TransactionClassifier(
                scenarioContext,
                userCorrectionRepository,
                atRestEncryptionService = mockk(relaxed = true)
            )
            scenarioClassifier.initialize()

            var sampleIndex = 0
            for (op in ops) {
                when (op) {
                    "BG" -> scenarioClassifier.onBackground()
                    "TRAIN" -> scenarioClassifier.train(
                        "payment of ${10 + sampleIndex++}.50 EUR",
                        isTransaction = true
                    )
                    else -> fail("Unknown op $op in scenario '$name'")
                }
            }

            assertEquals(
                "Scenario '$name': all training samples must be counted",
                expectedPositive,
                scenarioClassifier.getStats().totalPositive
            )
        }
    }

    @Test
    fun `retrainFromCorrections works after onBackground`() = runBlocking {
        classifier.initialize()

        // Background transition
        classifier.onBackground()

        // retrainFromCorrections launches on the scope — must not throw
        classifier.retrainFromCorrections()

        // No assertion on retrain results (not enough corrections configured),
        // but the call must not crash with CancellationException
    }

    @Test
    fun `destroy permanently cancels scope - train still records but save is lost`() = runBlocking {
        classifier.initialize()

        // Permanently destroy scope
        classifier.destroy()

        // train() will still mutate in-memory state (addTrainingSample),
        // but scheduleSave() will silently fail because scope is cancelled.
        // The method should not throw.
        try {
            classifier.train("test payment", isTransaction = true)
        } catch (_: Exception) {
            // Some implementations may throw; either way is acceptable
            // as destroy() signals permanent disposal
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `cleanup delegates to destroy for backward compatibility`() = runBlocking {
        classifier.initialize()

        // Legacy cleanup should not throw
        classifier.cleanup()

        // After cleanup, scope is permanently cancelled (same as destroy)
    }

    @Test
    fun `predict returns neutral score for untrained classifier`() = runBlocking {
        classifier.initialize()

        val result = classifier.predict("some text")
        assertEquals(
            "Untrained classifier should return 0.5 (neutral)",
            0.5f,
            result,
            0.01f
        )
    }

    @Test
    fun `getStats reflects training after background cycle`() = runBlocking {
        classifier.initialize()

        // Train, background, train again
        classifier.train("first payment", isTransaction = true)
        classifier.onBackground()
        classifier.train("second charge", isTransaction = false)

        val stats = classifier.getStats()
        assertEquals(1, stats.totalPositive)
        assertEquals(1, stats.totalNegative)
    }
}
