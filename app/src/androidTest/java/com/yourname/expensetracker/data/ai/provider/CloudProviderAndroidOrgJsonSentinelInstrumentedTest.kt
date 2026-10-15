package com.yourname.expensetracker.data.ai.provider

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.ai.provider.internal.CloudJsonParser
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.DedupeCandidateSummary
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeInput
import com.yourname.expensetracker.domain.ai.model.ReviewExplanationInput
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicy
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber

/**
 * R6 device twin (RG02B-1 / RG02B-4): the R6 bounded-error contract was proven on the JVM
 * against the `org.json:json` test artifact. This class re-proves it on the device's real
 * Android `org.json` (a lenient, different implementation).
 *
 * (a) [CloudJsonParser.extractFirstJsonObject] never throws for malformed or hostile
 *     input and returns null or a string that itself parses as a JSON object.
 * (b) [CloudDedupeJudgeService] and [CloudReviewExplanationService], driven through an
 *     OkHttp [Interceptor] fake (no network, no MockWebServer), map the same bodies to
 *     bounded failures only: `ParseError("PARSER_FAILED")`, `Unknown("UNKNOWN_ERROR")` or
 *     the existing fixed `ParseError` literals. The sentinel never appears in any result
 *     field nor in any captured log message.
 *
 * Android `org.json` may accept inputs the JVM artifact rejects (for example duplicate
 * keys or unquoted names), so those cases assert membership in the bounded set rather than
 * one exact error. Failure messages carry case labels only, never response text.
 */
@RunWith(AndroidJUnit4::class)
class CloudProviderAndroidOrgJsonSentinelInstrumentedTest {

    private companion object {
        const val SENTINEL = "R6SENTINEL_9f2c"
        const val PARSER_FAILED = "PARSER_FAILED"
        const val EMPTY_BODY = "Empty response body"
        const val NO_DEDUPE_VERDICT = "No usable dedupe verdict in response"
        const val NO_EXPLANATION = "No usable explanation in response"

        /** Raw text inputs for [CloudJsonParser.extractFirstJsonObject]: label to input. */
        val PARSER_INPUTS: List<Pair<String, String>> = listOf(
            "invalid-sentinel-key" to "{R6SENTINEL_9f2c 1}",
            "invalid-sentinel-key-quoted" to "{\"R6SENTINEL_9f2c\" 1}",
            "unterminated-object" to "{ R6SENTINEL_9f2c",
            "unterminated-string" to "{\"k\":\"R6SENTINEL_9f2c",
            "unterminated-string-in-fence" to "```json\n{\"R6SENTINEL_9f2c\":\"x\n```",
            "duplicate-key" to "{\"R6SENTINEL_9f2c\":1,\"R6SENTINEL_9f2c\":2}",
            "wrong-typed-valid" to "{\"candidates\":\"R6SENTINEL_9f2c\",\"confidence\":[1,2]}",
            "top-level-array" to "[\"R6SENTINEL_9f2c\"]",
            "stray-closing-brace" to "} R6SENTINEL_9f2c {",
            "deep-nesting-unclosed" to "{\"a\":{\"b\":{\"c\":\"R6SENTINEL_9f2c\""
        )

        /** HTTP response bodies for the services: label to body. */
        val SERVICE_BODIES: List<Pair<String, String>> = listOf(
            "invalid-sentinel-key" to "{R6SENTINEL_9f2c 1}",
            "unterminated-object" to "{ R6SENTINEL_9f2c",
            "unterminated-string" to "{\"R6SENTINEL_9f2c\": \"unterminated",
            "duplicate-key" to "{\"R6SENTINEL_9f2c\":1,\"R6SENTINEL_9f2c\":2}",
            "wrong-typed-candidates" to "{\"candidates\":\"R6SENTINEL_9f2c\"}",
            "wrong-typed-verdict-and-headline" to
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"verdict\\\":\\\"R6SENTINEL_9f2c\\\"}\"}]}}]}",
            "inner-invalid-sentinel-key" to
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{R6SENTINEL_9f2c 1}\"}]}}]}",
            "inner-wrong-typed-confidence" to
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"verdict\\\":\\\"UNCERTAIN\\\",\\\"confidence\\\":\\\"R6SENTINEL_9f2c\\\"}\"}]}}]}"
        )
    }

    // ---------------------------------------------------------------------------------
    // (a) CloudJsonParser on Android org.json
    // ---------------------------------------------------------------------------------

    private fun extractOrFail(label: String, input: String): String? =
        try {
            CloudJsonParser.extractFirstJsonObject(input)
        } catch (e: Throwable) {
            fail("extractFirstJsonObject THREW for case=$label class=${e.javaClass.simpleName}")
            null
        }

    @Test
    fun extractFirstJsonObject_hostileInputs_neverThrow_andReturnNullOrValidObject() {
        PARSER_INPUTS.forEach { (label, input) ->
            val extracted = extractOrFail(label, input)
            if (extracted != null) {
                val parsed = try {
                    JSONObject(extracted)
                } catch (e: Throwable) {
                    fail("extracted candidate is not a valid object for case=$label class=${e.javaClass.simpleName}")
                    null
                }
                assertNotNull("case=$label parsed object missing", parsed)
            }
        }
    }

    @Test
    fun extractFirstJsonObject_unterminatedInputs_returnNull() {
        listOf(
            "unterminated-object",
            "unterminated-string",
            "unterminated-string-in-fence",
            "top-level-array",
            "deep-nesting-unclosed"
        ).forEach { label ->
            val input = PARSER_INPUTS.first { it.first == label }.second
            assertNull("case=$label must yield null", extractOrFail(label, input))
        }
    }

    @Test
    fun extractFirstJsonObject_wrongTypedValidJson_returnsParseableObject() {
        val label = "wrong-typed-valid"
        val input = PARSER_INPUTS.first { it.first == label }.second
        val extracted = extractOrFail(label, input)
        assertNotNull("case=$label valid JSON object must be returned", extracted)
        assertTrue("case=$label returned value must parse", JSONObject(extracted!!).length() == 2)
    }

    @Test
    fun extractFirstJsonObject_duplicateKey_isNullOrValidObject_neverThrows() {
        val label = "duplicate-key"
        val input = PARSER_INPUTS.first { it.first == label }.second
        val extracted = extractOrFail(label, input)
        if (extracted != null) {
            assertTrue("case=$label returned value must parse", JSONObject(extracted).has(SENTINEL))
        }
    }

    // ---------------------------------------------------------------------------------
    // (b) Cloud services through an OkHttp Interceptor fake
    // ---------------------------------------------------------------------------------

    private fun clientReturning(body: String): OkHttpClient {
        val interceptor = Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        return OkHttpClient.Builder().addInterceptor(interceptor).build()
    }

    private fun clientThrowing(failure: RuntimeException): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(Interceptor { throw failure }).build()

    private fun keyStorage(): SecureKeyStorage {
        val storage = mockk<SecureKeyStorage>(relaxed = true)
        every { storage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "fake-api-key"
        return storage
    }

    private val allowAllGate = object : PrivacyGate {
        override suspend fun check(capability: PrivacyCapability, context: Map<String, String>): PrivacyDecision =
            PrivacyDecision.Allowed
    }

    private fun dedupeService(client: OkHttpClient) = CloudDedupeJudgeService(
        secureKeyStorage = keyStorage(),
        client = client,
        aiSettingsRepository = null,
        privacyGate = allowAllGate,
        cloudPayloadPolicy = DefaultCloudPayloadPolicy(
            EffectiveCloudAiPolicyResolver.failClosedNoAi(),
            DefaultCloudPayloadRedactor()
        )
    )

    private fun reviewService(client: OkHttpClient): CloudReviewExplanationService {
        val resolver = mockk<EffectiveCloudAiPolicyResolver>()
        coEvery { resolver.resolve() } returns EffectiveCloudAiPolicy(
            cloudAllowed = true,
            reason = null,
            redactBeforeCloud = false,
            receiptImageUploadAllowed = false,
            bankStatementCloudAllowed = false
        )
        return CloudReviewExplanationService(
            secureKeyStorage = keyStorage(),
            client = client,
            aiSettingsRepository = null,
            privacyGate = allowAllGate,
            cloudPayloadPolicy = DefaultCloudPayloadPolicy(
                policyResolver = resolver,
                redactor = DefaultCloudPayloadRedactor()
            ),
            effectiveCloudAiPolicyResolver = resolver
        )
    }

    private fun dedupeInput() = DedupeJudgeInput(
        subject = DedupeCandidateSummary(
            targetType = AiTargetType.PENDING_REVIEW,
            targetId = 1L,
            merchant = "Lidl",
            amount = 24.5,
            currency = "EUR",
            date = 1_000L,
            sourceLabel = "bank",
            textPreview = "Card purchase at Lidl"
        ),
        candidates = listOf(
            DedupeCandidateSummary(
                targetType = AiTargetType.EXPENSE,
                targetId = 2L,
                merchant = "Lidl",
                amount = 24.5,
                currency = "EUR",
                date = 1_010L,
                sourceLabel = "expense",
                textPreview = "Card purchase at Lidl"
            )
        )
    )

    private fun reviewInput() = ReviewExplanationInput(
        reviewId = 1L,
        merchant = "Test",
        amount = 10.0,
        currency = "EUR",
        suggestedType = "PURCHASE",
        suggestedCategoryId = null,
        confidence = 0.4f,
        matchType = null,
        explanation = null,
        packageName = "pkg",
        notificationTitle = "Title",
        notificationText = "Body"
    )

    /** Captures Timber output (messages and throwable messages only) for sentinel checks. */
    private inline fun <T> withCapturedLogs(block: (MutableList<String>) -> T): T {
        // Timber.log may be invoked from coroutine/IO threads while the test thread asserts.
        val captured: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                captured += message
                t?.message?.let { captured += it }
            }
        }
        Timber.plant(tree)
        try {
            return block(captured)
        } finally {
            Timber.uproot(tree)
        }
    }

    /** Asserts a bounded failure and that the sentinel is in no field of [result]. */
    private fun assertBoundedFailure(label: String, result: AiServiceResult<*>, allowedParseMessages: Set<String>) {
        assertTrue("case=$label expected Failure but was ${result.javaClass.simpleName}", result is AiServiceResult.Failure)
        val error = (result as AiServiceResult.Failure).error
        val bounded = when (error) {
            is AiServiceError.ParseError -> error.message in allowedParseMessages
            is AiServiceError.Unknown -> error.message == "UNKNOWN_ERROR"
            else -> false
        }
        assertTrue("case=$label error not in bounded set (type=${error.javaClass.simpleName})", bounded)
        assertFalse("case=$label sentinel leaked into result", result.toString().contains(SENTINEL))
    }

    private fun assertNoSentinelInLogs(label: String, logs: List<String>) {
        // Snapshot under the list lock so iteration cannot race a concurrent Timber.log append.
        val snapshot = synchronized(logs) { logs.toList() }
        assertTrue("case=$label sentinel leaked into logs", snapshot.none { it.contains(SENTINEL) })
    }

    @Test
    fun dedupeJudge_hostileBodies_mapToBoundedFailures_withoutSentinel() {
        val allowed = setOf(PARSER_FAILED, EMPTY_BODY, NO_DEDUPE_VERDICT)
        SERVICE_BODIES.forEach { (label, body) ->
            withCapturedLogs { logs ->
                val result = runBlocking { dedupeService(clientReturning(body)).judge(dedupeInput()) }
                assertBoundedFailure(label, result, allowed)
                assertNoSentinelInLogs(label, logs)
            }
        }
    }

    @Test
    fun dedupeJudge_deterministicCases_mapToExactBoundedErrors() {
        withCapturedLogs { logs ->
            val unterminated = runBlocking {
                dedupeService(clientReturning("{ $SENTINEL")).judge(dedupeInput())
            }
            assertEquals(
                AiServiceResult.Failure(AiServiceError.ParseError(PARSER_FAILED)),
                unterminated
            )
            val wrongTyped = runBlocking {
                dedupeService(clientReturning("{\"candidates\":\"$SENTINEL\"}")).judge(dedupeInput())
            }
            assertEquals(
                AiServiceResult.Failure(AiServiceError.ParseError(NO_DEDUPE_VERDICT)),
                wrongTyped
            )
            assertNoSentinelInLogs("dedupe-deterministic", logs)
        }
    }

    @Test
    fun dedupeJudge_unexpectedException_mapsToBoundedUnknownError_withoutSentinel() {
        withCapturedLogs { logs ->
            val result = runBlocking {
                dedupeService(clientThrowing(IllegalStateException(SENTINEL))).judge(dedupeInput())
            }
            assertEquals(
                AiServiceResult.Failure(AiServiceError.Unknown("UNKNOWN_ERROR")),
                result
            )
            assertFalse(result.toString().contains(SENTINEL))
            assertNoSentinelInLogs("dedupe-unexpected-exception", logs)
        }
    }

    @Test
    fun reviewExplanation_hostileBodies_mapToBoundedFailures_withoutSentinel() {
        val allowed = setOf(PARSER_FAILED, EMPTY_BODY, NO_EXPLANATION)
        SERVICE_BODIES.forEach { (label, body) ->
            withCapturedLogs { logs ->
                val result = runBlocking { reviewService(clientReturning(body)).generate(reviewInput()) }
                assertBoundedFailure(label, result, allowed)
                assertNoSentinelInLogs(label, logs)
            }
        }
    }

    @Test
    fun reviewExplanation_deterministicCases_mapToExactBoundedErrors() {
        withCapturedLogs { logs ->
            val unterminated = runBlocking {
                reviewService(clientReturning("{ $SENTINEL")).generate(reviewInput())
            }
            assertEquals(
                AiServiceResult.Failure(AiServiceError.ParseError(PARSER_FAILED)),
                unterminated
            )
            val wrongTyped = runBlocking {
                reviewService(clientReturning("{\"candidates\":\"$SENTINEL\"}")).generate(reviewInput())
            }
            assertEquals(
                AiServiceResult.Failure(AiServiceError.ParseError(NO_EXPLANATION)),
                wrongTyped
            )
            assertNoSentinelInLogs("review-deterministic", logs)
        }
    }

    @Test
    fun reviewExplanation_unexpectedException_mapsToBoundedUnknownError_withoutSentinel() {
        withCapturedLogs { logs ->
            val result = runBlocking {
                reviewService(clientThrowing(IllegalStateException(SENTINEL))).generate(reviewInput())
            }
            assertEquals(
                AiServiceResult.Failure(AiServiceError.Unknown("UNKNOWN_ERROR")),
                result
            )
            assertFalse(result.toString().contains(SENTINEL))
            assertNoSentinelInLogs("review-unexpected-exception", logs)
        }
    }
}
