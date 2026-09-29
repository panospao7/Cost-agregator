package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.*
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.privacy.*
import com.yourname.expensetracker.domain.model.DomainTransactionType
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.Base64

/** Actual provider invocations; policy-unit tests and file existence are not this evidence. */
@RunWith(Parameterized::class)
class CloudProviderTransportPayloadTest(private val entry: Entry) {
    enum class Entry(val purpose: CloudPayloadPurpose) {
        CATEGORIZATION(CloudPayloadPurpose.ITEM_CATEGORIZATION),
        DASHBOARD(CloudPayloadPurpose.DASHBOARD_BRIEFING),
        DEDUPE(CloudPayloadPurpose.DEDUPE_JUDGE),
        QUERY(CloudPayloadPurpose.QUERY_INTERPRETATION),
        RECEIPT(CloudPayloadPurpose.RECEIPT_ASSIST),
        RECEIPT_ITEMS(CloudPayloadPurpose.ITEM_CATEGORIZATION),
        REVIEW(CloudPayloadPurpose.REVIEW_EXPLANATION),
        WARRANTY(CloudPayloadPurpose.WARRANTY_EXTRACTION),
        BANK_STATEMENT(CloudPayloadPurpose.BANK_STATEMENT_VALIDATION)
    }

    companion object {
        private const val RAW = "RAW_INPUT_person@example.test_481516"
        private const val APPROVED = "POLICY_APPROVED_PAYLOAD_ONLY"
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun entries(): List<Array<Any>> = Entry.values().map { arrayOf<Any>(it) }
    }

    private inner class Harness(redacted: Boolean = true) {
        val keys = mockk<SecureKeyStorage>()
        val client = mockk<OkHttpClient>()
        val gate = mockk<PrivacyGate>()
        val policy = mockk<CloudPayloadPolicy>()
        val audit = mockk<PrivacyAuditLogger>(relaxed = true)
        private val settings = mockk<AiSettingsRepository>()
        private val privacySettings = mockk<PrivacySettingsRepository>()
        private val resolver = EffectiveCloudAiPolicyResolver(privacySettings, settings)
        val requests = mutableListOf<Request>()
        val rawPrompts = mutableListOf<String>()
        val auditContexts = mutableListOf<PrivacyAuditContext>()
        private val requiresRedaction = redacted || entry == Entry.BANK_STATEMENT
        val prepared = PreparedCloudPayload(
            purpose = entry.purpose,
            text = APPROVED,
            redactionApplied = requiresRedaction,
            fieldsRedacted = if (requiresRedaction) setOf("email") else emptySet(),
            payloadHash = "b".repeat(32),
            rawTextIncluded = !requiresRedaction,
            rawImageIncluded = entry == Entry.RECEIPT && !requiresRedaction,
            imageBytes = if (entry == Entry.RECEIPT && !requiresRedaction) byteArrayOf(11, 22, 33) else null,
            imageMimeType = if (entry == Entry.RECEIPT && !requiresRedaction) "image/jpeg" else null
        )

        init {
            every { keys.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"
            every { settings.settings() } returns flowOf(
                AiSettings(allowCloudAi = true, redactBeforeCloud = false, receiptImageCloudEnabled = true)
            )
            val allowedSettings = PrivacySettings(cloudAiEnabled = true, redactBeforeCloud = false)
            coEvery { privacySettings.getSettings() } returns allowedSettings
            every { privacySettings.observeSettings() } returns flowOf(allowedSettings)
            coEvery { gate.check(any(), any()) } returns PrivacyDecision.Allowed
            coEvery { policy.prepareText(any(), any(), any()) } coAnswers {
                assertEquals(entry.purpose, firstArg<CloudPayloadPurpose>())
                rawPrompts.add(secondArg())
                prepared
            }
            coEvery { policy.prepareReceiptAssist(any(), any(), any(), any(), any()) } coAnswers {
                assertEquals(Entry.RECEIPT, entry)
                assertTrue("Fixture must exercise the image-eligible provider path", arg<Boolean>(3))
                rawPrompts.add(firstArg())
                prepared
            }
            coEvery { policy.prepareBankStatementValidation(any(), any()) } coAnswers {
                assertEquals(Entry.BANK_STATEMENT, entry)
                rawPrompts.add(firstArg())
                prepared
            }
            coEvery { audit.logCloudCall(any(), any(), capture(auditContexts)) } just Runs
            every { client.newCall(any()) } answers {
                val request = firstArg<Request>()
                requests.add(request)
                mockk<Call> {
                    // A permanent HTTP failure stops retries without conflating
                    // response parsing with the outgoing-payload contract.
                    every { execute() } answers {
                        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                            .code(400).message("Bad Request")
                            .body("{}".toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                }
            }
        }

        suspend fun invoke() {
            when (entry) {
                Entry.CATEGORIZATION -> CloudCategorizationAssistService(
                    keys, client, settings, gate, policy, audit
                ).suggest(CategorizationAssistInput(
                    targetType = AiTargetType.PENDING_REVIEW, targetId = 1L,
                    merchant = RAW, amount = 12.0, currency = "EUR",
                    transactionType = DomainTransactionType.PURCHASE,
                    date = null, currentCategoryId = null,
                    deterministicMatchType = "FALLBACK", deterministicExplanation = "weak match",
                    candidateCategories = listOf(CategoryOption(1L, "Groceries")), supportingText = RAW
                ))
                Entry.DASHBOARD -> CloudDashboardBriefingService(
                    keys, client, mockk {
                        coEvery { buildPrompt(any(), any()) } returns RAW
                    }, settings, gate, policy, resolver, audit
                ).generate(mockk<DashboardBriefingInput>(relaxed = true))
                Entry.DEDUPE -> {
                    val subject = DedupeCandidateSummary(
                        targetType = AiTargetType.PENDING_REVIEW, targetId = 1L,
                        merchant = RAW, amount = 12.0, currency = "EUR", date = 1_000L,
                        sourceLabel = "bank", textPreview = RAW
                    )
                    CloudDedupeJudgeService(keys, client, settings, gate, policy, audit).judge(
                        DedupeJudgeInput(subject, listOf(subject.copy(targetType = AiTargetType.EXPENSE, targetId = 2L)))
                    )
                }
                Entry.QUERY -> CloudQueryInterpretationService(keys, client, gate, policy, audit)
                    .interpret(FinancialQueryInterpretationInput(RAW, 1_000L, "en-US"))
                Entry.RECEIPT -> CloudReceiptAssistService(settings, keys, client, gate, policy, audit)
                    .suggest(receiptInput())
                Entry.RECEIPT_ITEMS -> CloudReceiptItemCategorizationService(keys, client, gate, policy, audit)
                    .categorizeItems(ReceiptItemCategorizationInput(
                        receiptId = 1L, merchant = RAW, lineItems = emptyList(),
                        userCategories = emptyList(), cloudCategoryOptions = emptyList(),
                        totalTax = null, currency = "EUR", redactBeforeCloud = false
                    ))
                Entry.REVIEW -> CloudReviewExplanationService(
                    keys, client, settings, gate, policy, resolver, audit
                ).generate(ReviewExplanationInput(
                    reviewId = 1L, merchant = RAW, amount = 12.0, currency = "EUR",
                    suggestedType = "PURCHASE", suggestedCategoryId = null, confidence = 0.42f,
                    matchType = "WEAK_MATCH", explanation = "ambiguous", packageName = "example.test",
                    notificationTitle = "Payment", notificationText = RAW
                ))
                Entry.WARRANTY -> CloudWarrantyExtractionService(keys, client, gate, policy, audit)
                    .extractWarranty(WarrantyExtractionInput(
                        receiptText = RAW, merchant = RAW, totalAmount = 12.0,
                        purchaseDate = 1_700_000_000_000L, currency = "EUR"
                    ))
                Entry.BANK_STATEMENT -> CloudReceiptAssistService(settings, keys, client, gate, policy, audit)
                    .suggestFromText(RAW)
            }
        }

        private fun receiptInput(): ReceiptAssistInput = mockk(relaxed = true) {
            every { receiptId } returns 1L
            every { rawOcrText } returns RAW
            every { isImageAnalysisMode } returns true
            // No real file may be opened: only bytes returned by the mocked
            // policy may reach the actual provider's JSON body.
            every { imagePath } returns "/fixture/must-not-be-read.jpg"
            every { imageMimeType } returns "image/jpeg"
            every { parsedMerchant } returns null
            every { parsedTotal } returns null
            every { parsedDate } returns null
            every { parsedTaxAmount } returns null
            every { currency } returns "EUR"
            every { lineItemsJson } returns null
            every { currentTimeMs } returns 1_700_000_000_000L
        }

        fun assertNoPreparationOrTransport() {
            assertTrue(rawPrompts.isEmpty())
            assertTrue(requests.isEmpty())
            assertTrue(auditContexts.isEmpty())
            coVerify(exactly = 0) { policy.prepareText(any(), any(), any()) }
            coVerify(exactly = 0) { policy.prepareReceiptAssist(any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { policy.prepareBankStatementValidation(any(), any()) }
            verify(exactly = 0) { client.newCall(any()) }
        }

        fun assertPreparedBodyAndAudit() {
            assertEquals(1, rawPrompts.size)
            assertTrue("Real provider must prepare its input, not a probe", rawPrompts.single().contains(RAW))
            assertEquals(1, requests.size)
            val buffer = Buffer()
            requireNotNull(requests.single().body).writeTo(buffer)
            val json = buffer.readUtf8()
            assertFalse(json.contains(RAW))
            assertFalse(json.contains("must-not-be-read"))
            val parts = JSONObject(json).getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
            assertEquals(prepared.text, parts.getJSONObject(0).getString("text"))
            assertEquals(if (prepared.rawImageIncluded) 2 else 1, parts.length())
            if (prepared.rawImageIncluded) {
                val image = parts.getJSONObject(1).getJSONObject("inlineData")
                assertEquals(prepared.imageMimeType, image.getString("mimeType"))
                assertEquals(Base64.getEncoder().encodeToString(prepared.imageBytes), image.getString("data"))
            } else {
                assertFalse(json.contains("inlineData"))
            }
            val context = auditContexts.single()
            assertEquals(prepared.purpose, context.purpose)
            assertEquals(prepared.payloadHash, context.payloadHash)
            assertEquals(prepared.redactionApplied, context.redactionApplied)
            assertEquals(prepared.rawTextIncluded, context.rawTextIncluded)
            assertEquals(prepared.rawImageIncluded, context.rawImageIncluded)
            assertFalse(context.toMap().values.any { it.contains(RAW) })
            verify(exactly = 1) { client.newCall(any()) }
        }
    }

    @Test
    fun redactedPolicyPayloadIsExactlyWhatActualProviderSendsAndAudits() = runTest {
        val harness = Harness(redacted = true)
        harness.invoke()
        harness.assertPreparedBodyAndAudit()
    }

    @Test
    fun policyOutputNotCallerPreferencesControlsTextAndImageTransport() = runTest {
        val harness = Harness(redacted = false)
        harness.invoke()
        harness.assertPreparedBodyAndAudit()
    }

    @Test
    fun deniedGateNeverPreparesAuditsOrSends() = runTest {
        val harness = Harness()
        coEvery { harness.gate.check(any(), any()) } returns PrivacyDecision.Denied("test_denied")
        harness.invoke()
        harness.assertNoPreparationOrTransport()
    }

    @Test
    fun failClosedGateNeverPreparesAuditsOrSends() = runTest {
        val harness = Harness()
        coEvery { harness.gate.check(any(), any()) } returns PrivacyDecision.FailClosed("test_failure")
        harness.invoke()
        harness.assertNoPreparationOrTransport()
    }

    @Test
    fun missingKeyNeverPreparesAuditsOrSends() = runTest {
        val harness = Harness()
        every { harness.keys.getKey(SecureKeyStorage.KEY_GEMINI) } returns null
        harness.invoke()
        harness.assertNoPreparationOrTransport()
    }
}
