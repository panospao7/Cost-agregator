package com.yourname.expensetracker.domain.naturallanguage

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalLanguageSearchEngineVoiceInputTest {

    @Test
    fun `start voice input forwards result callback`() {
        val speechGateway = FakeSpeechInputGateway()
        val engine = NaturalLanguageSearchEngine(
            expenseQueryRepository = FakeNaturalLanguageExpenseQueryRepository(),
            speechInputGateway = speechGateway,
            timeProvider = mockk(),
            currencyConverter = mockk(),
            currencySettingsRepository = mockk(),
            categoryRepository = mockk(),
            merchantNormalizationRepository = mockk(relaxed = true),
        )

        var receivedResult: String? = null

        engine.startVoiceInput(onResult = { receivedResult = it })
        speechGateway.emitResult("coffee expenses")

        assertEquals("coffee expenses", receivedResult)
    }

    @Test
    fun `start voice input forwards error callback`() {
        val speechGateway = FakeSpeechInputGateway()
        val engine = NaturalLanguageSearchEngine(
            expenseQueryRepository = FakeNaturalLanguageExpenseQueryRepository(),
            speechInputGateway = speechGateway,
            timeProvider = mockk(),
            currencyConverter = mockk(),
            currencySettingsRepository = mockk(),
            categoryRepository = mockk(),
            merchantNormalizationRepository = mockk(relaxed = true),
        )

        var receivedError: SpeechInputError? = null

        engine.startVoiceInput(onResult = {}, onError = { receivedError = it })
        speechGateway.emitError(SpeechInputError.PermissionDenied)

        assertEquals(SpeechInputError.PermissionDenied, receivedError)
    }

    @Test
    fun `start voice input keeps default error callback optional`() {
        val speechGateway = FakeSpeechInputGateway()
        val engine = NaturalLanguageSearchEngine(
            expenseQueryRepository = FakeNaturalLanguageExpenseQueryRepository(),
            speechInputGateway = speechGateway,
            timeProvider = mockk(),
            currencyConverter = mockk(),
            currencySettingsRepository = mockk(),
            categoryRepository = mockk(),
            merchantNormalizationRepository = mockk(relaxed = true),
        )

        engine.startVoiceInput(onResult = {})

        assertTrue(speechGateway.started)
    }

    @Test
    fun `location query returns empty results with unsupported flag`() = runTest {
        val engine = NaturalLanguageSearchEngine(
            expenseQueryRepository = FakeNaturalLanguageExpenseQueryRepository(),
            speechInputGateway = FakeSpeechInputGateway(),
            timeProvider = mockk(),
            currencyConverter = mockk(),
            currencySettingsRepository = mockk(),
            categoryRepository = mockk(),
            merchantNormalizationRepository = mockk(relaxed = true),
        )

        val interpretation = NaturalLanguageSearchEngine.QueryInterpretation(
            originalQuery = "show me spending in Paris",
            queryType = NaturalLanguageSearchEngine.QueryType.FIND_TRANSACTIONS,
            extractedAmounts = null,
            dateRange = null,
            locations = listOf("Paris"),
            categories = null,
            merchants = null,
            searchFilter = NaturalLanguageSearchEngine.SearchFilter(
                minAmount = null, maxAmount = null, exactAmount = null,
                startDate = null, endDate = null,
                locations = listOf("Paris"),
                categories = null, merchants = null
            ),
            confidence = 70.0
        )

        val results = engine.executeSearch(interpretation)

        assertTrue("Expected empty results for location query", results.isEmpty())
        assertTrue("Expected unsupportedLocations flag set", interpretation.dataQuality.unsupportedLocations)
    }

    @Test
    fun `non-location query still proceeds normally`() = runTest {
        val engine = NaturalLanguageSearchEngine(
            expenseQueryRepository = FakeNaturalLanguageExpenseQueryRepository(),
            speechInputGateway = FakeSpeechInputGateway(),
            timeProvider = mockk(),
            currencyConverter = mockk(),
            currencySettingsRepository = mockk(),
            categoryRepository = mockk(),
            merchantNormalizationRepository = mockk(relaxed = true),
        )

        val interpretation = NaturalLanguageSearchEngine.QueryInterpretation(
            originalQuery = "show me food expenses",
            queryType = NaturalLanguageSearchEngine.QueryType.FIND_TRANSACTIONS,
            extractedAmounts = null,
            dateRange = null,
            locations = null,
            categories = null,
            merchants = null,
            searchFilter = NaturalLanguageSearchEngine.SearchFilter(
                minAmount = null, maxAmount = null, exactAmount = null,
                startDate = null, endDate = null,
                locations = null, categories = null, merchants = null
            ),
            confidence = 70.0
        )

        val results = engine.executeSearch(interpretation)
        assertFalse("Non-location queries should not set unsupportedLocations", interpretation.dataQuality.unsupportedLocations)
    }

    private class FakeSpeechInputGateway : SpeechInputGateway {
        var started = false
        private var onResult: ((String) -> Unit)? = null
        private var onError: ((SpeechInputError) -> Unit)? = null

        override fun isAvailable(): Boolean = true

        override fun startListening(
            onResult: (String) -> Unit,
            onError: (SpeechInputError) -> Unit
        ) {
            started = true
            this.onResult = onResult
            this.onError = onError
        }

        override fun stopListening() = Unit

        override fun destroy() = Unit

        fun emitResult(result: String) {
            onResult?.invoke(result)
        }

        fun emitError(error: SpeechInputError) {
            onError?.invoke(error)
        }
    }

    private class FakeNaturalLanguageExpenseQueryRepository : NaturalLanguageExpenseQueryRepository {
        override suspend fun getExpensesBetween(startMs: Long, endMs: Long): List<NaturalLanguageExpense> {
            return emptyList()
        }

        override suspend fun getExpensesBetweenFiltered(
            startMs: Long,
            endMs: Long,
            merchants: List<String>?,
            categories: List<String>?,
            minAmount: Double?,
            maxAmount: Double?
        ): List<NaturalLanguageExpense> = emptyList()

        override suspend fun getExpensesBetweenFilteredKeyset(
            startMs: Long,
            endMs: Long,
            categoryIds: Set<Long>?,
            merchants: List<String>?,
            transactionType: String?,
            keywordSearch: String?,
            limit: Int,
            cursor: com.yourname.expensetracker.domain.naturallanguage.SearchCursor?
        ): List<NaturalLanguageExpense> = emptyList()
    }
}