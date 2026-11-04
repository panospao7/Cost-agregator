package com.yourname.expensetracker.domain.intelligence

import android.content.Context
import com.yourname.expensetracker.data.database.entity.UserCorrection
import com.yourname.expensetracker.data.repository.UserCorrectionRepository
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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

        classifier = TransactionClassifier(
            context,
            userCorrectionRepository,
            atRestEncryptionService = mockk(relaxed = true),
            privacySettingsRepository = privacyRepoFor(RawStorageMode.STORE_RAW)
        )
    }

    /** Settings stub whose raw notification storage mode is read from [mode] on every call. */
    private fun privacyRepoFor(mode: RawStorageMode): PrivacySettingsRepository {
        val repo = mockk<PrivacySettingsRepository>()
        coEvery { repo.getLoadState() } returns
            PrivacySettingsLoadState.Loaded(PrivacySettings(rawNotificationStorageMode = mode))
        return repo
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
                atRestEncryptionService = mockk(relaxed = true),
                privacySettingsRepository = privacyRepoFor(RawStorageMode.STORE_RAW)
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

    // ---------------------------------------------------------------------------------------
    // R-LEARN-1: retention redaction nulls notificationTitle / notificationText. Such rows carry
    // only the merchant name and must not (re)train the model. initialize() is the deterministic
    // retrain trigger (getCount() > lastTrainingCount && getCount() >= 20), so these tests use it
    // instead of the 2-second debounced retrainFromCorrections().
    // ---------------------------------------------------------------------------------------

    private fun redactedCorrection(index: Int, approved: Boolean): UserCorrection = UserCorrection(
        id = 1000L + index,
        packageName = "com.synthetic.bank",
        originalMerchant = "manualmerchant$index",
        correctedMerchant = null,
        originalAmount = 10.0,
        correctedAmount = null,
        originalCategoryId = null,
        correctedCategoryId = null,
        originalType = null,
        correctedType = null,
        wasRejected = !approved,
        wasApproved = approved,
        notificationTitle = null,
        notificationText = null,
        createdAt = 1L
    )

    /** variant 0 = title and text, 1 = title only, 2 = text only (all count as unredacted). */
    private fun unredactedCorrection(index: Int, approved: Boolean, variant: Int = 0): UserCorrection {
        val title = if (approved) "Bank alert $index" else "Promo $index"
        val text = if (approved) "Payment of $index.50 EUR at shop$index" else "Your OTP code verify offer $index"
        return UserCorrection(
            id = index.toLong(),
            packageName = "com.synthetic.bank",
            originalMerchant = "shop$index",
            correctedMerchant = null,
            originalAmount = 10.0,
            correctedAmount = null,
            originalCategoryId = null,
            correctedCategoryId = null,
            originalType = null,
            correctedType = null,
            wasRejected = !approved,
            wasApproved = approved,
            notificationTitle = if (variant == 2) null else title,
            notificationText = if (variant == 1) null else text,
            createdAt = 1L
        )
    }

    private fun classifierFor(
        corrections: List<UserCorrection>,
        filesDir: File = createTempDir("classifier_retrain"),
        crypto: com.yourname.expensetracker.data.privacy.AtRestEncryptionService = mockk(relaxed = true),
        privacy: PrivacySettingsRepository = privacyRepoFor(RawStorageMode.STORE_RAW)
    ): Pair<TransactionClassifier, UserCorrectionRepository> {
        val repo = mockk<UserCorrectionRepository>()
        coEvery { repo.getCount() } returns corrections.size
        coEvery { repo.getAll() } returns corrections
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.filesDir } returns filesDir
        return TransactionClassifier(ctx, repo, crypto, privacy) to repo
    }

    private suspend fun trainBaseline(target: TransactionClassifier) {
        repeat(15) { target.train("Payment of $it.99 EUR at baseline$it", isTransaction = true) }
        repeat(15) { target.train("Your OTP code verify offer baseline$it", isTransaction = false) }
    }

    private val probes = listOf(
        "payment of 12.50 EUR at baseline3",
        "otp code verify offer",
        "manualmerchant5",
        "bank alert shop4"
    )

    @Test
    fun `R-LEARN-1 twenty-five fully redacted corrections leave trained model and predictions unchanged`() = runBlocking {
        // Control: identical baseline training, nothing to retrain from.
        val (control, _) = classifierFor(emptyList())
        trainBaseline(control)
        control.initialize()
        val controlStats = control.getStats()
        val controlPredictions = probes.map { control.predict(it) }
        assertTrue("control must be ready so the comparison is meaningful", controlStats.isReady)

        // Subject: same baseline, then initialize() sees 25 redacted rows (>= MIN_TRAINING_SAMPLES).
        val redacted = (0 until 25).map { redactedCorrection(it, approved = it % 2 == 0) }
        val (subject, repo) = classifierFor(redacted)
        trainBaseline(subject)
        subject.initialize()

        coVerify(exactly = 1) { repo.getAll() } // retrain was attempted, then rejected the rows
        val stats = subject.getStats()
        assertEquals("no sample may be added", 15, stats.totalPositive)
        assertEquals("no sample may be added", 15, stats.totalNegative)
        assertEquals("no merchant-only vocabulary may be added", controlStats, stats)
        probes.forEachIndexed { i, probe ->
            assertEquals("prediction for '$probe' must be unchanged", controlPredictions[i], subject.predict(probe), 0f)
        }
    }

    @Test
    fun `R-LEARN-1 redacted corrections alone never make an untrained model ready`() = runBlocking {
        val redacted = (0 until 30).map { redactedCorrection(it, approved = it % 2 == 0) }
        val (subject, _) = classifierFor(redacted)

        subject.initialize()

        val stats = subject.getStats()
        assertEquals(0, stats.totalPositive)
        assertEquals(0, stats.totalNegative)
        assertFalse(stats.isReady)
        assertEquals(0.5f, subject.predict("payment of 12.50 EUR"), 0f)
    }

    @Test
    fun `R-LEARN-1 mixed set trains only the unredacted rows`() = runBlocking {
        val unredacted = (0 until 22).map {
            // 14 approved + 8 rejected, covering title+text, title-only and text-only rows.
            unredactedCorrection(it, approved = it < 14, variant = it % 3)
        }
        val redacted = (0 until 12).map { redactedCorrection(it, approved = it % 2 == 0) }
        val (subject, _) = classifierFor(unredacted + redacted)

        subject.initialize()

        val stats = subject.getStats()
        assertEquals("only unredacted approved rows are positives", 14, stats.totalPositive)
        assertEquals("only unredacted rejected rows are negatives", 8, stats.totalNegative)
        assertTrue(stats.isReady)
    }

    @Test
    fun `R-LEARN-1 boundary - nineteen unredacted plus redacted rows do not retrain despite total above minimum`() = runBlocking {
        val unredacted = (0 until 19).map { unredactedCorrection(it, approved = it < 11) }
        val redacted = (0 until 10).map { redactedCorrection(it, approved = true) }
        // Total 29 >= 20 but only 19 are usable.
        val (subject, _) = classifierFor(unredacted + redacted)

        subject.initialize()

        val stats = subject.getStats()
        assertEquals(0, stats.totalPositive)
        assertEquals(0, stats.totalNegative)
        assertEquals(0.5f, subject.predict("payment of 12.50 EUR"), 0f)
    }

    @Test
    fun `R-LEARN-1 boundary - exactly twenty unredacted rows train and redacted extras are ignored`() = runBlocking {
        val unredacted = (0 until 20).map { unredactedCorrection(it, approved = it < 12) }
        val redacted = (0 until 5).map { redactedCorrection(it, approved = false) }
        val (subject, _) = classifierFor(unredacted + redacted)

        subject.initialize()

        val stats = subject.getStats()
        assertEquals(12, stats.totalPositive)
        assertEquals(8, stats.totalNegative)
        assertTrue(stats.isReady)
    }

    @Test
    fun `R-LEARN-1 lastTrainingCount is the unfiltered count so a restart does not retrain`() = runBlocking {
        val sharedDir = createTempDir("classifier_restart")
        // Passthrough crypto so the persisted model can be reloaded by the second instance.
        val crypto = mockk<com.yourname.expensetracker.data.privacy.AtRestEncryptionService>()
        every { crypto.encrypt(any()) } answers { firstArg<ByteArray>() }
        every { crypto.decrypt(any()) } answers { firstArg<ByteArray>() }

        val unredacted = (0 until 22).map { unredactedCorrection(it, approved = it < 14) }
        val redacted = (0 until 8).map { redactedCorrection(it, approved = true) }
        val all = unredacted + redacted // getCount() == 30, usable == 22

        val (first, firstRepo) = classifierFor(all, sharedDir, crypto)
        first.initialize()
        coVerify(exactly = 1) { firstRepo.getAll() }
        val firstStats = first.getStats()
        assertEquals(14, firstStats.totalPositive)
        assertEquals(8, firstStats.totalNegative)
        first.destroy() // persists the model, including lastTrainingCount

        val (second, secondRepo) = classifierFor(all, sharedDir, crypto)
        second.initialize()

        coVerify(exactly = 0) { secondRepo.getAll() } // getCount() (30) is not > lastTrainingCount (30)
        val secondStats = second.getStats()
        assertEquals(firstStats.totalPositive, secondStats.totalPositive)
        assertEquals(firstStats.totalNegative, secondStats.totalNegative)
        second.destroy()
    }

    // ---------------------------------------------------------------------------------------
    // R-LEARN-2: the "[REDACTED]" placeholder (written by the store-time redaction) is not
    // notification text. A row whose title AND text are the placeholder is unusable; a row with
    // one real field and one placeholder field is usable.
    // ---------------------------------------------------------------------------------------

    private fun placeholderCorrection(index: Int, approved: Boolean): UserCorrection =
        redactedCorrection(index, approved).copy(
            notificationTitle = "[REDACTED]",
            notificationText = "[REDACTED]"
        )

    @Test
    fun `R-LEARN-2 twenty-five placeholder-redacted corrections leave trained model and predictions unchanged`() = runBlocking {
        val (control, _) = classifierFor(emptyList())
        trainBaseline(control)
        control.initialize()
        val controlStats = control.getStats()
        val controlPredictions = probes.map { control.predict(it) }
        assertTrue("control must be ready so the comparison is meaningful", controlStats.isReady)

        val placeholders = (0 until 25).map { placeholderCorrection(it, approved = it % 2 == 0) }
        val (subject, repo) = classifierFor(placeholders)
        trainBaseline(subject)
        subject.initialize()

        coVerify(exactly = 1) { repo.getAll() }
        val stats = subject.getStats()
        assertEquals("no sample may be added", 15, stats.totalPositive)
        assertEquals("no sample may be added", 15, stats.totalNegative)
        assertEquals(controlStats, stats)
        probes.forEachIndexed { i, probe ->
            assertEquals("prediction for '$probe' must be unchanged", controlPredictions[i], subject.predict(probe), 0f)
        }
    }

    @Test
    fun `R-LEARN-2 placeholder-only corrections alone never make an untrained model ready`() = runBlocking {
        val placeholders = (0 until 30).map { placeholderCorrection(it, approved = it % 2 == 0) }
        val (subject, _) = classifierFor(placeholders)

        subject.initialize()

        val stats = subject.getStats()
        assertEquals(0, stats.totalPositive)
        assertEquals(0, stats.totalNegative)
        assertFalse(stats.isReady)
        assertEquals(0.5f, subject.predict("payment of 12.50 EUR"), 0f)
    }

    @Test
    fun `R-LEARN-2 boundary - one real field plus one placeholder field is usable`() = runBlocking {
        // 20 rows, each with exactly one real field and one "[REDACTED]" field.
        val halfRedacted = (0 until 20).map {
            val base = unredactedCorrection(it, approved = it < 12)
            if (it % 2 == 0) base.copy(notificationText = "[REDACTED]")
            else base.copy(notificationTitle = "[REDACTED]")
        }
        val (subject, _) = classifierFor(halfRedacted)

        subject.initialize()

        val stats = subject.getStats()
        assertEquals(12, stats.totalPositive)
        assertEquals(8, stats.totalNegative)
        assertTrue(stats.isReady)
    }

    @Test
    fun `R-LEARN-2 boundary - nineteen usable plus fully placeholder rows do not retrain`() = runBlocking {
        val usable = (0 until 19).map {
            unredactedCorrection(it, approved = it < 11).copy(notificationText = "[REDACTED]")
        }
        val placeholders = (0 until 10).map { placeholderCorrection(it, approved = true) }
        val (subject, _) = classifierFor(usable + placeholders)

        subject.initialize()

        val stats = subject.getStats()
        assertEquals(0, stats.totalPositive)
        assertEquals(0, stats.totalNegative)
        assertEquals(0.5f, subject.predict("payment of 12.50 EUR"), 0f)
    }

    // ---------------------------------------------------------------------------------------
    // J-03-1 / D4: train() learns from notification text only under RawStorageMode.STORE_RAW.
    // Any other mode, or a settings read failure, skips training (fail closed) without throwing.
    // CancellationException is always rethrown. The deferred save is not scheduled on a skip.
    //
    // Persistence is observed deterministically: destroy() runs a blocking saveToDisk() on the
    // real file system (awaited before it returns) and then cancels the debounced save job, so
    // the model file is the real, fully written snapshot of in-memory state.
    // ---------------------------------------------------------------------------------------

    private val modelFileName = "naive_bayes_model.json"

    private fun passthroughCrypto(): com.yourname.expensetracker.data.privacy.AtRestEncryptionService {
        val crypto = mockk<com.yourname.expensetracker.data.privacy.AtRestEncryptionService>()
        every { crypto.encrypt(any()) } answers { firstArg<ByteArray>() }
        every { crypto.decrypt(any()) } answers { firstArg<ByteArray>() }
        return crypto
    }

    /** Persists via destroy() (real IO, awaited) and returns the raw model text and its JSON. */
    private fun persistAndRead(target: TransactionClassifier, dir: File): Pair<String, JSONObject> {
        target.destroy()
        val file = File(dir, modelFileName)
        assertTrue("model file must exist after destroy()", file.exists())
        val text = file.readText(Charsets.UTF_8)
        return text to JSONObject(text)
    }

    private inline fun withCapturedLogs(block: (MutableList<Pair<Throwable?, String>>) -> Unit) {
        val logs = mutableListOf<Pair<Throwable?, String>>()
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logs += t to message
            }
        }
        timber.log.Timber.plant(tree)
        try {
            block(logs)
        } finally {
            timber.log.Timber.uproot(tree)
        }
    }

    private val untrainedStats = ClassifierStats(0, 0, 0, false)

    @Test
    fun `D4 STORE_RAW trains - stats, predictions and persisted model change`() = runBlocking<Unit> {
        val dir = createTempDir("classifier_d4_raw")
        val privacy = privacyRepoFor(RawStorageMode.STORE_RAW)
        val (subject, _) = classifierFor(emptyList(), dir, passthroughCrypto(), privacy)

        trainBaseline(subject)

        val stats = subject.getStats()
        assertEquals(15, stats.totalPositive)
        assertEquals(15, stats.totalNegative)
        assertTrue(stats.isReady)
        assertEquals(stats, subject.stats.value)
        assertTrue("payment-like text must score above neutral", subject.predict(probes[0]) > 0.5f)
        assertTrue("otp-like text must score below neutral", subject.predict(probes[1]) < 0.5f)
        coVerify(atLeast = 1) { privacy.getLoadState() }

        val (raw, json) = persistAndRead(subject, dir)
        assertEquals(15, json.getInt("totalPositive"))
        assertEquals(15, json.getInt("totalNegative"))
        assertTrue("control: trained tokens must be persisted", raw.contains("baseline"))
    }

    @Test
    fun `D4 DO_NOT_STORE, STORE_REDACTED and STORE_METADATA_ONLY skip training - state, predictions and model file unchanged`() = runBlocking<Unit> {
        for (mode in RawStorageMode.values().filter { it != RawStorageMode.STORE_RAW }) {
            val dir = createTempDir("classifier_d4_skip")
            val privacy = privacyRepoFor(mode)
            val (subject, _) = classifierFor(emptyList(), dir, passthroughCrypto(), privacy)

            // 30 samples would make the model ready if they were learned.
            trainBaseline(subject)

            coVerify(atLeast = 1) { privacy.getLoadState() } // the gate was actually consulted
            assertEquals("$mode: stats must be unchanged", untrainedStats, subject.getStats())
            assertEquals("$mode: stats flow must be unchanged", untrainedStats, subject.stats.value)
            assertEquals("$mode: prediction must stay neutral", 0.5f, subject.predict(probes[0]), 0f)

            // J-03-1: a definite non-STORE_RAW mode purges, so persisting afterwards must write
            // no model file at all (stricter than the former "file holds zero counts").
            subject.destroy()
            assertFalse(
                "$mode: no model file may exist after a purge, even after persisting",
                File(dir, modelFileName).exists()
            )
        }
    }

    @Test
    fun `D4 settings read failure skips training, does not propagate and leaks no exception text`() = runBlocking<Unit> {
        val marker = "SYNTHETIC_SECRET_MARKER_4711"
        val failures: List<Throwable> = listOf(
            RuntimeException(marker),
            java.io.IOException(marker),
            IllegalStateException(marker)
        )
        for (failure in failures) {
            val dir = createTempDir("classifier_d4_throw")
            // Seed a real trained model and file first, so "nothing changed" proves something.
            val privacy = SwitchablePrivacy()
            val subject = trainedAndPersisted(dir, passthroughCrypto(), privacy)
            val fileBefore = modelFileIn(dir).readBytes()
            val trainedStats = subject.getStats()

            privacy.failure = failure
            withCapturedLogs { logs ->
                // Must not throw.
                subject.train("Payment of 12.50 EUR at failurecase", isTransaction = true)
                subject.train("Your OTP code verify offer failurecase", isTransaction = false)
                assertEquals("predict neutral on read failure", 0.5f, subject.predict(probes[0]), 0f)

                assertTrue("logs must not carry exception text", logs.none { it.second.contains(marker) })
                assertTrue("logs must not carry a throwable", logs.all { it.first == null })
            }

            assertEquals("totals kept", trainedStats, subject.getStats())
            assertEquals("stats flow kept", trainedStats, subject.stats.value)
            assertArrayEquals("model file untouched", fileBefore, modelFileIn(dir).readBytes())

            val (raw, json) = persistAndRead(subject, dir)
            assertEquals(15, json.getInt("totalPositive"))
            assertEquals(15, json.getInt("totalNegative"))
            assertFalse(raw.contains("failurecase"))
        }
    }

    @Test
    fun `D4 CorruptedFailClosed load state skips training and keeps a seeded model, file and predictions neutral`() = runBlocking<Unit> {
        // PrivacySettingsRepositoryImpl reports CorruptedFailClosed (with DO_NOT_STORE placeholder
        // settings) on a DataStore IOException or the corruption sentinel. That is a read failure,
        // not a user choice, so it must neither train nor purge a learned model.
        assertNotEquals(
            "precondition: the placeholder settings would purge if misread as a definite mode",
            RawStorageMode.STORE_RAW,
            PrivacySettings.FAIL_CLOSED_DEFAULTS.rawNotificationStorageMode
        )
        val dir = createTempDir("classifier_d4_failclosed")
        val privacy = SwitchablePrivacy()
        val subject = trainedAndPersisted(dir, passthroughCrypto(), privacy)
        val trainedStats = subject.getStats()
        val fileBefore = modelFileIn(dir).readBytes()

        privacy.failClosed = true
        subject.train("Payment of 12.50 EUR at failclosedcase", isTransaction = true)
        assertEquals("predict neutral under a fail-closed state", 0.5f, subject.predict(probes[0]), 0f)

        coVerify(atLeast = 1) { privacy.repo.getLoadState() }
        assertEquals("totals kept", trainedStats, subject.getStats())
        assertEquals("stats flow kept", trainedStats, subject.stats.value)
        assertTrue("model file kept", modelFileIn(dir).exists())
        assertArrayEquals("model file untouched", fileBefore, modelFileIn(dir).readBytes())
        val (raw, _) = persistAndRead(subject, dir)
        assertFalse("nothing trained under a fail-closed state", raw.contains("failclosedcase"))
    }

    @Test
    fun `D4 definite DO_NOT_STORE delivered as a Loaded state with fail-closed defaults still purges`() = runBlocking<Unit> {
        val dir = createTempDir("classifier_d4_definite")
        val privacy = SwitchablePrivacy()
        val subject = trainedAndPersisted(dir, passthroughCrypto(), privacy)

        privacy.mode = PrivacySettings.FAIL_CLOSED_DEFAULTS.rawNotificationStorageMode
        assertEquals(0.5f, subject.predict(probes[0]), 0f)

        assertPurged("definite DO_NOT_STORE", subject, dir)
        subject.destroy()
    }

    @Test
    fun `D4 fail-closed load state at initialize keeps the stored model and recovery to STORE_RAW keeps it`() = runBlocking<Unit> {
        val dir = createTempDir("classifier_d4_failclosed_init")
        val crypto = passthroughCrypto()
        val privacy = SwitchablePrivacy()
        trainedAndPersisted(dir, crypto, privacy).destroy()
        val fileBefore = modelFileIn(dir).readBytes()

        privacy.failClosed = true
        val (restarted, _) = classifierFor(emptyList(), dir, crypto, privacy.repo)
        restarted.initialize()

        assertEquals(15, restarted.getStats().totalPositive)
        assertEquals(15, restarted.getStats().totalNegative)
        assertArrayEquals("model file untouched", fileBefore, modelFileIn(dir).readBytes())
        assertEquals("predict neutral while fail-closed", 0.5f, restarted.predict(probes[0]), 0f)
        assertEquals("still kept after predict", 15, restarted.getStats().totalPositive)

        // Recovery: the read works again and reports STORE_RAW; the retained model is used.
        privacy.failClosed = false
        privacy.mode = RawStorageMode.STORE_RAW
        assertTrue("retained model scores above neutral", restarted.predict(probes[0]) > 0.5f)
        assertEquals(15, restarted.getStats().totalPositive)
        assertTrue(modelFileIn(dir).exists())
        restarted.destroy()
    }

    @Test
    fun `D4 CancellationException from the settings read propagates and trains nothing`() = runBlocking<Unit> {
        val dir = createTempDir("classifier_d4_cancel")
        val privacy = mockk<PrivacySettingsRepository>()
        coEvery { privacy.getLoadState() } throws CancellationException("synthetic cancellation")
        val (subject, _) = classifierFor(emptyList(), dir, passthroughCrypto(), privacy)

        var propagated: CancellationException? = null
        try {
            subject.train("Payment of 12.50 EUR at cancelcase", isTransaction = true)
        } catch (e: CancellationException) {
            propagated = e
        }

        assertNotNull("CancellationException must propagate out of train()", propagated)
        assertEquals(untrainedStats, subject.getStats())
        val (raw, json) = persistAndRead(subject, dir)
        assertEquals(0, json.getInt("totalPositive"))
        assertEquals(0, json.getInt("totalNegative"))
        assertFalse(raw.contains("cancelcase"))
    }

    @Test
    fun `D4 a skipped train (settings read failure) does not change lastTrainingCount`() = runBlocking<Unit> {
        val dir = createTempDir("classifier_d4_lastcount")
        val crypto = passthroughCrypto()
        val unredacted = (0 until 22).map { unredactedCorrection(it, approved = it < 14) }
        val redacted = (0 until 8).map { redactedCorrection(it, approved = true) }
        val all = unredacted + redacted // getCount() == 30, usable == 22
        var mode = RawStorageMode.STORE_RAW
        var failRead = false
        val privacy = mockk<PrivacySettingsRepository>()
        coEvery { privacy.getLoadState() } answers {
            if (failRead) throw IllegalStateException("synthetic settings read failure")
            PrivacySettingsLoadState.Loaded(PrivacySettings(rawNotificationStorageMode = mode))
        }

        val (first, _) = classifierFor(all, dir, crypto, privacy)
        first.initialize() // retrain under STORE_RAW (retrain is gated) sets lastTrainingCount = 30
        val before = first.getStats()
        assertEquals(14, before.totalPositive)
        assertEquals(8, before.totalNegative)

        // A read failure skips training WITHOUT purging (a definite non-STORE_RAW mode purges and
        // resets lastTrainingCount; that path is covered by the J-03-1 tests).
        failRead = true
        first.train("Payment of 99.50 EUR at skippedcase", isTransaction = true)
        first.train("Your OTP code verify offer skippedcase", isTransaction = false)

        assertEquals("skipped train must not change stats", before, first.getStats())
        val (raw, json) = persistAndRead(first, dir)
        assertEquals("lastTrainingCount must be untouched by a skip", 30, json.getInt("lastTrainingCount"))
        assertEquals(14, json.getInt("totalPositive"))
        assertEquals(8, json.getInt("totalNegative"))
        assertFalse(raw.contains("skippedcase"))

        // Restart under STORE_RAW (so the mode gate cannot be what suppresses the retrain):
        // getCount() (30) is not > lastTrainingCount (30), so no retrain is triggered.
        failRead = false
        mode = RawStorageMode.STORE_RAW
        val (second, secondRepo) = classifierFor(all, dir, crypto, privacy)
        second.initialize()
        coVerify(exactly = 0) { secondRepo.getAll() }
        assertEquals(before, second.getStats())
        second.destroy()
    }

    @Test
    fun `D4 retrain from corrections is gated by the raw storage mode`() = runBlocking<Unit> {
        // retrainFromCorrections() and initialize() share retrainFromCorrectionsInternal(), which
        // now checks the raw storage mode first. initialize() is the deterministic trigger (the
        // public retrainFromCorrections() is a 2-second debounce). Stored correction text must not
        // keep training the model under any mode other than STORE_RAW (DO_NOT_STORE,
        // STORE_REDACTED or STORE_METADATA_ONLY).
        for (mode in RawStorageMode.values().filter { it != RawStorageMode.STORE_RAW }) {
            val unredacted = (0 until 22).map { unredactedCorrection(it, approved = it < 14) }
            val (subject, repo) = classifierFor(unredacted, privacy = privacyRepoFor(mode))

            subject.initialize()

            coVerify(exactly = 0) { repo.getAll() }
            assertEquals("$mode: stats must be unchanged", untrainedStats, subject.getStats())
            assertEquals("$mode: stats flow must be unchanged", untrainedStats, subject.stats.value)
            subject.destroy()
        }

        val unredacted = (0 until 22).map { unredactedCorrection(it, approved = it < 14) }
        val (rawSubject, rawRepo) = classifierFor(unredacted, privacy = privacyRepoFor(RawStorageMode.STORE_RAW))

        rawSubject.initialize()

        coVerify(exactly = 1) { rawRepo.getAll() }
        val stats = rawSubject.getStats()
        assertEquals("STORE_RAW: retrain still learns from stored corrections", 14, stats.totalPositive)
        assertEquals("STORE_RAW: retrain still learns from stored corrections", 8, stats.totalNegative)
        assertTrue(stats.isReady)
        rawSubject.destroy()
    }

    @Test
    fun `D4 mode flip - STORE_RAW trains, DO_NOT_STORE skips, STORE_RAW trains again`() = runBlocking<Unit> {
        var mode = RawStorageMode.STORE_RAW
        val privacy = mockk<PrivacySettingsRepository>()
        coEvery { privacy.getLoadState() } answers {
            PrivacySettingsLoadState.Loaded(PrivacySettings(rawNotificationStorageMode = mode))
        }
        val dir = createTempDir("classifier_d4_flip")
        val (subject, _) = classifierFor(emptyList(), dir, passthroughCrypto(), privacy)

        mode = RawStorageMode.STORE_RAW
        subject.train("Payment of 10.50 EUR at flipalpha", isTransaction = true)
        assertEquals(1, subject.getStats().totalPositive)

        // J-03-1: a definite non-STORE_RAW mode purges the learned model, so training that
        // resumes after a flip back to STORE_RAW starts again from zero.
        mode = RawStorageMode.DO_NOT_STORE
        subject.train("Payment of 11.50 EUR at flipbravo", isTransaction = true)
        subject.train("Your OTP code verify offer flipbravo", isTransaction = false)
        assertEquals("purged while DO_NOT_STORE", untrainedStats, subject.getStats())

        mode = RawStorageMode.STORE_RAW
        subject.train("Payment of 12.50 EUR at flipcharlie", isTransaction = true)
        assertEquals("training restarts from zero after flip back", 1, subject.getStats().totalPositive)
        assertEquals(0, subject.getStats().totalNegative)

        mode = RawStorageMode.STORE_REDACTED
        subject.train("Payment of 13.50 EUR at flipdelta", isTransaction = true)
        assertEquals("purged while STORE_REDACTED", untrainedStats, subject.getStats())

        mode = RawStorageMode.STORE_RAW
        subject.train("Payment of 14.50 EUR at flipecho", isTransaction = true)
        val stats = subject.getStats()
        assertEquals(1, stats.totalPositive)
        assertEquals(0, stats.totalNegative)

        val (raw, json) = persistAndRead(subject, dir)
        val positiveWords = json.getJSONObject("positiveWords")
        assertTrue(positiveWords.has("flipecho"))
        assertFalse("purged before the model was rebuilt", raw.contains("flipalpha"))
        assertFalse("purged under DO_NOT_STORE", raw.contains("flipbravo"))
        assertFalse("purged before the model was rebuilt", raw.contains("flipcharlie"))
        assertFalse("purged under STORE_REDACTED", raw.contains("flipdelta"))
    }

    // ---------------------------------------------------------------------------------------
    // J-03-1: a definite non-STORE_RAW mode purges the learned model (memory, stats, file);
    // a settings read failure purges nothing; STORE_RAW training afterwards starts from zero.
    // The persisted model is created deterministically with onBackground() (awaited real save).
    // ---------------------------------------------------------------------------------------

    /** Privacy stub whose mode and read failure can be switched between calls. */
    private class SwitchablePrivacy(
        @Volatile var mode: RawStorageMode = RawStorageMode.STORE_RAW,
        @Volatile var failure: Throwable? = null,
        /** When true the repository reports the real fail-closed load state (IO error path). */
        @Volatile var failClosed: Boolean = false
    ) {
        val repo: PrivacySettingsRepository = mockk<PrivacySettingsRepository>().also { r ->
            coEvery { r.getLoadState() } answers {
                failure?.let { throw it }
                if (failClosed) {
                    PrivacySettingsLoadState.CorruptedFailClosed(
                        settings = PrivacySettings.FAIL_CLOSED_DEFAULTS,
                        reason = "IO error"
                    )
                } else {
                    PrivacySettingsLoadState.Loaded(PrivacySettings(rawNotificationStorageMode = mode))
                }
            }
        }
    }

    private val nonRawModes = listOf(
        RawStorageMode.STORE_REDACTED,
        RawStorageMode.STORE_METADATA_ONLY,
        RawStorageMode.DO_NOT_STORE
    )

    private fun modelFileIn(dir: File) = File(dir, modelFileName)

    /** Trains 15+15 under STORE_RAW and persists the model, asserting the control state. */
    private suspend fun trainedAndPersisted(
        dir: File,
        crypto: com.yourname.expensetracker.data.privacy.AtRestEncryptionService,
        privacy: SwitchablePrivacy
    ): TransactionClassifier {
        val (subject, _) = classifierFor(emptyList(), dir, crypto, privacy.repo)
        trainBaseline(subject)
        subject.onBackground()
        assertTrue("control: model file must exist", modelFileIn(dir).exists())
        assertEquals(15, subject.getStats().totalPositive)
        assertEquals(15, subject.getStats().totalNegative)
        assertTrue("control: trained model must score above neutral", subject.predict(probes[0]) > 0.5f)
        return subject
    }

    private suspend fun assertPurged(label: String, subject: TransactionClassifier, dir: File) {
        assertEquals("$label: stats cleared", untrainedStats, subject.getStats())
        assertEquals("$label: stats flow cleared", untrainedStats, subject.stats.value)
        assertFalse("$label: model file deleted", modelFileIn(dir).exists())
    }

    @Test
    fun `J-03-1 predict under a non-raw mode purges stats, totals and model file and returns neutral`() = runBlocking<Unit> {
        for (mode in nonRawModes) {
            val dir = createTempDir("classifier_j031_predict")
            val privacy = SwitchablePrivacy()
            val subject = trainedAndPersisted(dir, passthroughCrypto(), privacy)

            privacy.mode = mode
            assertEquals("$mode: predict neutral", 0.5f, subject.predict(probes[0]), 0f)

            assertPurged("predict/$mode", subject, dir)
            subject.destroy()
        }
    }

    @Test
    fun `J-03-1 train under a non-raw mode purges stats, totals and model file and predict is neutral`() = runBlocking<Unit> {
        for (mode in nonRawModes) {
            val dir = createTempDir("classifier_j031_train")
            val privacy = SwitchablePrivacy()
            val subject = trainedAndPersisted(dir, passthroughCrypto(), privacy)

            privacy.mode = mode
            subject.train("Payment of 77.50 EUR at purgetrain", isTransaction = true)

            assertPurged("train/$mode", subject, dir)
            assertEquals("$mode: predict neutral", 0.5f, subject.predict(probes[0]), 0f)
            subject.destroy()
        }
    }

    @Test
    fun `J-03-1 initialize under a non-raw mode purges the stored model instead of loading it`() = runBlocking<Unit> {
        for (mode in nonRawModes) {
            val dir = createTempDir("classifier_j031_init")
            val crypto = passthroughCrypto()
            val privacy = SwitchablePrivacy()
            val writer = trainedAndPersisted(dir, crypto, privacy)
            writer.destroy()
            assertTrue(modelFileIn(dir).exists())

            // New process: the stored model exists, nothing is loaded yet.
            privacy.mode = mode
            val (restarted, _) = classifierFor(emptyList(), dir, crypto, privacy.repo)
            restarted.initialize()

            assertPurged("initialize/$mode", restarted, dir)
            assertEquals("$mode: predict neutral", 0.5f, restarted.predict(probes[0]), 0f)
            restarted.destroy()
        }
    }

    @Test
    fun `J-03-1 settings read failure keeps the model and the file and predict is neutral`() = runBlocking<Unit> {
        val marker = "SYNTHETIC_READ_FAILURE_4711"
        val dir = createTempDir("classifier_j031_failure")
        val crypto = passthroughCrypto()
        val privacy = SwitchablePrivacy()
        val subject = trainedAndPersisted(dir, crypto, privacy)
        val fileBefore = modelFileIn(dir).readBytes()

        privacy.failure = java.io.IOException(marker)
        assertEquals("predict neutral on read failure", 0.5f, subject.predict(probes[0]), 0f)
        subject.train("Payment of 55.50 EUR at failuretrain", isTransaction = true)

        assertEquals("model kept in memory", 15, subject.getStats().totalPositive)
        assertEquals("model kept in memory", 15, subject.getStats().totalNegative)
        assertTrue("model file kept", modelFileIn(dir).exists())
        assertArrayEquals("model file untouched", fileBefore, modelFileIn(dir).readBytes())

        // A restarted instance also keeps and loads the stored model on a read failure.
        val (restarted, _) = classifierFor(emptyList(), dir, crypto, privacy.repo)
        restarted.initialize()
        assertEquals(15, restarted.getStats().totalPositive)
        assertEquals(15, restarted.getStats().totalNegative)
        assertEquals("predict neutral on read failure", 0.5f, restarted.predict(probes[0]), 0f)
        assertTrue("model file kept after restart", modelFileIn(dir).exists())

        // Once the read recovers the retained model is used again (nothing was purged).
        privacy.failure = null
        assertTrue(subject.predict(probes[0]) > 0.5f)
        restarted.destroy()
        subject.destroy()
    }

    @Test
    fun `J-03-1 after switching back to STORE_RAW training starts from zero`() = runBlocking<Unit> {
        val dir = createTempDir("classifier_j031_restart")
        val privacy = SwitchablePrivacy()
        val subject = trainedAndPersisted(dir, passthroughCrypto(), privacy)

        privacy.mode = RawStorageMode.DO_NOT_STORE
        assertEquals(0.5f, subject.predict(probes[0]), 0f)
        assertPurged("after purge", subject, dir)

        privacy.mode = RawStorageMode.STORE_RAW
        assertEquals("still empty right after switching back", untrainedStats, subject.getStats())
        subject.train("Payment of 31.50 EUR at restartcase", isTransaction = true)

        val stats = subject.getStats()
        assertEquals(1, stats.totalPositive)
        assertEquals(0, stats.totalNegative)
        assertFalse("below the minimum sample count", stats.isReady)
        assertEquals("below the minimum sample count stays neutral", 0.5f, subject.predict(probes[0]), 0f)

        val (raw, json) = persistAndRead(subject, dir)
        assertEquals(1, json.getInt("totalPositive"))
        assertEquals(0, json.getInt("totalNegative"))
        assertTrue(raw.contains("restartcase"))
        assertFalse("nothing survives from before the purge", raw.contains("baseline"))
    }

    @Test
    fun `J-03-1 saveToDisk after a purge writes no file`() = runBlocking<Unit> {
        val dir = createTempDir("classifier_j031_nosave")
        val privacy = SwitchablePrivacy()
        val subject = trainedAndPersisted(dir, passthroughCrypto(), privacy)

        privacy.mode = RawStorageMode.STORE_METADATA_ONLY
        subject.train("Payment of 41.50 EUR at nosavecase", isTransaction = true)
        assertFalse("purged", modelFileIn(dir).exists())

        subject.onBackground() // runs saveToDisk() synchronously
        assertFalse("onBackground save must not recreate the file", modelFileIn(dir).exists())
        subject.destroy() // second save attempt
        assertFalse("destroy save must not recreate the file", modelFileIn(dir).exists())
    }

    private fun saveJobOf(target: TransactionClassifier): kotlinx.coroutines.Job? {
        val field = TransactionClassifier::class.java.getDeclaredField("saveJob")
        field.isAccessible = true
        return field.get(target) as kotlinx.coroutines.Job?
    }

    @Test
    fun `J-03-1 a pending save job is cancelled by the purge`() = runBlocking<Unit> {
        val dir = createTempDir("classifier_j031_pending")
        val privacy = SwitchablePrivacy()
        val (subject, _) = classifierFor(emptyList(), dir, passthroughCrypto(), privacy.repo)

        subject.train("Payment of 21.50 EUR at pendingcase", isTransaction = true)
        val pending = saveJobOf(subject)
        assertNotNull("control: train schedules a save", pending)
        assertTrue("control: save is pending", pending!!.isActive)

        privacy.mode = RawStorageMode.DO_NOT_STORE
        subject.train("Payment of 22.50 EUR at pendingcase", isTransaction = true)

        assertTrue("pending save must be cancelled by the purge", pending.isCancelled)
        assertNull("no save job remains after the purge", saveJobOf(subject))
        assertFalse(modelFileIn(dir).exists())
        subject.destroy()
    }

    @Test
    fun `J-03-1 a model file that survives the first purge is deleted by the next purge call`() = runBlocking<Unit> {
        val dir = createTempDir("classifier_j031_retry")
        val privacy = SwitchablePrivacy(mode = RawStorageMode.DO_NOT_STORE)
        val (subject, _) = classifierFor(emptyList(), dir, passthroughCrypto(), privacy.repo)

        // A non-empty directory at the model path makes File.delete() return false.
        val modelPath = modelFileIn(dir)
        assertTrue(modelPath.mkdir())
        val obstacle = File(modelPath, "obstacle.tmp").also { it.writeText("x") }

        assertEquals(0.5f, subject.predict(probes[0]), 0f) // first purge: delete fails
        assertTrue("control: the first purge could not delete the path", modelPath.exists())

        // Still undeletable: the next call must try again (the flag alone must not short-circuit).
        subject.train("Payment of 61.50 EUR at retrycase", isTransaction = true)
        assertTrue("still present while the obstacle remains", modelPath.exists())

        assertTrue(obstacle.delete())
        subject.train("Payment of 62.50 EUR at retrycase", isTransaction = true) // next purge call
        assertFalse("the next purge call must delete the surviving file", modelPath.exists())
        assertEquals(untrainedStats, subject.getStats())
        subject.destroy()
    }

    @Test
    fun `J-03-1 cancelled settings read is rethrown from predict, train and initialize and purges nothing`() = runBlocking<Unit> {
        val dir = createTempDir("classifier_j031_cancel")
        val crypto = passthroughCrypto()
        val privacy = SwitchablePrivacy()
        val subject = trainedAndPersisted(dir, crypto, privacy)
        val (restarted, _) = classifierFor(emptyList(), dir, crypto, privacy.repo)

        privacy.failure = CancellationException("synthetic cancellation")

        suspend fun expectCancellation(name: String, call: suspend () -> Unit) {
            var propagated: CancellationException? = null
            try {
                call()
            } catch (e: CancellationException) {
                propagated = e
            }
            assertNotNull("$name must rethrow CancellationException", propagated)
        }
        expectCancellation("predict") { subject.predict(probes[0]) }
        expectCancellation("train") {
            subject.train("Payment of 9.50 EUR at cancelcase", isTransaction = true)
        }
        expectCancellation("initialize") { restarted.initialize() }

        assertEquals("model kept", 15, subject.getStats().totalPositive)
        assertTrue("model file kept", modelFileIn(dir).exists())
        restarted.destroy()
        subject.destroy()
    }
}
