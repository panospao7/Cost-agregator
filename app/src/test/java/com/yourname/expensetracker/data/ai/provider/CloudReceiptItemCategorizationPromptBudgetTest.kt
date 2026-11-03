package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationInput
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationResult
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.CategorizeReceiptItemsUseCase
import com.yourname.expensetracker.domain.dto.CategoryRef
import com.yourname.expensetracker.domain.privacy.CloudPayloadPolicy
import com.yourname.expensetracker.domain.privacy.CloudPayloadPurpose
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver
import com.yourname.expensetracker.domain.privacy.PreparedCloudPayload
import com.yourname.expensetracker.domain.privacy.PrivacyAuditContext
import com.yourname.expensetracker.domain.privacy.PrivacyAuditLogger
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.SafePrivacyMetadata
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

/**
 * N-05-3 pins for the redaction-cap prompt budget of [CloudReceiptItemCategorizationService].
 *
 * The REAL [DefaultCloudPayloadPolicy] and [DefaultCloudPayloadRedactor] (with the real
 * CloudPiiSanitizer) are used; only the HTTP transport is faked, and it captures the text that
 * is actually sent. The only wrapper around the policy is a pure recorder that delegates every
 * call (it does not alter prepareText).
 */
class CloudReceiptItemCategorizationPromptBudgetTest {

    private val markerRegex = Regex("""\[sourceIndex=(\d+)\]""")
    private val redactor = DefaultCloudPayloadRedactor()
    private val tree = CapturingTree()

    @Before
    fun plantTree() {
        Timber.plant(tree)
    }

    @After
    fun uprootTree() {
        Timber.uprootAll()
    }

    // ---------------------------------------------------------------- 1. cap, schema, whole lines

    @Test
    fun `redaction on 60 items keeps schema block, ends with whole item line and stays within real cap`() = runBlocking {
        val scenario = plainScenario(60)
        val h = harness(scenario, redact = true)

        val result = h.service.categorizeItems(scenario.input)

        assertEquals("exactly one request must reach the transport", 1, h.sentTexts.size)
        val sent = h.sentTexts.single()
        val realCap = realRedactorCap()

        assertTrue("Output JSON format block must survive the cap", sent.contains("Output JSON format:"))
        assertTrue("schema tail must survive the cap", sent.contains("\"suggestedNewCategories\": [\"Sportswear\"]"))
        assertTrue("Items: header must survive", sent.contains("Items:"))
        assertTrue("sent text (${sent.length}) must be within the real cap ($realCap)", sent.length <= realCap)

        val sentCount = assertSentTextWhole(sent, scenario)
        assertTrue("budget must have dropped some of 60 items, sentCount=$sentCount", sentCount in 1..59)
        assertNotNull("response covering exactly the sent items must be accepted", result)
        assertEquals(60, result!!.items.size)
    }

    @Test
    fun `characterisation - unbudgeted prompt would be cut by the real redactor which is why the budget exists`() = runBlocking {
        val scenario = plainScenario(60)
        val h = harness(scenario, redact = true)

        h.service.categorizeItems(scenario.input)

        // First (discarded) preparation is the full 60-item prompt: the real redactor cuts it.
        val first = h.policy.calls.first()
        assertTrue("full raw prompt must exceed the cap, else this scenario proves nothing",
            first.raw.length > realRedactorCap())
        assertTrue("redactor really truncated the full prompt", first.prepared!!.text.length <= realRedactorCap())
        assertFalse("the last item cannot survive in the unbudgeted prompt",
            first.prepared.text.contains("[sourceIndex=59]"))
        assertFalse(
            "the cut text must never be what is sent",
            h.sentTexts.single().contains(first.prepared.text) && first.prepared.text.length == h.sentTexts.single().length
        )
    }

    @Test
    fun `mirrored prompt budget constant does not drift above the real redactor cap`() = runBlocking {
        val scenario = plainScenario(60)
        val h = harness(scenario, redact = true)

        h.service.categorizeItems(scenario.input)

        val realCap = realRedactorCap()
        assertEquals("real redactor cap for ITEM_CATEGORIZATION changed; revisit the mirrored constant", 2000, realCap)
        val budgetedRaw = h.policy.calls.last().raw // prompt actually built at the mirrored budget
        assertTrue(
            "budgeted prompt (${budgetedRaw.length}) must be below the real cap ($realCap): constant drifted",
            budgetedRaw.length < realCap
        )
        // The real redactor must leave the budgeted prompt untruncated (all item lines intact).
        val redacted = redactor.redactText(budgetedRaw, CloudPayloadPurpose.ITEM_CATEGORIZATION).text
        assertEquals(
            "real redactor must not cut any item of the budgeted prompt",
            markerRegex.findAll(budgetedRaw).count(),
            markerRegex.findAll(redacted).count()
        )
        // The redactor also rewrites number ranges in the instructions, so the text is not compared verbatim.
        assertTrue(redacted.contains("Output JSON format:"))
    }

    // ---------------------------------------------------------------- 2. lengthening redaction

    @Test
    fun `heavy lengthening PII markers near the budget never produce a cut item`() = runBlocking {
        // "a@b.co" (6 chars) becomes "[REDACTED_EMAIL]" (16 chars): +10 per item line.
        val scenario = emailScenario(60, emailCount = 60)
        val h = harness(scenario, redact = true)

        val result = h.service.categorizeItems(scenario.input)

        val sent = h.sentTexts.single()
        assertTrue("sent text (${sent.length}) must be within the real cap", sent.length <= realRedactorCap())
        assertTrue(sent.contains("Output JSON format:"))
        val sentCount = assertSentTextWhole(sent, scenario)
        assertTrue(sentCount in 1..59)
        assertTrue("emails were redacted in every sent line",
            Regex("""\[REDACTED_EMAIL]""").findAll(sent).count() >= sentCount)
        assertNotNull("model saw whole items only, so the response must be accepted", result)
    }

    @Test
    fun `moderate lengthening within the safety margin never produces a cut item control`() = runBlocking {
        // Only two items lengthen (+20 total, inside the 50 char margin); the rest are plain.
        val scenario = emailScenario(60, emailCount = 2)
        val h = harness(scenario, redact = true)

        val result = h.service.categorizeItems(scenario.input)

        val sent = h.sentTexts.single()
        assertTrue(sent.length <= realRedactorCap())
        val sentCount = assertSentTextWhole(sent, scenario)
        assertTrue(sentCount in 1..59)
        assertNotNull(result)
    }

    // ---------------------------------------------------------------- 3. few items fit

    @Test
    fun `redaction on few items that fit are all sent and sentCount equals n`() = runBlocking {
        val scenario = plainScenario(3)
        val h = harness(scenario, redact = true)

        val result = h.service.categorizeItems(scenario.input)

        val sent = h.sentTexts.single()
        assertEquals(3, assertSentTextWhole(sent, scenario))
        assertFalse("no partial warning when everything fits", tree.messages.any { it.contains("PROMPT_BUDGET_PARTIAL") })
        assertNotNull(result)
        assertTrue("every item categorised by the model", result!!.items.all { it.suggestedCategory != null })
        assertEquals(3, result.items.size)
    }

    // ---------------------------------------------------------------- 4. redaction off

    @Test
    fun `redaction off 60 items all sent and no budget applies`() = runBlocking {
        val scenario = plainScenario(60)
        val h = harness(scenario, redact = false)

        val result = h.service.categorizeItems(scenario.input)

        val sent = h.sentTexts.single()
        assertEquals(60, assertSentTextWhole(sent, scenario))
        assertTrue("no cap applies when unredacted, text is longer than the redaction cap", sent.length > realRedactorCap())
        assertEquals("unredacted text is prepared once", 1, h.policy.calls.size)
        assertFalse(tree.messages.any { it.contains("PROMPT_BUDGET_PARTIAL") })
        assertNotNull(result)
        assertTrue(result!!.items.all { it.suggestedCategory != null })
    }

    // ---------------------------------------------------------------- 5. zero items fit

    @Test
    fun `redaction on zero items fit returns null with bounded log and no transport call`() = runBlocking {
        val hugeDescription = "abcdefghij ".repeat(300) // 3300 chars, can never fit beside the fixed part
        val scenario = Scenario(
            items = listOf(ReceiptParser.LineItem(hugeDescription, null, null, 4.5)),
            sentDescriptions = listOf(hugeDescription)
        )
        val h = harness(scenario, redact = true)

        val result = h.service.categorizeItems(scenario.input)

        assertNull(result)
        assertEquals("transport must not be called", 0, h.requestCount)
        assertEquals("no audit record when nothing is sent", 0, h.audit.records)
        val log = tree.messages.single { it.contains("PROMPT_BUDGET_NO_ITEMS") }
        assertTrue(log.contains("total=1"))
        assertTrue(Regex("""correlationId=[0-9a-f]{8}""").containsMatchIn(log))
        assertFalse("no item text in logs", tree.messages.any { it.contains("abcdefghij") })
    }

    @Test
    fun `no line items keeps the existing path and is not treated as nothing fitting`() = runBlocking {
        val scenario = Scenario(items = emptyList(), sentDescriptions = emptyList())
        val h = harness(scenario, redact = true)

        val result = h.service.categorizeItems(scenario.input)

        assertEquals(emptyList<Any>(), result?.items)
        assertEquals(1, h.requestCount)
        assertFalse(tree.messages.any { it.contains("PROMPT_BUDGET_NO_ITEMS") })
    }

    // ---------------------------------------------------------------- 6. partial response

    @Test
    fun `partial response keeps sent categories and unsent items are uncategorised placeholders in place`() = runBlocking {
        val scenario = plainScenario(60).withAdjustmentAt(59)
        val h = harness(scenario, redact = true)

        val result = requireNotNull(h.service.categorizeItems(scenario.input))

        val sentCount = markerRegex.findAll(h.sentTexts.single()).count()
        assertTrue(sentCount in 1..59)
        assertEquals(60, result.items.size)
        for (i in 0 until 60) {
            val item = result.items[i]
            assertEquals("position $i keeps the local description", scenario.items[i].description, item.itemDescription)
            assertEquals("position $i keeps the local amount", scenario.items[i].totalPrice, item.amount, 0.0)
            if (i < sentCount) {
                val category = item.suggestedCategory
                assertNotNull("sent item $i keeps its category", category)
                assertEquals(1L, category!!.categoryId)
                assertEquals("Private Category Alpha", category.categoryName)
                assertEquals(0.9f, item.confidence, 0.0001f)
            } else {
                assertNull("unsent item $i has no category", item.suggestedCategory)
                assertEquals(0f, item.confidence, 0f)
                assertTrue("unsent item $i needs review", item.needsReview)
                assertTrue(item.alternatives.isEmpty())
                assertEquals("", item.rationale)
            }
        }
        assertTrue(result.needsReview)
        assertTrue("adjustment flag of an unsent item is preserved", result.items[59].isAdjustment)
        assertFalse(result.items[58].isAdjustment)
        assertEquals(
            "average confidence covers only categorised items",
            0.9f, result.totalConfidence, 0.0001f
        )
        assertNull(
            "real CategorizeReceiptItemsUseCase.validateResult must accept the partial result",
            validate(scenario.input, result)
        )
        assertNotNull(
            "validator control: dropping an item must be rejected",
            validate(scenario.input, result.copy(items = result.items.dropLast(1)))
        )
    }

    @Test
    fun `response with sourceIndex beyond the sent items is rejected`() = runBlocking {
        val scenario = plainScenario(60)
        // Model answers all sent items but one of them claims an index that was never sent.
        val h = harness(scenario, redact = true) { sentCount, items ->
            val indices = (0 until sentCount - 1).toList() + sentCount // sentCount is valid locally, not sent
            responseText(items, indices)
        }

        val result = h.service.categorizeItems(scenario.input)

        assertNull(result)
        assertEquals(1, h.requestCount)
    }

    @Test
    fun `response with negative sourceIndex is rejected`() = runBlocking {
        val scenario = plainScenario(60)
        val h = harness(scenario, redact = true) { sentCount, items ->
            responseText(items, (0 until sentCount - 1).toList() + (-1))
        }

        assertNull(h.service.categorizeItems(scenario.input))
    }

    @Test
    fun `response count different from sentCount is rejected`() = runBlocking {
        val scenario = plainScenario(60)
        val fewer = harness(scenario, redact = true) { sentCount, items ->
            responseText(items, (0 until sentCount - 1).toList())
        }
        assertNull("one fewer than sent", fewer.service.categorizeItems(scenario.input))

        // A model that answers for all 60 although only a prefix was sent must also be rejected.
        val all = harness(scenario, redact = true) { _, items -> responseText(items, (0 until 60).toList()) }
        assertNull("answers for items that were not sent", all.service.categorizeItems(scenario.input))
    }

    @Test
    fun `control - response covering exactly the sent items is accepted`() = runBlocking {
        val scenario = plainScenario(60)
        val h = harness(scenario, redact = true)
        assertNotNull(h.service.categorizeItems(scenario.input))
    }

    // ---------------------------------------------------------------- 7. prepareText twice / audit

    @Test
    fun `characterisation - prepareText is called twice under redaction but only the second result is sent and audited once`() = runBlocking {
        val scenario = plainScenario(60)
        val h = harness(scenario, redact = true)

        h.service.categorizeItems(scenario.input)

        // CHARACTERISATION: DefaultCloudPayloadPolicy.prepareText writes no audit/record of its own
        // (it holds no audit logger); the only record is the service's logCloudCall after the 2nd call.
        assertEquals(2, h.policy.calls.size)
        val first = h.policy.calls[0]
        val second = h.policy.calls[1]
        assertTrue("first call prepares the full prompt", markerRegex.findAll(first.raw).count() == 60)
        assertTrue("second call prepares the budgeted prompt", markerRegex.findAll(second.raw).count() in 1..59)

        assertEquals("transport sees exactly one request", 1, h.requestCount)
        assertEquals("sent text comes from the second prepareText call", second.prepared!!.text, h.sentTexts.single())
        assertNotEquals("the discarded first payload is never sent", first.prepared!!.text, h.sentTexts.single())

        assertEquals("exactly one cloud-call audit record", 1, h.audit.cloudCalls.size)
        assertEquals("no other audit decisions", 0, h.audit.decisions)
        val ctx = h.audit.cloudCalls.single()
        assertEquals("audit provenance matches the SENT payload", second.prepared.payloadHash, ctx.payloadHash)
        assertNotEquals("discarded first payload must not be what is recorded", first.prepared.payloadHash, ctx.payloadHash)
        assertEquals(CloudPayloadPurpose.ITEM_CATEGORIZATION, ctx.purpose)
        // correlation id in audit equals the one in the logs
        val partialLog = tree.messages.single { it.contains("PROMPT_BUDGET_PARTIAL") }
        assertTrue(partialLog.contains("correlationId=${ctx.correlationId}"))
    }

    @Test
    fun `redaction off prepares once and audits the sent payload`() = runBlocking {
        val scenario = plainScenario(60)
        val h = harness(scenario, redact = false)

        h.service.categorizeItems(scenario.input)

        assertEquals(1, h.policy.calls.size)
        assertEquals(1, h.audit.cloudCalls.size)
        assertEquals(h.policy.calls.single().prepared!!.payloadHash, h.audit.cloudCalls.single().payloadHash)
        assertEquals(h.policy.calls.single().prepared!!.text, h.sentTexts.single())
    }

    // ---------------------------------------------------------------- 8. logs and cancellation

    @Test
    fun `logs carry counts and correlation id only and never item text`() = runBlocking {
        val sentinelItems = (0 until 60).map { i ->
            ReceiptParser.LineItem("SENTINELITEM${"%02d".format(i)} zzqx", null, null, 1.0 + i * 0.5)
        }
        val scenario = Scenario(sentinelItems, sentinelItems.map { it.description })
        val h = harness(scenario, redact = true)

        h.service.categorizeItems(scenario.input)

        val partial = tree.messages.single { it.contains("PROMPT_BUDGET_PARTIAL") }
        val sentCount = markerRegex.findAll(h.sentTexts.single()).count()
        assertTrue(partial.contains("sent=$sentCount"))
        assertTrue(partial.contains("total=60"))
        assertTrue(Regex("""correlationId=[0-9a-f]{8}""").containsMatchIn(partial))
        val allLogs = tree.messages.joinToString("\n")
        assertFalse("no item description in logs", allLogs.contains("SENTINELITEM"))
        assertFalse("no merchant in logs", allLogs.contains("merchant_123"))
        assertFalse("no category name in logs", allLogs.contains("Private Category"))
        assertFalse("no sent prompt text in logs", allLogs.contains("Output JSON format"))
    }

    @Test
    fun `invalid response logs only lengths and hashes not the model text`() = runBlocking {
        val scenario = plainScenario(60)
        val h = harness(scenario, redact = true) { _, _ -> "{\"items\":\"MODELSENTINELTEXT\"}" }

        assertNull(h.service.categorizeItems(scenario.input))

        val allLogs = tree.messages.joinToString("\n")
        assertFalse(allLogs.contains("MODELSENTINELTEXT"))
        assertFalse(allLogs.contains("Item 00 widget"))
    }

    @Test
    fun `CancellationException from the first prepareText propagates and nothing is sent`() {
        val scenario = plainScenario(60)
        val h = harness(scenario, redact = true, cancelPrepareOnCall = 1)

        assertTrue(propagatesCancellation { h.service.categorizeItems(scenario.input) })
        assertEquals(0, h.requestCount)
        assertEquals(0, h.audit.records)
    }

    @Test
    fun `CancellationException from the second prepareText propagates and nothing is sent`() {
        val scenario = plainScenario(60)
        val h = harness(scenario, redact = true, cancelPrepareOnCall = 2)

        assertTrue(propagatesCancellation { h.service.categorizeItems(scenario.input) })
        assertEquals(0, h.requestCount)
        assertEquals("a payload that never got prepared must not be audited", 0, h.audit.records)
    }

    @Test
    fun `CancellationException from the transport propagates`() {
        val scenario = plainScenario(3)
        val h = harness(scenario, redact = true, transportThrows = CancellationException("cancelled by test"))

        assertTrue(
            "CancellationException thrown by the transport must not be swallowed into a null result",
            propagatesCancellation { h.service.categorizeItems(scenario.input) }
        )
    }

    // ================================================================ helpers

    private fun propagatesCancellation(block: suspend () -> Any?): Boolean = runBlocking {
        try {
            block()
            false
        } catch (e: CancellationException) {
            true
        }
    }

    /** Empirical real cap of the redactor for ITEM_CATEGORIZATION text (not a mirrored constant). */
    private fun realRedactorCap(): Int =
        redactor.redactText("wordx".repeat(2000), CloudPayloadPurpose.ITEM_CATEGORIZATION).text.length

    /**
     * Asserts the sent text carries a contiguous prefix of whole item lines (index, redacted
     * description, formatted price all intact), nothing after the prefix, and ends with the last
     * whole line. Returns the number of sent items.
     */
    private fun assertSentTextWhole(sent: String, scenario: Scenario): Int {
        val indices = markerRegex.findAll(sent).map { it.groupValues[1].toInt() }.toList()
        val sentCount = indices.size
        assertTrue("at least one item must be sent", sentCount > 0)
        assertEquals("sent items must be the contiguous prefix", (0 until sentCount).toList(), indices)
        for (k in 0 until sentCount) {
            assertTrue("item line $k must be whole in the sent text", sent.contains(expectedLine(scenario, k)))
        }
        assertTrue(
            "sent text must end with the whole last item line (no cut-off item)",
            sent.endsWith(expectedLine(scenario, sentCount - 1))
        )
        return sentCount
    }

    private fun expectedLine(scenario: Scenario, k: Int): String =
        "[sourceIndex=$k] ${scenario.sentDescriptions[k]}: ${CurrencyFormatter.format(scenario.items[k].totalPrice, "EUR")}"

    private fun validate(input: ReceiptItemCategorizationInput, result: ReceiptItemCategorizationResult): String? {
        val useCase = CategorizeReceiptItemsUseCase(
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true)
        )
        val method = CategorizeReceiptItemsUseCase::class.java.getDeclaredMethod(
            "validateResult",
            ReceiptItemCategorizationInput::class.java,
            ReceiptItemCategorizationResult::class.java
        )
        method.isAccessible = true
        return method.invoke(useCase, input, result) as String?
    }

    private class Scenario(
        val items: List<ReceiptParser.LineItem>,
        /** Description exactly as it must appear in the SENT (post-redaction) text. */
        val sentDescriptions: List<String>
    ) {
        val input = ReceiptItemCategorizationInput(
            receiptId = 1L,
            merchant = "merchant_123",
            lineItems = items,
            userCategories = listOf(
                CategoryRef(id = 1L, name = "Private Category Alpha"),
                CategoryRef(id = 2L, name = "Sensitive Category Beta")
            ),
            totalTax = null,
            currency = "EUR",
            redactBeforeCloud = true
        )

        fun withAdjustmentAt(index: Int): Scenario = Scenario(
            items.mapIndexed { i, item -> if (i == index) item.copy(isAdjustment = true) else item },
            sentDescriptions
        )
    }

    private fun plainScenario(n: Int): Scenario {
        val items = (0 until n).map { ReceiptParser.LineItem("Item %02d widget".format(it), null, null, 1.0 + it * 0.5) }
        return Scenario(items, items.map { it.description })
    }

    private fun emailScenario(n: Int, emailCount: Int): Scenario {
        val items = (0 until n).map {
            val desc = if (it < emailCount) "a@b.co" else "Item %02d widget".format(it)
            ReceiptParser.LineItem(desc, null, null, 1.0 + it * 0.5)
        }
        val sent = items.mapIndexed { i, item -> if (i < emailCount) "[REDACTED_EMAIL]" else item.description }
        return Scenario(items, sent)
    }

    /** Model text answering for [indices] (local item data echoed back, category id 1, confidence 0.9). */
    private fun responseText(items: List<ReceiptParser.LineItem>, indices: List<Int>): String {
        val array = JSONArray()
        for (i in indices) {
            val local = items.getOrNull(i) ?: items.first()
            array.put(
                JSONObject()
                    .put("sourceIndex", i)
                    .put("description", local.description)
                    .put("amount", local.totalPrice)
                    .put("categoryId", 1)
                    .put("categoryName", "Private Category Alpha")
                    .put("confidence", 0.9)
                    .put("rationale", "r")
                    .put("isNewCategorySuggestion", false)
                    .put("alternatives", JSONArray())
            )
        }
        return JSONObject().put("items", array).put("suggestedNewCategories", JSONArray()).toString()
    }

    private class Call(val raw: String, val prepared: PreparedCloudPayload?)

    /** Pure recorder: delegates everything to the REAL policy; optionally throws cancellation on call N. */
    private class RecordingPolicy(
        private val delegate: CloudPayloadPolicy,
        private val cancelOnCall: Int?
    ) : CloudPayloadPolicy by delegate {
        val calls = CopyOnWriteArrayList<Call>()

        override suspend fun prepareText(
            purpose: CloudPayloadPurpose,
            rawText: String,
            context: SafePrivacyMetadata
        ): PreparedCloudPayload {
            if (cancelOnCall == calls.size + 1) throw CancellationException("cancelled by test")
            val prepared = delegate.prepareText(purpose, rawText, context)
            calls += Call(rawText, prepared)
            return prepared
        }
    }

    private class RecordingAudit : PrivacyAuditLogger {
        val cloudCalls = CopyOnWriteArrayList<PrivacyAuditContext>()
        var decisions = 0
        val records: Int get() = cloudCalls.size + decisions

        override suspend fun logDecision(
            capability: PrivacyCapability,
            decision: PrivacyDecision,
            context: Map<String, String>
        ) {
            decisions++
        }

        override suspend fun logCloudCall(
            capability: PrivacyCapability,
            decision: PrivacyDecision,
            context: PrivacyAuditContext
        ) {
            cloudCalls += context
        }
    }

    private class CapturingTree : Timber.Tree() {
        val messages = CopyOnWriteArrayList<String>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            messages += if (t == null) message else "$message | ${t::class.java.simpleName}: ${t.message}"
        }
    }

    private class Harness(
        val service: CloudReceiptItemCategorizationService,
        val policy: RecordingPolicy,
        val audit: RecordingAudit,
        val sentTexts: CopyOnWriteArrayList<String>,
        private val requests: java.util.concurrent.atomic.AtomicInteger
    ) {
        val requestCount: Int get() = requests.get()
    }

    private fun resolver(redact: Boolean): EffectiveCloudAiPolicyResolver {
        val privacy = PrivacySettings.FAIL_CLOSED_DEFAULTS.copy(redactBeforeCloud = redact)
        val privacyRepo = object : PrivacySettingsRepository {
            override fun observeSettings() = flowOf(privacy)
            override fun observeLoadState() = flowOf<PrivacySettingsLoadState>(PrivacySettingsLoadState.FirstRunDefault(privacy))
            override suspend fun getSettings() = privacy
            override suspend fun getLoadState(): PrivacySettingsLoadState = PrivacySettingsLoadState.FirstRunDefault(privacy)
            override suspend fun updateSettings(transform: (PrivacySettings) -> PrivacySettings) {}
        }
        val aiRepo = object : AiSettingsRepository {
            override fun settings() = flowOf(AiSettings().copy(redactBeforeCloud = redact))
            override suspend fun update(transform: (AiSettings) -> AiSettings) {}
        }
        return EffectiveCloudAiPolicyResolver(privacyRepo, aiRepo)
    }

    private fun harness(
        scenario: Scenario,
        redact: Boolean,
        cancelPrepareOnCall: Int? = null,
        transportThrows: Throwable? = null,
        respond: ((sentCount: Int, items: List<ReceiptParser.LineItem>) -> String)? = null
    ): Harness {
        val keyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { keyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"
        val sentTexts = CopyOnWriteArrayList<String>()
        val requests = java.util.concurrent.atomic.AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requests.incrementAndGet()
                val request = chain.request()
                val buffer = Buffer()
                request.body?.writeTo(buffer)
                val text = JSONObject(buffer.readUtf8())
                    .getJSONArray("contents").getJSONObject(0)
                    .getJSONArray("parts").getJSONObject(0)
                    .getString("text")
                sentTexts += text
                transportThrows?.let { throw it }
                val sentCount = markerRegex.findAll(text).count()
                val modelText = respond?.invoke(sentCount, scenario.items)
                    ?: responseText(scenario.items, (0 until sentCount).toList())
                val body = JSONObject().put(
                    "candidates",
                    JSONArray().put(
                        JSONObject().put(
                            "content",
                            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", modelText)))
                        )
                    )
                ).toString()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val policy = RecordingPolicy(
            DefaultCloudPayloadPolicy(resolver(redact), redactor),
            cancelPrepareOnCall
        )
        val audit = RecordingAudit()
        val service = CloudReceiptItemCategorizationService(
            keyStorage,
            client,
            mockk<PrivacyGate>(relaxed = true),
            policy,
            audit
        )
        return Harness(service, policy, audit, sentTexts, requests)
    }
}
