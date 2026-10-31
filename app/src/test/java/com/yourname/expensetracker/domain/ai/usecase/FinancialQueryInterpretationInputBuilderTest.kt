package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.ai.model.AiCapability
import com.yourname.expensetracker.domain.ai.model.AiChatMessage
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.AssistantMessageKind
import com.yourname.expensetracker.domain.ai.model.AssistantMessageRole
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.common.sha256Prefix
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver
import com.yourname.expensetracker.domain.privacy.FakePrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.data.ai.provider.OnDeviceQueryInterpretationService
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FinancialQueryInterpretationInputBuilderTest {

    private lateinit var categoryRepository: CategoryRepository
    private lateinit var expenseRepository: ExpenseRepository
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var aiPolicy: AiPolicy
    private lateinit var aiSettingsRepository: CountingAiSettingsRepository
    private lateinit var builder: FinancialQueryInterpretationInputBuilder

    @Before
    fun setup() {
        categoryRepository = mockk()
        expenseRepository = mockk()
        timeProvider = FakeTimeProvider(1_710_000_000_000L)
        // Deliberately no stub for aiPolicy.shouldRedact: redaction must come only from the
        // EffectiveCloudAiPolicyResolver (INV-C18); a call to shouldRedact would fail the test.
        aiPolicy = mockk()
        every { aiPolicy.canUseCloudFor(any(), AiCapability.QUERY_INTERPRETATION) } returns true
        builder = newBuilder(privacyRedact = false, aiRedact = false)
    }

    private fun newBuilder(
        privacyRedact: Boolean,
        aiRedact: Boolean,
        policy: AiPolicy = aiPolicy
    ): FinancialQueryInterpretationInputBuilder {
        aiSettingsRepository = CountingAiSettingsRepository(AiSettings(redactBeforeCloud = aiRedact))
        val resolver = EffectiveCloudAiPolicyResolver(
            FakePrivacySettingsRepository(PrivacySettings(redactBeforeCloud = privacyRedact)),
            aiSettingsRepository
        )
        return FinancialQueryInterpretationInputBuilder(
            categoryRepository = categoryRepository,
            expenseRepository = expenseRepository,
            timeProvider = timeProvider,
            aiPolicy = policy,
            effectiveCloudAiPolicyResolver = resolver
        )
    }

    private fun message(
        id: Long,
        role: AssistantMessageRole,
        text: String,
        payloadJson: String? = null
    ) = AiChatMessage(
        id = id,
        sessionId = 1L,
        role = role,
        kind = AssistantMessageKind.QUERY,
        text = text,
        payloadJson = payloadJson,
        createdAt = id
    )

    @Test
    fun `build trims truncates and enriches interpretation input`() = runTest {
        coEvery { categoryRepository.getAll() } returns listOf(
            Category(id = 2L, name = "Transport", icon = "T", color = "#0000FF"),
            Category(id = 1L, name = "Groceries", icon = "G", color = "#00FF00")
        )
        coEvery { expenseRepository.getRecentMerchantNames() } returns listOf(
            "Lidl",
            "Lidl",
            "Spotify"
        )
        val rawQuery = "  ${"x".repeat(AppConfig.Ai.MAX_QUERY_INPUT_CHARS + 12)}  "
        // AE-C3: 7 prior USER turns (the 7th being 450 x "y") interleaved with 7 ASSISTANT turns.
        val history = (1..7).flatMap { turn ->
            val userText = if (turn == 7) "y".repeat(450) else "user-$turn"
            listOf(
                message(id = turn * 2L - 1, role = AssistantMessageRole.USER, text = userText),
                message(id = turn * 2L, role = AssistantMessageRole.ASSISTANT, text = "assistant-$turn")
            )
        }

        val result = builder.build(
            rawQuery = rawQuery,
            settings = AiSettings(),
            priorMessages = history
        )

        assertEquals(AppConfig.Ai.MAX_QUERY_INPUT_CHARS, result.rawQuery.length)
        assertTrue(result.rawQuery.all { it == 'x' })
        assertEquals(timeProvider.now(), result.currentTimeMs)
        assertEquals(listOf("Groceries", "Transport"), result.categoryNames)
        assertEquals(listOf("Lidl", "Spotify"), result.merchantNames)
        assertEquals("Lidl", result.merchantLookupMap["Lidl"])
        assertEquals(1L, result.categoryLookupMap["Groceries"])
        assertTrue(result.merchantAliasMap.isEmpty())
        assertTrue(result.categoryAliasMap.isEmpty())

        assertEquals(AppConfig.Ai.MAX_QUERY_HISTORY_USER_TURNS_FOR_MODEL, result.priorUserQueries.size)
        assertEquals(
            listOf("user-4", "user-5", "user-6", "y".repeat(AppConfig.Ai.MAX_QUERY_INPUT_CHARS)),
            result.priorUserQueries
        )
        assertEquals(AppConfig.Ai.MAX_QUERY_INPUT_CHARS, result.priorUserQueries.last().length)
    }

    @Test
    fun `build keeps all prior user queries when fewer than the cap exist`() = runTest {
        coEvery { categoryRepository.getAll() } returns emptyList()
        coEvery { expenseRepository.getRecentMerchantNames() } returns emptyList()
        val history = listOf(
            message(1L, AssistantMessageRole.USER, "first"),
            message(2L, AssistantMessageRole.ASSISTANT, "answer"),
            message(3L, AssistantMessageRole.USER, "second")
        )

        val result = builder.build("third", AiSettings(), history)

        assertEquals(listOf("first", "second"), result.priorUserQueries)
    }

    @Test
    fun `build drops blank prior user queries`() = runTest {
        coEvery { categoryRepository.getAll() } returns emptyList()
        coEvery { expenseRepository.getRecentMerchantNames() } returns emptyList()
        val history = listOf(
            message(1L, AssistantMessageRole.USER, "   "),
            message(2L, AssistantMessageRole.USER, "  show food  "),
            message(3L, AssistantMessageRole.USER, "")
        )

        val result = builder.build("total", AiSettings(), history)

        assertEquals(listOf("show food"), result.priorUserQueries)
    }

    @Test
    fun `build caps merchant context to 100 entries`() = runTest {
        coEvery { categoryRepository.getAll() } returns emptyList()
        coEvery { expenseRepository.getRecentMerchantNames() } returns (1..120).map { "Merchant $it" }

        val result = builder.build(rawQuery = "total this month", settings = AiSettings())

        assertEquals(100, result.merchantNames.size)
        assertEquals("Merchant 1", result.merchantNames.first())
        assertEquals("Merchant 100", result.merchantNames.last())
    }

    @Test
    fun `build creates reversible alias maps when redaction is enabled`() = runTest {
        builder = newBuilder(privacyRedact = true, aiRedact = false)
        coEvery { categoryRepository.getAll() } returns listOf(
            Category(id = 1L, name = "Groceries", icon = "G", color = "#00FF00"),
            Category(id = 2L, name = "Transport", icon = "T", color = "#0000FF")
        )
        coEvery { expenseRepository.getRecentMerchantNames() } returns listOf("Lidl", "Uber")
        val history = listOf(
            AiChatMessage(
                id = 1L,
                sessionId = 1L,
                role = AssistantMessageRole.USER,
                kind = AssistantMessageKind.QUERY,
                text = "Show Lidl groceries",
                createdAt = 1L
            )
        )

        val result = builder.build(
            rawQuery = "Card 4242 4242 4242 4242 spent at Lidl for Groceries",
            settings = AiSettings(),
            priorMessages = history
        )

        val lidlAlias = "merchant_${"Lidl".sha256Prefix()}"
        val uberAlias = "merchant_${"Uber".sha256Prefix()}"
        val groceriesAlias = "category_${"Groceries".sha256Prefix()}"
        val transportAlias = "category_${"Transport".sha256Prefix()}"

        assertEquals(listOf(lidlAlias, uberAlias), result.merchantNames)
        assertEquals("Lidl", result.merchantAliasMap[lidlAlias])
        assertEquals("Uber", result.merchantAliasMap[uberAlias])
        assertEquals("Lidl", result.merchantLookupMap[lidlAlias])
        assertTrue(result.merchantLookupMap["Lidl"] == null)

        assertTrue(result.categoryNames.contains(groceriesAlias))
        assertTrue(result.categoryNames.contains(transportAlias))
        assertEquals("Groceries", result.categoryAliasMap[groceriesAlias])
        assertEquals("Transport", result.categoryAliasMap[transportAlias])
        assertEquals(1L, result.categoryLookupMap[groceriesAlias])
        assertEquals(2L, result.categoryLookupMap[transportAlias])
        assertTrue(result.categoryLookupMap["Groceries"] == null)
        assertEquals(1L, result.categoryNameToIdMap[groceriesAlias])
        assertTrue(result.categoryNameToIdMap["Groceries"] == null)

        assertTrue(result.rawQuery.contains("[REDACTED_CARD]"))
        assertTrue(result.rawQuery.contains(lidlAlias))
        assertTrue(result.rawQuery.contains(groceriesAlias))
        assertTrue(result.priorUserQueries.single().contains(lidlAlias))
        assertTrue(result.priorUserQueries.single().contains(groceriesAlias))
    }

    @Test
    fun `build redacts when privacy requires it even though ai redaction is off`() = runTest {
        // AE-C29 / INV-C18: privacy redact ON + AI redact OFF, cloud usable for query
        // interpretation -> labels hashed, decision taken from the effective-policy resolver.
        builder = newBuilder(privacyRedact = true, aiRedact = false)
        coEvery { categoryRepository.getAll() } returns listOf(
            Category(id = 1L, name = "Groceries", icon = "G", color = "#00FF00")
        )
        coEvery { expenseRepository.getRecentMerchantNames() } returns listOf("Lidl")

        val cloudOnRedactionOff = AiSettings(
            aiEnabled = true,
            allowCloudAi = true,
            queryInterpretationEnabled = true,
            redactBeforeCloud = false
        )

        val result = builder.build(
            rawQuery = "spent at Lidl for Groceries",
            settings = cloudOnRedactionOff
        )

        val lidlAlias = "merchant_${"Lidl".sha256Prefix()}"
        val groceriesAlias = "category_${"Groceries".sha256Prefix()}"
        assertEquals(listOf(lidlAlias), result.merchantNames)
        assertTrue(result.categoryNames.contains(groceriesAlias))
        assertTrue(result.merchantLookupMap["Lidl"] == null)
        assertTrue(result.rawQuery.contains(lidlAlias))
        assertTrue(result.rawQuery.contains(groceriesAlias))
        assertTrue(
            "redaction decision must be read through the effective-policy resolver",
            aiSettingsRepository.settingsReads >= 1
        )
    }

    @Test
    fun `build does not redact when neither privacy nor ai redaction is on`() = runTest {
        builder = newBuilder(privacyRedact = false, aiRedact = false)
        coEvery { categoryRepository.getAll() } returns listOf(
            Category(id = 1L, name = "Groceries", icon = "G", color = "#00FF00")
        )
        coEvery { expenseRepository.getRecentMerchantNames() } returns listOf("Lidl")
        val history = listOf(message(1L, AssistantMessageRole.USER, "Lidl groceries"))

        val result = builder.build(
            rawQuery = "spent at Lidl for Groceries",
            settings = AiSettings(
                aiEnabled = true,
                allowCloudAi = true,
                queryInterpretationEnabled = true,
                redactBeforeCloud = false
            ),
            priorMessages = history
        )

        assertEquals(listOf("Lidl"), result.merchantNames)
        assertEquals(listOf("Groceries"), result.categoryNames)
        assertEquals("spent at Lidl for Groceries", result.rawQuery)
        assertEquals(listOf("Lidl groceries"), result.priorUserQueries)
        assertTrue(result.merchantAliasMap.isEmpty())
        assertTrue(result.categoryAliasMap.isEmpty())
    }

    @Test
    fun `build redacts when only ai redaction is on`() = runTest {
        builder = newBuilder(privacyRedact = false, aiRedact = true)
        coEvery { categoryRepository.getAll() } returns emptyList()
        coEvery { expenseRepository.getRecentMerchantNames() } returns listOf("Lidl")

        val result = builder.build("spent at Lidl", AiSettings())

        val lidlAlias = "merchant_${"Lidl".sha256Prefix()}"
        assertEquals(listOf(lidlAlias), result.merchantNames)
        assertTrue(result.rawQuery.contains(lidlAlias))
    }

    @Test
    fun `AE-C1 INV-C1 assistant and system text and payload never reach priorUserQueries`() = runTest {
        coEvery { categoryRepository.getAll() } returns emptyList()
        coEvery { expenseRepository.getRecentMerchantNames() } returns emptyList()
        val history = listOf(
            message(1L, AssistantMessageRole.USER, "food this month"),
            message(
                id = 2L,
                role = AssistantMessageRole.ASSISTANT,
                text = "Total spending\n123.45 EUR\nPrevious period: 98.10 EUR",
                payloadJson = """{"primaryText":"Total spending 123.45 EUR"}"""
            ),
            message(3L, AssistantMessageRole.USER, "show transport"),
            message(
                id = 4L,
                role = AssistantMessageRole.ASSISTANT,
                text = "Matching category transactions (7)",
                payloadJson = """{"primaryText":"Matching (7)"}"""
            ),
            message(5L, AssistantMessageRole.SYSTEM, "system-note secret-sys-text")
        )

        val result = builder.build("transport last month", AiSettings(), history)

        assertEquals(listOf("food this month", "show transport"), result.priorUserQueries)
        val joined = result.priorUserQueries.joinToString("\n")
        listOf("123.45", "98.10", "(7)", "primaryText", "Total spending", "secret-sys-text").forEach {
            assertFalse("priorUserQueries leaked '$it'", joined.contains(it))
        }
    }

    @Test
    fun `AE-C1 INV-C1 history with no user rows yields empty priorUserQueries`() = runTest {
        coEvery { categoryRepository.getAll() } returns emptyList()
        coEvery { expenseRepository.getRecentMerchantNames() } returns emptyList()
        val history = listOf(
            message(1L, AssistantMessageRole.ASSISTANT, "an answer", payloadJson = """{"k":"v"}"""),
            message(2L, AssistantMessageRole.SYSTEM, "a system note")
        )

        val result = builder.build("food", AiSettings(), history)

        assertTrue(result.priorUserQueries.isEmpty())
    }

    @Test
    fun `AE-C1 INV-C1 assistant text stays out of priorUserQueries when redaction is on`() = runTest {
        builder = newBuilder(privacyRedact = true, aiRedact = false)
        coEvery { categoryRepository.getAll() } returns emptyList()
        coEvery { expenseRepository.getRecentMerchantNames() } returns emptyList()
        val history = listOf(
            message(1L, AssistantMessageRole.USER, "food this month"),
            message(2L, AssistantMessageRole.ASSISTANT, "Total spending 123.45 EUR")
        )

        val result = builder.build("food", AiSettings(), history)

        assertEquals(listOf("food this month"), result.priorUserQueries)
    }

    @Test
    fun `AE-C4 INV-C2 prior user queries get alias and pii redaction when redaction is on`() = runTest {
        builder = newBuilder(privacyRedact = true, aiRedact = false)
        coEvery { categoryRepository.getAll() } returns listOf(
            Category(id = 1L, name = "Groceries", icon = "G", color = "#00FF00")
        )
        coEvery { expenseRepository.getRecentMerchantNames() } returns listOf("Lidl")
        val history = listOf(
            message(1L, AssistantMessageRole.USER, "Lidl groceries card 4242 4242 4242 4242")
        )

        val result = builder.build("what about last week", AiSettings(), history)

        val lidlAlias = "merchant_${"Lidl".sha256Prefix()}"
        val groceriesAlias = "category_${"Groceries".sha256Prefix()}"
        val entry = result.priorUserQueries.single()
        assertTrue(entry.contains(lidlAlias))
        assertTrue(entry.contains(groceriesAlias))
        assertTrue(entry.contains("[REDACTED_CARD]"))
        assertFalse(entry.contains("Lidl", ignoreCase = true))
        // Strip the (hex) aliases first so a digit run inside a hash cannot cause a false failure.
        assertFalse(entry.replace(lidlAlias, "").replace(groceriesAlias, "").contains("4242"))
    }

    @Test
    fun `AE-C4 INV-C2 prior user queries are left intact when redaction is off`() = runTest {
        coEvery { categoryRepository.getAll() } returns listOf(
            Category(id = 1L, name = "Groceries", icon = "G", color = "#00FF00")
        )
        coEvery { expenseRepository.getRecentMerchantNames() } returns listOf("Lidl")
        val history = listOf(
            message(1L, AssistantMessageRole.USER, "Lidl groceries card 4242 4242 4242 4242")
        )

        val result = builder.build("what about last week", AiSettings(), history)

        assertEquals(listOf("Lidl groceries card 4242 4242 4242 4242"), result.priorUserQueries)
    }

    @Test
    fun `INV-C18 no redaction when cloud is not usable even if resolver says redact`() = runTest {
        val cloudDenied = mockk<AiPolicy>()
        every { cloudDenied.canUseCloudFor(any(), AiCapability.QUERY_INTERPRETATION) } returns false
        builder = newBuilder(privacyRedact = true, aiRedact = true, policy = cloudDenied)
        coEvery { categoryRepository.getAll() } returns listOf(
            Category(id = 1L, name = "Groceries", icon = "G", color = "#00FF00")
        )
        coEvery { expenseRepository.getRecentMerchantNames() } returns listOf("Lidl")
        val history = listOf(message(1L, AssistantMessageRole.USER, "Lidl groceries"))

        val result = builder.build("spent at Lidl for Groceries", AiSettings(), history)

        assertEquals(listOf("Lidl"), result.merchantNames)
        assertEquals(listOf("Groceries"), result.categoryNames)
        assertEquals("spent at Lidl for Groceries", result.rawQuery)
        assertEquals(listOf("Lidl groceries"), result.priorUserQueries)
        assertTrue(result.merchantAliasMap.isEmpty())
        assertTrue(result.categoryAliasMap.isEmpty())
    }

    // ── Resolver failure: fail closed, never return an unredacted input ─────────────────────

    private fun builderWithResolver(resolver: EffectiveCloudAiPolicyResolver) =
        FinancialQueryInterpretationInputBuilder(
            categoryRepository = categoryRepository,
            expenseRepository = expenseRepository,
            timeProvider = timeProvider,
            aiPolicy = aiPolicy,
            effectiveCloudAiPolicyResolver = resolver
        )

    private fun stubEmptyContext() {
        coEvery { categoryRepository.getAll() } returns emptyList()
        coEvery { expenseRepository.getRecentMerchantNames() } returns emptyList()
    }

    @Test
    fun `INV-C18 build throws when the privacy settings read fails with IOException`() =
        runTest(timeout = 60.seconds) {
            stubEmptyContext()
            val failing = builderWithResolver(
                EffectiveCloudAiPolicyResolver(
                    ThrowingPrivacySettingsRepository(IOException("synthetic privacy read failure")),
                    CountingAiSettingsRepository(AiSettings(redactBeforeCloud = true))
                )
            )

            assertFailsWith<IOException> {
                failing.build(
                    rawQuery = "spent at Lidl",
                    settings = AiSettings(),
                    priorMessages = listOf(message(1L, AssistantMessageRole.USER, "Lidl groceries"))
                )
            }
        }

    @Test
    fun `INV-C18 build throws when the ai settings flow fails with IllegalStateException`() =
        runTest(timeout = 60.seconds) {
            stubEmptyContext()
            val failing = builderWithResolver(
                EffectiveCloudAiPolicyResolver(
                    FakePrivacySettingsRepository(PrivacySettings(redactBeforeCloud = true)),
                    ThrowingAiSettingsRepository(IllegalStateException("synthetic ai settings failure"))
                )
            )

            assertFailsWith<IllegalStateException> {
                failing.build(rawQuery = "spent at Lidl", settings = AiSettings())
            }
        }

    @Test
    fun `INV-C18 build rethrows CancellationException from the privacy settings read`() =
        runTest(timeout = 60.seconds) {
            stubEmptyContext()
            val cancelling = builderWithResolver(
                EffectiveCloudAiPolicyResolver(
                    ThrowingPrivacySettingsRepository(CancellationException("synthetic cancellation")),
                    CountingAiSettingsRepository(AiSettings())
                )
            )

            assertFailsWith<CancellationException> {
                cancelling.build(rawQuery = "spent at Lidl", settings = AiSettings())
            }
        }

    @Test
    fun `INV-C18 build rethrows CancellationException from the ai settings flow`() =
        runTest(timeout = 60.seconds) {
            stubEmptyContext()
            val cancelling = builderWithResolver(
                EffectiveCloudAiPolicyResolver(
                    FakePrivacySettingsRepository(PrivacySettings(redactBeforeCloud = true)),
                    ThrowingAiSettingsRepository(CancellationException("synthetic cancellation"))
                )
            )

            assertFailsWith<CancellationException> {
                cancelling.build(rawQuery = "spent at Lidl", settings = AiSettings())
            }
        }

    // ── AE-C1 end to end: builder output rendered by the real prompt builder ────────────────

    @Test
    fun `AE-C1 INV-C1 rendered prompt has only USER history and the current query exactly once`() =
        runTest(timeout = 60.seconds) {
            stubEmptyContext()
            val history = listOf(
                message(1L, AssistantMessageRole.USER, "food this month"),
                message(
                    id = 2L,
                    role = AssistantMessageRole.ASSISTANT,
                    text = "Total spending\n123.45 EUR\nPrevious period: 98.10 EUR",
                    payloadJson = """{"primaryText":"Total spending 123.45 EUR"}"""
                ),
                message(3L, AssistantMessageRole.USER, "show transport"),
                message(
                    id = 4L,
                    role = AssistantMessageRole.ASSISTANT,
                    text = "Matching category transactions (7)",
                    payloadJson = """{"primaryText":"Matching (7)"}"""
                )
            )

            val input = builder.build("transport last month", AiSettings(), history)
            val prompt = OnDeviceQueryInterpretationService().buildPrompt(input)

            assertTrue(prompt.contains("- USER: food this month"))
            assertTrue(prompt.contains("- USER: show transport"))
            listOf("ASSISTANT", "123.45", "98.10", "(7)", "primaryText").forEach {
                assertFalse("rendered prompt leaked '$it'", prompt.contains(it))
            }
            assertEquals(
                "current raw query must appear exactly once (the Raw query line)",
                1,
                Regex(Regex.escape("transport last month")).findAll(prompt).count()
            )
            assertTrue(prompt.lines().contains("Raw query: transport last month"))
        }

    private class ThrowingPrivacySettingsRepository(private val failure: Throwable) : PrivacySettingsRepository {
        override fun observeSettings(): Flow<PrivacySettings> = flow { throw failure }
        override fun observeLoadState(): Flow<PrivacySettingsLoadState> = flow { throw failure }
        override suspend fun getSettings(): PrivacySettings = throw failure
        override suspend fun getLoadState(): PrivacySettingsLoadState = throw failure
        override suspend fun updateSettings(transform: (PrivacySettings) -> PrivacySettings) {
            throw failure
        }
    }

    private class ThrowingAiSettingsRepository(private val failure: Throwable) : AiSettingsRepository {
        override fun settings(): Flow<AiSettings> = flow { throw failure }
        override suspend fun update(transform: (AiSettings) -> AiSettings) {
            throw failure
        }
    }

    /** Real [AiSettingsRepository] semantics (flow of settings) plus a read counter. */
    private class CountingAiSettingsRepository(initial: AiSettings) : AiSettingsRepository {
        private val state = MutableStateFlow(initial)

        @Volatile
        var settingsReads: Int = 0
            private set

        override fun settings(): Flow<AiSettings> = state.onStart { settingsReads++ }

        override suspend fun update(transform: (AiSettings) -> AiSettings) {
            state.value = transform(state.value)
        }
    }
}
