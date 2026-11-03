package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor
import com.yourname.expensetracker.data.provider.MerchantCategoryProvider
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationInput
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationResult
import com.yourname.expensetracker.domain.ai.model.ReceiptItemPlaceholder
import com.yourname.expensetracker.domain.ai.model.ReceiptTaxAllocation
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.dto.CategoryRef
import com.yourname.expensetracker.domain.privacy.CloudPayloadPolicy
import com.yourname.expensetracker.domain.privacy.CloudPayloadPurpose
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * Prompt budgeting, reply-token sizing, purpose separation, parsing and log hygiene of
 * [CloudReceiptItemCategorizationService]. The REAL [DefaultCloudPayloadRedactor] (with its real
 * CloudPiiSanitizer) and the REAL seeded category list are used; only the HTTP transport is
 * faked, and it records the text and request body that were sent.
 *
 * Budget numbers: the redactor caps this purpose at 4000 characters; the service keeps the raw
 * prompt within 3900 (cap less a 100 character margin) and sends at most 25 items per call.
 */
class CloudReceiptItemCategorizationPromptTest {

    // ---------------------------------------------------------------- 1. seeded categories

    @Test
    fun `seeded list has the 20 blueprint categories with ids 1 to 20`() {
        assertEquals(20, seeded.size)
        assertEquals((1L..20L).toList(), seeded.map { it.id })
    }

    @Test
    fun `seeded 10 items all sent with the schema present in both modes`() {
        for (redact in listOf(true, false)) {
            val rig = Rig(redact)
            val result = rig.categorize(input(items(10), redact = redact))

            assertEquals("redact=$redact", (0 until 10).toList(), markersIn(rig.sentText))
            assertTrue(rig.sentText.contains("Format: {\"items\":[{\"sourceIndex\":0"))
            assertTrue(rig.sentText.contains("\"suggestedNewCategories\":[]"))
            assertNotNull(result)
            assertEquals(10, result!!.items.size)
            assertTrue(result.items.none { ReceiptItemPlaceholder.isPlaceholder(it) })
        }
    }

    @Test
    fun `seeded 25 items all sent in both modes`() {
        for (redact in listOf(true, false)) {
            val rig = Rig(redact)
            val result = rig.categorize(input(items(25), redact = redact))

            assertEquals("redact=$redact", (0 until 25).toList(), markersIn(rig.sentText))
            assertEquals(25, result!!.items.size)
            assertTrue(result.items.none { ReceiptItemPlaceholder.isPlaceholder(it) })
        }
    }

    @Test
    fun `seeded 60 items sends exactly 25 whole lines and 35 placeholders in the right positions`() {
        val reference = Rig(false)
        reference.categorize(input(items(60)))
        val referenceLines = reference.sentText.split("\n")
        assertEquals(6 + 25, referenceLines.size)
        assertEquals("Items:", referenceLines[5])
        val price = referenceLines[6].substringAfterLast(": ")
        referenceLines.drop(6).forEachIndexed { i, line ->
            assertEquals("- [sourceIndex=$i] ${productName(i)}: $price", line)
        }

        val redacted = Rig(true)
        val result = redacted.categorize(input(items(60), redact = true))
        assertEquals((0 until 25).toList(), markersIn(redacted.sentText))
        assertTrue("ends in the last whole line", redacted.sentText.endsWith(referenceLines.last()))
        // INV: both modes carry the same text before redaction (no PII here, so only whitespace differs).
        assertEquals(reference.sentText.replace(Regex("\\s+"), " "), redacted.sentText)

        assertNotNull(result)
        assertEquals(60, result!!.items.size)
        result.items.forEachIndexed { i, item ->
            assertEquals("index $i", i >= 25, ReceiptItemPlaceholder.isPlaceholder(item))
            if (i >= 25) {
                assertEquals(productName(i), item.itemDescription)
                assertEquals(1.0, item.amount, 0.0)
                assertTrue(item.needsReview)
            }
        }
    }

    // ---------------------------------------------------------------- 2. long categories

    @Test
    fun `30 long custom categories fit at least 15 items and stop at the last whole line within budget`() {
        val cats = longCategories(count = 30, nameLength = 80)
        val header = measureHeader(cats)
        val firstLine = measureFirstLine(cats) { productName(it) }
        val price = firstLine.substringAfterLast(": ")

        val unredacted = Rig(false)
        unredacted.categorize(input(items(60), cats))
        val n = markersIn(unredacted.sentText).size

        assertTrue("sent $n", n in 15..24)
        assertEquals((0 until n).toList(), markersIn(unredacted.sentText))
        assertTrue("header $header leaves room", header + 1 + firstLine.length <= 3900)
        // Characterisation of the 3900 budget: n lines fit, the next whole line would not.
        assertTrue(unredacted.sentText.length <= 3900)
        assertTrue(unredacted.sentText.length + 1 + nextLineLength(firstLine, n) > 3900)
        assertWholeLines(unredacted.sentText, n, price) { productName(it) }
        assertEquals(256 + 80 * n, unredacted.maxTokens)

        // Redacted mode is budgeted the same way.
        val redacted = Rig(true)
        redacted.categorize(input(items(60), cats, redact = true))
        assertEquals(markersIn(unredacted.sentText), markersIn(redacted.sentText))
        assertEquals(unredacted.sentText.replace(Regex("\\s+"), " "), redacted.sentText)
        assertEquals(256 + 80 * n, redacted.maxTokens)
    }

    // ---------------------------------------------------------------- 3. exhausting / lengthening

    @Test
    fun `long descriptions exhaust the budget before 25 and only whole lines under the cap are sent`() {
        val describe = { i: Int -> (productName(i) + " " + "alpha beta gamma delta ".repeat(10)).take(150).trimEnd() }
        val header = measureHeader(seeded)
        val firstLine = measureFirstLine(seeded, describe)
        val price = firstLine.substringAfterLast(": ")

        val unredacted = Rig(false)
        unredacted.categorize(input(items(40, describe = describe)))
        val n = markersIn(unredacted.sentText).size
        assertTrue("sent $n of 40", n in 10..24)
        assertEquals((0 until n).toList(), markersIn(unredacted.sentText))
        assertTrue(header < 1200)
        assertTrue(unredacted.sentText.length <= 3900)
        assertTrue(unredacted.sentText.length + 1 + nextLineLength(firstLine, n) > 3900)
        assertWholeLines(unredacted.sentText, n, price, describe)

        val redacted = Rig(true)
        val result = redacted.categorize(input(items(40, describe = describe), redact = true))
        assertEquals((0 until n).toList(), markersIn(redacted.sentText))
        assertTrue(redacted.sentText.length < 4000)
        // The RECEIPT_ITEM_CATEGORIZATION cap (4000) is in force: the old 2000 cap would cut this.
        assertTrue("length ${redacted.sentText.length}", redacted.sentText.length > 2000)
        assertTrue(redacted.sentText.endsWith(describe(n - 1) + ": " + price))
        assertEquals(40, result!!.items.size)
        assertEquals(n, result.items.count { !ReceiptItemPlaceholder.isPlaceholder(it) })
    }

    @Test
    fun `lengthening PII near the budget is never cut and sentCount matches the text`() {
        val prefix = "a@b.co z"
        val pad = calibratedPad(prefix)
        assertTrue("pad $pad", pad > 0)
        val describe = { _: Int -> prefix + "z".repeat(pad) }
        val firstLine = measureFirstLine(seeded, describe)
        val price = firstLine.substringAfterLast(": ")

        // Raw prompt of all 25 items fits within 3900, so unredacted sends all 25 untouched.
        val unredacted = Rig(false)
        unredacted.categorize(input(items(25, describe = describe)))
        assertEquals((0 until 25).toList(), markersIn(unredacted.sentText))
        assertTrue(unredacted.sentText.length in 3875..3900)
        assertEquals(25, Regex("a@b.co").findAll(unredacted.sentText).count())

        // Redaction lengthens each "a@b.co" by 10 characters: 25 lines would overflow 4000.
        val redacted = Rig(true)
        val result = redacted.categorize(input(items(25, describe = describe), redact = true))
        val sent = markersIn(redacted.sentText)
        val n = sent.size
        assertTrue("sent $n", n in 15..24)
        assertEquals((0 until n).toList(), sent)
        assertTrue(redacted.sentText.length < 4000)
        assertEquals(n, Regex("\\[REDACTED_EMAIL]").findAll(redacted.sentText).count())
        assertFalse(redacted.sentText.contains("a@b.co"))
        assertFalse("no truncated marker", Regex("\\[REDACTED_(?!EMAIL])").containsMatchIn(redacted.sentText))
        assertTrue("ends in a whole line", redacted.sentText.endsWith("z".repeat(pad) + ": " + price))
        assertEquals(256 + 80 * n, redacted.maxTokens)
        assertEquals(25, result!!.items.size)
        assertEquals(n, result.items.count { !ReceiptItemPlaceholder.isPlaceholder(it) })
    }

    // ---------------------------------------------------------------- 4. redacted prompt text

    @Test
    fun `redacted prompt keeps the confidence wording, no redaction marker in instructions and every source marker`() {
        val logs = captureLogs {
            for (redact in listOf(true, false)) {
                val describe = { i: Int -> "EAN 4006381333931 ref 12345678 pack $i" }
                val rig = Rig(redact)
                val result = rig.categorize(input(items(10, describe = describe), redact = redact))

                val text = rig.sentText
                val fixedPart = text.substringBefore("Items:")
                assertTrue(fixedPart.contains("0.7 or more"))
                assertTrue(fixedPart.contains("under 0.7"))
                assertFalse("redact=$redact", fixedPart.contains("[REDACTED_"))
                assertEquals((0 until 10).toList(), markersIn(text))
                if (redact) assertTrue(text.substringAfter("Items:").contains("[REDACTED_"))
                // A numeric description cannot be mistaken for a cut: all 10 are sent.
                assertEquals(1056, rig.maxTokens)
                assertEquals(10, result!!.items.count { !ReceiptItemPlaceholder.isPlaceholder(it) })
            }
        }
        assertTrue(logs.none { it.contains("PROMPT_SHRINK") })
    }

    // ---------------------------------------------------------------- 6. nothing fits / empty

    @Test
    fun `non-empty receipt where no item fits returns null without preparing or sending and logs counts only`() {
        val cats = longCategories(count = 40, nameLength = 120)
        val rig = Rig(true, policyWrap = { spyk(it) })
        val logs = captureLogs {
            val result = rig.categorize(
                input(items(5, describe = { "SENTINELQZ item" }), cats, redact = true)
            )
            assertNull(result)
        }
        assertEquals(0, rig.transportCalls.get())
        coVerify(exactly = 0) { rig.policy.prepareText(any(), any(), any()) }
        val noFit = logs.filter { it.contains("PROMPT_NO_ITEM_FITS") }
        assertEquals(1, noFit.size)
        assertTrue(noFit.single().contains("total=5"))
        assertTrue(logs.all { it.length < 300 })
        assertTrue(logs.none { it.contains("SENTINELQZ") || it.contains(cats.first().name) })
    }

    @Test
    fun `empty item list is still prepared and sent`() {
        val rig = Rig(true, policyWrap = { spyk(it) })
        rig.categorize(input(emptyList(), redact = true))

        assertEquals(1, rig.transportCalls.get())
        coVerify(exactly = 1) {
            rig.policy.prepareText(CloudPayloadPurpose.RECEIPT_ITEM_CATEGORIZATION, any(), any())
        }
        assertTrue(markersIn(rig.sentText).isEmpty())
        assertTrue(rig.sentText.contains("Items:"))
        assertEquals(256, rig.maxTokens)
    }

    // ---------------------------------------------------------------- 7. reply tokens

    @Test
    fun `maxOutputTokens is 256 plus 80 per sent item`() {
        for ((count, expected) in mapOf(1 to 336, 10 to 1056, 25 to 2256)) {
            val rig = Rig(true)
            rig.categorize(input(items(count), redact = true))
            assertEquals("items=$count", expected, rig.maxTokens)
        }
    }

    @Test
    fun `maxOutputTokens counts the sent items not the total for 60 items`() {
        val rig = Rig(false)
        rig.categorize(input(items(60)))
        assertEquals(2256, rig.maxTokens)
        assertTrue(rig.maxTokens != 256 + 80 * 60)
    }

    // ---------------------------------------------------------------- 8. purpose separation

    @Test
    fun `every purpose keeps its redaction cap and only RECEIPT_ITEM_CATEGORIZATION is 4000`() {
        val expected = mapOf(
            CloudPayloadPurpose.RECEIPT_ASSIST to 3000,
            CloudPayloadPurpose.WARRANTY_EXTRACTION to 3000,
            CloudPayloadPurpose.DASHBOARD_BRIEFING to 1500,
            CloudPayloadPurpose.RECEIPT_ITEM_CATEGORIZATION to 4000,
            CloudPayloadPurpose.ITEM_CATEGORIZATION to 2000,
            CloudPayloadPurpose.QUERY_INTERPRETATION to 2000,
            CloudPayloadPurpose.REVIEW_EXPLANATION to 2000,
            CloudPayloadPurpose.DEDUPE_JUDGE to 2000,
            CloudPayloadPurpose.BANK_STATEMENT_VALIDATION to 2000,
            CloudPayloadPurpose.BANK_TRANSACTION_CLASSIFICATION to 2000,
            CloudPayloadPurpose.EXPORT_SUMMARY to 2000
        )
        // A new purpose must be given an explicit cap here.
        assertEquals(CloudPayloadPurpose.values().toSet(), expected.keys)
        val redactor = DefaultCloudPayloadRedactor()
        expected.forEach { (purpose, cap) ->
            assertEquals(purpose.name, cap, redactor.redactText("a".repeat(5000), purpose).text.length)
        }
    }

    @Test
    fun `service prepares through RECEIPT_ITEM_CATEGORIZATION and never ITEM_CATEGORIZATION`() {
        val rig = Rig(true, policyWrap = { spyk(it) })
        rig.categorize(input(items(3), redact = true))

        coVerify(atLeast = 1) {
            rig.policy.prepareText(CloudPayloadPurpose.RECEIPT_ITEM_CATEGORIZATION, any(), any())
        }
        coVerify(exactly = 0) {
            rig.policy.prepareText(CloudPayloadPurpose.ITEM_CATEGORIZATION, any(), any())
        }
    }

    // ---------------------------------------------------------------- 9. parsing

    @Test
    fun `partial response for the sent items is accepted and the rest become placeholders`() {
        val rig = Rig(true)
        val result = rig.categorize(input(items(60), redact = true))

        assertNotNull(result)
        assertEquals(60, result!!.items.size)
        assertEquals(25, result.items.count { !ReceiptItemPlaceholder.isPlaceholder(it) })
        assertEquals(35, result.items.count { ReceiptItemPlaceholder.isPlaceholder(it) })
        // Placeholders are not AI results: the average covers the 25 answered items only (0.9).
        assertEquals(0.9f, result.totalConfidence, 1e-6f)
        assertTrue(result.needsReview)
    }

    @Test
    fun `sourceIndex outside the sent range is rejected`() {
        for (bad in listOf(25, 59, -1)) {
            val rig = Rig(false, reply = { prompt -> replyJson(markersIn(prompt) + bad) })
            assertNull("index $bad", rig.categorize(input(items(60))))
            assertEquals(1, rig.transportCalls.get())
        }
    }

    @Test
    fun `answer count different from sentCount is rejected`() {
        val fewer = Rig(false, reply = { prompt -> replyJson(markersIn(prompt).dropLast(1)) })
        assertNull(fewer.categorize(input(items(60))))

        val none = Rig(false, reply = { replyJson(emptyList()) })
        assertNull(none.categorize(input(items(60))))
    }

    @Test
    fun `a blank echoed description is filled from the local item`() {
        val rig = Rig(false, reply = { prompt -> replyJson(markersIn(prompt), blankDescriptionAt = setOf(3)) })
        val result = rig.categorize(input(items(60)))

        assertNotNull(result)
        assertEquals(productName(3), result!!.items[3].itemDescription)
        assertEquals("echo", result.items[4].itemDescription)
    }

    @Test
    fun `malformed model output returns null`() {
        val replies = listOf(
            "this is not json",
            "{\"items\":[{\"sourceIndex\":0,",
            "{\"items\":[{\"sourceIndex\":\"zero\"}]}",
            "{\"notItems\":[]}"
        )
        for (reply in replies) {
            val rig = Rig(false, reply = { reply })
            assertNull(reply, rig.categorize(input(items(3))))
        }
    }

    // ---------------------------------------------------------------- 10. tax stays local

    @Test
    fun `prompt carries no tax and local allocation conserves total tax with placeholders`() {
        val rig = Rig(false)
        val result = rig.categorize(input(items(60), tax = 6.0))

        assertFalse(rig.sentText.contains("tax", ignoreCase = true))
        assertFalse(rig.sentText.contains("taxDistribution"))

        assertNotNull(result)
        val allocation = result!!.taxAllocation as? ReceiptTaxAllocation.Available
        assertNotNull("tax allocation must be available with placeholders", allocation)
        val perItem = allocation!!.itemAmounts.map { it.amount }
        assertEquals(60, perItem.size)
        assertEquals(6.0, perItem.sum(), 1e-9)
        // Placeholders (indices 25..59) have no category: their share is unallocated.
        assertEquals(setOf(1L), result.taxDistribution.keys)
        val allocated = result.taxDistribution.values.sum()
        val unallocated = perItem.drop(25).sum()
        assertEquals(2.5, allocated, 1e-9)
        assertEquals(6.0, allocated + unallocated, 1e-9)
    }

    // ---------------------------------------------------------------- 14. logs and cancellation

    @Test
    fun `logs carry counts and the correlation id only`() {
        val prefix = "a@b.co SENTINELQZ z"
        val pad = calibratedPad(prefix)
        val describe = { _: Int -> prefix + "z".repeat(pad) }
        val logs = captureLogs {
            Rig(true).categorize(input(items(25, describe = describe), redact = true))
        }

        val shrink = logs.filter { it.contains("PROMPT_SHRINK") }
        assertTrue("redaction overflow must shrink the prompt at least once", shrink.isNotEmpty())
        val shape = Regex(
            "CloudReceiptItemCategorizationService: PROMPT_SHRINK sent=\\d+ next=\\d+ total=25 correlationId=\\S+"
        )
        assertTrue(shrink.all { shape.matches(it) })
        assertTrue(logs.none { it.contains("SENTINELQZ") || it.contains("a@b.co") || it.contains("Product") })
    }

    @Test
    fun `CancellationException from prepareText propagates`() {
        val policy = mockk<CloudPayloadPolicy>()
        coEvery { policy.prepareText(any(), any(), any()) } throws CancellationException("cancel-sentinel")
        val keys = mockk<SecureKeyStorage>(relaxed = true)
        every { keys.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"
        val service = CloudReceiptItemCategorizationService(keys, OkHttpClient(), allowGate, policy)

        val outcome = runBlocking { runCatching { service.categorizeItems(input(items(3))) } }

        val error = outcome.exceptionOrNull()
        assertTrue("was $error", error is CancellationException)
        assertEquals("cancel-sentinel", error!!.message)
    }

    @Test
    fun `CancellationException from the transport propagates and is not retried`() {
        val rig = Rig(false, reply = { throw CancellationException("cancel-sentinel") })

        val outcome = runBlocking { runCatching { rig.service.categorizeItems(input(items(3))) } }

        val error = outcome.exceptionOrNull()
        assertTrue("was $error", error is CancellationException)
        assertEquals(1, rig.transportCalls.get())
    }
}

// ------------------------------------------------------------------------ fixtures

private val MARKER = Regex("""\[sourceIndex=(\d+)]""")

private val allowGate = object : PrivacyGate {
    override suspend fun check(capability: PrivacyCapability, context: Map<String, String>): PrivacyDecision =
        PrivacyDecision.Allowed
}

private val seeded: List<CategoryRef> = MerchantCategoryProvider.categoryBlueprints
    .mapIndexed { index, category -> CategoryRef(id = (index + 1).toLong(), name = category.name) }

private fun longCategories(count: Int, nameLength: Int): List<CategoryRef> = (1..count).map { id ->
    CategoryRef(id.toLong(), ("Cat" + ('a' + id % 26) + ('a' + id / 26)).padEnd(nameLength, 'x'))
}

private fun productName(index: Int): String = "Product " + ('a' + index % 26) + ('a' + index / 26)

private fun items(
    count: Int,
    describe: (Int) -> String = ::productName
): List<ReceiptParser.LineItem> = (0 until count).map {
    ReceiptParser.LineItem(description = describe(it), quantity = null, unitPrice = null, totalPrice = 1.0)
}

private fun input(
    lineItems: List<ReceiptParser.LineItem>,
    categories: List<CategoryRef> = seeded,
    redact: Boolean = false,
    tax: Double? = null
) = ReceiptItemCategorizationInput(
    receiptId = 1L,
    merchant = "Test Store",
    lineItems = lineItems,
    userCategories = categories,
    totalTax = tax,
    currency = "EUR",
    redactBeforeCloud = redact
)

private fun markersIn(text: String): List<Int> =
    MARKER.findAll(text).map { it.groupValues[1].toInt() }.toList()

private fun replyJson(indices: List<Int>, blankDescriptionAt: Set<Int> = emptySet()): String {
    val array = JSONArray()
    indices.forEach { index ->
        array.put(
            JSONObject()
                .put("sourceIndex", index)
                .put("description", if (index in blankDescriptionAt) "" else "echo")
                .put("amount", 1.0)
                .put("categoryId", 1)
                .put("categoryName", "Groceries")
                .put("confidence", 0.9)
                .put("rationale", "matches")
                .put("isNewCategorySuggestion", false)
                .put("alternatives", JSONArray())
        )
    }
    return JSONObject().put("items", array).put("suggestedNewCategories", JSONArray()).toString()
}

private fun envelope(modelText: String): String = JSONObject().put(
    "candidates",
    JSONArray().put(
        JSONObject().put(
            "content",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", modelText)))
        )
    )
).toString()

private fun promptOf(body: JSONObject): String =
    body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")

private fun realPolicy(redact: Boolean): DefaultCloudPayloadPolicy {
    val privacy = PrivacySettings(cloudAiEnabled = true, redactBeforeCloud = redact)
    val privacyRepo = mockk<PrivacySettingsRepository>(relaxed = true)
    coEvery { privacyRepo.getSettings() } returns privacy
    every { privacyRepo.observeSettings() } returns flowOf(privacy)
    val aiRepo = mockk<AiSettingsRepository>(relaxed = true)
    every { aiRepo.settings() } returns flowOf(AiSettings(allowCloudAi = true, redactBeforeCloud = false))
    return DefaultCloudPayloadPolicy(EffectiveCloudAiPolicyResolver(privacyRepo, aiRepo), DefaultCloudPayloadRedactor())
}

/** The real service and policy over a fake transport that records every request body. */
private class Rig(
    redact: Boolean,
    reply: (String) -> String = { prompt -> replyJson(markersIn(prompt)) },
    policyWrap: ((DefaultCloudPayloadPolicy) -> CloudPayloadPolicy)? = null
) {
    val transportCalls = AtomicInteger(0)
    private val bodies = mutableListOf<JSONObject>()
    val policy: CloudPayloadPolicy
    val service: CloudReceiptItemCategorizationService

    init {
        val base = realPolicy(redact)
        policy = policyWrap?.invoke(base) ?: base
        val keys = mockk<SecureKeyStorage>(relaxed = true)
        every { keys.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                transportCalls.incrementAndGet()
                val buffer = Buffer()
                chain.request().body?.writeTo(buffer)
                val body = JSONObject(buffer.readUtf8())
                bodies += body
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(envelope(reply(promptOf(body))).toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        service = CloudReceiptItemCategorizationService(keys, client, allowGate, policy)
    }

    val sentText: String get() = promptOf(bodies.last())

    val maxTokens: Int get() = bodies.last().getJSONObject("generationConfig").getInt("maxOutputTokens")

    fun categorize(input: ReceiptItemCategorizationInput): ReceiptItemCategorizationResult? =
        runBlocking { service.categorizeItems(input) }
}

/** Length of the prompt header: an empty receipt is sent as the header alone. */
private fun measureHeader(categories: List<CategoryRef>): Int {
    val rig = Rig(false)
    rig.categorize(input(emptyList(), categories))
    return rig.sentText.length
}

/** The item line of index 0 as sent (unredacted), measured from a one-item prompt. */
private fun measureFirstLine(categories: List<CategoryRef>, describe: (Int) -> String): String {
    val rig = Rig(false)
    rig.categorize(input(items(1, describe), categories))
    return rig.sentText.substringAfterLast("\n")
}

/** Index n is one character longer for each extra digit than index 0. */
private fun nextLineLength(firstLine: String, n: Int): Int = firstLine.length + n.toString().length - 1

/** Padding so that 25 lines with a [prefix] description fill the raw 3900 budget to within 25 characters. */
private fun calibratedPad(prefix: String): Int {
    val header = measureHeader(seeded)
    val base = measureFirstLine(seeded) { prefix }.length
    // 10 lines have a one-digit marker, 15 have two digits: 15 extra characters.
    return (3900 - header - 15) / 25 - 1 - base
}

private fun assertWholeLines(text: String, n: Int, price: String, describe: (Int) -> String) {
    val lines = text.split("\n")
    assertEquals(6 + n, lines.size)
    assertEquals("Items:", lines[5])
    for (i in 0 until n) {
        assertEquals("- [sourceIndex=$i] ${describe(i)}: $price", lines[6 + i])
    }
}

private fun captureLogs(block: () -> Unit): List<String> {
    val logs = mutableListOf<String>()
    val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs += message + (t?.let { " " + it.toString() } ?: "")
        }
    }
    Timber.plant(tree)
    try {
        block()
    } finally {
        Timber.uproot(tree)
    }
    return logs
}
