package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationInput
import com.yourname.expensetracker.domain.ai.model.FinancialQueryInterpretationResult
import com.yourname.expensetracker.domain.privacy.CloudPayloadPolicy
import com.yourname.expensetracker.domain.privacy.CloudPayloadPurpose
import com.yourname.expensetracker.domain.privacy.PreparedCloudPayload
import com.yourname.expensetracker.domain.privacy.PrivacyAuditContext
import com.yourname.expensetracker.domain.privacy.PrivacyAuditLogger
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFailsWith
import timber.log.Timber

class CloudQueryPreparedPayloadTest {
    private val keyStorage = mockk<SecureKeyStorage>()
    private val client = mockk<OkHttpClient>()
    private val gate = mockk<PrivacyGate>()
    private val policy = mockk<CloudPayloadPolicy>()
    private val audit = mockk<PrivacyAuditLogger>(relaxed = true)
    private val input = FinancialQueryInterpretationInput("spending for person@example.test", 1_000L, "en-US")

    @Before
    fun setUp() {
        every { keyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"
        coEvery { gate.check(PrivacyCapability.CLOUD_AI_GENERAL) } returns PrivacyDecision.Allowed
    }

    private fun service() = CloudQueryInterpretationService(keyStorage, client, gate, policy, audit)

    private fun payload(redacted: Boolean) = PreparedCloudPayload(
        purpose = CloudPayloadPurpose.QUERY_INTERPRETATION,
        text = "POLICY_APPROVED_QUERY",
        redactionApplied = redacted,
        fieldsRedacted = if (redacted) setOf("email") else emptySet(),
        payloadHash = "a".repeat(32),
        rawTextIncluded = !redacted,
        rawImageIncluded = false
    )

    private suspend fun assertTransportAndAuditUsePreparedPayload(redacted: Boolean) {
        val prepared = payload(redacted)
        val rawPrompt = slot<String>()
        val request = slot<Request>()
        val context = slot<PrivacyAuditContext>()
        coEvery { policy.prepareText(CloudPayloadPurpose.QUERY_INTERPRETATION, capture(rawPrompt), any()) } returns prepared
        coEvery { audit.logCloudCall(PrivacyCapability.CLOUD_AI_GENERAL, PrivacyDecision.Allowed, capture(context)) } returns Unit
        val call = mockk<Call>()
        every { client.newCall(capture(request)) } returns call
        every { call.execute() } answers {
            Response.Builder().request(request.captured).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"candidates":[]}""".toResponseBody("application/json".toMediaType())).build()
        }

        service().interpret(input)

        assertTrue(rawPrompt.captured.contains(input.rawQuery))
        val buffer = Buffer()
        requireNotNull(request.captured.body).writeTo(buffer)
        val body = JSONObject(buffer.readUtf8())
        val sentText = body.getJSONArray("contents").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0).getString("text")
        assertEquals(prepared.text, sentText)
        assertFalse(sentText.contains(input.rawQuery))
        assertEquals(prepared.payloadHash, context.captured.payloadHash)
        assertEquals(prepared.redactionApplied, context.captured.redactionApplied)
        assertEquals(prepared.rawTextIncluded, context.captured.rawTextIncluded)
        assertEquals(false, context.captured.rawImageIncluded)
        assertEquals(CloudPayloadPurpose.QUERY_INTERPRETATION, context.captured.purpose)
        assertFalse(context.captured.toMap().values.any { it.contains(input.rawQuery) })
        coVerify(exactly = 1) { policy.prepareText(CloudPayloadPurpose.QUERY_INTERPRETATION, any(), any()) }
        coVerify(exactly = 1) { audit.logCloudCall(any(), any(), any()) }
        verify(exactly = 1) { client.newCall(any()) }
    }

    @Test
    fun redactedPreparedTextIsTheOnlyTextSentAndItsProvenanceIsAudited() = runTest {
        assertTransportAndAuditUsePreparedPayload(redacted = true)
    }

    @Test
    fun providerPreservesPolicyOwnedRawTextPermissionAndProvenance() = runTest {
        assertTransportAndAuditUsePreparedPayload(redacted = false)
    }

    @Test
    fun deniedOrFailClosedGateNeverPreparesAuditsOrSends() = runTest {
        for (decision in listOf(PrivacyDecision.Denied("test_denied"), PrivacyDecision.FailClosed("test_failure"))) {
            coEvery { gate.check(PrivacyCapability.CLOUD_AI_GENERAL) } returns decision
            val result = service().interpret(input)
            assertEquals("PROVIDER_DISABLED", (result as FinancialQueryInterpretationResult.Unsupported).reason)
        }
        coVerify(exactly = 0) { policy.prepareText(any(), any(), any()) }
        coVerify(exactly = 0) { audit.logCloudCall(any(), any(), any()) }
        verify(exactly = 0) { client.newCall(any()) }
    }

    @Test
    fun missingKeyNeverPreparesOrSends() = runTest {
        every { keyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns null
        assertTrue(service().interpret(input) is FinancialQueryInterpretationResult.Unsupported)
        coVerify(exactly = 0) { policy.prepareText(any(), any(), any()) }
        coVerify(exactly = 0) { audit.logCloudCall(any(), any(), any()) }
        verify(exactly = 0) { client.newCall(any()) }
    }

    @Test
    fun policyFailureFailsClosedWithBoundedDiagnostics() = runTest {
        val sensitiveMessage = "/private/receipt financial_data person@example.test"
        coEvery { policy.prepareText(any(), any(), any()) } throws IllegalStateException(sensitiveMessage)
        val logs = mutableListOf<Pair<Throwable?, String>>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logs += t to message
            }
        }
        Timber.plant(tree)
        try {
            val result = service().interpret(input)
            assertEquals("UNKNOWN_ERROR", (result as FinancialQueryInterpretationResult.Unsupported).reason)
            assertTrue(logs.any { it.second.contains("stage=payload_policy") })
            assertTrue(logs.all { it.first == null && !it.second.contains(sensitiveMessage) })
        } finally {
            Timber.uproot(tree)
        }
        coVerify(exactly = 0) { audit.logCloudCall(any(), any(), any()) }
        verify(exactly = 0) { client.newCall(any()) }
    }

    @Test
    fun policyCancellationPropagatesWithoutAuditOrHttp() = runTest {
        val cancellation = CancellationException("test cancellation")
        coEvery { policy.prepareText(any(), any(), any()) } throws cancellation
        assertSame(cancellation, assertFailsWith<CancellationException> { service().interpret(input) })
        coVerify(exactly = 0) { audit.logCloudCall(any(), any(), any()) }
        verify(exactly = 0) { client.newCall(any()) }
    }

    @Test
    fun gateCancellationPropagatesBeforePayloadPreparation() = runTest {
        val cancellation = CancellationException("test cancellation")
        coEvery { gate.check(PrivacyCapability.CLOUD_AI_GENERAL) } throws cancellation
        assertSame(cancellation, assertFailsWith<CancellationException> { service().interpret(input) })
        coVerify(exactly = 0) { policy.prepareText(any(), any(), any()) }
        verify(exactly = 0) { client.newCall(any()) }
    }

    @Test
    fun failedOrCancelledProvenancePreventsHttpDispatch() = runTest {
        coEvery { policy.prepareText(any(), any(), any()) } returns payload(true)
        for (failure in listOf(IllegalStateException("test audit failure"), CancellationException("test cancellation"))) {
            coEvery { audit.logCloudCall(any(), any(), any()) } throws failure
            assertSame(failure, assertFailsWith<Exception> { service().interpret(input) })
        }
        verify(exactly = 0) { client.newCall(any()) }
    }
}
